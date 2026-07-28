import { useEffect, useRef } from 'react';
import { Application, Container, Graphics } from 'pixi.js';

const DURATION_MS = 1950;
const IMPACT_AT_MS = 650;
const BLADE_COUNT = 5;
const PARTICLE_COUNT = 120;

interface WindParticle {
  view: Graphics;
  startAt: number;
  x: number;
  y: number;
  vx: number;
  vy: number;
  life: number;
  maxLife: number;
  spin: number;
}

function randomBetween(min: number, max: number) {
  return min + Math.random() * (max - min);
}

function snap(value: number, grid: number) {
  return Math.round(value / grid) * grid;
}

function quadraticPoint(
  start: { x: number; y: number },
  control: { x: number; y: number },
  end: { x: number; y: number },
  progress: number,
) {
  const inverse = 1 - progress;
  return {
    x:
      inverse * inverse * start.x +
      2 * inverse * progress * control.x +
      progress * progress * end.x,
    y:
      inverse * inverse * start.y +
      2 * inverse * progress * control.y +
      progress * progress * end.y,
  };
}

function drawPixelCurve(
  graphics: Graphics,
  start: { x: number; y: number },
  control: { x: number; y: number },
  end: { x: number; y: number },
  progress: number,
  grid: number,
  colors: { glow: number; body: number; core: number },
  maxWidth: number,
) {
  const steps = 58;
  const visibleSteps = Math.floor(steps * progress);
  for (let index = 0; index <= visibleSteps; index += 1) {
    const pathProgress = index / steps;
    const point = quadraticPoint(start, control, end, pathProgress);
    const taper = Math.sin(pathProgress * Math.PI);
    const width = grid * (0.75 + taper * maxWidth);
    const x = snap(point.x, grid);
    const y = snap(point.y, grid);

    graphics
      .rect(x - width, y - width, width * 2, width * 2)
      .fill({ color: colors.glow, alpha: 0.13 + taper * 0.12 })
      .rect(x - width * 0.48, y - width * 0.48, width * 0.96, width * 0.96)
      .fill({ color: colors.body, alpha: 0.68 + taper * 0.24 })
      .rect(
        x - grid * 0.42,
        y - grid * 0.42,
        grid * 0.84,
        grid * 0.84,
      )
      .fill({ color: colors.core, alpha: 0.94 });
  }
}

function getBladeStart(width: number, height: number, index: number) {
  const starts = [
    { x: 0.04, y: 0.2 },
    { x: 0.03, y: 0.68 },
    { x: 0.28, y: 0.94 },
    { x: 0.96, y: 0.25 },
    { x: 0.95, y: 0.78 },
  ];
  return {
    x: starts[index].x * width,
    y: starts[index].y * height,
  };
}

function drawConvergingBlades(
  graphics: Graphics,
  width: number,
  height: number,
  grid: number,
  elapsed: number,
) {
  graphics.clear();
  const target = { x: width * 0.5, y: height * 0.53 };

  for (let index = 0; index < BLADE_COUNT; index += 1) {
    const localTime = elapsed - index * 52;
    const progress = Math.max(0, Math.min(1, localTime / 540));
    if (progress <= 0 || elapsed >= IMPACT_AT_MS + 170) continue;

    const start = getBladeStart(width, height, index);
    const control = {
      x: width * (index < 3 ? 0.24 + index * 0.06 : 0.76 - index * 0.045),
      y: height * (index % 2 === 0 ? 0.22 : 0.8),
    };
    drawPixelCurve(
      graphics,
      start,
      control,
      target,
      progress,
      grid,
      { glow: 0x52ffb5, body: 0xb8ff8a, core: 0xffffff },
      1.35,
    );
  }
}

function drawFinalScars(
  graphics: Graphics,
  width: number,
  height: number,
  grid: number,
  elapsed: number,
) {
  graphics.clear();
  const start = { x: width * 0.5, y: height * 0.56 };
  const endpoints = [
    { x: 0.02, y: 0.18 },
    { x: 0.2, y: 0.03 },
    { x: 0.5, y: -0.05 },
    { x: 0.8, y: 0.03 },
    { x: 0.98, y: 0.18 },
  ];

  for (let index = 0; index < endpoints.length; index += 1) {
    const localTime = elapsed - IMPACT_AT_MS - index * 48;
    const progress = Math.max(0, Math.min(1, localTime / 440));
    if (progress <= 0) continue;

    const end = {
      x: endpoints[index].x * width,
      y: endpoints[index].y * height,
    };
    const control = {
      x: width * (0.18 + index * 0.16),
      y: height * (0.36 - Math.abs(index - 2) * 0.04),
    };
    drawPixelCurve(
      graphics,
      start,
      control,
      end,
      progress,
      grid,
      { glow: 0xffb51b, body: 0xffe44f, core: 0xffffff },
      3.4,
    );
  }

  const fade = Math.max(0, 1 - (elapsed - 1280) / 420);
  graphics.alpha = elapsed < 1280 ? 1 : fade;
}

function drawPixelEllipseRing(
  graphics: Graphics,
  centerX: number,
  centerY: number,
  radiusX: number,
  radiusY: number,
  grid: number,
  color: number,
  alpha: number,
) {
  const steps = Math.max(44, Math.ceil((radiusX + radiusY) / grid) * 3);
  for (let index = 0; index < steps; index += 1) {
    const angle = (index / steps) * Math.PI * 2;
    graphics
      .rect(
        snap(centerX + Math.cos(angle) * radiusX, grid),
        snap(centerY + Math.sin(angle) * radiusY, grid),
        grid,
        grid,
      )
      .fill({ color, alpha });
  }
}

function drawShockwave(
  graphics: Graphics,
  width: number,
  height: number,
  grid: number,
  elapsed: number,
) {
  graphics.clear();
  const localTime = elapsed - IMPACT_AT_MS;
  if (localTime < 0 || localTime > 760) return;

  const progress = localTime / 760;
  const alpha = Math.max(0, 1 - progress);
  const centerX = width * 0.5;
  const centerY = height * 0.55;
  const minDimension = Math.min(width, height);

  for (let index = 0; index < 3; index += 1) {
    const delayedProgress = Math.max(0, progress - index * 0.11);
    if (delayedProgress <= 0) continue;
    const radiusX =
      minDimension * (0.04 + delayedProgress * (0.48 + index * 0.08));
    drawPixelEllipseRing(
      graphics,
      centerX,
      centerY,
      radiusX,
      radiusX * 0.32,
      grid,
      index === 1 ? 0xbaff75 : 0xffdf4b,
      alpha * (0.74 - index * 0.15),
    );
  }
}

function createParticles(
  layer: Container,
  width: number,
  height: number,
  grid: number,
): WindParticle[] {
  const centerX = width * 0.5;
  const centerY = height * 0.55;
  const palette = [0xffffff, 0xf9ef83, 0xbaff75, 0x54eeb2, 0xd19a35];

  return Array.from({ length: PARTICLE_COUNT }, (_, index) => {
    const size = grid * randomBetween(0.65, 1.75);
    const view = new Graphics();
    if (index % 6 === 0) {
      view
        .rect(-size * 1.8, -size / 2, size * 3.6, size)
        .fill({ color: palette[index % palette.length] })
        .rect(-size / 2, -size * 1.8, size, size * 3.6)
        .fill({ color: palette[index % palette.length] });
    } else {
      view
        .rect(-size * 0.5, -size * 1.2, size, size * 2.4)
        .fill({ color: palette[index % palette.length] });
    }
    view.visible = false;
    view.blendMode = 'add';
    layer.addChild(view);

    const angle = randomBetween(Math.PI * 1.05, Math.PI * 1.95);
    const speed = randomBetween(190, 760);
    const maxLife = randomBetween(520, 1120);
    return {
      view,
      startAt: IMPACT_AT_MS + randomBetween(0, 180),
      x: centerX + randomBetween(-grid * 3, grid * 3),
      y: centerY + randomBetween(-grid * 2, grid * 2),
      vx: Math.cos(angle) * speed,
      vy: Math.sin(angle) * speed - randomBetween(80, 250),
      life: maxLife,
      maxLife,
      spin: randomBetween(-9, 9),
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
  const localTime = elapsed - IMPACT_AT_MS;
  if (localTime < 0 || localTime > 130) return;
  const alpha = 0.48 * (1 - localTime / 130);

  for (let x = -height; x < width + height; x += grid * 4) {
    graphics
      .rect(x, 0, grid * 1.4, height * 1.5)
      .fill({ color: 0xfff4a4, alpha });
  }
}

export function PixelWindScarEffect() {
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
      const converging = new Graphics();
      const shockwave = new Graphics();
      const finalScars = new Graphics();
      const particleLayer = new Container();
      converging.blendMode = 'add';
      shockwave.blendMode = 'add';
      finalScars.blendMode = 'add';
      nextApp.stage.addChild(
        flash,
        converging,
        shockwave,
        finalScars,
        particleLayer,
      );

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

        drawFlash(flash, width, height, grid, elapsed);
        drawConvergingBlades(converging, width, height, grid, elapsed);
        drawShockwave(shockwave, width, height, grid, elapsed);
        drawFinalScars(finalScars, width, height, grid, elapsed);

        const deltaSeconds = time.deltaMS / 1000;
        for (const particle of particles) {
          if (elapsed < particle.startAt || particle.life <= 0) continue;
          particle.view.visible = true;
          particle.life -= time.deltaMS;
          particle.x += particle.vx * deltaSeconds;
          particle.y += particle.vy * deltaSeconds;
          particle.vx *= Math.pow(0.985, time.deltaMS / 16.67);
          particle.vy += 310 * deltaSeconds;
          particle.view.position.set(snap(particle.x, grid), snap(particle.y, grid));
          particle.view.rotation += particle.spin * deltaSeconds;
          particle.view.alpha = Math.max(0, particle.life / particle.maxLife);
        }

        const impactLife = Math.max(
          0,
          1 - (elapsed - IMPACT_AT_MS) / 650,
        );
        if (elapsed >= IMPACT_AT_MS) {
          nextApp.stage.position.set(
            snap(randomBetween(-grid * 1.7, grid * 1.7) * impactLife, grid),
            snap(randomBetween(-grid, grid) * impactLife, grid),
          );
        }

        nextApp.stage.alpha =
          elapsed < 1600 ? 1 : Math.max(0, (DURATION_MS - elapsed) / 350);
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
