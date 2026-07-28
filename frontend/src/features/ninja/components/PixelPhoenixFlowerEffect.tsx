import { useEffect, useRef } from 'react';
import { Application, Container, Graphics } from 'pixi.js';

const DURATION_MS = 1900;
const SHOT_COUNT = 5;
const SHOT_GAP_MS = 95;
const FLIGHT_MS = 470;
const EMBERS_PER_SHOT = 26;

export type PhoenixFlowerVariant = 'cast' | 'hit';

interface Ember {
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

function getTarget(width: number, height: number, index: number) {
  const targets = [
    { x: 0.24, y: 0.27 },
    { x: 0.52, y: 0.15 },
    { x: 0.78, y: 0.33 },
    { x: 0.3, y: 0.76 },
    { x: 0.73, y: 0.72 },
  ];
  return {
    x: width * targets[index].x,
    y: height * targets[index].y,
  };
}

function getFlightPoint(
  width: number,
  height: number,
  shotIndex: number,
  progress: number,
  variant: PhoenixFlowerVariant,
) {
  const center = { x: width * 0.5, y: height * 0.5 };
  const outerPoint = getTarget(width, height, shotIndex);
  const start = variant === 'cast' ? center : outerPoint;
  const target = variant === 'cast' ? outerPoint : center;
  const directionX = target.x - start.x;
  const directionY = target.y - start.y;
  const curveDirection = shotIndex % 2 === 0 ? 1 : -1;
  const controlX =
    (start.x + target.x) / 2 - directionY * 0.22 * curveDirection;
  const controlY =
    (start.y + target.y) / 2 + directionX * 0.22 * curveDirection;
  const inverse = 1 - progress;

  return {
    x:
      inverse * inverse * start.x +
      2 * inverse * progress * controlX +
      progress * progress * target.x,
    y:
      inverse * inverse * start.y +
      2 * inverse * progress * controlY +
      progress * progress * target.y,
  };
}

function drawProjectiles(
  graphics: Graphics,
  width: number,
  height: number,
  grid: number,
  elapsed: number,
  variant: PhoenixFlowerVariant,
) {
  graphics.clear();

  for (let shotIndex = 0; shotIndex < SHOT_COUNT; shotIndex += 1) {
    const shotTime = elapsed - shotIndex * SHOT_GAP_MS;
    if (shotTime <= 0 || shotTime >= FLIGHT_MS) continue;

    const progress = Math.min(1, shotTime / FLIGHT_MS);
    for (let tailIndex = 15; tailIndex >= 0; tailIndex -= 1) {
      const tailProgress = Math.max(0, progress - tailIndex * 0.018);
      const point = getFlightPoint(
        width,
        height,
        shotIndex,
        tailProgress,
        variant,
      );
      const tailLife = 1 - tailIndex / 16;
      const size = grid * (1 + tailLife * 2.4);
      const color =
        tailIndex < 3
          ? 0xffffff
          : tailIndex < 7
            ? 0xffef86
            : tailIndex < 11
              ? 0xffa51f
              : 0xff421c;

      graphics
        .rect(
          snap(point.x, grid) - size,
          snap(point.y, grid) - size,
          size * 2,
          size * 2,
        )
        .fill({ color: 0xff2718, alpha: 0.08 + tailLife * 0.15 })
        .rect(
          snap(point.x, grid) - size / 2,
          snap(point.y, grid) - size / 2,
          size,
          size,
        )
        .fill({ color, alpha: 0.18 + tailLife * 0.78 });
    }

    const head = getFlightPoint(width, height, shotIndex, progress, variant);
    const flicker = 1 + Math.sin(elapsed * 0.045 + shotIndex * 1.8) * 0.12;
    graphics
      .rect(
        snap(head.x, grid) - grid * 5 * flicker,
        snap(head.y, grid) - grid * 5 * flicker,
        grid * 10 * flicker,
        grid * 10 * flicker,
      )
      .fill({ color: 0xff2014, alpha: 0.16 })
      .rect(
        snap(head.x, grid) - grid * 3.4 * flicker,
        snap(head.y, grid) - grid * 3.4 * flicker,
        grid * 6.8 * flicker,
        grid * 6.8 * flicker,
      )
      .fill({ color: 0xff4a18, alpha: 0.42 })
      .rect(
        snap(head.x, grid) - grid * 2.2 * flicker,
        snap(head.y, grid) - grid * 2.2 * flicker,
        grid * 4.4 * flicker,
        grid * 4.4 * flicker,
      )
      .fill({ color: 0xffa51f, alpha: 0.96 })
      .rect(
        snap(head.x, grid) - grid * 1.25,
        snap(head.y, grid) - grid * 1.25,
        grid * 2.5,
        grid * 2.5,
      )
      .fill({ color: 0xfff1a0 })
      .rect(
        snap(head.x, grid) - grid * 0.55,
        snap(head.y, grid) - grid * 0.55,
        grid * 1.1,
        grid * 1.1,
      )
      .fill({ color: 0xffffff })
      .rect(
        snap(head.x, grid) - grid * 0.7,
        snap(head.y, grid) - grid * 4.2,
        grid * 1.4,
        grid * 2.4,
      )
      .fill({ color: 0xffcc31, alpha: 0.85 });
  }
}

function drawPixelRing(
  graphics: Graphics,
  centerX: number,
  centerY: number,
  radius: number,
  grid: number,
  color: number,
  alpha: number,
) {
  const steps = Math.max(36, Math.ceil(radius / grid) * 7);
  for (let index = 0; index < steps; index += 1) {
    const angle = (index / steps) * Math.PI * 2;
    const x = snap(centerX + Math.cos(angle) * radius, grid);
    const y = snap(centerY + Math.sin(angle) * radius * 0.82, grid);
    graphics.rect(x, y, grid, grid).fill({ color, alpha });
  }
}

function drawExplosions(
  graphics: Graphics,
  width: number,
  height: number,
  grid: number,
  elapsed: number,
  variant: PhoenixFlowerVariant,
) {
  graphics.clear();
  const minDimension = Math.min(width, height);
  const blastCount = variant === 'cast' ? 1 : SHOT_COUNT;

  for (let shotIndex = 0; shotIndex < blastCount; shotIndex += 1) {
    const blastStart =
      variant === 'cast' ? 0 : shotIndex * SHOT_GAP_MS + FLIGHT_MS;
    const localTime = elapsed - blastStart;
    if (localTime <= 0 || localTime >= 720) continue;

    const progress = localTime / 720;
    const target = { x: width * 0.5, y: height * 0.5 };
    const alpha = Math.max(0, 1 - progress);
    const radius =
      minDimension *
      (0.035 + progress * (variant === 'cast' ? 0.15 : 0.2));

    drawPixelRing(
      graphics,
      target.x,
      target.y,
      radius,
      grid,
      shotIndex % 2 === 0 ? 0xff4b21 : 0xffa51f,
      alpha * 0.82,
    );
    drawPixelRing(
      graphics,
      target.x,
      target.y,
      radius * 0.62,
      grid,
      0xffdf52,
      alpha,
    );

    for (let rayIndex = 0; rayIndex < 12; rayIndex += 1) {
      const angle = (rayIndex / 12) * Math.PI * 2 + shotIndex * 0.35;
      const distance = radius * (0.42 + (rayIndex % 3) * 0.18);
      const x = snap(target.x + Math.cos(angle) * distance, grid);
      const y = snap(target.y + Math.sin(angle) * distance * 0.82, grid);
      const size = grid * (rayIndex % 4 === 0 ? 2 : 1);
      graphics.rect(x, y, size, size).fill({
        color: rayIndex % 3 === 0 ? 0xffffff : 0xff7a20,
        alpha,
      });
    }
  }
}

function createEmbers(
  layer: Container,
  width: number,
  height: number,
  grid: number,
  variant: PhoenixFlowerVariant,
): Ember[] {
  const palette = [
    0xffffff,
    0xffffc2,
    0xffdf52,
    0xff9b21,
    0xff4d20,
    0xe42a18,
  ];
  const embers: Ember[] = [];

  for (let shotIndex = 0; shotIndex < SHOT_COUNT; shotIndex += 1) {
    const target = { x: width * 0.5, y: height * 0.5 };
    for (let index = 0; index < EMBERS_PER_SHOT; index += 1) {
      const size = grid * randomBetween(0.9, 2);
      const view = new Graphics()
        .rect(-size / 2, -size / 2, size, size)
        .fill({ color: palette[index % palette.length] });
      view.visible = false;
      view.blendMode = 'add';
      layer.addChild(view);

      const angle = randomBetween(0, Math.PI * 2);
      const speed = randomBetween(120, 480);
      const maxLife = randomBetween(430, 880);
      embers.push({
        view,
        startAt:
          variant === 'cast'
            ? shotIndex * SHOT_GAP_MS
            : shotIndex * SHOT_GAP_MS + FLIGHT_MS,
        x: target.x,
        y: target.y,
        vx: Math.cos(angle) * speed,
        vy: Math.sin(angle) * speed - randomBetween(40, 180),
        life: maxLife,
        maxLife,
        spin: randomBetween(-8, 8),
      });
    }
  }

  return embers;
}

function drawFlash(
  graphics: Graphics,
  width: number,
  height: number,
  grid: number,
  elapsed: number,
  variant: PhoenixFlowerVariant,
) {
  graphics.clear();
  let alpha = 0;
  const flashCount = variant === 'cast' ? 1 : SHOT_COUNT;
  for (let shotIndex = 0; shotIndex < flashCount; shotIndex += 1) {
    const flashStart =
      variant === 'cast' ? 0 : shotIndex * SHOT_GAP_MS + FLIGHT_MS;
    const localTime = elapsed - flashStart;
    if (localTime >= 0 && localTime < 90) {
      alpha = Math.max(alpha, 0.24 * (1 - localTime / 90));
    }
  }
  if (alpha <= 0) return;

  for (let y = 0; y < height; y += grid * 3) {
    graphics
      .rect(0, y, width, grid)
      .fill({ color: 0xff6b24, alpha });
  }
}

// 공격자는 중앙에서 불씨를 방출하고, 피공격자는 같은 불씨가 중앙으로 모여 피격된다.
export function PixelPhoenixFlowerEffect({
  variant,
}: {
  variant: PhoenixFlowerVariant;
}) {
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
      const projectileLayer = new Graphics();
      const explosionLayer = new Graphics();
      const emberLayer = new Container();
      projectileLayer.blendMode = 'add';
      explosionLayer.blendMode = 'add';
      nextApp.stage.addChild(flash, projectileLayer, explosionLayer, emberLayer);

      let width = nextApp.screen.width;
      let height = nextApp.screen.height;
      let grid = Math.max(5, Math.min(11, Math.round(Math.min(width, height) / 82)));
      let embers = createEmbers(emberLayer, width, height, grid, variant);
      let elapsed = 0;

      const rebuild = () => {
        grid = Math.max(5, Math.min(11, Math.round(Math.min(width, height) / 82)));
        emberLayer.removeChildren().forEach((child) => child.destroy());
        embers = createEmbers(emberLayer, width, height, grid, variant);
      };

      const update = (time: { deltaMS: number }) => {
        elapsed += time.deltaMS;
        if (width !== nextApp.screen.width || height !== nextApp.screen.height) {
          width = nextApp.screen.width;
          height = nextApp.screen.height;
          rebuild();
        }

        const progress = Math.min(1, elapsed / DURATION_MS);
        drawFlash(flash, width, height, grid, elapsed, variant);
        drawProjectiles(
          projectileLayer,
          width,
          height,
          grid,
          elapsed,
          variant,
        );
        drawExplosions(
          explosionLayer,
          width,
          height,
          grid,
          elapsed,
          variant,
        );

        const deltaSeconds = time.deltaMS / 1000;
        for (const ember of embers) {
          if (elapsed < ember.startAt || ember.life <= 0) continue;
          ember.view.visible = true;
          ember.life -= time.deltaMS;
          ember.x += ember.vx * deltaSeconds;
          ember.y += ember.vy * deltaSeconds;
          ember.vy += 410 * deltaSeconds;
          ember.view.position.set(snap(ember.x, grid), snap(ember.y, grid));
          ember.view.rotation += ember.spin * deltaSeconds;
          ember.view.alpha = Math.max(0, ember.life / ember.maxLife);
        }

        const shakeLife = Math.max(0, 1 - (elapsed - FLIGHT_MS) / 720);
        if (elapsed >= FLIGHT_MS) {
          nextApp.stage.position.set(
            snap(randomBetween(-grid, grid) * shakeLife, grid),
            snap(randomBetween(-grid, grid) * shakeLife, grid),
          );
        }
        nextApp.stage.alpha =
          progress < 0.8 ? 1 : Math.max(0, (1 - progress) / 0.2);

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
  }, [variant]);

  return (
    <div
      ref={hostRef}
      className="ninja-effect-overlay ninja-effect-overlay--pixel"
      aria-hidden
    />
  );
}
