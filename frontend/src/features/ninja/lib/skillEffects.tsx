import { PixelCatPunchEffect } from '../components/PixelCatPunchEffect';
import { PixelLightningEffect } from '../components/PixelLightningEffect';
import { PixelPhoenixFlowerEffect } from '../components/PixelPhoenixFlowerEffect';
import { PixelWaterDragonEffect } from '../components/PixelWaterDragonEffect';
import { PixelWindScarEffect } from '../components/PixelWindScarEffect';

// DB skill.id → 픽셀 이펙트 컴포넌트. 지금까진 이 매핑이 없어서 8종(3,142줄)이 /effects-preview에만
// 쓰였고, 실제 게임 인터미션에는 effect 테이블의 색/파티클 수로 그리는 원형 버스트만 나왔다.
// 이펙트는 "맞은 사람 캠 타일" 안에서 재생되므로(호스트 div에 resizeTo) variant는 항상 hit이다.
// 대응 스킬이 없는 CherryBlossomSlash/Rasengan/Amaterasu는 일부러 뺐다 — 스킬 시드가 생기면 추가한다.
const BY_SKILL_ID: Record<number, () => React.ReactElement> = {
  1: () => <PixelLightningEffect />, // 뇌절
  2: () => <PixelPhoenixFlowerEffect variant="hit" />, // 봉선화의 술
  3: () => <PixelWaterDragonEffect variant="hit" />, // 수룡탄의 술
  4: () => <PixelCatPunchEffect />, // 냥냥펀치
  5: () => <PixelWindScarEffect />, // 바람의 상처
};

/** 스킬에 대응하는 픽셀 이펙트. 매핑이 없는 스킬이면 null(호출부가 파티클 폴백을 쓴다). */
export function skillEffect(skillId: number | null | undefined) {
  if (skillId == null) return null;
  return BY_SKILL_ID[skillId]?.() ?? null;
}
