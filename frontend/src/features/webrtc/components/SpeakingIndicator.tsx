import type { CSSProperties } from 'react';
import './SpeakingIndicator.css';

interface SpeakingIndicatorProps {
  active: boolean;
  color?: string;
}

// 말하는 사람 표시 — 캠 테두리를 참가자 고유색으로 켜고 상단 가운데에 파형 배지를 얹는다.
//
// 대기방·닌자·몸으로말해요·물건가져오기·종합결과가 각자 다른 캠 껍데기를 쓰므로, 레이아웃을
// 건드리지 않는 absolute 오버레이 하나로 통일했다(부모 캠 컨테이너가 position 컨텍스트를 갖는다는
// 전제 — 여섯 곳 모두 이미 relative/absolute다). 자리를 차지하지 않으니 타일마다 배치를 손볼
// 필요가 없고, 표시 규격이 화면별로 갈라지지도 않는다.
//
// 위치가 상단 가운데인 이유: 좌상단(READY 배지·닉네임 스티커)과 우상단(왕관)은 이미 쓰이고
// 하단은 이름 바가 차지한다 — 여섯 화면 모두에서 비어 있는 자리가 여기뿐이다.
export function SpeakingIndicator({ active, color }: SpeakingIndicatorProps) {
  if (!active) return null;

  return (
    <span
      className="speaking-indicator"
      title="말하는 중"
      aria-hidden="true"
      style={color ? ({ '--speaking-color': color } as CSSProperties) : undefined}
    >
      <span className="speaking-indicator__wave">
        <i className="speaking-indicator__bar" />
        <i className="speaking-indicator__bar" />
        <i className="speaking-indicator__bar" />
      </span>
    </span>
  );
}
