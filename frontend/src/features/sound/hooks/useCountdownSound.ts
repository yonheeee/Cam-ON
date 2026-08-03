import { useEffect, useRef } from 'react';

/**
 * 카운트다운 효과음. countdown.mp3는 "3, 2, 1"을 1초에 하나씩 세는 3초짜리 음원이라,
 * startOffsetSeconds로 파일 안쪽부터 재생해 남은 숫자에 맞춘다.
 *
 * @param playbackRate 화면 카운트다운이 1초보다 빠르게 넘어가는 경우(닌자는 한 칸 0.5초)
 *   음원을 그 배수로 빨리 돌려 숫자와 비트를 맞춘다. 1이면 원래 속도.
 */
export function useCountdownSound(
  active: boolean,
  countdownKey: string,
  startOffsetSeconds = 0,
  playbackRate = 1,
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
    audio.playbackRate = playbackRate;
    audio.currentTime = Math.max(0, startOffsetSeconds);
    void audio.play().catch(() => {
      // 효과음 재생 실패가 카운트다운 진행을 막아서는 안 된다.
    });
  }, [active, countdownKey, startOffsetSeconds, playbackRate]);
}
