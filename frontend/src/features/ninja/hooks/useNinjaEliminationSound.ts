import { useEffect, useRef } from 'react';

export function useNinjaEliminationSound(
  round: number | null,
  alivePlayers: string[],
) {
  const audioRef = useRef<HTMLAudioElement>(null);
  const previousRoundRef = useRef<number | null>(null);
  const previousAlivePlayersRef = useRef<Set<string>>(new Set());

  useEffect(() => {
    const audio = new Audio('/assets/sounds/ninja-die.mp3');
    audio.preload = 'auto';
    audio.volume = 0.9;
    audioRef.current = audio;

    return () => {
      audio.pause();
      audio.currentTime = 0;
      audioRef.current = null;
    };
  }, []);

  useEffect(() => {
    const currentAlivePlayers = new Set(alivePlayers);

    if (round === null || previousRoundRef.current !== round) {
      previousRoundRef.current = round;
      previousAlivePlayersRef.current = currentAlivePlayers;
      return;
    }

    const playerWasEliminated = [...previousAlivePlayersRef.current].some(
      (participantId) => !currentAlivePlayers.has(participantId),
    );
    previousAlivePlayersRef.current = currentAlivePlayers;

    if (!playerWasEliminated) return;

    const audio = audioRef.current;
    if (!audio) return;

    audio.currentTime = 0;
    void audio.play().catch(() => {
      // 효과음 재생 실패가 게임 진행을 막아서는 안 된다.
    });
  }, [alivePlayers, round]);
}
