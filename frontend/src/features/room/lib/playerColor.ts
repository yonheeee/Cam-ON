const PLAYER_COLOR_COUNT = 4;

/**
 * 방 입장 순서를 참가자 색의 유일한 기준으로 사용한다.
 * joinOrder를 아직 받지 못한 짧은 구간에는 participantId 정렬처럼 모든 클라이언트에서
 * 동일한 fallbackOrder를 넘겨 화면마다 색이 달라지지 않게 한다.
 */
export function playerColorIndex(
  participantId: string,
  joinOrder: readonly string[],
  fallbackOrder: readonly string[] = [],
) {
  const joinedIndex = joinOrder.indexOf(participantId);
  const fallbackIndex = fallbackOrder.indexOf(participantId);
  const index = joinedIndex >= 0 ? joinedIndex : Math.max(0, fallbackIndex);
  return (index % PLAYER_COLOR_COUNT) + 1;
}

export function playerColor(
  participantId: string,
  joinOrder: readonly string[],
  fallbackOrder: readonly string[] = [],
) {
  return `var(--pap-player-${playerColorIndex(participantId, joinOrder, fallbackOrder)})`;
}

export function playerTextColor(
  participantId: string,
  joinOrder: readonly string[],
  fallbackOrder: readonly string[] = [],
) {
  return `var(--pap-player-${playerColorIndex(participantId, joinOrder, fallbackOrder)}-text)`;
}
