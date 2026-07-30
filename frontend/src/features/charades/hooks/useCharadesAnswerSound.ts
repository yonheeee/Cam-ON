import { useCallback, useEffect, useRef } from 'react';

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
        audio.volume = 0.85;
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

    audio.currentTime = 0;
    void audio.play().catch(() => {
      // 효과음 재생 실패가 게임 진행을 막아서는 안 된다.
    });
  }, []);
}
