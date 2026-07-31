// 로비에서 쓰는 작은 인라인 SVG 아이콘 모음.
// (Figma 원칙상 디자인 확정 아이콘은 Material Icons 페이지 기준 — 확정되면 여기만 교체)

export const CopyIcon = (
  <svg width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true">
    <rect x="5" y="5" width="9" height="9" rx="1.5" stroke="currentColor" strokeWidth="1.8" />
    <path d="M11 3H4.5A1.5 1.5 0 0 0 3 4.5V11" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
  </svg>
);

export const LinkIcon = (
  <svg width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true">
    <path d="M6.5 9.5 9.5 6.5" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
    <path d="M7 4.8 8.6 3.2a2.7 2.7 0 0 1 3.8 3.8L10.8 8.6" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
    <path d="M9 11.2 7.4 12.8a2.7 2.7 0 0 1-3.8-3.8L5.2 7.4" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
  </svg>
);

export const CamOnIcon = (
  <svg width="15" height="15" viewBox="0 0 16 16" fill="none" aria-hidden="true">
    <rect x="1.5" y="4" width="9" height="8" rx="1.5" stroke="currentColor" strokeWidth="1.8" />
    <path d="m10.5 7 4-2v6l-4-2" stroke="currentColor" strokeWidth="1.8" strokeLinejoin="round" />
  </svg>
);

export const CamOffIcon = (
  <svg width="15" height="15" viewBox="0 0 16 16" fill="none" aria-hidden="true">
    <rect x="1.5" y="4" width="9" height="8" rx="1.5" stroke="currentColor" strokeWidth="1.8" />
    <path d="m10.5 7 4-2v6l-4-2" stroke="currentColor" strokeWidth="1.8" strokeLinejoin="round" />
    <path d="m2 2 12 12" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
  </svg>
);

export const MicOnIcon = (
  <svg width="15" height="15" viewBox="0 0 16 16" fill="none" aria-hidden="true">
    <rect x="5.5" y="1.5" width="5" height="8" rx="2.5" stroke="currentColor" strokeWidth="1.8" />
    <path d="M3 8a5 5 0 0 0 10 0M8 13v2" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
  </svg>
);

export const MicOffIcon = (
  <svg width="15" height="15" viewBox="0 0 16 16" fill="none" aria-hidden="true">
    <rect x="5.5" y="1.5" width="5" height="8" rx="2.5" stroke="currentColor" strokeWidth="1.8" />
    <path d="M3 8a5 5 0 0 0 10 0M8 13v2" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
    <path d="m2 2 12 12" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
  </svg>
);

export const GearIcon = (
  // Material Design "settings" 형태 — 이빨 달린 톱니가 명확히 보이는 채움형
  <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
    <path d="M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58a.49.49 0 0 0 .12-.61l-1.92-3.32a.488.488 0 0 0-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54a.484.484 0 0 0-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96a.488.488 0 0 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58a.49.49 0 0 0-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z" />
  </svg>
);

// 방장 표시 — 금색 채움 + 잉크 외곽선. 색은 currentColor(--pap-host-gold)로 제어한다.
export const CrownIcon = (
  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" aria-hidden="true">
    <path
      d="M3 8v9.5h18V8l-4.5 3L12 5.5 7.5 11 3 8Z"
      fill="currentColor"
      stroke="var(--pap-ink, #2b1833)"
      strokeWidth="1.8"
      strokeLinejoin="round"
    />
  </svg>
);

// 토스트 앞머리 — 초록 원형 채움 + 흰 체크 (Figma `Icon / check`)
export const CheckCircleIcon = (
  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" aria-hidden="true">
    <circle cx="12" cy="12" r="10" fill="currentColor" />
    <path
      d="m7.2 12.4 3.2 3.2 6.4-7"
      stroke="#fffdf5"
      strokeWidth="2.4"
      strokeLinecap="round"
      strokeLinejoin="round"
    />
  </svg>
);

// 준비 버튼 / 토스트 — Material "check"
export const CheckIcon = (
  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" aria-hidden="true">
    <path
      d="m4.5 12.8 5 5L19.5 7.2"
      stroke="currentColor"
      strokeWidth="2.6"
      strokeLinecap="round"
      strokeLinejoin="round"
    />
  </svg>
);

// 하단 주 액션 — Material "play_arrow"
export const PlayIcon = (
  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" aria-hidden="true">
    <path d="M8 5.5v13l11-6.5-11-6.5Z" stroke="currentColor" strokeWidth="2" strokeLinejoin="round" />
  </svg>
);

export const ChevronUpIcon = (
  <svg width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true">
    <path d="m3.5 10 4.5-4.5L12.5 10" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
  </svg>
);

export const ChevronDownIcon = (
  <svg width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true">
    <path d="m3.5 6 4.5 4.5L12.5 6" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
  </svg>
);

// 강퇴 버튼(방장 전용) — 캠/마이크 토글과 같은 크기의 X.
export const KickIcon = (
  <svg width="15" height="15" viewBox="0 0 16 16" fill="none" aria-hidden="true">
    <path d="m4 4 8 8M12 4l-8 8" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
  </svg>
);

// 재접속 대기 로딩 — 원을 따라 8개 픽셀 블록이 돌아가는 아케이드형 스피너.
// (CSS에서 steps(8)로 회전시켜 한 칸씩 딸깍 넘어가게 한다)
export const SpinnerIcon = (
  <svg width="24" height="24" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
    {[0, 45, 90, 135, 180, 225, 270, 315].map((deg, i) => (
      <rect
        key={deg}
        x="10.6"
        y="1.6"
        width="2.8"
        height="5.2"
        opacity={0.16 + i * 0.12}
        transform={`rotate(${deg} 12 12)`}
      />
    ))}
  </svg>
);

// 아직 코스 결과 화면에 남아 있는 참가자("게임 중") 타일 오버레이용 트로피 —
// 재접속 스피너와 같은 자리에 같은 크기로 쓴다.
export const InResultIcon = (
  <svg width="32" height="32" viewBox="0 0 16 16" fill="none" aria-hidden="true">
    <path
      d="M4.5 2h7v3.5a3.5 3.5 0 0 1-7 0V2Z"
      stroke="currentColor"
      strokeWidth="1.6"
      strokeLinejoin="round"
    />
    <path
      d="M4.5 3H2.8v1.2A2.2 2.2 0 0 0 5 6.4M11.5 3h1.7v1.2A2.2 2.2 0 0 1 11 6.4"
      stroke="currentColor"
      strokeWidth="1.4"
      strokeLinecap="round"
    />
    <path
      d="M8 9v2.5M5.5 14h5M6.5 11.5h3l.6 2.5H5.9l.6-2.5Z"
      stroke="currentColor"
      strokeWidth="1.5"
      strokeLinecap="round"
      strokeLinejoin="round"
    />
  </svg>
);
