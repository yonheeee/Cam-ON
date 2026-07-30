import { useEffect } from 'react';

const BUTTON_SOUNDS = {
  basic: { source: '/assets/sounds/basic-button.mp3', volume: 0.55 },
  cancel: { source: '/assets/sounds/cancel-button.mp3', volume: 0.65 },
  ready: { source: '/assets/sounds/ready.mp3', volume: 0.7 },
} as const;

type ButtonSoundName = keyof typeof BUTTON_SOUNDS;

export function ButtonSounds() {
  useEffect(() => {
    const audioByName = Object.fromEntries(
      Object.entries(BUTTON_SOUNDS).map(([name, config]) => {
        const audio = new Audio(config.source);
        audio.preload = 'auto';
        audio.volume = config.volume;
        return [name, audio];
      }),
    ) as Record<ButtonSoundName, HTMLAudioElement>;

    const playButtonSound = (event: MouseEvent) => {
      if (!(event.target instanceof Element)) return;

      const button = event.target.closest('button');
      if (!(button instanceof HTMLButtonElement) || button.disabled) return;

      const soundName = button.dataset.buttonSound ?? 'basic';
      if (soundName === 'none' || !(soundName in audioByName)) return;

      const audio = audioByName[soundName as ButtonSoundName];
      audio.currentTime = 0;
      void audio.play().catch(() => {
        // 효과음 재생 실패가 버튼 동작을 막아서는 안 된다.
      });
    };

    document.addEventListener('click', playButtonSound, true);

    return () => {
      document.removeEventListener('click', playButtonSound, true);
      Object.values(audioByName).forEach((audio) => audio.pause());
    };
  }, []);

  return null;
}
