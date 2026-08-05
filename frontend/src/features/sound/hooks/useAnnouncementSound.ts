import { useEffect, useRef } from 'react';
import { mixSfxVolume } from '../lib/sfxVolume';

export function useAnnouncementSound(
  active: boolean,
  playbackKey: string,
  source: string,
  volume = 0.9,
) {
  const audioRef = useRef<HTMLAudioElement>(null);
  const playedKeyRef = useRef<string | null>(null);
  // 기준 음량은 재생 직전에만 읽는다. 아래 재생 effect의 의존성에 넣으면 값이 바뀔 때
  // 재생 로직이 다시 돌게 되는데, 이 훅은 playbackKey가 바뀔 때만 울려야 한다.
  const volumeRef = useRef(volume);
  volumeRef.current = volume;

  useEffect(() => {
    const audio = new Audio(source);
    audio.preload = 'auto';
    audioRef.current = audio;

    return () => {
      audio.pause();
      audio.currentTime = 0;
      audioRef.current = null;
    };
  }, [source]);

  useEffect(() => {
    const audio = audioRef.current;
    if (!audio) return;

    if (!active) {
      audio.pause();
      audio.currentTime = 0;
      return;
    }
    if (playedKeyRef.current === playbackKey) return;

    playedKeyRef.current = playbackKey;
    // 재사용하는 Audio라 재생 직전에 음량을 계산한다(슬라이더 변경 즉시 반영).
    audio.volume = mixSfxVolume(volumeRef.current);
    audio.currentTime = 0;
    void audio.play().catch(() => {
      // 발표음 재생 실패가 게임 진행을 막아서는 안 된다.
    });
  }, [active, playbackKey]);
}
