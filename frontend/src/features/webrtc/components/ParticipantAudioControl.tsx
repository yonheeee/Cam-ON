import { useRef, useState, type CSSProperties } from 'react';
import { useLocalParticipant, useRoomContext } from '@livekit/components-react';
import { Track } from 'livekit-client';
import './ParticipantAudioControl.css';

interface ParticipantAudioControlProps {
  identity: string;
  /** 게임 규칙이 내 마이크를 직접 제어하는 순간(몸으로 말해요 출제자 등)에는 false. */
  allowLocalToggle?: boolean;
  variant?: 'game' | 'lobby';
}

function MicIcon({ muted }: { muted: boolean }) {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" aria-hidden>
      <rect x="8" y="3" width="8" height="12" rx="4" />
      <path d="M5 11a7 7 0 0 0 14 0M12 18v3M9 21h6" />
      {muted && <path d="M3 3l18 18" />}
    </svg>
  );
}

function SpeakerIcon({ muted }: { muted: boolean }) {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" aria-hidden>
      <path d="M4 9v6h4l5 4V5L8 9H4Z" />
      {!muted && <path d="M16 9.5a4 4 0 0 1 0 5M18.5 7a7.5 7.5 0 0 1 0 10" />}
      {muted && <path d="M3 3l18 18" />}
    </svg>
  );
}

export function ParticipantAudioControl({
  identity,
  allowLocalToggle = true,
  variant = 'game',
}: ParticipantAudioControlProps) {
  const room = useRoomContext();
  const { localParticipant, isMicrophoneEnabled } = useLocalParticipant();
  const isLocal = localParticipant.identity === identity;
  const remoteParticipant = room.remoteParticipants.get(identity);
  const initialVolume = remoteParticipant?.getVolume(Track.Source.Microphone) ?? 1;
  const [remoteVolume, setRemoteVolume] = useState(initialVolume);
  const [remoteControlOpen, setRemoteControlOpen] = useState(false);
  const lastRemoteVolume = useRef(initialVolume > 0 ? initialVolume : 1);

  if (isLocal) {
    const muted = !isMicrophoneEnabled;
    return (
      <div className={`participant-audio participant-audio--local participant-audio--${variant}${muted ? ' participant-audio--muted' : ''}`}>
        <button
          type="button"
          className="participant-audio__button"
          onClick={() => void localParticipant.setMicrophoneEnabled(muted)}
          disabled={!allowLocalToggle}
          data-button-sound="none"
          aria-label={muted ? '내 마이크 켜기' : '내 마이크 끄기'}
          title={allowLocalToggle ? (muted ? '내 마이크 켜기' : '내 마이크 끄기') : '게임에서 마이크를 제어하고 있어요'}
        >
          <MicIcon muted={muted} />
        </button>
      </div>
    );
  }

  if (!remoteParticipant) return null;

  const muted = remoteVolume === 0;
  const setVolume = (next: number) => {
    const value = Math.min(1, Math.max(0, next));
    if (value > 0) lastRemoteVolume.current = value;
    remoteParticipant.setVolume(value, Track.Source.Microphone);
    setRemoteVolume(value);
  };

  const toggleMute = () => {
    setVolume(muted ? Math.max(0.01, lastRemoteVolume.current) : 0);
  };

  const handleRemoteButtonClick = () => {
    if (!remoteControlOpen) {
      setRemoteControlOpen(true);
      if (variant === 'game') toggleMute();
      return;
    }
    toggleMute();
  };

  return (
    <div
      className={`participant-audio participant-audio--remote participant-audio--${variant}${
        muted ? ' participant-audio--muted' : ''
      }${remoteControlOpen ? ' participant-audio--open' : ''}`}
      onMouseLeave={() => setRemoteControlOpen(false)}
    >
      <div className="participant-audio__popover">
        <button
          type="button"
          className="participant-audio__button"
          onClick={handleRemoteButtonClick}
          data-button-sound="none"
          aria-expanded={remoteControlOpen}
          aria-label={muted ? '이 참가자 소리 켜기' : '이 참가자 음소거'}
          title={muted ? '소리 켜기' : '음소거'}
        >
          <SpeakerIcon muted={muted} />
        </button>
        <input
          className="participant-audio__range"
          type="range"
          min={0}
          max={100}
          step={1}
          value={Math.round(remoteVolume * 100)}
          style={{
            '--participant-audio-progress': `${Math.round(remoteVolume * 100)}%`,
          } as CSSProperties}
          onChange={(event) => setVolume(Number(event.target.value) / 100)}
          aria-label="이 참가자 음성 볼륨"
          aria-valuetext={`${Math.round(remoteVolume * 100)}퍼센트`}
        />
      </div>
    </div>
  );
}
