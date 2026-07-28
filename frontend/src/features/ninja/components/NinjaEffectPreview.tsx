import { useState } from 'react';
import { NinjaEffectOverlay } from './NinjaEffectOverlay';
import './NinjaGamePanel.css';

export function NinjaEffectPreview() {
  const [playbackKey, setPlaybackKey] = useState(0);

  return (
    <main
      style={{
        minHeight: '100vh',
        display: 'grid',
        placeItems: 'center',
        overflow: 'hidden',
        color: '#eafcff',
        background:
          'radial-gradient(circle at 50% 35%, #18354f 0%, #0a1726 38%, #03070d 78%)',
      }}
    >
      <NinjaEffectOverlay key={playbackKey} />
      <section
        style={{
          position: 'relative',
          zIndex: 50,
          width: 'min(34rem, calc(100vw - 2rem))',
          padding: '2rem',
          textAlign: 'center',
          border: '1px solid rgba(124, 225, 255, 0.35)',
          borderRadius: '1rem',
          background: 'rgba(3, 12, 22, 0.72)',
          boxShadow: '0 1.5rem 5rem rgba(0, 0, 0, 0.45)',
          backdropFilter: 'blur(12px)',
        }}
      >
        <p style={{ margin: 0, color: '#66e4ff', letterSpacing: '0.16em' }}>NINJA SKILL FX</p>
        <h1
          style={{
            margin: '0.45rem 0 0.75rem',
            color: '#f5fdff',
            fontSize: 'clamp(2rem, 8vw, 4.5rem)',
            textShadow: '0 0 1.5rem rgba(78, 220, 255, 0.6)',
          }}
        >
          번개
        </h1>
        <p style={{ margin: '0 0 1.5rem', opacity: 0.72 }}>
          다중 번개 · 충격파 · 방사선 · 스파크 · 화면 흔들림
        </p>
        <button
          type="button"
          onClick={() => setPlaybackKey((key) => key + 1)}
          style={{
            padding: '0.85rem 1.35rem',
            color: '#03111c',
            fontWeight: 800,
            border: 0,
            borderRadius: '0.6rem',
            background: 'linear-gradient(135deg, #f4fdff, #4edcff)',
            cursor: 'pointer',
          }}
        >
          다시 재생
        </button>
      </section>
    </main>
  );
}
