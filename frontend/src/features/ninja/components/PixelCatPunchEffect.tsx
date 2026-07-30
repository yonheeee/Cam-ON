import { useEffect, useRef } from 'react';
import { Application, Container, Graphics } from 'pixi.js';

export const DURATION_MS = 1650;
const IMPACT_AT_MS = 190;
const PARTICLE_COUNT = 72;

interface Particle {
  view: Graphics;
  x: number;
  y: number;
  vx: number;
  vy: number;
  spin: number;
  life: number;
  maxLife: number;
}

function randomBetween(min: number, max: number) {
  return min + Math.random() * (max - min);
}

function snap(value: number, grid: number) {
  return Math.round(value / grid) * grid;
}

function getImpactCenter(width: number, height: number, grid: number) {
  return {
    x: snap(width * 0.5, grid),
    y: snap(height * 0.5, grid),
  };
}

function drawSlash(
  graphics: Graphics,
  width: number,
  height: number,
  grid: number,
  clawIndex: number,
  slashIndex: number,
  progress: number,
) {
  const steps = 48;
  const visibleSteps = Math.floor(steps * progress);
  const yOffset = (slashIndex - 1) * grid * 5;

  for (let index = 0; index <= visibleSteps; index += 1) {
    const pathProgress = index / steps;
    const x = snap(
      width *
        (clawIndex === 0
          ? 0.24 + pathProgress * 0.52
          : 0.76 - pathProgress * 0.52),
      grid,
    );
    const curve = Math.sin(pathProgress * Math.PI) * height * 0.085;
    const zigzag =
      (Math.floor(pathProgress * 10) % 2 === 0 ? -1 : 1) * grid * 1.15;
    const y = snap(
      height * (0.28 + pathProgress * 0.44) +
        (clawIndex === 0 ? curve : -curve) +
        (clawIndex === 0 ? zigzag : -zigzag) +
        yOffset,
      grid,
    );
    const taper = Math.sin(pathProgress * Math.PI);
    const glowSize = grid * (2.8 + taper * 2.2);
    const coreSize = grid * (0.65 + taper * 0.55);

    graphics
      .rect(x - glowSize / 2, y - glowSize / 2, glowSize, glowSize)
      .fill({ color: slashIndex === 1 ? 0xff41aa : 0xd446ff, alpha: 0.12 })
      .rect(x - grid, y - grid, grid * 2, grid * 2)
      .fill({ color: 0xff4fb2, alpha: 0.82 })
      .rect(x - coreSize / 2, y - coreSize / 2, coreSize, coreSize)
      .fill({ color: 0xffffff, alpha: 1 });
  }
}

function drawSlashes(
  graphics: Graphics,
  width: number,
  height: number,
  grid: number,
  elapsed: number,
) {
  graphics.clear();
  for (let clawIndex = 0; clawIndex < 2; clawIndex += 1) {
    for (let slashIndex = 0; slashIndex < 3; slashIndex += 1) {
      const slashGap = clawIndex === 0 ? 55 : 45;
      const drawDuration = clawIndex === 0 ? 310 : 200;
      const delay = clawIndex * 500 + slashIndex * slashGap;
      const localProgress = Math.min(
        1,
        Math.max(0, (elapsed - delay) / drawDuration),
      );
      if (localProgress > 0) {
        drawSlash(
          graphics,
          width,
          height,
          grid,
          clawIndex,
          slashIndex,
          localProgress,
        );
      }
    }
  }
}

function drawPixelEllipseShockwave(
  graphics: Graphics,
  centerX: number,
  centerY: number,
  radiusX: number,
  radiusY: number,
  grid: number,
  color: number,
  alpha: number,
) {
  const steps = Math.max(48, Math.ceil((radiusX + radiusY) / grid) * 3);
  for (let index = 0; index < steps; index += 1) {
    const angle = (index / steps) * Math.PI * 2;
    const x = snap(centerX + Math.cos(angle) * radiusX, grid);
    const y = snap(centerY + Math.sin(angle) * radiusY, grid);
    graphics.rect(x, y, grid, grid).fill({ color, alpha });
  }
}

function drawImpact(
  graphics: Graphics,
  width: number,
  height: number,
  grid: number,
  afterImpact: number,
) {
  graphics.clear();

  const { x: centerX, y: centerY } = getImpactCenter(width, height, grid);
  const minDimension = Math.min(width, height);
  const delays = [0, 110, 230];

  for (let index = 0; index < delays.length; index += 1) {
    const progress = Math.min(1, Math.max(0, (afterImpact - delays[index]) / (560 + index * 80)));
    if (progress <= 0 || progress >= 1) continue;
    const radius = minDimension * (0.06 + progress * (0.42 + index * 0.07));
    drawPixelEllipseShockwave(
      graphics,
      centerX,
      centerY,
      radius,
      radius * 0.56,
      grid,
      index === 1 ? 0xffcf42 : 0xff66bd,
      (1 - progress) * 0.72,
    );
  }
}

function createParticles(
  layer: Container,
  width: number,
  height: number,
  grid: number,
): Particle[] {
  const { x: centerX, y: centerY } = getImpactCenter(width, height, grid);
  const palette = [0xffffff, 0xff9bd5, 0xff3aa5, 0xd94cff, 0xffd84c];

  return Array.from({ length: PARTICLE_COUNT }, (_, index) => {
    const size = grid * randomBetween(0.7, 1.6);
    const view = new Graphics();
    if (index % 5 === 0) {
      view
        .rect(-size * 1.8, -size / 2, size * 3.6, size)
        .fill({ color: palette[index % palette.length] })
        .rect(-size / 2, -size * 1.8, size, size * 3.6)
        .fill({ color: palette[index % palette.length] });
    } else {
      view
        .rect(-size / 2, -size / 2, size, size)
        .fill({ color: palette[index % palette.length] });
    }
    view.blendMode = 'add';
    layer.addChild(view);

    const angle = randomBetween(Math.PI * 0.75, Math.PI * 2.25);
    const speed = randomBetween(170, 720);
    const maxLife = randomBetween(340, 980);
    return {
      view,
      x: centerX + randomBetween(-grid * 2, grid * 2),
      y: centerY + randomBetween(-grid * 2, grid * 2),
      vx: Math.cos(angle) * speed,
      vy: Math.sin(angle) * speed - randomBetween(20, 140),
      spin: randomBetween(-7, 7),
      life: maxLife,
      maxLife,
    };
  });
}

function drawFlash(
  graphics: Graphics,
  width: number,
  height: number,
  grid: number,
  elapsed: number,
) {
  graphics.clear();
  const afterImpact = elapsed - IMPACT_AT_MS;
  const alpha =
    elapsed < 65
      ? 0.2 * (1 - elapsed / 65)
      : afterImpact >= 0 && afterImpact < 125
        ? 0.48 * (1 - afterImpact / 125)
        : 0;
  if (alpha <= 0) return;

  for (let x = 0; x < width; x += grid * 2) {
    graphics
      .rect(x, 0, grid, height)
      .fill({ color: afterImpact >= 0 ? 0xffb9e4 : 0xb346d3, alpha });
  }
}

// 도트 게임 스타일 냥냥펀치: 3연속 발톱 참격과 고양이 발바닥 충돌 마크.
export function PixelCatPunchEffect() {
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
      const slashes = new Graphics();
      const impact = new Graphics();
      const particleLayer = new Container();
      slashes.blendMode = 'add';
      impact.blendMode = 'add';
      nextApp.stage.addChild(flash, slashes, impact, particleLayer);

      let width = nextApp.screen.width;
      let height = nextApp.screen.height;
      let grid = Math.max(5, Math.min(11, Math.round(Math.min(width, height) / 82)));
      let particles = createParticles(particleLayer, width, height, grid);
      let elapsed = 0;

      const rebuild = () => {
        grid = Math.max(5, Math.min(11, Math.round(Math.min(width, height) / 82)));
        particleLayer.removeChildren().forEach((child) => child.destroy());
        particles = createParticles(particleLayer, width, height, grid);
      };

      const update = (time: { deltaMS: number }) => {
        elapsed += time.deltaMS;
        if (width !== nextApp.screen.width || height !== nextApp.screen.height) {
          width = nextApp.screen.width;
          height = nextApp.screen.height;
          rebuild();
        }

        const progress = Math.min(1, elapsed / DURATION_MS);
        const afterImpact = Math.max(0, elapsed - IMPACT_AT_MS);
        drawFlash(flash, width, height, grid, elapsed);
        drawSlashes(slashes, width, height, grid, elapsed);
        slashes.alpha =
          elapsed < 1250 ? 1 : Math.max(0, (1550 - elapsed) / 300);
        drawImpact(impact, width, height, grid, afterImpact);

        if (afterImpact > 0) {
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
            particle.vy += 620 * deltaSeconds;
            particle.view.position.set(snap(particle.x, grid), snap(particle.y, grid));
            particle.view.rotation += particle.spin * deltaSeconds;
            particle.view.alpha = Math.max(0, particle.life / particle.maxLife);
          }
        } else {
          particleLayer.alpha = 0;
        }

        const shakeLife = Math.max(0, 1 - afterImpact / 430);
        nextApp.stage.position.set(
          snap(randomBetween(-grid * 1.5, grid * 1.5) * shakeLife, grid),
          snap(randomBetween(-grid, grid) * shakeLife, grid),
        );
        nextApp.stage.alpha =
          progress < 0.76 ? 1 : Math.max(0, (1 - progress) / 0.24);

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
