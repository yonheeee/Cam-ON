import { useEffect, useId, useRef, useState } from 'react';
import { useOptionalVoiceVolume } from '../context/voiceVolume';
import './BackgroundMusic.css';

const MUSIC_ENABLED_KEY = 'camon:background-music-enabled';
// 음량은 화면(랜딩/대기방/게임)마다 따로 기억하지 않는다 — 한 번 맞춘 크기가 게임을 넘어가도
// 유지되는 쪽이 자연스럽다. 그래서 source와 무관한 단일 키를 쓴다.
const MUSIC_VOLUME_KEY = 'camon:background-music-volume';

interface BackgroundMusicProps {
  source: string;
  className: string;
  /** 저장된 음량이 없을 때 쓰는 초기값. 사용자가 슬라이더를 만지면 그 값이 우선한다. */
  volume?: number;
}

function clampVolume(value: number) {
  return Math.min(1, Math.max(0, value));
}

function readStoredVolume(fallback: number) {
  const raw = window.localStorage.getItem(MUSIC_VOLUME_KEY);
  if (raw === null) return fallback;
  const parsed = Number(raw);
  // 값이 손상돼 있으면(수동 편집, 예전 포맷 등) 화면이 넘긴 기본값으로 되돌린다.
  if (!Number.isFinite(parsed)) return fallback;
  return clampVolume(parsed);
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

interface VolumeRowProps {
  label: string;
  /** 0.0 ~ 1.0 */
  value: number;
  onChange: (value: number) => void;
  disabled?: boolean;
  /** disabled일 때 왜 못 만지는지 스크린리더에 덧붙일 설명. */
  disabledHint?: string;
}

function VolumeRow({ label, value, onChange, disabled = false, disabledHint }: VolumeRowProps) {
  const percent = Math.round(value * 100);
  return (
    <div className="background-music__row">
      <span className="background-music__row-label">{label}</span>
      <input
        className="background-music__range"
        type="range"
        min={0}
        max={100}
        step={1}
        value={percent}
        onChange={(event) => onChange(clampVolume(Number(event.target.value) / 100))}
        aria-label={disabled && disabledHint ? `${label} 음량 (${disabledHint})` : `${label} 음량`}
        aria-valuetext={`${percent}퍼센트`}
        disabled={disabled}
      />
      <span className="background-music__value">{percent}%</span>
    </div>
  );
}

function CaretIcon() {
  return (
    <svg
      className="background-music__caret"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="3"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden
    >
      <path d="M6 9l6 6 6-6" />
    </svg>
  );
}

export function BackgroundMusic({
  source,
  className,
  volume: defaultVolume = 0.35,
}: BackgroundMusicProps) {
  const audioRef = useRef<HTMLAudioElement | null>(null);
  const containerRef = useRef<HTMLDivElement | null>(null);
  const [enabled, setEnabled] = useState(
    () => window.localStorage.getItem(MUSIC_ENABLED_KEY) !== 'false',
  );
  const [volume, setVolume] = useState(() => readStoredVolume(defaultVolume));
  const [waitingForInteraction, setWaitingForInteraction] = useState(false);
  const [open, setOpen] = useState(false);
  const panelId = useId();
  // 방 밖(랜딩)에서는 null — 들을 참가자 음성이 없으므로 음성 슬라이더를 그리지 않는다.
  const voice = useOptionalVoiceVolume();

  // 오디오 생성 효과가 volume에 의존하면 슬라이더를 움직일 때마다 Audio가 새로 만들어져
  // 곡이 처음부터 다시 재생된다. 그래서 현재 음량은 ref로 읽고, 의존성은 source만 둔다.
  const volumeRef = useRef(volume);

  useEffect(() => {
    const audio = new Audio(source);
    audio.autoplay = true;
    audio.loop = true;
    audio.preload = 'auto';
    audio.volume = volumeRef.current;
    audioRef.current = audio;

    return () => {
      audio.pause();
      audio.currentTime = 0;
      audioRef.current = null;
    };
  }, [source]);

  useEffect(() => {
    volumeRef.current = volume;
    if (audioRef.current) {
      audioRef.current.volume = volume;
    }
    window.localStorage.setItem(MUSIC_VOLUME_KEY, String(volume));
  }, [volume]);

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
    // 별도 시작 버튼 없이 화면 어디든 첫 조작이면 충분하다 — BGM 버튼을 눌러 창을 여는
    // 조작도 여기에 걸리므로, 자동 재생이 막혀 있었다면 창이 열리면서 재생도 함께 풀린다.
    document.addEventListener('pointerdown', unlockPlayback, true);
    document.addEventListener('keydown', unlockPlayback, true);

    return removeUnlockListeners;
  }, [enabled, source]);

  useEffect(() => {
    if (!open) return;

    const closeOnOutside = (event: PointerEvent) => {
      if (containerRef.current?.contains(event.target as Node)) return;
      setOpen(false);
    };
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setOpen(false);
    };

    document.addEventListener('pointerdown', closeOnOutside);
    document.addEventListener('keydown', closeOnEscape);

    return () => {
      document.removeEventListener('pointerdown', closeOnOutside);
      document.removeEventListener('keydown', closeOnEscape);
    };
  }, [open]);

  const toggleMusic = async () => {
    const audio = audioRef.current;

    // 자동 재생이 막힌 상태에서 누른 것은 "끄기"가 아니라 사용자가 직접 재생을 허용한 것으로
    // 처리한다.
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

  const muteLabel = !enabled
    ? '배경음악 켜기'
    : waitingForInteraction
      ? '화면을 클릭하면 배경음악이 재생됩니다'
      : '배경음악 끄기';

  return (
    <div
      ref={containerRef}
      className={`${className} background-music${open ? ' background-music--open' : ''}`}
    >
      <button
        type="button"
        className={`background-music__toggle pap-pixel-btn${
          enabled ? '' : ' background-music__toggle--muted'
        }${waitingForInteraction ? ' background-music__toggle--waiting' : ''}`}
        onClick={() => setOpen((previous) => !previous)}
        aria-expanded={open}
        aria-controls={panelId}
        aria-label={open ? '배경음악 설정 닫기' : '배경음악 설정 열기'}
        title={waitingForInteraction ? muteLabel : '배경음악 설정'}
      >
        <SpeakerIcon muted={!enabled} />
        <span>BGM</span>
        <CaretIcon />
      </button>

      <div
        id={panelId}
        className="background-music__panel pap-pixel-card"
        role="group"
        aria-label="배경음악 설정"
        hidden={!open}
      >
        <button
          type="button"
          className="background-music__mute pap-pixel-btn"
          onClick={() => void toggleMusic()}
          aria-pressed={enabled}
          title={muteLabel}
        >
          <SpeakerIcon muted={!enabled} />
          <span>{enabled ? '배경음악 켜짐' : '배경음악 꺼짐'}</span>
        </button>

        <VolumeRow
          label="음악"
          value={volume}
          onChange={setVolume}
          disabled={!enabled}
          disabledHint="배경음악이 꺼져 있어요"
        />

        {/* 참가자 음성은 방 안에서만 존재한다 — LiveKit의 RoomAudioRenderer 볼륨으로 이어진다.
            배경음악과 한 슬라이더를 공유하면 "음악만 줄이고 말은 크게" 같은 조절이 불가능하다. */}
        {voice && (
          <VolumeRow label="음성" value={voice.voiceVolume} onChange={voice.setVoiceVolume} />
        )}

        {waitingForInteraction && (
          <p className="background-music__hint">화면을 한 번 클릭하면 재생돼요</p>
        )}
      </div>
    </div>
  );
}
