import { useEffect, useRef } from 'react';
import { Application, Graphics } from 'pixi.js';
import { mixSfxVolume } from '../../sound/lib/sfxVolume';

export const DURATION_MS = 2300;

function randomBetween(min: number, max: number) {
  return min + Math.random() * (max - min);
}

function snap(value: number, grid: number) {
  return Math.round(value / grid) * grid;
}

function clamp01(value: number) {
  return Math.max(0, Math.min(1, value));
}

function easeOutBack(value: number) {
  const c1 = 1.70158;
  const c3 = c1 + 1;
  return 1 + c3 * Math.pow(value - 1, 3) + c1 * Math.pow(value - 1, 2);
}

function drawPixelDome(
  graphics: Graphics,
  centerX: number,
  centerY: number,
  radiusX: number,
  radiusY: number,
  grid: number,
  color: number,
  alpha: number,
) {
  if (radiusX < grid || radiusY < grid) return;
  for (let y = -radiusY; y <= radiusY * 0.42; y += grid) {
    for (let x = -radiusX; x <= radiusX; x += grid) {
      const normalized =
        (x * x) / (radiusX * radiusX) +
        (y * y) / (radiusY * radiusY);
      if (normalized > 1) continue;
      graphics
        .rect(snap(centerX + x, grid), snap(centerY + y, grid), grid, grid)
        .fill({ color, alpha });
    }
  }
}

function drawBaldHead(
  graphics: Graphics,
  width: number,
  height: number,
  grid: number,
  elapsed: number,
) {
  graphics.clear();
  const local = elapsed - 260;
  if (local < 0) return;

  const centerX = width * 0.5;
  const centerY = height * 0.43;
  const minDimension = Math.min(width, height);
  const entrance = easeOutBack(clamp01(local / 430));
  const fade = elapsed < 1990 ? 1 : Math.max(0, (DURATION_MS - elapsed) / 310);
  const pulse = 1 + Math.sin(elapsed * 0.012) * 0.018;
  const radiusX = minDimension * 0.185 * entrance * pulse;
  const radiusY = minDimension * 0.135 * entrance * pulse;

  drawPixelDome(
    graphics,
    centerX,
    centerY,
    radiusX + grid * 1.5,
    radiusY + grid * 1.5,
    grid,
    0x7e4840,
    fade * 0.72,
  );
  drawPixelDome(
    graphics,
    centerX,
    centerY,
    radiusX,
    radiusY,
    grid,
    0xf0ad91,
    fade,
  );

  drawPixelDome(
    graphics,
    centerX,
    centerY + grid,
    radiusX * 0.92,
    radiusY * 0.9,
    grid,
    0xf6b89d,
    fade * 0.48,
  );

  for (let seam = -4; seam <= 4; seam += 1) {
    graphics
      .rect(
        snap(centerX + seam * radiusX * 0.2, grid),
        snap(centerY + radiusY * 0.38, grid),
        grid,
        grid,
      )
      .fill({ color: 0xc77f6b, alpha: fade * 0.54 });
  }
}

function drawBaldSparkles(
  graphics: Graphics,
  width: number,
  height: number,
  grid: number,
  elapsed: number,
) {
  graphics.clear();
  const local = elapsed - 560;
  if (local < 0 || local > 1250) return;

  const centerX = width * 0.5;
  const centerY = height * 0.43;
  const minDimension = Math.min(width, height);
  const fade = local < 950 ? 1 : Math.max(0, (1250 - local) / 300);
  const points = [
    { x: -0.09, y: -0.07, delay: 0 },
    { x: 0.1, y: -0.03, delay: 180 },
    { x: 0.02, y: -0.105, delay: 380 },
  ];

  for (const point of points) {
    const pointLocal = local - point.delay;
    if (pointLocal < 0) continue;
    const pulse = 0.55 + Math.abs(Math.sin(pointLocal * 0.014)) * 0.75;
    const size = grid * pulse;
    const x = centerX + minDimension * point.x;
    const y = centerY + minDimension * point.y;
    graphics
      .rect(x - size * 2.2, y - size / 2, size * 4.4, size)
      .fill({ color: 0xffffff, alpha: fade * 0.88 })
      .rect(x - size / 2, y - size * 2.2, size, size * 4.4)
      .fill({ color: 0xffe47a, alpha: fade });
  }
}

export function PixelBaldEffect() {
  const hostRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    const host = hostRef.current;
    if (!host) return;

    let disposed = false;
    let app: Application | null = null;
    let soundTimer: number | null = null;
    let sound: HTMLAudioElement | null = null;

    const start = async () => {
      const nextApp = new Application();
      await nextApp.init({
        backgroundAlpha: 0,
        antialias: false,
        autoDensity: true,
        resolution: Math.min(window.devicePixelRatio || 1, 2),
        resizeTo: host,
      });

      if (disposed) {
        nextApp.destroy({ removeView: true }, { children: true });
        return;
      }

      app = nextApp;
      host.appendChild(nextApp.canvas);

      const head = new Graphics();
      const sparkles = new Graphics();
      sparkles.blendMode = 'add';
      nextApp.stage.addChild(head, sparkles);

      let width = nextApp.screen.width;
      let height = nextApp.screen.height;
      let grid = Math.max(5, Math.min(11, Math.round(Math.min(width, height) / 88)));
      let elapsed = 0;

      sound = new Audio('/assets/sounds/beam.mp3');
      sound.preload = 'auto';
      soundTimer = window.setTimeout(() => {
        if (disposed || !sound) return;
        sound.volume = mixSfxVolume(0.85);
        void sound.play().catch(() => {
          // 브라우저가 자동 재생을 막더라도 이펙트 진행은 유지한다.
        });
      }, 260);

      const rebuild = () => {
        grid = Math.max(5, Math.min(11, Math.round(Math.min(width, height) / 88)));
      };

      const update = (time: { deltaMS: number }) => {
        elapsed += time.deltaMS;
        if (width !== nextApp.screen.width || height !== nextApp.screen.height) {
          width = nextApp.screen.width;
          height = nextApp.screen.height;
          rebuild();
        }

        drawBaldHead(head, width, height, grid, elapsed);
        drawBaldSparkles(sparkles, width, height, grid, elapsed);

        const popShake = Math.max(0, 1 - Math.abs(elapsed - 300) / 300);
        nextApp.stage.position.set(
          snap(randomBetween(-grid, grid) * popShake, grid),
          snap(randomBetween(-grid * 0.7, grid * 0.7) * popShake, grid),
        );
        nextApp.stage.alpha =
          elapsed < 2020 ? 1 : Math.max(0, (DURATION_MS - elapsed) / 280);

        if (elapsed >= DURATION_MS) {
          nextApp.stage.position.set(0, 0);
          nextApp.ticker.remove(update);
        }
      };

      nextApp.ticker.add(update);
    };

    void start();

    return () => {
      disposed = true;
      if (soundTimer !== null) window.clearTimeout(soundTimer);
      sound?.pause();
      if (sound) sound.currentTime = 0;
      app?.destroy({ removeView: true }, { children: true });
      host.replaceChildren();
    };
  }, []);

  return (
    <div
      ref={hostRef}
      className="ninja-effect-overlay ninja-effect-overlay--pixel"
      aria-hidden
    />
  );
}
