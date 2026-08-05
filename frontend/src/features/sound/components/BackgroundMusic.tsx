import { useEffect, useId, useRef, useState, type CSSProperties, type ReactNode } from 'react';
import { useSfxVolume } from '../hooks/useSfxVolume';
import './BackgroundMusic.css';

const MUSIC_ENABLED_KEY = 'camon:background-music-enabled';
// 음량은 화면(랜딩/대기방/게임)마다 따로 기억하지 않는다 — 한 번 맞춘 크기가 게임을 넘어가도
// 유지되는 쪽이 자연스럽다. 그래서 source와 무관한 단일 키를 쓴다.
const MUSIC_VOLUME_KEY = 'camon:background-music-volume';

interface BackgroundMusicProps {
  source: string;
  className: string;
  variant?: 'popover' | 'compact';
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
  icon: ReactNode;
  iconLabel: string;
  onIconClick: () => void;
  /** 0.0 ~ 1.0 */
  value: number;
  onChange: (value: number) => void;
  disabled?: boolean;
  /** disabled일 때 왜 못 만지는지 스크린리더에 덧붙일 설명. */
  disabledHint?: string;
}

function VolumeRow({
  label,
  icon,
  iconLabel,
  onIconClick,
  value,
  onChange,
  disabled = false,
  disabledHint,
}: VolumeRowProps) {
  const percent = Math.round(value * 100);
  return (
    <div className="background-music__row">
      <button
        type="button"
        className="background-music__row-icon"
        onClick={onIconClick}
        aria-label={iconLabel}
        title={iconLabel}
      >
        {icon}
      </button>
      <span className="background-music__row-label">{label}</span>
      <input
        className="background-music__range"
        type="range"
        min={0}
        max={100}
        step={1}
        value={percent}
        style={{ '--background-music-progress': `${percent}%` } as CSSProperties}
        onChange={(event) => onChange(clampVolume(Number(event.target.value) / 100))}
        aria-label={disabled && disabledHint ? `${label} 음량 (${disabledHint})` : `${label} 음량`}
        aria-valuetext={`${percent}퍼센트`}
        disabled={disabled}
      />
      <span className="background-music__value">{percent}%</span>
    </div>
  );
}

function MusicIcon({ muted }: { muted: boolean }) {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" aria-hidden>
      <path d="M9 18V6l10-2v12" />
      <circle cx="6" cy="18" r="3" />
      <circle cx="16" cy="16" r="3" />
      {muted && <path d="M3 3l18 18" />}
    </svg>
  );
}

function EffectsIcon({ muted }: { muted: boolean }) {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" aria-hidden>
      <path d="M4 14v-4M8 17V7M12 20V4M16 17V7M20 14v-4" />
      {muted && <path d="M3 3l18 18" />}
    </svg>
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
  variant = 'popover',
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
  const lastMusicVolumeRef = useRef(volume > 0 ? volume : defaultVolume);
  const { sfxVolume, setSfxVolume } = useSfxVolume();
  const lastSfxVolumeRef = useRef(sfxVolume > 0 ? sfxVolume : 0.7);
  const panelId = useId();

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

  const changeMusicVolume = (next: number) => {
    if (next > 0) {
      lastMusicVolumeRef.current = next;
      setEnabled(true);
    } else {
      setEnabled(false);
    }
    setVolume(next);
  };

  const toggleMusicMute = () => {
    if (enabled && volume > 0) {
      lastMusicVolumeRef.current = volume;
      setVolume(0);
      setEnabled(false);
      return;
    }

    setVolume(Math.max(0.01, lastMusicVolumeRef.current || defaultVolume));
    setEnabled(true);
  };

  const changeSfxVolume = (next: number) => {
    if (next > 0) lastSfxVolumeRef.current = next;
    setSfxVolume(next);
  };

  const toggleSfxMute = () => {
    if (sfxVolume > 0) {
      lastSfxVolumeRef.current = sfxVolume;
      setSfxVolume(0);
      return;
    }
    setSfxVolume(Math.max(0.01, lastSfxVolumeRef.current));
  };

  const muteLabel = !enabled
    ? '배경음악 켜기'
    : waitingForInteraction
      ? '화면을 클릭하면 배경음악이 재생됩니다'
      : '배경음악 끄기';

  if (variant === 'compact') {
    const musicPercent = Math.round(volume * 100);
    const sfxPercent = Math.round(sfxVolume * 100);

    return (
      <div
        className={`${className} background-music background-music--compact pap-pixel-card`}
        role="group"
        aria-label="사운드 설정"
      >
        <button
          type="button"
          className="background-music__compact-icon"
          onClick={toggleMusicMute}
          aria-label={!enabled || volume === 0 ? '배경음악 음소거 해제' : '배경음악 음소거'}
          title="배경음악"
        >
          <MusicIcon muted={!enabled || volume === 0} />
        </button>
        <input
          className="background-music__range background-music__range--compact"
          type="range"
          min={0}
          max={100}
          step={1}
          value={musicPercent}
          style={{ '--background-music-progress': `${musicPercent}%` } as CSSProperties}
          onChange={(event) => changeMusicVolume(clampVolume(Number(event.target.value) / 100))}
          aria-label="배경음악 볼륨"
          aria-valuetext={`${musicPercent}퍼센트`}
        />

        <span className="background-music__compact-divider" aria-hidden />

        <button
          type="button"
          className="background-music__compact-icon"
          onClick={toggleSfxMute}
          aria-label={sfxPercent === 0 ? '효과음 음소거 해제' : '효과음 음소거'}
          title="효과음"
        >
          <EffectsIcon muted={sfxPercent === 0} />
        </button>
        <input
          className="background-music__range background-music__range--compact"
          type="range"
          min={0}
          max={100}
          step={1}
          value={sfxPercent}
          style={{ '--background-music-progress': `${sfxPercent}%` } as CSSProperties}
          onChange={(event) => changeSfxVolume(clampVolume(Number(event.target.value) / 100))}
          aria-label="효과음 볼륨"
          aria-valuetext={`${sfxPercent}퍼센트`}
        />
      </div>
    );
  }

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
        <span>사운드</span>
        <CaretIcon />
      </button>

      <div
        id={panelId}
        className="background-music__panel pap-pixel-card"
        role="group"
        aria-label="사운드 설정"
        hidden={!open}
      >
        <div className="background-music__panel-head">
          <strong>사운드 설정</strong>
        </div>

        <VolumeRow
          label="배경음악"
          icon={<MusicIcon muted={!enabled || volume === 0} />}
          iconLabel={!enabled || volume === 0 ? '배경음악 음소거 해제' : '배경음악 음소거'}
          onIconClick={toggleMusicMute}
          value={volume}
          onChange={changeMusicVolume}
          disabled={!enabled}
          disabledHint="배경음악이 꺼져 있어요"
        />

        <VolumeRow
          label="효과음"
          icon={<EffectsIcon muted={sfxVolume === 0} />}
          iconLabel={sfxVolume === 0 ? '효과음 음소거 해제' : '효과음 음소거'}
          onIconClick={toggleSfxMute}
          value={sfxVolume}
          onChange={changeSfxVolume}
        />

        {waitingForInteraction && (
          <p className="background-music__hint">화면을 한 번 클릭하면 재생돼요</p>
        )}
      </div>
    </div>
  );
}
