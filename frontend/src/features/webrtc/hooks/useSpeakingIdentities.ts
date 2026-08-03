import { useSpeakingParticipants } from '@livekit/components-react';
import { useMemo } from 'react';

// 지금 말하고 있는 참가자들의 LiveKit identity(= participantId) 집합.
//
// 화면마다 캠 타일을 직접 그리기 때문에(LiveKit VideoConference를 쓰지 않는다) ParticipantTile의
// 기본 표시(disableSpeakingIndicator로 꺼둔 파란 테두리)는 쓸 수 없다. 표시는 각 화면이
// SpeakingIndicator로 하고, "누가 말하는가"의 판단만 여기 한 곳으로 모은다.
export function useSpeakingIdentities(): Set<string> {
  const speakers = useSpeakingParticipants();
  return useMemo(
    () =>
      // 마이크가 꺼진 사람은 제외한다 — activeSpeakers는 말이 끝난 뒤에도 잠깐 남아 있어서,
      // 말하는 도중 음소거되면(몸으로 말해요 표현자 차례가 시작될 때) 한 박자 늦게까지 켜져 보인다.
      new Set(speakers.filter((participant) => participant.isMicrophoneEnabled).map((participant) => participant.identity)),
    [speakers],
  );
}
