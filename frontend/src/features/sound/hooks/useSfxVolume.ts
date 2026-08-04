import { useSyncExternalStore } from 'react';
import { getSfxVolume, setSfxVolume, subscribeSfxVolume } from '../lib/sfxVolume';

/** 효과음 음량 슬라이더용. 값이 바뀌면 구독한 컴포넌트만 다시 그린다. */
export function useSfxVolume() {
  const sfxVolume = useSyncExternalStore(subscribeSfxVolume, getSfxVolume, getSfxVolume);
  return { sfxVolume, setSfxVolume };
}
