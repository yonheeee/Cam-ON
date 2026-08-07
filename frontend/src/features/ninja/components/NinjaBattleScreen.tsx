import {
  ParticipantTile,
  useLocalParticipant,
  useParticipants,
  useTracks,
} from '@livekit/components-react';
import { Track } from 'livekit-client';
import { useEffect, useMemo, useRef, useState } from 'react';
import { GesturePanel } from '../../gesture/components/GesturePanel';
import { useGestureBoardStore } from '../../gesture/store/gestureBoardStore';
import { RoomTopBar } from '../../room/components/RoomTopBar';
import { useSpeakingIdentities } from '../../webrtc/hooks/useSpeakingIdentities';
import { ParticipantAudioControl } from '../../webrtc/components/ParticipantAudioControl';
import { useAnnouncementSound } from '../../sound/hooks/useAnnouncementSound';
import { useCountdownSound } from '../../sound/hooks/useCountdownSound';
import { useNinjaEliminationSound } from '../hooks/useNinjaEliminationSound';
import { useNinjaEffectSound } from '../hooks/useNinjaEffectSound';
import { useNinjaRound } from '../hooks/useNinjaRound';
import { gestureImage } from '../lib/gestureImages';
import { skillEffect, skillEffectMs, skillShake } from '../lib/skillEffects';
import { PixelConfirmModal } from '../../system/components/PixelConfirmModal';
import { CINEMATIC_BLACKOUT_MS, NinjaCinematicOverlay } from './NinjaCinematicOverlay';
import './NinjaBattleScreen.css';

// 백엔드 NinjaGameService.TARGET_DURATION 및 useNinjaRound의 대상 선택 타이머와 동일해야
// 진행 바가 15초에서 100%로 시작해 0%까지 정확히 줄어든다.
const ATTACK_TARGET_TIMER_SECONDS = 15;
const MAX_HP = 100;
// 다음 교환 카운트다운이 세는 숫자 개수(3 → 2 → 1). useNinjaRound.COUNTDOWN_STEPS와 같은 값.
const NINJA_COUNTDOWN_STEPS = 3;

// 고정 디자인 캔버스. 이 화면의 모든 px 값은 1440×810 기준이고, 뷰포트에는 통째로 확대/축소해서
// 맞춘다(웹게임 표준 방식). 이유: 캠 크기는 폭(열 30% × 16:9)에, 보드는 절대 px에 묶여 있어서
// 뷰포트 비율이 바뀔 때마다 요소 간 비율이 따로 움직였다 — 큰 모니터에선 HUD가 상대적으로
// 쪼그라들고 세로가 짧은 창에선 보드만 커 보였다. 캔버스를 고정하면 어느 화면에서도 비율이 같다.
const STAGE_WIDTH = 1440;
const STAGE_HEIGHT = 810;
// ponytail: 연속 배율(스냅 없음). 픽셀 폰트/아트가 뭉개져 보이면 0.25로 바꿔 내림 스냅한다 —
// 대신 배율이 한 칸 떨어지면서 화면을 최대 25% 못 쓴다. 지금은 화면을 다 쓰는 쪽을 택했다.
const SCALE_SNAP = 0;

function useStageScale() {
  const [scale, setScale] = useState(1);
  useEffect(() => {
    const fit = () => {
      const raw = Math.min(window.innerWidth / STAGE_WIDTH, window.innerHeight / STAGE_HEIGHT);
      setScale(SCALE_SNAP ? Math.floor(raw / SCALE_SNAP) * SCALE_SNAP : raw);
    };
    fit();
    window.addEventListener('resize', fit);
    return () => window.removeEventListener('resize', fit);
  }, []);
  return scale;
}

// 상단 알림에 붙는 쿠나이(닌자의 상징). 12×12 격자 위 도형이라 crispEdges로 그리면 확대돼도
// 안티에일리어싱 없이 계단 픽셀로 남는다 — 이 화면의 픽셀 아트 톤과 맞다.
function KunaiIcon() {
  return (
    <svg className="ninja-toast__kunai" viewBox="0 0 12 12" shapeRendering="crispEdges" aria-hidden>
      <path d="M9 0l3 3-5 5-3-3z" fill="#e6e2f2" />
      <path d="M4 6l2 2-3 3-2-2z" fill="#9b8bc4" />
      <path d="M0 9h3v3H0zM1 10h1v1H1z" fillRule="evenodd" fill="#9b8bc4" />
    </svg>
  );
}

// 닌자 전투 화면. 대기방(LobbyScreen)/몸으로말해요(CharadesGamePanel)와 같은 방식으로 이 화면이
// 캠 타일까지 직접 그린다 — 그래서 HP·점수·공격권·이펙트를 "그 사람 타일 위"에 얹을 수 있다.
//
// 배치: 참가자 캠을 좌/우 열로 갈라 세우고 따라할 인술 카드를 가운데 둔다(시안 기준). 시선이
// 중앙 한 곳에 머무르면서 양옆 상대 상태가 주변시에 들어온다 — 손동작 게임이라 화면을 훑을
// 여유가 없다. 좌우 열은 각 30%, 중앙이 남은 40%를 갖는다(중앙 : 좌우합 ≈ 0.62 : 1, 황금비).
interface NinjaBattleScreenProps {
  roomId: string;
  gameId: number;
  accessToken: string;
  onActiveChange: (active: boolean) => void;
  /** 로고 클릭 → 확인 팝업 → 방 나가기 (확정안: 방 안에서 로고는 항상 확인 팝업 경유) */
  onLeave: () => void;
  participantColorIndexById: ReadonlyMap<string, number>;
}

export function NinjaBattleScreen({
  roomId,
  gameId,
  accessToken,
  onActiveChange,
  onLeave,
  participantColorIndexById,
}: NinjaBattleScreenProps) {
  const [confirmLeave, setConfirmLeave] = useState(false);
  const { localParticipant } = useLocalParticipant();
  const participants = useParticipants();
  const myId = localParticipant.identity || null;
  const stageScale = useStageScale();

  const comboEntry = useGestureBoardStore((state) => state.entries[localParticipant.identity]);

  const {
    round,
    exchange,
    alivePlayers,
    hp,
    currentAttackerToken,
    isMyAttack,
    isEliminated,
    roundTimerSeconds,
    attackTimerSeconds,
    requiredSkill,
    stepIndex,
    completed,
    holdProgress,
    attackAccepted,
    ranking,
    gameEnded,
    error,
    submitTarget,
    isIntermission,
    inEffectPlayback,
    inCountdown,
    countdownSeconds,
    lastAttack,
    roundResult,
    connectionState,
  } = useNinjaRound(roomId, gameId, accessToken, myId, comboEntry?.comboLabel ?? null, comboEntry?.confidence ?? 0);

  // 카운트다운이 한 칸 0.5초라(총 1.5초) 음원을 2배속으로 돌려 "3, 2, 1" 비트를 화면과 맞춘다.
  // seek 위치는 파일 시간 기준이라 그대로 초 단위로 계산한다(파일은 1초에 숫자 하나).
  useCountdownSound(
    inCountdown,
    `ninja:${round}:${exchange}`,
    Math.max(0, NINJA_COUNTDOWN_STEPS - (countdownSeconds ?? NINJA_COUNTDOWN_STEPS)),
    2,
  );
  useNinjaEffectSound(
    inEffectPlayback,
    lastAttack?.skillId ?? requiredSkill?.skillId,
    `${round}:${exchange}`,
  );
  useNinjaEliminationSound(round, alivePlayers);
  useAnnouncementSound(
    !!roundResult?.length && !gameEnded,
    `ninja-round-result:${round}`,
    '/assets/sounds/middle-winner.mp3',
  );
  useAnnouncementSound(
    gameEnded && ranking.length > 0,
    `ninja-winner:${ranking[0]?.token ?? 'unknown'}`,
    '/assets/sounds/ninja-winner.mp3',
  );

  const tracks = useTracks([{ source: Track.Source.Camera, withPlaceholder: true }], {
    onlySubscribed: false,
  });
  const trackByIdentity = useMemo(
    () => new Map(tracks.map((t) => [t.participant.identity, t])),
    [tracks],
  );
  const speakingIds = useSpeakingIdentities();
  const nicknameCacheRef = useRef(new Map<string, string>());
  const stageRef = useRef<HTMLDivElement>(null);
  const tileRefs = useRef(new Map<string, HTMLDivElement>());
  const [cinematicFocus, setCinematicFocus] = useState<{ x: number; y: number } | null>(null);

  // 타일 순서와 플레이어 색은 모든 참가자 화면에서 같아야 한다(내 화면에선 2P인 사람이 남의 화면에선
  // 3P면 색으로 소통이 안 된다). LiveKit participants 배열 순서는 클라이언트마다 다를 수 있어서
  // identity 문자열로 정렬해 결정적으로 만든다.
  const seats = useMemo(
    () =>
      [...participants].sort(
        (a, b) =>
          (participantColorIndexById.get(a.identity) ?? 99) -
            (participantColorIndexById.get(b.identity) ?? 99) ||
          a.identity.localeCompare(b.identity),
      ),
    [participants, participantColorIndexById],
  );

  useEffect(() => {
    for (const participant of participants) {
      if (participant.name) nicknameCacheRef.current.set(participant.identity, participant.name);
    }
  }, [participants]);

  // 짝수 자리는 왼쪽, 홀수 자리는 오른쪽. 2인전은 1:1 대면, 3인은 2:1, 4인은 2:2가 된다.
  const leftSeats = seats.filter((_, i) => i % 2 === 0);
  const rightSeats = seats.filter((_, i) => i % 2 === 1);

  const nicknameOf = (id: string) =>
    participants.find((p) => p.identity === id)?.name || (id === myId ? '나' : '상대');

  const connectionNicknameOf = (id: string) =>
    participants.find((participant) => participant.identity === id)?.name ||
    nicknameCacheRef.current.get(id) ||
    (id === myId ? '나' : '참가자');

  const connectionBanner = (() => {
    if (connectionState.selfConnectionTimedOut) {
      return { tone: 'danger', text: '연결 시간이 초과되어 탈락 처리됩니다.' };
    }
    if (connectionState.selfReconnecting) {
      return { tone: 'warning', text: '연결이 끊겼습니다. 재연결 중...' };
    }
    const notice = connectionState.connectionNotice;
    if (notice) {
      if (notice.participantId === null) {
        return { tone: 'success', text: '서버와 다시 연결되었습니다.' };
      }
      const nickname = connectionNicknameOf(notice.participantId);
      if (notice.kind === 'TIMED_OUT') {
        return { tone: 'danger', text: `${nickname}님의 연결이 끊겨 탈락 처리되었습니다.` };
      }
      if (notice.kind === 'RECONNECTED') {
        return { tone: 'success', text: `${nickname}님이 다시 연결되었습니다.` };
      }
      return { tone: 'warning', text: `${nickname}님의 연결이 끊겼습니다.` };
    }
    if (connectionState.disconnectedParticipantIds.length > 0) {
      const names = connectionState.disconnectedParticipantIds
        .slice(0, 2)
        .map(connectionNicknameOf)
        .join(', ');
      const rest = Math.max(0, connectionState.disconnectedParticipantIds.length - 2);
      return {
        tone: 'warning',
        text: `${names}${rest ? ` 외 ${rest}명` : ''}의 재연결을 기다리는 중입니다.`,
      };
    }
    return null;
  })();

  const gameStarted = round !== null;

  // 세션이 사라지면(코스가 다음 게임으로 넘어감) 부모에 알려 화면을 넘긴다. 마운트 직후 첫 폴링
  // 전 round=null 상태로 잘못 튕기지 않도록 한 번이라도 활성이었을 때만 비활성을 통지한다.
  const wasActiveRef = useRef(false);
  useEffect(() => {
    if (gameStarted) {
      wasActiveRef.current = true;
      onActiveChange(true);
    } else if (wasActiveRef.current) {
      onActiveChange(false);
    }
  }, [gameStarted, onActiveChange]);

  // 피격 진동 트리거. 같은 클래스를 다시 붙여도 CSS 애니메이션은 재생되지 않으므로 두 클래스를
  // 번갈아 붙여야 하는데, 그 기준을 교환 홀짝으로 삼으면 판이 짝수 교환에서 끝났을 때 다음 판
  // 첫 피격의 홀짝이 그대로여서 진동을 건너뛴다. 교환이 바뀔 때마다 1 늘어나는 카운터로 판단한다.
  const [shakeTick, setShakeTick] = useState(0);
  const shakeKeyRef = useRef<string | null>(null);
  useEffect(() => {
    if (!inEffectPlayback) return;
    const key = `${round}:${exchange}`;
    if (shakeKeyRef.current === key) return;
    shakeKeyRef.current = key;
    setShakeTick((tick) => tick + 1);
  }, [inEffectPlayback, round, exchange]);

  const effectTargetId = inEffectPlayback ? (lastAttack?.targetToken ?? null) : null;
  const effectSkillId = lastAttack?.skillId ?? requiredSkill?.skillId;
  const [cinematicPhase, setCinematicPhase] = useState<'idle' | 'blackout' | 'focus'>('idle');
  const pixelEffect = cinematicPhase === 'focus' && effectTargetId
    ? skillEffect(effectSkillId)
    : null;
  const shake = skillShake(effectSkillId);

  useEffect(() => {
    if (!inEffectPlayback || !effectTargetId) {
      setCinematicPhase('idle');
      return;
    }

    setCinematicPhase('blackout');
    const focusTimer = window.setTimeout(() => setCinematicPhase('focus'), CINEMATIC_BLACKOUT_MS);
    const endTimer = window.setTimeout(
      () => setCinematicPhase('idle'),
      CINEMATIC_BLACKOUT_MS + skillEffectMs(effectSkillId),
    );
    return () => {
      window.clearTimeout(focusTimer);
      window.clearTimeout(endTimer);
    };
  }, [effectSkillId, effectTargetId, exchange, inEffectPlayback, round]);

  useEffect(() => {
    if (cinematicPhase !== 'focus' || !effectTargetId) {
      setCinematicFocus(null);
      return;
    }

    const measureTarget = () => {
      const stage = stageRef.current;
      const target = tileRefs.current.get(effectTargetId);
      if (!stage || !target) return;

      const stageRect = stage.getBoundingClientRect();
      const targetRect = target.getBoundingClientRect();
      if (stageRect.width === 0 || stageRect.height === 0) return;

      setCinematicFocus({
        x: ((targetRect.left + targetRect.width / 2 - stageRect.left) / stageRect.width) * 100,
        y: ((targetRect.top + targetRect.height / 2 - stageRect.top) / stageRect.height) * 100,
      });
    };

    const frame = window.requestAnimationFrame(measureTarget);
    window.addEventListener('resize', measureTarget);
    return () => {
      window.cancelAnimationFrame(frame);
      window.removeEventListener('resize', measureTarget);
    };
  }, [cinematicPhase, effectTargetId, stageScale]);

  if (!gameStarted) return null;

  // 이펙트는 맞은 사람 타일에서 재생한다 — 데미지가 "누구에게" 들어갔는지가 화면에서 바로 읽힌다.
  // 진동은 스킬마다 다르다(단발 타격은 1회, 연발/굽이침은 이펙트가 끝날 때까지 반복).

  const renderTile = (id: string, seat: number) => {
    const trackRef = trackByIdentity.get(id);
    // HP는 데미지가 남은 체력보다 크면 음수로 내려온다 — 화면엔 0 미만을 보여주지 않는다.
    const value = Math.max(0, Math.min(MAX_HP, hp[id] ?? MAX_HP));
    // 탈락 표시(회색 + "탈락")는 이펙트가 끝난 뒤에 켠다 — 서버는 attack-resolved 하나로 HP 0과
    // 탈락을 같이 알려주므로, 그대로 그리면 맞는 순간 타일이 먼저 회색이 되고 그 위에서 이펙트가
    // 재생돼 "죽은 사람을 때리는" 순서로 보였다. 이펙트 대상인 동안은 살아있는 모습을 유지한다.
    const dead = !alivePlayers.includes(id) && effectTargetId !== id;
    const isMe = id === myId;
    // 공격권이 내게 있고 상대가 둘 이상일 때만 타일이 선택 버튼이 된다(한 명이면 자동 공격).
    const pickable =
      isMyAttack && !isMe && !dead && !isIntermission &&
      alivePlayers.filter((t) => t !== myId).length > 1;
    return (
      <div
        key={id}
        ref={(node) => {
          if (node) tileRefs.current.set(id, node);
          else tileRefs.current.delete(id);
        }}
        className={`ninja-tile ninja-tile--p${participantColorIndexById.get(id) ?? (seat % 4) + 1}${dead ? ' ninja-tile--dead' : ''}${
          cinematicPhase === 'focus' && effectTargetId === id ? ' ninja-tile--cinematic-target' : ''
        }${!dead && speakingIds.has(id) ? ' ninja-tile--speaking' : ''}`}
      >
        <div className="ninja-tile__cam participant-audio-host">
          {trackRef ? (
            <ParticipantTile trackRef={trackRef} disableSpeakingIndicator />
          ) : (
            <span className="ninja-tile__avatar pap-pixel-title">{nicknameOf(id).slice(0, 1)}</span>
          )}
          {/* 내 손 스켈레톤은 내 캠 위에 직접 그린다 — 랜드마크는 내 카메라에서만 나오므로
              (남의 랜드마크는 전송되지 않는다) 각자 자기 타일에서만 보인다. 이 컴포넌트가
              인식 루프를 소유하므로 게임 중 정확히 한 번만 마운트된다. */}
          {/* 탈락하면 인식 루프와 브로드캐스트를 끊는다 — 판정에 쓰이지 않는 추론을 매 프레임
              돌릴 이유가 없다(제출도 훅에서 이미 막혀 있다). 다음 판이 열리면 다시 켜진다. */}
          {/* 안내 문구(coach)는 실제로 손을 들고 있어야 하는 구간에서만 — 교환 사이 인터미션이나
              이펙트 재생 중에는 손을 내리는 게 정상이라 그때까지 "손을 보여주세요"가 뜨면 잔소리다.
              카운트다운은 자세를 잡는 시간이라 켜 둔다(그때 위치를 고치는 게 가장 도움이 된다). */}
          {isMe && (
            <GesturePanel
              variant="overlay"
              active={!isEliminated}
              coach={(!isIntermission || inCountdown) && !inEffectPlayback}
            />
          )}
          <ParticipantAudioControl identity={id} />
          {/* 탈락한 사람은 회색 오버레이 위에 표시가 겹치지 않게 뺀다 — 판에서 빠진 사람이라
              누가 말하는지 알려줄 대상이 아니다. */}
          {/* 이펙트는 이 타일 안에서만 재생된다 — 컴포넌트가 호스트 div 크기에 맞춰 그린다.
              시드의 모든 스킬이 skillEffects의 BY_SKILL_ID에 있어서 폴백 파티클은 없앴다 —
              매핑이 빠진 스킬이 생기면 이펙트 없이 진동만 남으니 스킬 추가 시 표를 함께 고친다. */}
          {effectTargetId === id && pixelEffect}
          {/* 닉네임은 전원 같은 방식(픽셀 스티커)으로 캠 위에 얹는다 — 내 것만 노란색 */}
          <span className={`ninja-tile__badge ninja-tile__badge--name${isMe ? ' ninja-tile__badge--me' : ''}`}>
            {nicknameOf(id)}
          </span>
          {dead && (
            <div className="ninja-tile__dead">
              <span className="pap-pixel-title">탈락</span>
            </div>
          )}
          {pickable && (
            <button type="button" className="ninja-tile__pick" onClick={() => void submitTarget(id)}>
              <span className="pap-pixel-title">공격!</span>
            </button>
          )}
        </div>

        <div className="ninja-hp">
          <div
            className={`ninja-hp__fill${value <= 30 ? ' ninja-hp__fill--low' : ''}`}
            style={{ width: `${Math.max(0, Math.min(100, value))}%` }}
          />
          {/* 픽셀 폰트(Galmuri11)는 11의 정수배에서만 선명한데 그 크기로는 영상 위에서 안 읽힌다 —
              이 숫자만 본문 폰트 16px로 간다. */}
          <span className="ninja-hp__num">
            {value} / {MAX_HP}
          </span>
        </div>
      </div>
    );
  };

  // 공격자가 대상을 고르는 중인가 — 남은 상대가 둘 이상일 때만 실제로 "고르는" 순간이 있다
  // (한 명이면 useNinjaRound가 자동으로 공격한다). 고르는 본인에게는 띄우지 않는다.
  const someoneElsePicking =
    !isIntermission &&
    currentAttackerToken !== null &&
    currentAttackerToken !== myId &&
    alivePlayers.filter((t) => t !== currentAttackerToken).length > 1;

  // 처치 알림 — 마지막 공격으로 HP가 0이 된 순간부터 인터미션이 끝날 때까지만 띄운다
  // (lastAttack은 다음 교환 시작 시 서버 이벤트가 null로 지운다).
  const killed = isIntermission && lastAttack?.targetEliminated ? lastAttack : null;

  return (
    <div className="ninja-screen">
      {/* 배경을 별도 레이어로 뺀 이유: 피격 진동을 이 레이어의 transform으로만 돌린다. 화면 전체를
          흔들면 캠 비디오·pixi 캔버스까지 매 프레임 재합성돼 무겁고, 영상이 같이 떨려서 어지럽다.
          이 레이어는 자식이 없어서 합성만 다시 하면 되고, scale로 살짝 키워둬서 흔들려도 여백이
          드러나지 않는다. key=shakeTick으로 매 피격마다 리마운트해 애니메이션을 재시작시킨다
          (같은 클래스를 다시 붙이는 것만으로는 재생되지 않는다 — 위 shakeTick 주석 참고). */}
      <div
        key={shakeTick}
        className={`ninja-screen__bg${inEffectPlayback ? ` ${shake.className}` : ''}`}
        style={inEffectPlayback ? { animationIterationCount: shake.iterations } : undefined}
      />
      {/* 닌자 전용 1440×810 스케일 캔버스 밖에 둬야 다른 게임과 같은 뷰포트 여백을 쓴다. */}
      <RoomTopBar
        musicSource="/assets/sounds/ninja-bgm.mp3"
        className="ninja-screen__topbar"
        onRequestLeave={() => setConfirmLeave(true)}
      />
      {/* 여기서부터가 1440×810 고정 캔버스. 배경은 이 밖(뷰포트 전체)에 있어서 비율이 안 맞는
          화면에서도 레터박스 검은 띠 대신 야경 배경이 그대로 보인다. */}
      <div
        ref={stageRef}
        className="ninja-stage"
        style={{ '--ninja-scale': stageScale } as React.CSSProperties}
      >
        {connectionBanner && (
          <div className={`ninja-connection-toast ninja-connection-toast--${connectionBanner.tone}`}>
            {connectionBanner.text}
          </div>
        )}
        {cinematicPhase !== 'idle' && effectTargetId && (
          <NinjaCinematicOverlay
            focus={cinematicPhase === 'focus' ? cinematicFocus : null}
            blackout={cinematicPhase === 'blackout'}
          />
        )}
      <div className="ninja-screen__body">
        <section className="ninja-screen__col ninja-screen__col--left">
          {leftSeats.map((p) => renderTile(p.identity, seats.indexOf(p)))}
        </section>

        {/* 중앙 — 타이머 + 따라할 인술. 게임 중 시선이 머무는 곳이라 여기만 보면 된다. */}
        <section className="ninja-screen__center">
          <div className="ninja-board">
            <div className="ninja-board__head">
              <p
                className={`ninja-board__timer pap-pixel-title${
                  !isIntermission && roundTimerSeconds !== null && roundTimerSeconds <= 5
                    ? ' ninja-board__timer--urgent'
                    : ''
                }`}
              >
                {isIntermission
                  ? inCountdown && countdownSeconds !== null
                    ? `00:${String(countdownSeconds).padStart(2, '0')}`
                    : '--:--'
                  : `00:${String(roundTimerSeconds ?? 0).padStart(2, '0')}`}
              </p>
            </div>

            {isIntermission ? (
              <div className="ninja-board__body ninja-board__body--intermission">
                {roundResult && roundResult.length > 0 && (
                  <>
                    <p className="ninja-board__label">대전 종료</p>
                    <ol className="ninja-result">
                      {roundResult.map((entry) => (
                        <li key={entry.token} className={entry.token === myId ? 'ninja-result--me' : ''}>
                          <span className="ninja-result__rank pap-pixel-title">{entry.rank}</span>
                          <span className="ninja-result__name">{nicknameOf(entry.token)}</span>
                        </li>
                      ))}
                    </ol>
                  </>
                )}
              </div>
            ) : isEliminated ? (
              /* 탈락자는 손동작이 판정되지 않는다(훅이 콤보 추적을 끊는다). 콤보 트래커를 그대로
                 두면 눌러도 반응이 없는 화면이 되므로, 관전 중임을 분명히 보여준다. */
              <div className="ninja-board__body ninja-board__body--eliminated">
                <p className="ninja-board__label">탈락</p>
                <p className="ninja-board__skill pap-pixel-title">관전 중</p>
                <p className="ninja-board__hint">이 판은 끝났어요. 손동작은 판정되지 않아요.</p>
                {requiredSkill && (
                  <p className="ninja-board__hint">지금 술법: {requiredSkill.skillName}</p>
                )}
              </div>
            ) : (
              requiredSkill && (
                <div className="ninja-board__body">
                  <p className="ninja-board__label">현재 술법</p>
                  <p className="ninja-board__skill pap-pixel-title">{requiredSkill.skillName}</p>

                  <p className="ninja-board__label ninja-board__label--steps">필요한 인술</p>
                  <ol className="ninja-seals">
                    {requiredSkill.gestures.map((step, index) => (
                      <li
                        key={step.seq}
                        className={`ninja-seal${
                          index < stepIndex
                            ? ' ninja-seal--done'
                            : index === stepIndex
                              ? ' ninja-seal--active'
                              : ''
                        }`}
                      >
                        <span className="ninja-seal__ring">
                          {gestureImage(step.gestureName) ? (
                            <>
                              <img
                                className="ninja-seal__img"
                                src={gestureImage(step.gestureName)!}
                                alt={step.gestureLabelKr}
                                title={step.gestureLabelKr}
                              />
                              <span className="ninja-seal__caption">{step.gestureLabelKr}</span>
                            </>
                          ) : (
                            /* 이미지가 등록되지 않은 손동작은 한글 라벨로 폴백 */
                            <span className="ninja-seal__kr">{step.gestureLabelKr}</span>
                          )}
                          {index === stepIndex && !completed && (
                            <svg className="ninja-seal__hold" viewBox="0 0 100 100" aria-hidden>
                              {/* 0.7초 유지 진행도를 링 둘레로 — 원형이라 막대보다 자리를 안 먹는다.
                                  둘레 = 2πr = 2π×46 ≈ 289 */}
                              <circle
                                className="ninja-seal__hold-track"
                                cx="50"
                                cy="50"
                                r="46"
                                pathLength="289"
                              />
                              <circle
                                className="ninja-seal__hold-fill"
                                cx="50"
                                cy="50"
                                r="46"
                                pathLength="289"
                                strokeDasharray="289"
                                strokeDashoffset={289 * (1 - holdProgress)}
                              />
                            </svg>
                          )}
                        </span>
                        {index < requiredSkill.gestures.length - 1 && (
                          <span className="ninja-seal__arrow" aria-hidden>
                            ›
                          </span>
                        )}
                      </li>
                    ))}
                  </ol>

                  {completed && !attackAccepted && !currentAttackerToken && (
                    <p className="ninja-board__flash">콤보 완성 — 전송 중</p>
                  )}
                  {attackAccepted && (
                    <p className="ninja-board__flash ninja-board__flash--win">공격권 획득!</p>
                  )}
                  {currentAttackerToken && !isMyAttack && (
                    <p className="ninja-board__flash ninja-board__flash--lost">
                      {nicknameOf(currentAttackerToken)} 님이 선점
                    </p>
                  )}

                  {isMyAttack && attackTimerSeconds !== null && (
                    <div className="ninja-board__pick">
                      <span className="ninja-board__pick-bar">
                        <span
                          style={{ width: `${(attackTimerSeconds / ATTACK_TARGET_TIMER_SECONDS) * 100}%` }}
                        />
                      </span>
                      <p className="ninja-board__hint">
                        {alivePlayers.filter((t) => t !== myId).length > 1
                          ? '캠에서 상대를 고르세요'
                          : '자동으로 공격합니다...'}
                      </p>
                    </div>
                  )}
                </div>
              )
            )}
          </div>
        </section>

        <section className="ninja-screen__col ninja-screen__col--right">
          {rightSeats.map((p) => renderTile(p.identity, seats.indexOf(p)))}
        </section>
      </div>

      {/* 공격권을 놓친 쪽은 지금까지 화면에 아무 변화가 없어서 "왜 멈췄지" 싶었다 —
          화면을 어둡게 덮어 지금이 남의 차례임을 알린다(누가 고르는지는 상단 알림이 말해준다). */}
      {someoneElsePicking && <div className="ninja-waiting" />}

      {/* 상단 알림 — 대기방 토스트와 같은 위치·크기, 색만 어둡게 하고 쿠나이를 달았다.
          처치 알림과 대상 지정 알림은 동시에 뜨지 않는다(전자는 인터미션, 후자는 라운드 중). */}
      {(killed || someoneElsePicking) && (
        <div className="ninja-toast">
          <KunaiIcon />
          {killed ? (
            <span>
              <strong>{nicknameOf(killed.attackerToken)}</strong> 님이{' '}
              <strong>{nicknameOf(killed.targetToken)}</strong> 님을 처치했어요
            </span>
          ) : (
            <span>
              <strong>{nicknameOf(currentAttackerToken)}</strong> 님이 대상을 고르는 중
              <span className="ninja-waiting__dots" aria-hidden>
                ...
              </span>
            </span>
          )}
        </div>
      )}

      {/* 세트가 끝난 뒤의 순위 발표는 이 화면이 하지 않는다 — 코스 공통 중간 결과 화면
          (SetResultScreen)이 게임 위에 덮어 그린다. 여기서 또 띄우면 두 겹으로 겹친다. */}

        {error && <p className="ninja-screen__error">{error}</p>}
      </div>
      {/* 스테이지 밖에 둔다 — 안에 넣으면 --ninja-scale 확대/축소를 같이 받는다 */}
      {confirmLeave && (
        <PixelConfirmModal
          title="정말 방을 나갈까요?"
          message="현재 방과 게임 결과에서 나가 메인 화면으로 이동해요."
          confirmLabel="방 나가기"
          cancelLabel="취소"
          tone="danger"
          onConfirm={onLeave}
          onCancel={() => setConfirmLeave(false)}
        />
      )}
    </div>
  );
}
