import { HAND_COACH_MESSAGE, type HandCoachIssue } from '../lib/handCoachHint';
import './HandCoachHint.css';

interface HandCoachHintProps {
  /** useHandCoachHint가 돌려준 원인. null이면 아무것도 그리지 않는다. */
  issue: HandCoachIssue | null;
  /** 호출부에서 위치를 조정할 때(설정 모달의 디버그 readout 회피 등) */
  className?: string;
}

/* 손 인식이 막혔을 때 캠 위에 뜨는 안내 문구.
 *
 * 자기 캠 안에 띄운다 — "내 화면이 이렇게 잡히고 있다"는 맥락 바로 위에 있어야 다가오라는 말이
 * 무슨 뜻인지 바로 읽힌다. 위치는 아래쪽 가운데: 닉네임 배지(좌상단)와 겹치지 않고, 손은 보통
 * 화면 중앙~위쪽에 있어서 안내가 손을 가리지도 않는다.
 *
 * 부모는 position: relative여야 한다(.ninja-tile__cam, .settings-modal__preview 모두 그렇다). */
export function HandCoachHint({ issue, className }: HandCoachHintProps) {
  if (!issue) return null;

  return (
    <p
      className={`hand-coach hand-coach--${issue}${className ? ` ${className}` : ''}`}
      role="status"
      aria-live="polite"
    >
      {HAND_COACH_MESSAGE[issue]}
    </p>
  );
}
