import { useEffect, useRef } from 'react';

export function useCountdownSound(
  active: boolean,
  countdownKey: string,
  startOffsetSeconds = 0,
) {
  const audioRef = useRef<HTMLAudioElement>(null);
  const playedKeyRef = useRef<string | null>(null);

  useEffect(() => {
    const audio = new Audio('/assets/sounds/countdown.mp3');
    audio.preload = 'auto';
    audio.volume = 0.8;
    audioRef.current = audio;

    return () => {
      audio.pause();
      audioRef.current = null;
    };
  }, []);

  useEffect(() => {
    const audio = audioRef.current;
    if (!audio) return;

    if (!active) {
      audio.pause();
      return;
    }
    if (playedKeyRef.current === countdownKey) return;

    playedKeyRef.current = countdownKey;
    audio.currentTime = Math.max(0, startOffsetSeconds);
    void audio.play().catch(() => {
      // 효과음 재생 실패가 카운트다운 진행을 막아서는 안 된다.
    });
  }, [active, countdownKey, startOffsetSeconds]);
}
