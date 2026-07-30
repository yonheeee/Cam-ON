import { useLocalParticipant } from '@livekit/components-react';
import { useCallback } from 'react';
import { useFetchRound } from '../hooks/useFetchRound';
import { FetchObjectGame } from './FetchObjectGame';

interface FetchCoursePanelProps {
  roomId: string;
  /** 카탈로그 gameId — game:started(코스)에서 온 값. Spring 제출 경로 검증에 쓴다 */
  gameId: number;
  accessToken: string;
  /** participantId → 닉네임 (방 스냅샷 기준 — round:success 등 이벤트가 id만 주므로 필요) */
  nicknameById: Map<string, string>;
  onLeave: () => void;
}

// 코스가 연 물건 가져오기 세션의 화면 — 서버 주도 진행(useFetchRound)을 mock과 같은
// 화면(FetchObjectGame)에 붙이는 어댑터. 진행 콜백(onEndRound/onNextRound/onExit)을 넘기지
// 않는 것이 곧 "서버 주도 모드" 스위치다.
export function FetchCoursePanel({
  roomId,
  gameId,
  accessToken,
  nicknameById,
  onLeave,
}: FetchCoursePanelProps) {
  const { localParticipant } = useLocalParticipant();
  const myNickname = localParticipant.name || '나';

  const resolveNickname = useCallback(
    (participantId: string) => nicknameById.get(participantId) ?? '참가자',
    [nicknameById],
  );
  const { state, submit } = useFetchRound(roomId, gameId, accessToken, resolveNickname);

  // 첫 round:start가 오기 전(막 시작) 또는 게임 도중 새로고침 직후 — 백엔드에 상태 조회
  // GET이 없어서 다음 라운드 시작까지(최대 23초) 제시어를 알 수 없다. 대기 화면으로 버틴다.
  if (state.phase === 'idle') {
    return (
      <div className="video-call-room__intermission">
        <p className="pap-pixel-title">물건 가져오기를 준비하고 있어요...</p>
      </div>
    );
  }

  return (
    <FetchObjectGame
      state={state}
      myNickname={myNickname}
      onReportSuccess={() => void submit()}
      onLeave={onLeave}
    />
  );
}
