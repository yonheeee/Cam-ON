import { useEffect, useRef } from 'react';

export function useAnnouncementSound(
  active: boolean,
  playbackKey: string,
  source: string,
  volume = 0.9,
) {
  const audioRef = useRef<HTMLAudioElement>(null);
  const playedKeyRef = useRef<string | null>(null);

  useEffect(() => {
    const audio = new Audio(source);
    audio.preload = 'auto';
    audio.volume = volume;
    audioRef.current = audio;

    return () => {
      audio.pause();
      audio.currentTime = 0;
      audioRef.current = null;
    };
  }, [source, volume]);

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
    audio.currentTime = 0;
    void audio.play().catch(() => {
      // 발표음 재생 실패가 게임 진행을 막아서는 안 된다.
    });
  }, [active, playbackKey]);
}
