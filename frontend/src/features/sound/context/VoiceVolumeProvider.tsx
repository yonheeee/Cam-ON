import { useCallback, useEffect, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import {
  VOICE_VOLUME_KEY,
  VoiceVolumeContext,
  clampVoiceVolume,
  readStoredVoiceVolume,
} from './voiceVolume';

// 컨텍스트/훅은 voiceVolume.ts에 따로 둔다 — 한 파일에서 컴포넌트와 훅을 같이 export하면
// Fast Refresh가 동작하지 않는다(oxlint react/only-export-components).
export function VoiceVolumeProvider({ children }: { children: ReactNode }) {
  const [voiceVolume, setVoiceVolumeState] = useState(readStoredVoiceVolume);

  useEffect(() => {
    window.localStorage.setItem(VOICE_VOLUME_KEY, String(voiceVolume));
  }, [voiceVolume]);

  const setVoiceVolume = useCallback((value: number) => {
    setVoiceVolumeState(clampVoiceVolume(value));
  }, []);

  const value = useMemo(() => ({ voiceVolume, setVoiceVolume }), [voiceVolume, setVoiceVolume]);

  return <VoiceVolumeContext.Provider value={value}>{children}</VoiceVolumeContext.Provider>;
}
