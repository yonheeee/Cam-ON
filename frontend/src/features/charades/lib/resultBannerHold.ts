// 턴 결과 배너("정답!" / "시간 초과!")를 최소 이만큼 보여준다. 백엔드는 결과가 확정되면 딜레이 없이
// 다음 턴을 열거나(CharadesGameService의 submitGuess·handleTimeout → advanceAfterTerminalTurn 동기
// 호출) 마지막 턴이면 곧바로 charades:game-ended를 발행한다 — 둘 다 거의 즉시 도착하므로, 프론트가
// 이 시간만큼 붙잡아뒀다가 반영해서 배너가 잘리지 않게 한다.
//
// 이 값을 바꾸면 배너에 표시되는 카운트다운 숫자도 같이 바뀐다(CharadesGamePanel이 같은 상수를
// 초로 환산해 쓴다).
//
// 주의: 마지막 턴에서는 이 유예가 세트 결과 화면 시간을 그만큼 잡아먹는다. 세트 결과 길이(8초)는
// charades:game-ended 시점부터 재는 서버 타이머라(CourseRunner.SET_RESULT_DURATION) 프론트가
// 늦춘 만큼 짧아진다 — 현재 3초 유예 = 세트 결과 5초.
export const RESULT_BANNER_HOLD_MS = 3000;

// 배너를 띄운 시각(shownAt) 기준으로 아직 붙잡아둬야 하는 시간. 붙잡을 필요가 없으면 0.
export function remainingResultHoldMs(shownAt: number | null): number {
  if (shownAt === null) return 0;
  return Math.max(0, RESULT_BANNER_HOLD_MS - (Date.now() - shownAt));
}
