import { GAME_LABELS } from '../api/courseApi';
import { SetResultScreen } from './SetResultScreen';

// [개발 전용] /dev/set-result — 방을 만들지 않고 중간 결과 화면 배치만 확인하는 진입로.
export function SetResultPreview() {
  return (
    <SetResultScreen
      setIndex={1}
      totalSets={3}
      participantId="p2"
      setResult={[
        { participantId: 'p1', score: 100, rank: 1 },
        { participantId: 'p2', score: 70, rank: 2 },
        { participantId: 'p3', score: 40, rank: 3 },
        { participantId: 'p4', score: 20, rank: 4 },
      ]}
      courseRanking={[
        { participantId: 'p1', totalScore: 100, rank: 1, delta: 'up' },
        { participantId: 'p2', totalScore: 70, rank: 2, delta: 'same' },
        { participantId: 'p3', totalScore: 40, rank: 3, delta: 'down' },
        { participantId: 'p4', totalScore: 20, rank: 4, delta: 'same' },
      ]}
      nicknameById={
        new Map([
          ['p1', '민수'],
          ['p2', '지윤'],
          ['p3', '준호'],
          ['p4', '서연'],
        ])
      }
      nextGameLabel={GAME_LABELS.NINJA}
      isHost
      secondsLeft={7}
      onNext={() => {}}
    />
  );
}
