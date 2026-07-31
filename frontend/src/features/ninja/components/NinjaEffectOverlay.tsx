import { useEffect, useRef } from 'react';
import type { EffectDto } from '../api/ninjaApi';
import './NinjaEffectOverlay.css';

interface Particle {
  x: number;
  y: number;
  vx: number;
  vy: number;
  radius: number;
  age: number;
  life: number;
}

// 공격 스킬의 effect 데이터(color/particleCount/life/radius/speed)로 파티클 버스트를 그린다. 지금까진
// effect 데이터를 받아만 두고 실제로 그리는 렌더러가 없었는데, 인터미션의 "이펙트 재생" 구간을
// 의미 있게 채우기 위해 캔버스 파티클로 재생한다. 이 컴포넌트는 마운트돼 있는 동안(=서버가 준
// 이펙트 창) 계속 방출/애니메이션하고, 언마운트되면 멈춘다.
export function NinjaEffectOverlay({ effect }: { effect: EffectDto }) {
  const canvasRef = useRef<HTMLCanvasElement | null>(null);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    const dpr = window.devicePixelRatio || 1;
    const resize = () => {
      canvas.width = canvas.clientWidth * dpr;
      canvas.height = canvas.clientHeight * dpr;
    };
    resize();
    window.addEventListener('resize', resize);

    const particles: Particle[] = [];
    // life는 프레임 수 근사치(원본 프로토타입과 동일 개념) — 60fps 기준.
    const lifeFrames = Math.max(20, effect.life);

    const emitBurst = () => {
      const cx = canvas.width / 2;
      const cy = canvas.height / 2;
      for (let i = 0; i < effect.particleCount; i += 1) {
        const angle = (Math.PI * 2 * i) / effect.particleCount + Math.random() * 0.5;
        const speed = (effect.speedMin + Math.random() * (effect.speedMax - effect.speedMin)) * dpr;
        const radius = (effect.radiusMin + Math.random() * (effect.radiusMax - effect.radiusMin)) * dpr;
        particles.push({
          x: cx,
          y: cy,
          vx: Math.cos(angle) * speed,
          vy: Math.sin(angle) * speed,
          radius,
          age: 0,
          life: lifeFrames,
        });
      }
    };

    let raf = 0;
    let running = true;
    // 버스트는 한 번만. 예전엔 서버 이펙트 창(5초)을 채우려고 24프레임마다 다시 방출했는데,
    // 이펙트 자체는 life 프레임이면 끝나는 연출이라 그 반복이 재생 시간을 억지로 늘리고 있었다.
    emitBurst();

    const tick = () => {
      if (!running) return;
      ctx.clearRect(0, 0, canvas.width, canvas.height);
      for (let i = particles.length - 1; i >= 0; i -= 1) {
        const p = particles[i];
        p.age += 1;
        if (p.age >= p.life) {
          particles.splice(i, 1);
          continue;
        }
        p.x += p.vx;
        p.y += p.vy;
        p.vy += 0.05 * dpr; // 약한 중력감
        const alpha = 1 - p.age / p.life;
        ctx.globalAlpha = alpha;
        ctx.fillStyle = effect.color;
        ctx.beginPath();
        ctx.arc(p.x, p.y, p.radius, 0, Math.PI * 2);
        ctx.fill();
      }
      ctx.globalAlpha = 1;
      raf = window.requestAnimationFrame(tick);
    };
    raf = window.requestAnimationFrame(tick);

    return () => {
      running = false;
      window.cancelAnimationFrame(raf);
      window.removeEventListener('resize', resize);
    };
  }, [effect]);

  return <canvas ref={canvasRef} className="ninja-effect-overlay" aria-hidden />;
}
