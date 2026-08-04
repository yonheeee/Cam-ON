/**
 * 효과음(버튼·이펙트·카운트다운 등) 공통 음량.
 *
 * 컨텍스트가 아니라 모듈 스토어인 이유: 효과음은 React 트리 밖에서 명령형으로 재생된다
 * (ButtonSounds는 document click 리스너에서, 나머지는 effect 안에서 `new Audio().play()`).
 * 컨텍스트로 만들면 재생 지점마다 훅을 끌어와야 하고, App 최상단에 Provider도 하나 더
 * 필요하다. 여기서는 `mixSfxVolume()` 함수 하나만 부르면 되므로 재생 지점이 React인지
 * 아닌지와 무관하다.
 *
 * 각 효과음이 갖고 있던 기준 음량(버튼 0.55, 이펙트 0.85 …)은 서로의 상대적인 크기를 맞춘
 * 값이라 그대로 둔다. 사용자 설정은 그 위에 곱해서 전체를 같은 비율로 키우거나 줄인다.
 */
const SFX_VOLUME_KEY = 'camon:sfx-volume';

function clamp(value: number) {
  return Math.min(1, Math.max(0, value));
}

function readStored() {
  const raw = window.localStorage.getItem(SFX_VOLUME_KEY);
  // 저장값이 없으면 1.0 — 지금까지 각 효과음의 기준 음량 그대로 들리던 상태가 기본값이다.
  if (raw === null) return 1;
  const parsed = Number(raw);
  if (!Number.isFinite(parsed)) return 1;
  return clamp(parsed);
}

let currentVolume = readStored();
const listeners = new Set<() => void>();

export function getSfxVolume() {
  return currentVolume;
}

export function setSfxVolume(value: number) {
  const next = clamp(value);
  if (next === currentVolume) return;
  currentVolume = next;
  window.localStorage.setItem(SFX_VOLUME_KEY, String(next));
  listeners.forEach((listener) => listener());
}

export function subscribeSfxVolume(listener: () => void) {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

/**
 * 효과음 하나의 기준 음량에 사용자 설정을 곱한 최종 값.
 *
 * **Audio를 만들 때가 아니라 재생 직전에 부른다.** 대부분의 효과음은 Audio 객체를 한 번
 * 만들어 두고 재사용하므로, 생성 시점에만 volume을 넣으면 슬라이더를 움직여도 다음 마운트까지
 * 반영되지 않는다.
 */
export function mixSfxVolume(baseVolume: number) {
  return clamp(baseVolume * currentVolume);
}
