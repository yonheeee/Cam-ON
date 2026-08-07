import { PixelAmaterasuEffect, DURATION_MS as AMATERASU_MS } from '../components/PixelAmaterasuEffect';
import { PixelBaldEffect, DURATION_MS as BALD_MS } from '../components/PixelBaldEffect';
import { PixelCatPunchEffect, DURATION_MS as CAT_PUNCH_MS } from '../components/PixelCatPunchEffect';
import {
  PixelCherryBlossomSlashEffect,
  DURATION_MS as CHERRY_BLOSSOM_MS,
} from '../components/PixelCherryBlossomSlashEffect';
import { PixelLightningEffect, DURATION_MS as LIGHTNING_MS } from '../components/PixelLightningEffect';
import { PixelPhoenixFlowerEffect, DURATION_MS as PHOENIX_MS } from '../components/PixelPhoenixFlowerEffect';
import { PixelRasenganEffect, DURATION_MS as RASENGAN_MS } from '../components/PixelRasenganEffect';
import { PixelSlapEffect, DURATION_MS as SLAP_MS } from '../components/PixelSlapEffect';
import { PixelWaterDragonEffect, DURATION_MS as WATER_DRAGON_MS } from '../components/PixelWaterDragonEffect';
import { PixelWindScarEffect, DURATION_MS as WIND_SCAR_MS } from '../components/PixelWindScarEffect';
// Pixel*Effect 들이 공유하는 .ninja-effect-overlay 컨테이너 스타일. 게임 경로에서 이펙트를
// 고르는 곳이 여기라, 스타일도 여기서 한 번 가져오면 컴포넌트 8개에 흩어놓지 않아도 된다.
import '../components/pixelEffectOverlay.css';

// DB skill.id → 픽셀 이펙트 컴포넌트.
// 이펙트는 "맞은 사람 캠 타일" 안에서 재생되므로(호스트 div에 resizeTo) variant는 항상 hit이다.
//
// 시드의 모든 스킬이 여기 있어야 한다 — 매핑이 빠진 스킬은 이펙트 없이 진동만 남는다(파티클
// 폴백을 없앴다). 스킬을 추가하면 DevNinjaDataSeeder와 이 표를 함께 고친다.
//
// id는 DevNinjaDataSeeder의 seedSkill 호출 순서(auto_increment)에 묶여 있다 — 시더의 호출
// 순서를 바꾸면 새 DB에서 이 표가 어긋난다. 순서를 바꾸지 말고 뒤에만 추가할 것.
//
// 신규 스킬 3종도 각각의 전용 Pixel*Effect를 사용한다.
const BY_SKILL_ID: Record<number, () => React.ReactElement> = {
  1: () => <PixelLightningEffect />, // 뇌절
  2: () => <PixelPhoenixFlowerEffect variant="hit" />, // 봉선화의 술
  3: () => <PixelWaterDragonEffect variant="hit" />, // 수룡탄의 술
  4: () => <PixelCatPunchEffect />, // 냥냥펀치
  5: () => <PixelWindScarEffect />, // 바람의 상처
  6: () => <PixelAmaterasuEffect />, // 아마테라스
  7: () => <PixelRasenganEffect />, // 나선환
  8: () => <PixelCherryBlossomSlashEffect />, // 벚꽃 참격
  9: () => <PixelBaldEffect />, // 탈모빔
  10: () => <PixelSlapEffect />, // slap
};

// 각 이펙트가 실제로 재생되는 길이(컴포넌트의 DURATION_MS 그대로). 서버 이펙트 창(5초)이 아니라
// 이 값이 진짜 재생 시간이라, 피격 진동처럼 이펙트에 맞춰야 하는 연출은 이걸 기준으로 한다.
const MS_BY_SKILL_ID: Record<number, number> = {
  1: LIGHTNING_MS,
  2: PHOENIX_MS,
  3: WATER_DRAGON_MS,
  4: CAT_PUNCH_MS,
  5: WIND_SCAR_MS,
  6: AMATERASU_MS,
  7: RASENGAN_MS,
  8: CHERRY_BLOSSOM_MS,
  9: BALD_MS,
  10: SLAP_MS,
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
  // 아래 둘은 전용 진동을 새로 만들지 않고 성격이 가까운 기존 키프레임을 재사용했다 —
  // 감각이 애매하면 CSS에 키프레임을 추가하고 여기만 바꾸면 된다.
  6: { name: 'base', cycleMs: 420, fill: true }, // 아마테라스 — 계속 타오르는 지속형
  7: { name: 'roll', cycleMs: 900, fill: true }, // 나선환 — 회전하며 갈아내는 느낌
  8: { name: 'slash', cycleMs: 340 }, // 벚꽃 참격 — 참격이라 바람의 상처와 같은 밀림
  9: { name: 'zap', cycleMs: 260 }, // 탈모빔 — 빔이라 고주파 1회
  10: { name: 'punch', cycleMs: 460 }, // slap — 손바닥 타격 1회
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
