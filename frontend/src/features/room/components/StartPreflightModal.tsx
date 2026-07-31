import { useCallback, useEffect, useRef, useState } from 'react';
import { aiApi } from '../../fetch/api/aiApi';
import { createHandLandmarker } from '../../gesture/lib/handLandmarker';
import { GAME_LABELS, type Course, type GameName } from '../../course/api/courseApi';
import './StartPreflightModal.css';

type CheckStatus = 'pending' | 'running' | 'ok' | 'failed';

interface Check {
  key: string;
  label: string;
  /** 왜 필요한지 — 실패했을 때 뭘 해야 하는지 알 수 있게 */
  hint: string;
  run: () => Promise<void>;
}

interface StartPreflightModalProps {
  /** 이번에 진행할 코스 — 담긴 게임에 따라 필요한 점검만 돌린다 */
  course: Course | null;
  gameNameOf: (gameId: number) => GameName | null;
  /** 모든 점검 통과 후 실제 시작을 요청한다 */
  onProceed: () => void;
  onCancel: () => void;
  /** 시작 요청이 진행 중인가 (상위의 startGame) */
  starting: boolean;
  startError: string | null;
}

// 게임 시작 전 사전 점검. 인식이 외부 자원에 의존하는 게임만 골라 확인한다:
//   - 닌자: MediaPipe WASM/모델(외부 CDN)을 실제로 로드해 본다
//   - 물건 가져오기: AI 서버(별개 호스트) /health
//   - 몸으로 말해요: 채팅 텍스트 매칭이라 확인할 외부 자원이 없다
//
// 게임에 들어간 뒤 인식만 조용히 실패하는 걸 막는 게 목적이다. 모델 로드는 8MB쯤 받으므로
// 이 점검이 워밍업도 겸한다 — 통과한 뒤 게임에 들어가면 이미 브라우저 캐시에 있다.
export function StartPreflightModal({
  course,
  gameNameOf,
  onProceed,
  onCancel,
  starting,
  startError,
}: StartPreflightModalProps) {
  const courseGames = new Set(
    (course?.items ?? [])
      .map((item) => gameNameOf(item.gameId))
      .filter((name): name is GameName => name !== null),
  );

  const checks: Check[] = [];
  if (courseGames.has('NINJA')) {
    checks.push({
      key: 'mediapipe',
      label: '손동작 인식 준비',
      hint: `${GAME_LABELS.NINJA}에 필요해요. 인식 모델을 내려받는 중이라 처음엔 조금 걸려요.`,
      run: async () => {
        // 만들어 보는 것 자체가 점검이다 — URL만 찔러보면 GPU 델리게이트 초기화 실패를 못 잡는다.
        const landmarker = await createHandLandmarker();
        landmarker.close();
      },
    });
  }
  if (courseGames.has('FETCH_OBJECT')) {
    checks.push({
      key: 'ai',
      label: '물건 인식 서버 연결',
      hint: `${GAME_LABELS.FETCH_OBJECT}에 필요해요. 인식 서버가 켜져 있는지 확인해 주세요.`,
      run: async () => {
        if (!(await aiApi.isHealthy())) throw new Error('서버 응답 없음');
      },
    });
  }

  const [statuses, setStatuses] = useState<Record<string, CheckStatus>>({});
  const [errors, setErrors] = useState<Record<string, string>>({});
  // 점검을 한 번이라도 돌렸는가 — 통과 후 자동 진행을 한 번만 하기 위한 표식.
  const [done, setDone] = useState(false);
  // 점검 한 번에 시작 요청도 한 번. 아래 자동 진행 이펙트가 재실행돼도 두 번 부르지 않게 한다.
  const proceededRef = useRef(false);

  const runAll = useCallback(async () => {
    setDone(false);
    proceededRef.current = false;
    setStatuses(Object.fromEntries(checks.map((check) => [check.key, 'running' as CheckStatus])));
    setErrors({});
    // 서로 독립이라 동시에 돌린다 — 순차로 하면 모델 다운로드가 끝날 때까지 서버 확인이 밀린다.
    await Promise.all(
      checks.map(async (check) => {
        try {
          await check.run();
          setStatuses((prev) => ({ ...prev, [check.key]: 'ok' }));
        } catch (err) {
          setStatuses((prev) => ({ ...prev, [check.key]: 'failed' }));
          setErrors((prev) => ({
            ...prev,
            [check.key]: err instanceof Error ? err.message : String(err),
          }));
        }
      }),
    );
    setDone(true);
    // eslint-disable-next-line react-hooks/exhaustive-deps -- checks는 매 렌더 새 배열이라 넣으면 무한 루프
  }, [course]);

  useEffect(() => {
    void runAll();
  }, [runAll]);

  const allOk = checks.every((check) => statuses[check.key] === 'ok');
  const anyFailed = checks.some((check) => statuses[check.key] === 'failed');

  // 전부 통과하면 바로 시작한다 — 확인 버튼을 한 번 더 누르게 하면 점검이 관문처럼만 느껴진다.
  //
  // 점검 한 번에 시작 요청도 한 번이어야 한다. starting을 조건에 넣고 ref 없이 두면, 시작이
  // 실패해 starting이 true→false로 돌아오는 순간 이펙트가 다시 돌아 무한 재시도가 된다.
  useEffect(() => {
    if (!done || !allOk || proceededRef.current) return;
    proceededRef.current = true;
    onProceed();
  }, [done, allOk, onProceed]);

  return (
    <div className="pap-modal-backdrop">
      <div className="pap-modal preflight">
        <div className="preflight__card pap-pixel-card">
          <h2 className="preflight__title pap-pixel-title">게임 준비 확인</h2>
          {checks.length === 0 ? (
            <p className="preflight__empty">확인할 항목이 없어요. 바로 시작합니다...</p>
          ) : (
            <ul className="preflight__list">
              {checks.map((check) => {
                const status = statuses[check.key] ?? 'pending';
                return (
                  <li key={check.key} className={`preflight__item preflight__item--${status}`}>
                    <span className="preflight__icon" aria-hidden>
                      {status === 'ok' ? '✔' : status === 'failed' ? '✕' : '…'}
                    </span>
                    <span className="preflight__body">
                      <span className="preflight__label">{check.label}</span>
                      <span className="preflight__hint">
                        {status === 'failed' ? `${check.hint} (${errors[check.key]})` : check.hint}
                      </span>
                    </span>
                  </li>
                );
              })}
            </ul>
          )}

          {allOk && done && (
            <p className="preflight__ok">모두 정상이에요. 첫 게임을 시작합니다...</p>
          )}
          {startError && <p className="preflight__error">{startError}</p>}

          <div className="preflight__actions">
            {anyFailed && (
              <button type="button" className="pap-pixel-btn" onClick={() => void runAll()}>
                다시 확인
              </button>
            )}
            <button
              type="button"
              className="pap-pixel-btn"
              onClick={onCancel}
              disabled={starting}
            >
              {anyFailed ? '닫기' : '취소'}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
