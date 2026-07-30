import { useEffect, useRef, useState } from 'react';
import './BackgroundMusic.css';

const MUSIC_ENABLED_KEY = 'camon:background-music-enabled';

interface BackgroundMusicProps {
  source: string;
  className: string;
  volume?: number;
}

function SpeakerIcon({ muted }: { muted: boolean }) {
  return (
    <svg
      className="background-music__icon"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden
    >
      <path d="M4 9v6h4l5 4V5L8 9H4Z" />
      {!muted && (
        <>
          <path d="M16 9.5a4 4 0 0 1 0 5" />
          <path d="M18.5 7a7.5 7.5 0 0 1 0 10" />
        </>
      )}
      {muted && <path className="background-music__slash" d="M3 3l18 18" />}
    </svg>
  );
}

export function BackgroundMusic({
  source,
  className,
  volume = 0.35,
}: BackgroundMusicProps) {
  const audioRef = useRef<HTMLAudioElement | null>(null);
  const [enabled, setEnabled] = useState(
    () => window.localStorage.getItem(MUSIC_ENABLED_KEY) !== 'false',
  );
  const [waitingForInteraction, setWaitingForInteraction] = useState(false);

  useEffect(() => {
    const audio = new Audio(source);
    audio.autoplay = true;
    audio.loop = true;
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

    window.localStorage.setItem(MUSIC_ENABLED_KEY, String(enabled));
    if (!enabled) {
      audio.pause();
      setWaitingForInteraction(false);
      return;
    }

    const removeUnlockListeners = () => {
      document.removeEventListener('pointerdown', unlockPlayback, true);
      document.removeEventListener('keydown', unlockPlayback, true);
    };
    const tryPlay = async () => {
      try {
        await audio.play();
        setWaitingForInteraction(false);
        removeUnlockListeners();
      } catch {
        // 소리가 있는 자동 재생이 차단되면 첫 사용자 조작까지 재생 대기 상태를 유지한다.
        setWaitingForInteraction(true);
      }
    };
    const unlockPlayback = () => {
      void tryPlay();
    };

    void tryPlay();
    // 캡처 단계에서 받아 버튼 클릭이나 화면 전환보다 먼저 재생 권한을 얻는다.
    // 별도 시작 버튼 없이 화면 어디든 첫 조작이면 충분하다.
    document.addEventListener('pointerdown', unlockPlayback, true);
    document.addEventListener('keydown', unlockPlayback, true);

    return removeUnlockListeners;
  }, [enabled, source, volume]);

  const toggleMusic = async () => {
    const audio = audioRef.current;

    // 자동 재생이 막힌 상태에서 스피커 버튼을 누른 것은 "끄기"가 아니라
    // 사용자가 직접 재생을 허용한 것으로 처리한다.
    if (enabled && waitingForInteraction) {
      try {
        await audio?.play();
        setWaitingForInteraction(false);
      } catch {
        setWaitingForInteraction(true);
      }
      return;
    }

    if (!enabled) {
      setEnabled(true);
      try {
        await audio?.play();
        setWaitingForInteraction(false);
      } catch {
        setWaitingForInteraction(true);
      }
      return;
    }

    setEnabled(false);
  };

  const actionLabel = !enabled
    ? '배경음악 켜기'
    : waitingForInteraction
      ? '화면을 클릭하면 배경음악이 재생됩니다'
      : '배경음악 끄기';

  return (
    <button
      type="button"
      className={`${className} background-music${enabled ? '' : ' background-music--muted'}${
        waitingForInteraction ? ' background-music--waiting' : ''
      } pap-pixel-btn`}
      onClick={() => void toggleMusic()}
      aria-pressed={enabled}
      aria-label={actionLabel}
      title={actionLabel}
    >
      <SpeakerIcon muted={!enabled} />
      <span>BGM</span>
    </button>
  );
}
