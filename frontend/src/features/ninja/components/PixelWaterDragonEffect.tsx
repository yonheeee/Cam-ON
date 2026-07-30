import { useEffect, useRef } from 'react';
import { Application, Container, Graphics } from 'pixi.js';

export const DURATION_MS = 2050;
const FLIGHT_MS = 1220;
const BODY_SEGMENTS = 54;
const DROPLET_COUNT = 88;

export type WaterDragonVariant = 'cast' | 'hit';

interface Droplet {
  view: Graphics;
  angle: number;
  radius: number;
  speed: number;
  startAt: number;
  alphaOffset: number;
}

function randomBetween(min: number, max: number) {
  return min + Math.random() * (max - min);
}

function snap(value: number, grid: number) {
  return Math.round(value / grid) * grid;
}

function getSpinePoint(
  width: number,
  height: number,
  progress: number,
  elapsed: number,
  variant: WaterDragonVariant,
) {
  const clamped = Math.max(0, Math.min(1, progress));
  const directionY = variant === 'cast' ? -1 : 1;
  const startY = variant === 'cast' ? height * 0.88 : height * 0.08;
  const verticalTravel = height * 0.76;
  const curveEnvelope = 0.22 + Math.sin(clamped * Math.PI) * 0.78;
  const wave =
    Math.sin(clamped * Math.PI * 3.1 + elapsed * 0.0018) *
    width *
    0.145 *
    curveEnvelope;

  return {
    x: width * 0.5 + wave,
    y: startY + directionY * verticalTravel * clamped,
  };
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

function drawWhisker(
  graphics: Graphics,
  headX: number,
  headY: number,
  side: number,
  directionY: number,
  grid: number,
  alpha: number,
) {
  const steps = 11;
  for (let index = 0; index <= steps; index += 1) {
    const progress = index / steps;
    const loop = Math.sin(progress * Math.PI * 1.35);
    const x =
      headX +
      side * grid * (4 + progress * 5.5 + loop * 3);
    const y =
      headY +
      directionY * grid * (1.5 + progress * 6.5) -
      loop * grid * 2.5;
    const size = grid * (1.05 - progress * 0.5);
    graphics
      .rect(snap(x, grid), snap(y, grid), size, size)
      .fill({ color: 0xd7fdff, alpha: alpha * (0.66 - progress * 0.48) });
  }
}

function drawDragonHead(
  graphics: Graphics,
  headX: number,
  headY: number,
  directionY: number,
  grid: number,
  alpha: number,
) {
  graphics
    .ellipse(headX, headY, grid * 6.2, grid * 4.6)
    .fill({ color: 0x073f9f, alpha: alpha * 0.22 })
    .ellipse(headX, headY, grid * 5.1, grid * 3.8)
    .fill({ color: 0x27cfff, alpha: alpha * 0.66 })
    .ellipse(
      headX - grid * 1.1,
      headY - directionY * grid * 0.6,
      grid * 3.7,
      grid * 2.6,
    )
    .fill({ color: 0x9af6ff, alpha: alpha * 0.24 })
    .rect(headX - grid * 4.1, headY - grid * 1.2, grid * 8.2, grid * 1.5)
    .fill({ color: 0x062f79, alpha: alpha * 0.28 });

  for (let step = 0; step < 4; step += 1) {
    const width = grid * (6.1 - step * 0.9);
    const y = headY + directionY * grid * (2.5 + step * 0.88);
    graphics
      .rect(
        snap(headX - width / 2, grid),
        snap(y - grid * 0.75, grid),
        width,
        grid * 1.5,
      )
      .fill({
        color: step < 2 ? 0x73edff : 0x39c9ee,
        alpha: alpha * (0.66 - step * 0.08),
      });
  }

  graphics
    .rect(
      headX - grid * 2.5,
      headY + directionY * grid * 1.2,
      grid * 5,
      grid,
    )
    .fill({ color: 0x0a559a, alpha: alpha * 0.24 })
    .rect(
      headX - grid * 1.7,
      headY - directionY * grid * 2.9,
      grid * 3.4,
      grid,
    )
    .fill({ color: 0xd7fdff, alpha: alpha * 0.5 });

  for (let hornIndex = 0; hornIndex < 6; hornIndex += 1) {
    const hornProgress = hornIndex / 5;
    const hornSize = grid * (1.4 - hornProgress * 0.45);
    const hornY =
      headY -
      directionY * grid * (2.8 + hornProgress * 7.5) -
      Math.sin(hornProgress * Math.PI) * grid * 2.4;
    graphics
      .rect(
        snap(headX - grid * (2.8 + hornProgress * 5.8), grid),
        snap(hornY, grid),
        hornSize,
        hornSize,
      )
      .fill({
        color: hornIndex < 2 ? 0xc9fbff : 0x5adfff,
        alpha: alpha * (0.62 - hornProgress * 0.3),
      })
      .rect(
        snap(headX + grid * (2.8 + hornProgress * 5.8), grid),
        snap(hornY, grid),
        hornSize,
        hornSize,
      )
      .fill({
        color: hornIndex < 2 ? 0xc9fbff : 0x5adfff,
        alpha: alpha * (0.62 - hornProgress * 0.3),
      });
  }

  drawWhisker(graphics, headX, headY, -1, directionY, grid, alpha);
  drawWhisker(graphics, headX, headY, 1, directionY, grid, alpha);
}

function drawLongDragon(
  graphics: Graphics,
  width: number,
  height: number,
  grid: number,
  elapsed: number,
  variant: WaterDragonVariant,
) {
  graphics.clear();
  const headProgress = Math.min(1, elapsed / FLIGHT_MS);
  const visibleLength = Math.min(1, headProgress * 1.8);
  const directionY = variant === 'cast' ? -1 : 1;

  for (let segment = BODY_SEGMENTS; segment >= 0; segment -= 1) {
    const distanceBehind = (segment / BODY_SEGMENTS) * 0.66;
    const spineProgress = headProgress - distanceBehind;
    if (spineProgress < 0 || distanceBehind > visibleLength) continue;

    const point = getSpinePoint(
      width,
      height,
      spineProgress,
      elapsed,
      variant,
    );
    const bodyLife = 1 - segment / (BODY_SEGMENTS + 1);
    const taper = Math.sin(bodyLife * Math.PI * 0.72);
    const bodyRadius = grid * (1 + taper * 4.1);
    const color =
      segment < 10
        ? 0xaaf8ff
        : segment < 24
          ? 0x49dcff
          : segment < 40
            ? 0x1689ee
            : 0x1649b8;

    graphics
      .rect(
        snap(point.x, grid) - bodyRadius,
        snap(point.y, grid) - bodyRadius,
        bodyRadius * 2,
        bodyRadius * 2,
      )
      .fill({ color: 0x0b3f9d, alpha: 0.14 + taper * 0.12 })
      .rect(
        snap(point.x, grid) - bodyRadius * 0.55,
        snap(point.y, grid) - bodyRadius * 0.55,
        bodyRadius * 1.1,
        bodyRadius * 1.1,
      )
      .fill({ color, alpha: 0.54 + taper * 0.36 });

    if (segment % 3 === 0 && segment < 46) {
      const maneSide =
        Math.sin(spineProgress * Math.PI * 3.1 + elapsed * 0.0018) >= 0
          ? 1
          : -1;
      graphics
        .rect(
          snap(point.x + maneSide * bodyRadius * 0.86, grid),
          snap(point.y - directionY * grid, grid),
          grid * (1.2 + taper),
          grid * (2 + taper * 1.4),
        )
        .fill({ color: 0xc6fbff, alpha: 0.42 + taper * 0.3 });
    }

    if (segment % 5 === 0) {
      graphics
        .rect(
          snap(point.x - bodyRadius * 0.3, grid),
          snap(point.y, grid),
          grid * 1.2,
          grid * 1.2,
        )
        .fill({ color: 0xffffff, alpha: 0.22 + taper * 0.3 });
    }
  }

  const head = getSpinePoint(
    width,
    height,
    headProgress,
    elapsed,
    variant,
  );
  drawDragonHead(
    graphics,
    snap(head.x, grid),
    snap(head.y, grid),
    directionY,
    grid,
    Math.min(1, elapsed / 180),
  );
}

function drawWaterRings(
  graphics: Graphics,
  width: number,
  height: number,
  grid: number,
  elapsed: number,
  variant: WaterDragonVariant,
) {
  graphics.clear();
  const eventTime = variant === 'cast' ? elapsed : elapsed - FLIGHT_MS;
  if (eventTime < 0 || eventTime > 850) return;

  const progress = eventTime / 850;
  const alpha = Math.max(0, 1 - progress);
  const centerX = width * 0.5;
  const centerY = height * (variant === 'cast' ? 0.88 : 0.84);
  const minDimension = Math.min(width, height);

  for (let index = 0; index < 4; index += 1) {
    const radius =
      minDimension * (0.035 + progress * (0.22 + index * 0.035));
    drawPixelEllipseRing(
      graphics,
      centerX,
      centerY,
      radius,
      radius * 0.28,
      grid,
      index % 2 === 0 ? 0x64eaff : 0x176fe0,
      alpha * (0.78 - index * 0.12),
    );
  }

  if (variant === 'hit') {
    for (let jet = 0; jet < 22; jet += 1) {
      const angle = (jet / 22) * Math.PI;
      const distance = minDimension * progress * (0.12 + (jet % 5) * 0.04);
      const x = centerX + Math.cos(angle) * distance;
      const y =
        centerY -
        Math.sin(angle) * distance * 1.45 +
        progress * progress * minDimension * 0.08;
      const size = grid * (jet % 4 === 0 ? 2.3 : 1.2);
      graphics
        .rect(snap(x, grid), snap(y, grid), size, size * 1.5)
        .fill({
          color: jet % 3 === 0 ? 0xffffff : 0x4bdeff,
          alpha,
        });
    }
  }
}

function createDroplets(layer: Container, grid: number): Droplet[] {
  const palette = [0xffffff, 0xbdfaff, 0x4de4ff, 0x168eff, 0x174fc6];
  return Array.from({ length: DROPLET_COUNT }, (_, index) => {
    const size = grid * randomBetween(0.65, 1.65);
    const view = new Graphics()
      .rect(-size / 2, -size, size, size * 2)
      .fill({ color: palette[index % palette.length] });
    view.blendMode = 'add';
    layer.addChild(view);
    return {
      view,
      angle: randomBetween(0, Math.PI * 2),
      radius: randomBetween(grid * 2, grid * 18),
      speed: randomBetween(0.8, 2.6),
      startAt: randomBetween(0, 520),
      alphaOffset: randomBetween(0, Math.PI * 2),
    };
  });
}

function updateDroplets(
  droplets: Droplet[],
  width: number,
  height: number,
  grid: number,
  elapsed: number,
  variant: WaterDragonVariant,
) {
  const progress = Math.min(1, elapsed / FLIGHT_MS);
  const head = getSpinePoint(width, height, progress, elapsed, variant);
  for (const droplet of droplets) {
    if (elapsed < droplet.startAt) {
      droplet.view.visible = false;
      continue;
    }
    droplet.view.visible = true;
    const orbit = droplet.angle + elapsed * 0.001 * droplet.speed;
    const spread = droplet.radius * (0.65 + progress * 1.4);
    droplet.view.position.set(
      snap(head.x + Math.cos(orbit) * spread, grid),
      snap(head.y + Math.sin(orbit) * spread * 0.7, grid),
    );
    droplet.view.rotation = orbit;
    droplet.view.alpha =
      0.32 + Math.sin(elapsed * 0.012 + droplet.alphaOffset) * 0.24;
  }
}

function drawFlash(
  graphics: Graphics,
  width: number,
  height: number,
  grid: number,
  elapsed: number,
  variant: WaterDragonVariant,
) {
  graphics.clear();
  const flashStart = variant === 'cast' ? 0 : FLIGHT_MS;
  const localTime = elapsed - flashStart;
  if (localTime < 0 || localTime >= 120) return;

  const alpha = 0.3 * (1 - localTime / 120);
  for (let x = 0; x < width; x += grid * 3) {
    graphics
      .rect(x, 0, grid, height)
      .fill({ color: 0x72eaff, alpha });
  }
}

export function PixelWaterDragonEffect({
  variant,
}: {
  variant: WaterDragonVariant;
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
      const rings = new Graphics();
      const dragon = new Graphics();
      const dropletLayer = new Container();
      rings.blendMode = 'add';
      dragon.blendMode = 'normal';
      nextApp.stage.addChild(flash, rings, dragon, dropletLayer);

      let width = nextApp.screen.width;
      let height = nextApp.screen.height;
      let grid = Math.max(5, Math.min(11, Math.round(Math.min(width, height) / 82)));
      let droplets = createDroplets(dropletLayer, grid);
      let elapsed = 0;

      const rebuild = () => {
        grid = Math.max(5, Math.min(11, Math.round(Math.min(width, height) / 82)));
        dropletLayer.removeChildren().forEach((child) => child.destroy());
        droplets = createDroplets(dropletLayer, grid);
      };

      const update = (time: { deltaMS: number }) => {
        elapsed += time.deltaMS;
        if (width !== nextApp.screen.width || height !== nextApp.screen.height) {
          width = nextApp.screen.width;
          height = nextApp.screen.height;
          rebuild();
        }

        drawFlash(flash, width, height, grid, elapsed, variant);
        drawWaterRings(rings, width, height, grid, elapsed, variant);
        drawLongDragon(dragon, width, height, grid, elapsed, variant);
        updateDroplets(
          droplets,
          width,
          height,
          grid,
          elapsed,
          variant,
        );

        const impactLife =
          variant === 'hit'
            ? Math.max(0, 1 - (elapsed - FLIGHT_MS) / 620)
            : 0;
        if (elapsed >= FLIGHT_MS && variant === 'hit') {
          nextApp.stage.position.set(
            snap(randomBetween(-grid, grid) * impactLife, grid),
            snap(randomBetween(-grid, grid) * impactLife, grid),
          );
        }

        const fadeStart = variant === 'cast' ? 1320 : 1600;
        nextApp.stage.alpha =
          elapsed < fadeStart
            ? 1
            : Math.max(0, (DURATION_MS - elapsed) / (DURATION_MS - fadeStart));

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
  }, [variant]);

  return (
    <div
      ref={hostRef}
      className="ninja-effect-overlay ninja-effect-overlay--pixel"
      aria-hidden
    />
  );
}
