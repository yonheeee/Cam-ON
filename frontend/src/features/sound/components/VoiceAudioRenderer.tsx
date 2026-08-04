import { RoomAudioRenderer } from '@livekit/components-react';
import { useOptionalVoiceVolume } from '../context/voiceVolume';

/**
 * 원격 참가자 마이크를 실제로 재생하는 지점. RoomAudioRenderer를 그대로 쓰면서 볼륨만
 * VoiceVolumeContext에 연결한다.
 *
 * VideoCallRoom이 직접 컨텍스트를 읽을 수 없어서(자기가 Provider를 렌더하므로) 이 얇은
 * 컴포넌트를 둔다.
 */
export function VoiceAudioRenderer() {
  const voice = useOptionalVoiceVolume();
  return <RoomAudioRenderer volume={voice?.voiceVolume ?? 1} />;
}
