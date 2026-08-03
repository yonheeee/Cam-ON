import { useEffect, useRef } from 'react';
import { Application, Container, Graphics } from 'pixi.js';

export const DURATION_MS = 1750;
const STRIKE_AT_MS = 120;
const BOLT_END_MS = 980;
const PARTICLE_COUNT = 78;

interface Point {
  x: number;
  y: number;
}

interface Particle {
  view: Graphics;
  x: number;
  y: number;
  vx: number;
  vy: number;
  life: number;
  maxLife: number;
}

function randomBetween(min: number, max: number) {
  return min + Math.random() * (max - min);
}

function snap(value: number, grid: number) {
  return Math.round(value / grid) * grid;
}

function drawPixelLine(
  graphics: Graphics,
  from: Point,
  to: Point,
  grid: number,
  color: number,
  alpha: number,
  size = grid,
) {
  const distance = Math.hypot(to.x - from.x, to.y - from.y);
  const steps = Math.max(1, Math.ceil(distance / (grid * 0.72)));
  for (let index = 0; index <= steps; index += 1) {
    const progress = index / steps;
    const x = snap(from.x + (to.x - from.x) * progress, grid);
    const y = snap(from.y + (to.y - from.y) * progress, grid);
    graphics.rect(x - size / 2, y - size / 2, size, size).fill({ color, alpha });
  }
}

function createBoltPath(
  width: number,
  height: number,
  grid: number,
  startXRatio: number,
  endXRatio: number,
  spread: number,
) {
  const segments = 12;
  return Array.from({ length: segments + 1 }, (_, index) => {
    const progress = index / segments;
    const edgeFactor = Math.sin(progress * Math.PI);
    return {
      x: snap(
        width * (startXRatio + (endXRatio - startXRatio) * progress) +
          randomBetween(-width * spread, width * spread) * edgeFactor,
        grid,
      ),
      y: snap(height * (0.025 + progress * 0.76), grid),
    };
  });
}

function drawBolt(graphics: Graphics, width: number, height: number, grid: number) {
  graphics.clear();
  const paths = [
    {
      points: createBoltPath(width, height, grid, 0.28, 0.47, 0.075),
      glow: 0x774cff,
      core: 0xbca9ff,
      scale: 0.7,
    },
    {
      points: createBoltPath(width, height, grid, 0.73, 0.49, 0.085),
      glow: 0x00a8e8,
      core: 0x8bf4ff,
      scale: 0.8,
    },
    {
      points: createBoltPath(width, height, grid, 0.58, 0.46, 0.11),
      glow: 0x00d5ff,
      core: 0xffffff,
      scale: 1,
    },
  ];

  for (const path of paths) {
    for (let index = 0; index < path.points.length - 1; index += 1) {
      const from = path.points[index];
      const to = path.points[index + 1];
      drawPixelLine(graphics, from, to, grid, path.glow, 0.12, grid * 3.1 * path.scale);
      drawPixelLine(graphics, from, to, grid, path.glow, 0.85, grid * 1.55 * path.scale);
      drawPixelLine(graphics, from, to, grid, path.core, 1, grid * 0.66 * path.scale);

      if (index > 1 && index < path.points.length - 2 && index % 2 === 0) {
        const direction = index % 4 === 0 ? 1 : -1;
        drawPixelLine(
          graphics,
          from,
          {
            x: from.x + direction * grid * randomBetween(5, 10),
            y: from.y + grid * randomBetween(3, 7),
          },
          grid,
          path.core,
          0.68,
          grid * 0.48,
        );
      }
    }
  }
}

function createParticles(
  layer: Container,
  width: number,
  height: number,
  grid: number,
): Particle[] {
  const centerX = snap(width * 0.46, grid);
  const centerY = snap(height * 0.78, grid);
  const palette = [0xffffff, 0x7ff4ff, 0x16cbea, 0xa985ff, 0x6e51df];

  return Array.from({ length: PARTICLE_COUNT }, (_, index) => {
    const size = grid * randomBetween(0.45, 1.45);
    const view = new Graphics()
      .rect(-size / 2, -size / 2, size, size)
      .fill({ color: palette[index % palette.length], alpha: 0.95 });
    view.blendMode = 'add';
    layer.addChild(view);

    const angle = randomBetween(Math.PI * 1.03, Math.PI * 1.97);
    const speed = randomBetween(180, 780);
    const maxLife = randomBetween(320, 1020);
    return {
      view,
      x: centerX + randomBetween(-grid * 2, grid * 2),
      y: centerY + randomBetween(-grid, grid),
      vx: Math.cos(angle) * speed,
      vy: Math.sin(angle) * speed - randomBetween(20, 180),
      life: maxLife,
      maxLife,
    };
  });
}

function drawDiamond(
  graphics: Graphics,
  centerX: number,
  centerY: number,
  radius: number,
  grid: number,
  color: number,
  alpha: number,
) {
  const points = [
    { x: centerX, y: centerY - radius * 0.42 },
    { x: centerX + radius, y: centerY },
    { x: centerX, y: centerY + radius * 0.42 },
    { x: centerX - radius, y: centerY },
    { x: centerX, y: centerY - radius * 0.42 },
  ];
  for (let index = 0; index < points.length - 1; index += 1) {
    drawPixelLine(graphics, points[index], points[index + 1], grid, color, alpha, grid * 0.72);
  }
}

function drawImpact(
  graphics: Graphics,
  width: number,
  height: number,
  grid: number,
  afterStrike: number,
) {
  graphics.clear();
  const centerX = snap(width * 0.46, grid);
  const centerY = snap(height * 0.78, grid);
  const minDimension = Math.min(width, height);
  const coreLife = Math.max(0, 1 - afterStrike / 720);

  if (coreLife > 0) {
    const pulse = Math.round(Math.sin(afterStrike * 0.04) * 2) * grid;
    graphics
      .rect(
        centerX - grid * 4 - pulse,
        centerY - grid * 4 - pulse,
        grid * 8 + pulse * 2,
        grid * 8 + pulse * 2,
      )
      .fill({ color: 0x20cff2, alpha: coreLife * 0.12 })
      .rect(centerX - grid * 2, centerY - grid * 2, grid * 4, grid * 4)
      .fill({ color: 0x9474ff, alpha: coreLife * 0.36 })
      .rect(centerX - grid, centerY - grid, grid * 2, grid * 2)
      .fill({ color: 0xffffff, alpha: coreLife });
  }

  const delays = [0, 100, 210];
  for (let index = 0; index < delays.length; index += 1) {
    const progress = Math.min(1, Math.max(0, (afterStrike - delays[index]) / (600 + index * 80)));
    if (progress <= 0 || progress >= 1) continue;
    drawDiamond(
      graphics,
      centerX,
      centerY,
      minDimension * (0.05 + progress * (0.48 + index * 0.08)),
      grid,
      index === 1 ? 0x9878ff : 0x74efff,
      (1 - progress) * 0.82,
    );
  }

  const crossProgress = Math.min(1, afterStrike / 430);
  if (crossProgress < 1) {
    const length = snap(minDimension * (0.12 + crossProgress * 0.5), grid);
    drawPixelLine(
      graphics,
      { x: centerX - length, y: centerY },
      { x: centerX + length, y: centerY },
      grid,
      0xe5fdff,
      (1 - crossProgress) * 0.72,
      grid * 0.45,
    );
    drawPixelLine(
      graphics,
      { x: centerX, y: centerY - length * 0.65 },
      { x: centerX, y: centerY + length * 0.65 },
      grid,
      0xe5fdff,
      (1 - crossProgress) * 0.72,
      grid * 0.45,
    );
  }
}

function drawFlash(
  graphics: Graphics,
  width: number,
  height: number,
  grid: number,
  elapsed: number,
) {
  graphics.clear();
  const afterStrike = elapsed - STRIKE_AT_MS;
  const alpha =
    elapsed < 75
      ? 0.34 * (1 - elapsed / 75)
      : afterStrike >= 0 && afterStrike < 120
        ? 0.58 * (1 - afterStrike / 120)
        : 0;
  if (alpha <= 0) return;

  for (let y = 0; y < height; y += grid * 2) {
    graphics
      .rect(0, y, width, grid)
      .fill({ color: afterStrike >= 0 ? 0xb8f7ff : 0x634bc9, alpha });
  }
}

// 도트 게임 스타일 번개: 모든 형태를 그리드에 스냅하고 사각 픽셀로만 렌더링한다.
export function PixelLightningEffect() {
  const hostRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    const host = hostRef.current;
    if (!host) return;

    let disposed = false;
    let app: Application | null = null;

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

      const flash = new Graphics();
      const bolt = new Graphics();
      const impact = new Graphics();
      const particleLayer = new Container();
      bolt.blendMode = 'add';
      impact.blendMode = 'add';
      nextApp.stage.addChild(flash, bolt, impact, particleLayer);

      let width = nextApp.screen.width;
      let height = nextApp.screen.height;
      let grid = Math.max(5, Math.min(11, Math.round(Math.min(width, height) / 82)));
      let particles = createParticles(particleLayer, width, height, grid);
      let elapsed = 0;
      let boltElapsed = 72;

      const rebuild = () => {
        grid = Math.max(5, Math.min(11, Math.round(Math.min(width, height) / 82)));
        particleLayer.removeChildren().forEach((child) => child.destroy());
        particles = createParticles(particleLayer, width, height, grid);
        boltElapsed = 72;
      };

      const update = (time: { deltaMS: number }) => {
        elapsed += time.deltaMS;
        boltElapsed += time.deltaMS;

        if (width !== nextApp.screen.width || height !== nextApp.screen.height) {
          width = nextApp.screen.width;
          height = nextApp.screen.height;
          rebuild();
        }

        const progress = Math.min(1, elapsed / DURATION_MS);
        const afterStrike = Math.max(0, elapsed - STRIKE_AT_MS);
        drawFlash(flash, width, height, grid, elapsed);

        if (elapsed >= STRIKE_AT_MS && elapsed < BOLT_END_MS && boltElapsed >= 72) {
          drawBolt(bolt, width, height, grid);
          boltElapsed = 0;
        }
        bolt.alpha =
          elapsed < STRIKE_AT_MS
            ? 0
            : elapsed < 690
              ? 1
              : Math.max(0, (BOLT_END_MS - elapsed) / (BOLT_END_MS - 690));
        drawImpact(impact, width, height, grid, afterStrike);

        if (afterStrike > 0) {
          const deltaSeconds = time.deltaMS / 1000;
          particleLayer.alpha = 1;
          for (const particle of particles) {
            particle.life -= time.deltaMS;
            if (particle.life <= 0) {
              particle.view.visible = false;
              continue;
            }
            particle.x += particle.vx * deltaSeconds;
            particle.y += particle.vy * deltaSeconds;
            particle.vy += 720 * deltaSeconds;
            particle.view.position.set(snap(particle.x, grid), snap(particle.y, grid));
            particle.view.alpha = Math.max(0, particle.life / particle.maxLife);
          }
        } else {
          particleLayer.alpha = 0;
        }

        const shakeLife = Math.max(0, 1 - afterStrike / 480);
        nextApp.stage.position.set(
          snap(randomBetween(-grid * 2, grid * 2) * shakeLife, grid),
          snap(randomBetween(-grid, grid) * shakeLife, grid),
        );
        nextApp.stage.alpha =
          progress < 0.74 ? 1 : Math.max(0, (1 - progress) / 0.26);

        if (progress >= 1) {
          nextApp.stage.position.set(0, 0);
          nextApp.ticker.remove(update);
        }
      };
      nextApp.ticker.add(update);
    };

    void start();

    return () => {
      disposed = true;
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
