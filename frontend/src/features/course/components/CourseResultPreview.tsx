import { LiveKitRoom } from '@livekit/components-react';
import { CourseResultScreen } from './CourseResultScreen';

const RANKING = [
  { participantId: 'p1', totalScore: 260, rank: 1 },
  { participantId: 'p2', totalScore: 210, rank: 2 },
  { participantId: 'p3', totalScore: 170, rank: 3 },
  { participantId: 'p4', totalScore: 120, rank: 4 },
];

const NICKNAMES = new Map([
  ['p1', '민수'],
  ['p2', '지윤'],
  ['p3', '준호'],
  ['p4', '서연'],
]);

// [개발 전용] /dev/course-result — 방을 만들지 않고 최종 결과 화면 배치만 확인하는 진입로.
// 캠 타일은 LiveKit 컨텍스트를 요구하므로 연결하지 않는(connect={false}) 빈 Room으로 감싼다 —
// 트랙이 없으니 실제 화면과 같은 "닉네임 첫 글자" 폴백이 그려진다.
export function CourseResultPreview() {
  return (
    <LiveKitRoom serverUrl="" token="" connect={false}>
      <CourseResultScreen
        ranking={RANKING}
        totalSessions={3}
        nicknameById={NICKNAMES}
        participantId="p2"
        // 먼저 대기방으로 간 사람의 "대기방으로 갔어요" 표시까지 미리보기에 담는다
        returnedParticipantIds={new Set(['p4'])}
        onReturnToLobby={() => {}}
        returning={false}
        returnError={null}
        onLeave={() => {}}
        participantColorIndexById={new Map(
          RANKING.map((entry, index) => [entry.participantId, index + 1]),
        )}
      />
    </LiveKitRoom>
  );
}
