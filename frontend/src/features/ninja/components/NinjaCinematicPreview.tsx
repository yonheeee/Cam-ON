import { useEffect, useState } from 'react';
import { CINEMATIC_BLACKOUT_MS, NinjaCinematicOverlay } from './NinjaCinematicOverlay';
import { DURATION_MS as LIGHTNING_DURATION_MS, PixelLightningEffect } from './PixelLightningEffect';
import './NinjaBattleScreen.css';

const TARGET_FOCUS = { x: 77, y: 43 };

export function NinjaCinematicPreview() {
  const [playbackKey, setPlaybackKey] = useState(0);
  const [phase, setPhase] = useState<'idle' | 'blackout' | 'focus'>('blackout');

  useEffect(() => {
    if (phase === 'idle') return;
    const timer = window.setTimeout(
      () => setPhase(phase === 'blackout' ? 'focus' : 'idle'),
      phase === 'blackout' ? CINEMATIC_BLACKOUT_MS : LIGHTNING_DURATION_MS,
    );
    return () => window.clearTimeout(timer);
  }, [phase, playbackKey]);

  const replay = () => {
    setPhase('idle');
    window.requestAnimationFrame(() => {
      setPlaybackKey((key) => key + 1);
      setPhase('blackout');
    });
  };

  return (
    <main
      style={{
        position: 'fixed',
        inset: 0,
        overflow: 'hidden',
        background: '#1b1230 url(/assets/ninja-background-v6.png) center / cover no-repeat',
      }}
    >
      <section
        className="ninja-tile ninja-tile--p2"
        style={{ position: 'absolute', left: '7%', top: '18%', width: '29%' }}
      >
        <div className="ninja-tile__cam" style={{ background: '#5f7893' }}>
          <span className="ninja-tile__avatar pap-pixel-title">공격자</span>
        </div>
      </section>

      <section
        className="ninja-tile ninja-tile--p3"
        style={{ position: 'absolute', left: '10%', bottom: '12%', width: '25%' }}
      >
        <div className="ninja-tile__cam" style={{ background: '#3c716b' }}>
          <span className="ninja-tile__avatar pap-pixel-title">참가자</span>
        </div>
      </section>

      <section
        className={`ninja-tile ninja-tile--p1${phase === 'focus' ? ' ninja-tile--cinematic-target' : ''}`}
        style={{ position: 'absolute', right: '7%', top: '29%', width: '32%' }}
      >
        <div className="ninja-tile__cam" style={{ background: '#6d4d63' }}>
          <span className="ninja-tile__avatar pap-pixel-title">피격 대상</span>
          {phase === 'focus' && <PixelLightningEffect key={playbackKey} />}
          <span className="ninja-tile__badge ninja-tile__badge--name">대상</span>
        </div>
      </section>

      {phase !== 'idle' && (
        <NinjaCinematicOverlay
          focus={phase === 'focus' ? TARGET_FOCUS : null}
          blackout={phase === 'blackout'}
        />
      )}

      <button
        type="button"
        className="pap-pixel-btn pap-pixel-btn--primary"
        onClick={replay}
        style={{ position: 'absolute', left: '50%', bottom: 24, zIndex: 50, transform: 'translateX(-50%)' }}
      >
        공격 연출 다시 보기
      </button>
    </main>
  );
}
