import { useEffect, useRef } from 'react';
import { Application, Container, Graphics } from 'pixi.js';

const DURATION_MS = 2400;
const BLOOM_AT_MS = 180;
const PETAL_COUNT = 110;

interface PetalParticle {
  view: Graphics;
  startAt: number;
  x: number;
  y: number;
  vx: number;
  vy: number;
  life: number;
  maxLife: number;
  spin: number;
  sway: number;
}

function randomBetween(min: number, max: number) {
  return min + Math.random() * (max - min);
}

function snap(value: number, grid: number) {
  return Math.round(value / grid) * grid;
}

function drawPixelPetal(
  graphics: Graphics,
  centerX: number,
  centerY: number,
  angle: number,
  length: number,
  width: number,
  grid: number,
  color: number,
  alpha: number,
) {
  const cos = Math.cos(angle);
  const sin = Math.sin(angle);
  for (let localY = -length; localY <= length; localY += grid) {
    for (let localX = -width; localX <= width; localX += grid) {
      const normalized =
        (localX * localX) / (width * width) +
        (localY * localY) / (length * length);
      if (normalized > 1) continue;
      const taper = 1 - Math.abs(localY / length) * 0.35;
      if (Math.abs(localX) > width * taper) continue;
      const x = centerX + localX * cos - localY * sin;
      const y = centerY + localX * sin + localY * cos;
      graphics
        .rect(snap(x, grid), snap(y, grid), grid, grid)
        .fill({ color, alpha });
    }
  }
}

function drawFlowerSigil(
  graphics: Graphics,
  width: number,
  height: number,
  grid: number,
  elapsed: number,
) {
  graphics.clear();
  const localTime = elapsed - BLOOM_AT_MS;
  if (localTime < 0 || localTime > 1500) return;

  const progress = localTime / 1500;
  const entrance = Math.min(1, localTime / 320);
  const fade = Math.max(0, 1 - Math.max(0, progress - 0.72) / 0.28);
  const pulse = 0.3 + entrance * 0.78 + Math.sin(localTime * 0.012) * 0.05;
  const minDimension = Math.min(width, height);
  const centerX = width * 0.5;
  const centerY = height * 0.5;
  const rotation = localTime * 0.00065;
  const petalLength = minDimension * 0.115 * pulse;
  const petalWidth = minDimension * 0.064 * pulse;

  for (let index = 0; index < 5; index += 1) {
    const angle = rotation + (index / 5) * Math.PI * 2;
    const petalCenterX = centerX + Math.cos(angle) * petalLength * 1.12;
    const petalCenterY = centerY + Math.sin(angle) * petalLength * 1.12;
    drawPixelPetal(
      graphics,
      petalCenterX,
      petalCenterY,
      angle + Math.PI / 2,
      petalLength,
      petalWidth,
      grid,
      index % 2 === 0 ? 0xff74bd : 0xf29ada,
      fade * 0.76,
    );
  }

  const centerSize = grid * (1.35 + pulse * 1.35);
  graphics
    .rect(
      snap(centerX, grid) - centerSize / 2,
      snap(centerY, grid) - centerSize / 2,
      centerSize,
      centerSize,
    )
    .fill({ color: 0xffffff, alpha: fade * 0.92 });
}

function drawBloomRings(
  graphics: Graphics,
  width: number,
  height: number,
  grid: number,
  elapsed: number,
) {
  graphics.clear();
  const localTime = elapsed - BLOOM_AT_MS;
  if (localTime < 0 || localTime > 1100) return;
  const progress = localTime / 1100;
  const alpha = Math.max(0, 1 - progress);
  const centerX = width * 0.5;
  const centerY = height * 0.5;
  const minDimension = Math.min(width, height);

  for (let ringIndex = 0; ringIndex < 3; ringIndex += 1) {
    const radius =
      minDimension * (0.04 + progress * (0.36 + ringIndex * 0.08));
    const steps = Math.max(40, Math.ceil(radius / grid) * 8);
    for (let index = 0; index < steps; index += 1) {
      const angle = (index / steps) * Math.PI * 2;
      const x = snap(centerX + Math.cos(angle) * radius, grid);
      const y = snap(centerY + Math.sin(angle) * radius * 0.72, grid);
      graphics
        .rect(x, y, grid, grid)
        .fill({
          color: ringIndex === 1 ? 0xffffff : 0xff78c5,
          alpha: alpha * (0.65 - ringIndex * 0.12),
        });
    }
  }
}

function createPetals(
  layer: Container,
  width: number,
  height: number,
  grid: number,
): PetalParticle[] {
  const centerX = width * 0.5;
  const centerY = height * 0.5;
  const palette = [0xffffff, 0xffc7e7, 0xff8fce, 0xe78cff, 0xff5aaa];

  return Array.from({ length: PETAL_COUNT }, (_, index) => {
    const size = grid * randomBetween(0.75, 1.8);
    const view = new Graphics()
      .rect(-size * 0.5, -size, size, size * 2)
      .fill({ color: palette[index % palette.length] })
      .rect(-size, -size * 0.5, size * 2, size)
      .fill({
        color: index % 3 === 0 ? 0xffffff : palette[index % palette.length],
        alpha: 0.7,
      });
    view.visible = false;
    view.blendMode = 'add';
    layer.addChild(view);

    const angle = randomBetween(0, Math.PI * 2);
    const speed = randomBetween(70, 280);
    const maxLife = randomBetween(1100, 1850);
    return {
      view,
      startAt: BLOOM_AT_MS + randomBetween(260, 800),
      x: centerX + randomBetween(-grid * 8, grid * 8),
      y: centerY + randomBetween(-grid * 6, grid * 6),
      vx: Math.cos(angle) * speed,
      vy: Math.sin(angle) * speed - randomBetween(30, 110),
      life: maxLife,
      maxLife,
      spin: randomBetween(-7, 7),
      sway: randomBetween(0, Math.PI * 2),
    };
  });
}

export function PixelCherryBlossomSlashEffect() {
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

      const rings = new Graphics();
      const flower = new Graphics();
      const petalLayer = new Container();
      rings.blendMode = 'add';
      flower.blendMode = 'normal';
      nextApp.stage.addChild(rings, flower, petalLayer);

      let width = nextApp.screen.width;
      let height = nextApp.screen.height;
      let grid = Math.max(5, Math.min(11, Math.round(Math.min(width, height) / 82)));
      let petals = createPetals(petalLayer, width, height, grid);
      let elapsed = 0;

      const rebuild = () => {
        grid = Math.max(5, Math.min(11, Math.round(Math.min(width, height) / 82)));
        petalLayer.removeChildren().forEach((child) => child.destroy());
        petals = createPetals(petalLayer, width, height, grid);
      };

      const update = (time: { deltaMS: number }) => {
        elapsed += time.deltaMS;
        if (width !== nextApp.screen.width || height !== nextApp.screen.height) {
          width = nextApp.screen.width;
          height = nextApp.screen.height;
          rebuild();
        }

        drawBloomRings(rings, width, height, grid, elapsed);
        drawFlowerSigil(flower, width, height, grid, elapsed);

        const deltaSeconds = time.deltaMS / 1000;
        for (const petal of petals) {
          if (elapsed < petal.startAt || petal.life <= 0) continue;
          petal.view.visible = true;
          petal.life -= time.deltaMS;
          petal.sway += time.deltaMS * 0.006;
          petal.x +=
            (petal.vx + Math.sin(petal.sway) * 42) * deltaSeconds;
          petal.y += petal.vy * deltaSeconds;
          petal.vy += 55 * deltaSeconds;
          petal.view.position.set(snap(petal.x, grid), snap(petal.y, grid));
          petal.view.rotation += petal.spin * deltaSeconds;
          petal.view.alpha = Math.max(0, petal.life / petal.maxLife);
        }

        nextApp.stage.alpha =
          elapsed < 2050 ? 1 : Math.max(0, (DURATION_MS - elapsed) / 350);

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
