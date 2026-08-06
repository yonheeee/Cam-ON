// 발표 시연 모드 — 랜딩 페이지의 토글 버튼으로 켜고, 그 상태로 만든 방이 "시연용 방"이 된다.
//
// 무엇이 바뀌는지는 전부 서버(백엔드 DemoScenario)가 정한다. 프론트는 "이 방을 시연 모드로
// 열어 달라"는 플래그 하나만 방 생성 요청에 실어 보낸다 — 게임 규칙이 클라이언트마다 갈리면
// 안 되기 때문(참가자는 방장이 만든 방의 설정을 그대로 따라간다).
//
// 발표가 끝나면 이 파일과 LandingPage의 트리거, 대기방 배지만 지우면 원래대로 돌아온다.

const STORAGE_KEY = 'camon.demoMode';

// 트리거가 성립하는 연타 수와 간격. 간격을 넘겨 누르면 처음부터 다시 센다 —
// 로고를 심심해서 두어 번 누른 사람이 실수로 켜지 않도록.
export const DEMO_TRIGGER_CLICKS = 5;
export const DEMO_TRIGGER_GAP_MS = 800;

// sessionStorage에 두는 이유: 탭을 닫으면 저절로 꺼진다. 발표가 끝난 뒤 켜진 줄 모르고
// 계속 시연용 방을 만드는 사고를 막는 안전장치다.
export function isDemoArmed(): boolean {
  try {
    return sessionStorage.getItem(STORAGE_KEY) === 'true';
  } catch {
    return false;
  }
}

export function setDemoArmed(armed: boolean): void {
  try {
    if (armed) {
      sessionStorage.setItem(STORAGE_KEY, 'true');
    } else {
      sessionStorage.removeItem(STORAGE_KEY);
    }
  } catch {
    // 시크릿 모드 등에서 저장이 막히면 시연 모드를 못 켤 뿐, 일반 진행에는 영향이 없다.
  }
}
