import { PixelCatPunchEffect, DURATION_MS as CAT_PUNCH_MS } from '../components/PixelCatPunchEffect';
import { PixelLightningEffect, DURATION_MS as LIGHTNING_MS } from '../components/PixelLightningEffect';
import { PixelPhoenixFlowerEffect, DURATION_MS as PHOENIX_MS } from '../components/PixelPhoenixFlowerEffect';
import { PixelWaterDragonEffect, DURATION_MS as WATER_DRAGON_MS } from '../components/PixelWaterDragonEffect';
import { PixelWindScarEffect, DURATION_MS as WIND_SCAR_MS } from '../components/PixelWindScarEffect';

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

// 각 이펙트가 실제로 재생되는 길이(컴포넌트의 DURATION_MS 그대로). 서버 이펙트 창(5초)이 아니라
// 이 값이 진짜 재생 시간이라, 피격 진동처럼 이펙트에 맞춰야 하는 연출은 이걸 기준으로 한다.
const MS_BY_SKILL_ID: Record<number, number> = {
  1: LIGHTNING_MS,
  2: PHOENIX_MS,
  3: WATER_DRAGON_MS,
  4: CAT_PUNCH_MS,
  5: WIND_SCAR_MS,
};

/** 매핑이 없는 스킬(파티클 폴백)의 재생 길이 — 픽셀 이펙트들의 평균 근처. */
export const FALLBACK_EFFECT_MS = 1800;

// 스킬별 피격 진동. 이펙트가 5종인데 진동이 하나뿐이라 어떤 술을 맞아도 감각이 같았다 —
// 이펙트의 성격(단발/연발/굽이침)에 진동을 맞춘다.
//   cycleMs: 키프레임 한 바퀴 길이. CSS의 animation-duration과 반드시 같은 값이어야 한다.
//   fill: 이펙트가 끝날 때까지 반복할지. 연발/지속형만 true, 단발 타격은 1회로 끝낸다.
const SHAKE_BY_SKILL_ID: Record<number, { name: string; cycleMs: number; fill?: boolean }> = {
  1: { name: 'zap', cycleMs: 260 }, // 뇌절 — 잔진폭 고주파 1회, 급감쇠
  2: { name: 'volley', cycleMs: 520, fill: true }, // 봉선화 — 탄이 여러 발이라 연타
  3: { name: 'roll', cycleMs: 900, fill: true }, // 수룡탄 — 크고 느린 굽이침
  4: { name: 'punch', cycleMs: 460 }, // 냥냥펀치 — 스윙 두 번이라 진동도 2연타
  5: { name: 'slash', cycleMs: 340 }, // 바람의 상처 — 대각 한 방향 밀림 + 미세 회전
};

const FALLBACK_SHAKE = { name: 'base', cycleMs: 420, fill: true };

/** 그 스킬을 맞았을 때의 진동 클래스와 반복 횟수. 매핑이 없으면 기존 범용 진동. */
export function skillShake(skillId: number | null | undefined) {
  const shake = (skillId != null ? SHAKE_BY_SKILL_ID[skillId] : undefined) ?? FALLBACK_SHAKE;
  return {
    className: `ninja-screen__bg--shake-${shake.name}`,
    iterations: shake.fill ? Math.max(1, Math.round(skillEffectMs(skillId) / shake.cycleMs)) : 1,
  };
}

/** 스킬에 대응하는 픽셀 이펙트. 매핑이 없는 스킬이면 null(호출부가 파티클 폴백을 쓴다). */
export function skillEffect(skillId: number | null | undefined) {
  if (skillId == null) return null;
  return BY_SKILL_ID[skillId]?.() ?? null;
}

/** 그 스킬 이펙트의 실제 재생 시간(ms). 매핑이 없으면 폴백 길이. */
export function skillEffectMs(skillId: number | null | undefined) {
  return (skillId != null ? MS_BY_SKILL_ID[skillId] : undefined) ?? FALLBACK_EFFECT_MS;
}
