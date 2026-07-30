import { useEffect, useRef } from 'react';

const EFFECT_SOUND_BY_SKILL_ID: Partial<Record<number, string>> = {
  1: '/assets/sounds/thunder.mp3',
  2: '/assets/sounds/fireball.mp3',
  3: '/assets/sounds/waterball.mp3',
  4: '/assets/sounds/meow.mp3',
  5: '/assets/sounds/wind.mp3',
};

export function useNinjaEffectSound(
  active: boolean,
  skillId: number | null | undefined,
  playbackKey: string,
) {
  const audioRef = useRef<HTMLAudioElement>(null);
  const playedKeyRef = useRef<string | null>(null);

  useEffect(() => {
    const source = skillId == null ? undefined : EFFECT_SOUND_BY_SKILL_ID[skillId];
    if (!active || !source) {
      audioRef.current?.pause();
      audioRef.current = null;
      return;
    }

    const effectKey = `${playbackKey}:${skillId}`;
    if (playedKeyRef.current === effectKey) return;
    playedKeyRef.current = effectKey;

    const audio = new Audio(source);
    audio.preload = 'auto';
    audio.volume = 0.85;
    audioRef.current = audio;
    void audio.play().catch(() => {
      // 효과음 재생 실패가 닌자 게임 진행을 막아서는 안 된다.
    });

    return () => {
      audio.pause();
      audio.currentTime = 0;
      if (audioRef.current === audio) audioRef.current = null;
    };
  }, [active, playbackKey, skillId]);
}
