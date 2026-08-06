import { useLocalParticipant, useParticipants } from '@livekit/components-react';
import { useCallback, useMemo } from 'react';
import { useFetchRound } from '../hooks/useFetchRound';
import { FetchObjectGame } from './FetchObjectGame';

interface FetchCoursePanelProps {
  roomId: string;
  /** 카탈로그 gameId — game:started(코스)에서 온 값. Spring 제출 경로 검증에 쓴다 */
  gameId: number;
  accessToken: string;
  /** participantId → 닉네임 (방 스냅샷 기준 — round:success 등 이벤트가 id만 주므로 필요) */
  nicknameById: Map<string, string>;
  /** 입장 순서대로의 participantId. 대기방에서 배정된 색·자리를 그대로 이어받는다. */
  joinOrder: string[];
  participantColorIndexById: ReadonlyMap<string, number>;
  onLeave: () => void;
}

// 코스가 연 물건 가져오기 세션의 서버 상태를 실제 게임 화면에 연결한다.
export function FetchCoursePanel({
  roomId,
  gameId,
  accessToken,
  nicknameById,
  joinOrder,
  participantColorIndexById,
  onLeave,
}: FetchCoursePanelProps) {
  const { localParticipant } = useLocalParticipant();
  const liveParticipants = useParticipants();
  const myNickname = localParticipant.name || '나';

  // 방 REST 스냅샷은 이 화면이 처음 열린 시점의 참가자만 담고 있을 수 있다. LiveKit identity는
  // 백엔드 participantId와 동일하므로 현재 접속자 목록을 우선 사용해야, 뒤늦게 입장한 참가자의
  // round:success도 모든 화면에서 정확한 타일과 닉네임에 연결된다.
  const liveNicknameById = useMemo(
    () =>
      new Map(
        liveParticipants.map((participant) => [
          participant.identity,
          participant.name || '참가자',
        ]),
      ),
    [liveParticipants],
  );
  const resolveNickname = useCallback(
    (participantId: string) =>
      liveNicknameById.get(participantId) ?? nicknameById.get(participantId) ?? '참가자',
    [liveNicknameById, nicknameById],
  );
  const { state, submit, submissionError, syncError } = useFetchRound(
    roomId,
    gameId,
    accessToken,
    resolveNickname,
  );

  // 첫 round:start 수신 전에는 /state 복구가 끝날 때까지 짧게 준비 화면을 보여준다.
  if (state.phase === 'idle') {
    return (
      <div className="video-call-room__intermission">
        <p className="pap-pixel-title">물건 가져오기를 준비하고 있어요...</p>
        {syncError && <p>{syncError}</p>}
      </div>
    );
  }

  return (
    <FetchObjectGame
      state={state}
      myNickname={myNickname}
      joinOrder={joinOrder}
      participantColorIndexById={participantColorIndexById}
      onReportSuccess={(_, result) =>
        submit(result.confidence, result.targetScore ?? undefined)
      }
      submissionError={submissionError}
      onLeave={onLeave}
    />
  );
}
