import { useEffect, useRef } from 'react';
import { Application, Container, Graphics } from 'pixi.js';
import { mixSfxVolume } from '../../sound/lib/sfxVolume';

export const DURATION_MS = 1700;
const FIRST_HIT_MS = 430;
const SECOND_HIT_MS = 850;
const PARTICLES_PER_HIT = 46;

interface ImpactParticle {
  view: Graphics;
  startAt: number;
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

function clamp01(value: number) {
  return Math.max(0, Math.min(1, value));
}

function easeOutCubic(value: number) {
  return 1 - Math.pow(1 - value, 3);
}

function drawPixelEllipse(
  graphics: Graphics,
  centerX: number,
  centerY: number,
  radiusX: number,
  radiusY: number,
  grid: number,
  color: number,
  alpha: number,
) {
  for (let y = -radiusY; y <= radiusY; y += grid) {
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

function drawPixelLine(
  graphics: Graphics,
  startX: number,
  startY: number,
  endX: number,
  endY: number,
  grid: number,
  color: number,
  alpha: number,
  thickness = 1,
) {
  const distance = Math.hypot(endX - startX, endY - startY);
  const steps = Math.max(1, Math.ceil(distance / grid));
  for (let index = 0; index <= steps; index += 1) {
    const progress = index / steps;
    graphics
      .rect(
        snap(startX + (endX - startX) * progress, grid),
        snap(startY + (endY - startY) * progress, grid),
        grid * thickness,
        grid * thickness,
      )
      .fill({ color, alpha });
  }
}

function drawGlovedHand(
  graphics: Graphics,
  centerX: number,
  centerY: number,
  size: number,
  grid: number,
  side: -1 | 1,
  alpha: number,
) {
  const outline = 0x965e50;
  const glove = 0xe3a084;
  const highlight = 0xffd3bd;

  drawPixelEllipse(
    graphics,
    centerX,
    centerY + size * 0.12,
    size * 0.48 + grid,
    size * 0.56 + grid,
    grid,
    outline,
    alpha,
  );
  drawPixelEllipse(
    graphics,
    centerX,
    centerY + size * 0.12,
    size * 0.44,
    size * 0.52,
    grid,
    glove,
    alpha,
  );

  const fingers = [
    { x: -0.34, y: -0.62, rx: 0.11, ry: 0.35 },
    { x: -0.11, y: -0.77, rx: 0.12, ry: 0.45 },
    { x: 0.13, y: -0.75, rx: 0.12, ry: 0.43 },
    { x: 0.35, y: -0.59, rx: 0.11, ry: 0.33 },
  ];

  for (const finger of fingers) {
    drawPixelEllipse(
      graphics,
      centerX + size * finger.x,
      centerY + size * finger.y,
      size * finger.rx + grid,
      size * finger.ry + grid,
      grid,
      outline,
      alpha,
    );
    drawPixelEllipse(
      graphics,
      centerX + size * finger.x,
      centerY + size * finger.y,
      size * finger.rx,
      size * finger.ry,
      grid,
      glove,
      alpha,
    );
  }

  drawPixelEllipse(
    graphics,
    centerX - side * size * 0.55,
    centerY - size * 0.02,
    size * 0.25 + grid,
    size * 0.13 + grid,
    grid,
    outline,
    alpha,
  );
  drawPixelEllipse(
    graphics,
    centerX - side * size * 0.55,
    centerY - size * 0.02,
    size * 0.23,
    size * 0.11,
    grid,
    glove,
    alpha,
  );

  drawPixelLine(
    graphics,
    centerX - side * size * 0.24,
    centerY - size * 0.18,
    centerX + side * size * 0.22,
    centerY + size * 0.28,
    grid,
    highlight,
    alpha * 0.5,
  );
}

function drawWristSwing(
  graphics: Graphics,
  width: number,
  height: number,
  grid: number,
  elapsed: number,
) {
  const minDimension = Math.min(width, height);
  const size = minDimension * 0.145;
  const entrance = easeOutCubic(clamp01(elapsed / 180));
  const fade = elapsed < 1320 ? 1 : Math.max(0, (1580 - elapsed) / 260);
  const alpha = entrance * fade;

  let angle = 0;
  if (elapsed < FIRST_HIT_MS) {
    angle = -0.58 * easeOutCubic(clamp01((elapsed - 170) / (FIRST_HIT_MS - 170)));
  } else if (elapsed < SECOND_HIT_MS) {
    const across = clamp01((elapsed - FIRST_HIT_MS) / (SECOND_HIT_MS - FIRST_HIT_MS));
    angle = -0.58 + across * 1.16;
  } else {
    angle = 0.58 * (1 - easeOutCubic(clamp01((elapsed - SECOND_HIT_MS) / 330)));
  }

  graphics.position.set(snap(width * 0.5, grid), snap(height * 0.76, grid));
  graphics.pivot.set(0, 0);
  graphics.rotation = angle;
  graphics.alpha = alpha;

  graphics
    .rect(-size * 0.17, -size * 0.38, size * 0.34, size * 1.28)
    .fill({ color: 0x965e50, alpha })
    .rect(-size * 0.12, -size * 0.36, size * 0.24, size * 1.22)
    .fill({ color: 0xe3a084, alpha });
  drawGlovedHand(graphics, 0, -size * 0.86, size, grid, 1, alpha);
}

function drawSingleImpact(
  graphics: Graphics,
  width: number,
  height: number,
  grid: number,
  elapsed: number,
  side: -1 | 1,
  hitAt: number,
) {
  const local = elapsed - hitAt;
  if (local < 0 || local > 850) return;

  const minDimension = Math.min(width, height);
  const centerX = width * 0.5 + side * minDimension * 0.075;
  const centerY = height * 0.51;
  const fade = local < 480 ? 1 : Math.max(0, (850 - local) / 370);

  drawPixelEllipse(
    graphics,
    centerX,
    centerY,
    minDimension * 0.07,
    minDimension * 0.035,
    grid,
    0xff324f,
    fade * 0.26,
  );

  for (let mark = -1; mark <= 1; mark += 1) {
    drawPixelLine(
      graphics,
      centerX - side * minDimension * 0.045,
      centerY + mark * grid * 2,
      centerX + side * minDimension * 0.04,
      centerY + mark * grid * 4,
      grid,
      mark === 0 ? 0xffffff : 0xff5269,
      fade * (mark === 0 ? 0.88 : 0.72),
      mark === 0 ? 1.25 : 1,
    );
  }

  const burstLife = Math.max(0, 1 - local / 360);
  for (let ray = 0; ray < 9; ray += 1) {
    const angle = (ray / 9) * Math.PI * 2;
    const inner = minDimension * 0.06;
    const outer = minDimension * (0.11 + (1 - burstLife) * 0.12);
    drawPixelLine(
      graphics,
      centerX + Math.cos(angle) * inner,
      centerY + Math.sin(angle) * inner,
      centerX + Math.cos(angle) * outer,
      centerY + Math.sin(angle) * outer,
      grid,
      ray % 2 === 0 ? 0xffe15d : 0xff6479,
      burstLife * 0.75,
    );
  }
}

function createParticles(
  layer: Container,
  width: number,
  height: number,
  grid: number,
): ImpactParticle[] {
  const minDimension = Math.min(width, height);
  const palette = [0xffffff, 0xffdf57, 0xff7188, 0xff3555];
  const hitDefinitions = [
    { startAt: FIRST_HIT_MS, side: -1 },
    { startAt: SECOND_HIT_MS, side: 1 },
  ] as const;

  return hitDefinitions.flatMap(({ startAt, side }) =>
    Array.from({ length: PARTICLES_PER_HIT }, (_, index) => {
      const size = grid * randomBetween(0.6, 1.35);
      const view = new Graphics();
      if (index % 7 === 0) {
        view
          .rect(-size * 1.6, -size / 2, size * 3.2, size)
          .fill({ color: palette[index % palette.length] })
          .rect(-size / 2, -size * 1.6, size, size * 3.2)
          .fill({ color: palette[index % palette.length] });
      } else {
        view
          .rect(-size / 2, -size / 2, size, size)
          .fill({ color: palette[index % palette.length] });
      }
      view.visible = false;
      view.blendMode = 'add';
      layer.addChild(view);

      const angle = randomBetween(0, Math.PI * 2);
      const speed = randomBetween(150, 610);
      const maxLife = randomBetween(330, 760);
      return {
        view,
        startAt,
        x: width * 0.5 + side * minDimension * 0.075,
        y: height * 0.51,
        vx: Math.cos(angle) * speed - side * 80,
        vy: Math.sin(angle) * speed - randomBetween(10, 90),
        spin: randomBetween(-8, 8),
        life: maxLife,
        maxLife,
      };
    }),
  );
}

function drawFlash(
  graphics: Graphics,
  width: number,
  height: number,
  grid: number,
  elapsed: number,
) {
  graphics.clear();
  const localFirst = elapsed - FIRST_HIT_MS;
  const localSecond = elapsed - SECOND_HIT_MS;
  const activeLocal =
    localSecond >= 0 && localSecond < 110
      ? localSecond
      : localFirst >= 0 && localFirst < 110
        ? localFirst
        : null;
  if (activeLocal === null) return;

  const alpha = 0.18 * (1 - activeLocal / 110);
  for (let y = 0; y < height; y += grid * 5) {
    graphics.rect(0, y, width, grid).fill({ color: 0xffd2b0, alpha });
  }
}

export function PixelSlapEffect() {
  const hostRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    const host = hostRef.current;
    if (!host) return;

    let disposed = false;
    let app: Application | null = null;
    const soundTimers: number[] = [];
    const activeSounds: HTMLAudioElement[] = [];

    const scheduleSlapSound = (delayMs: number) => {
      const audio = new Audio('/assets/sounds/slap.mp3');
      audio.preload = 'auto';
      activeSounds.push(audio);
      const timer = window.setTimeout(() => {
        if (disposed) return;
        audio.volume = mixSfxVolume(0.9);
        void audio.play().catch(() => {
          // 브라우저가 자동 재생을 막더라도 이펙트 진행은 유지한다.
        });
      }, delayMs);
      soundTimers.push(timer);
    };

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
      const hands = new Graphics();
      const impacts = new Graphics();
      const particleLayer = new Container();
      hands.blendMode = 'normal';
      impacts.blendMode = 'add';
      nextApp.stage.addChild(flash, hands, impacts, particleLayer);

      let width = nextApp.screen.width;
      let height = nextApp.screen.height;
      let grid = Math.max(5, Math.min(11, Math.round(Math.min(width, height) / 86)));
      let particles = createParticles(particleLayer, width, height, grid);
      let elapsed = 0;

      scheduleSlapSound(FIRST_HIT_MS);
      scheduleSlapSound(SECOND_HIT_MS);

      const rebuild = () => {
        grid = Math.max(5, Math.min(11, Math.round(Math.min(width, height) / 86)));
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
        hands.clear();
        drawWristSwing(hands, width, height, grid, elapsed);
        impacts.clear();
        drawSingleImpact(impacts, width, height, grid, elapsed, -1, FIRST_HIT_MS);
        drawSingleImpact(impacts, width, height, grid, elapsed, 1, SECOND_HIT_MS);

        const deltaSeconds = time.deltaMS / 1000;
        for (const particle of particles) {
          if (elapsed < particle.startAt || particle.life <= 0) continue;
          particle.view.visible = true;
          particle.life -= time.deltaMS;
          particle.x += particle.vx * deltaSeconds;
          particle.y += particle.vy * deltaSeconds;
          particle.vy += 560 * deltaSeconds;
          particle.view.position.set(snap(particle.x, grid), snap(particle.y, grid));
          particle.view.rotation += particle.spin * deltaSeconds;
          particle.view.alpha = Math.max(0, particle.life / particle.maxLife);
        }

        const firstShake = Math.max(0, 1 - Math.abs(elapsed - FIRST_HIT_MS) / 270);
        const secondShake = Math.max(0, 1 - Math.abs(elapsed - SECOND_HIT_MS) / 270);
        const shake = Math.max(firstShake, secondShake);
        nextApp.stage.position.set(
          snap(randomBetween(-grid, grid) * shake, grid),
          snap(randomBetween(-grid * 0.7, grid * 0.7) * shake, grid),
        );
        nextApp.stage.alpha =
          elapsed < 1400 ? 1 : Math.max(0, (DURATION_MS - elapsed) / 300);

        if (elapsed >= DURATION_MS) {
          hands.rotation = 0;
          nextApp.stage.position.set(0, 0);
          nextApp.ticker.remove(update);
        }
      };

      nextApp.ticker.add(update);
    };

    void start();

    return () => {
      disposed = true;
      soundTimers.forEach((timer) => window.clearTimeout(timer));
      activeSounds.forEach((audio) => {
        audio.pause();
        audio.currentTime = 0;
      });
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
