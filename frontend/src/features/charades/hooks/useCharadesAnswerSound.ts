import { useCallback, useEffect, useRef } from 'react';
import { mixSfxVolume } from '../../sound/lib/sfxVolume';

const ANSWER_SOUND_SOURCE = {
  correct: '/assets/sounds/correct.mp3',
  incorrect: '/assets/sounds/incorrect.mp3',
} as const;

export function useCharadesAnswerSound() {
  const audioByResultRef = useRef<
    Partial<Record<keyof typeof ANSWER_SOUND_SOURCE, HTMLAudioElement>>
  >({});

  useEffect(() => {
    const audioByResult = Object.fromEntries(
      Object.entries(ANSWER_SOUND_SOURCE).map(([result, source]) => {
        const audio = new Audio(source);
        audio.preload = 'auto';
        return [result, audio];
      }),
    ) as Record<keyof typeof ANSWER_SOUND_SOURCE, HTMLAudioElement>;

    audioByResultRef.current = audioByResult;

    return () => {
      Object.values(audioByResult).forEach((audio) => {
        audio.pause();
        audio.currentTime = 0;
      });
      audioByResultRef.current = {};
    };
  }, []);

  return useCallback((correct: boolean) => {
    const audio = audioByResultRef.current[correct ? 'correct' : 'incorrect'];
    if (!audio) return;

    // 재사용하는 Audio라 재생 직전에 음량을 계산한다(슬라이더 변경 즉시 반영).
    audio.volume = mixSfxVolume(0.85);
    audio.currentTime = 0;
    void audio.play().catch(() => {
      // 효과음 재생 실패가 게임 진행을 막아서는 안 된다.
    });
  }, []);
}
