import { clearRoom } from '../../room/lib/roomStorage';
import { clearSession } from './sessionStorage';

// 저장된 accessToken이 더 이상 통하지 않게 되는 경우는 만료(12시간)만이 아니다 — 백엔드가
// local 프로필로 도는 동안은 재시작할 때마다 JWT 서명키가 새로 생성돼서, 그 순간 발급된 모든
// 토큰이 한꺼번에 무효가 된다. 강퇴/방 종료 후 남은 세션도 같은 상태가 된다.
//
// 예전엔 이 상황을 처리하는 코드가 아예 없어서: REST는 401을 받아 화면에 원인 불명 에러 문구만
// 남기고, STOMP는 reconnectDelay(3초)로 죽은 토큰을 들고 영원히 재연결을 시도했다. 사용자는
// 스스로 빠져나갈 방법이 없었다. 그래서 죽은 세션을 감지하면 여기서 한 번에 정리하고 첫 화면으로
// 되돌린다 — 회원가입이 없는 서비스라 복구는 "닉네임 다시 입력"으로 끝난다.

const EXPIRED_NOTICE_KEY = 'camon.sessionExpired';

// 방 화면은 STOMP를 여러 개(로비/하트비트/코스/게임별) 열기 때문에 토큰이 죽으면 실패가 동시에
// 여러 건 들어온다. 정리와 화면 이동을 여러 번 트리거하지 않도록 한 번만 처리한다.
let handled = false;

export function handleExpiredSession(): void {
  if (handled) return;
  handled = true;

  // 첫 화면에서 왜 돌아왔는지 알려주기 위한 표시. 저장분을 지우기 전에 세운다.
  sessionStorage.setItem(EXPIRED_NOTICE_KEY, '1');
  clearSession();
  clearRoom();

  // api 모듈과 STOMP 콜백은 React Router 밖이라 navigate를 쓸 수 없다. 또한 죽은 토큰을 들고
  // 있는 훅들이 전부 정리돼야 재연결 루프가 멈추므로, 라우팅이 아니라 문서를 새로 띄운다.
  window.location.replace('/');
}

/** 첫 화면에서 한 번 읽고 지운다(뒤로가기 등으로 안내가 계속 남지 않게). */
export function consumeSessionExpiredNotice(): boolean {
  const expired = sessionStorage.getItem(EXPIRED_NOTICE_KEY) === '1';
  if (expired) {
    sessionStorage.removeItem(EXPIRED_NOTICE_KEY);
  }
  return expired;
}

/** REST 응답이 "이 세션은 끝났다"는 뜻인지. 401은 토큰 자체가 무효라는 신호다. */
export function isSessionDead(status: number): boolean {
  return status === 401;
}
