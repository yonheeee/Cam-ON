import { createContext, useContext } from 'react';

export const VOICE_VOLUME_KEY = 'camon:voice-volume';

export interface VoiceVolumeValue {
  /** 0.0 ~ 1.0. RoomAudioRenderer의 volume으로 그대로 넘어간다. */
  voiceVolume: number;
  setVoiceVolume: (value: number) => void;
}

// 기본값을 null로 둔다. 이 컨텍스트는 VideoCallRoom 안에만 있으므로, "값이 없다"가 곧
// "지금 방 안이 아니다(= 들을 참가자 음성이 없다)"라는 뜻이 된다. 랜딩의 BGM 버튼이 조작할
// 대상 없는 음성 슬라이더를 띄우지 않게 하는 장치가 이 null 하나로 끝난다.
export const VoiceVolumeContext = createContext<VoiceVolumeValue | null>(null);

export function clampVoiceVolume(value: number) {
  return Math.min(1, Math.max(0, value));
}

export function readStoredVoiceVolume() {
  const raw = window.localStorage.getItem(VOICE_VOLUME_KEY);
  // 저장값이 없으면 LiveKit 기본값과 같은 1.0에서 시작한다 — 음성은 게임 진행에 필요한
  // 정보라서 기본을 줄여두지 않는다(배경음악과 반대).
  if (raw === null) return 1;
  const parsed = Number(raw);
  if (!Number.isFinite(parsed)) return 1;
  return clampVoiceVolume(parsed);
}

/** 방 안이면 음성 볼륨 제어를, 방 밖(랜딩 등)이면 null을 돌려준다. */
export function useOptionalVoiceVolume() {
  return useContext(VoiceVolumeContext);
}
