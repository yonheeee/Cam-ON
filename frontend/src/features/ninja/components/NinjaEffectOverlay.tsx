import { useEffect, useRef } from 'react';
import { Application, Container, Graphics } from 'pixi.js';
import type { EffectDto } from '../api/ninjaApi';

const EFFECT_DURATION_MS = 1950;
const STRIKE_AT_MS = 150;
const BOLT_END_MS = 1080;
const BOLT_REFRESH_MS = 48;
const SPARK_COUNT = 96;
const SPEED_LINE_COUNT = 34;

interface Point {
  x: number;
  y: number;
}

interface Spark {
  view: Graphics;
  x: number;
  y: number;
  vx: number;
  vy: number;
  rotationSpeed: number;
  life: number;
  maxLife: number;
}

interface SpeedLine {
  view: Graphics;
  delay: number;
  duration: number;
}

function randomBetween(min: number, max: number) {
  return min + Math.random() * (max - min);
}

function createBoltPoints(
  width: number,
  height: number,
  startXRatio: number,
  endXRatio: number,
  spread: number,
): Point[] {
  const points: Point[] = [];
  const segments = 15;

  for (let index = 0; index <= segments; index += 1) {
    const progress = index / segments;
    const baseX = width * (startXRatio + (endXRatio - startXRatio) * progress);
    const edgeFactor = Math.sin(progress * Math.PI);
    points.push({
      x: baseX + (Math.random() - 0.5) * width * spread * edgeFactor,
      y: height * (0.015 + progress * 0.79),
    });
  }
  return points;
}

function tracePath(graphics: Graphics, points: Point[]) {
  graphics.moveTo(points[0].x, points[0].y);
  for (const point of points.slice(1)) graphics.lineTo(point.x, point.y);
}

function strokeLayeredBolt(
  graphics: Graphics,
  points: Point[],
  glowColor: number,
  coreColor: number,
  scale = 1,
) {
  tracePath(graphics, points);
  graphics.stroke({ color: glowColor, width: 34 * scale, alpha: 0.08 });
  tracePath(graphics, points);
  graphics.stroke({ color: glowColor, width: 18 * scale, alpha: 0.2 });
  tracePath(graphics, points);
  graphics.stroke({ color: glowColor, width: 8 * scale, alpha: 0.72 });
  tracePath(graphics, points);
  graphics.stroke({ color: coreColor, width: 2.8 * scale, alpha: 1 });
}

function drawBranches(
  graphics: Graphics,
  points: Point[],
  width: number,
  height: number,
  color: number,
) {
  for (let index = 2; index < points.length - 2; index += 2) {
    const origin = points[index];
    const direction = index % 4 === 0 ? 1 : -1;
    const length = randomBetween(0.045, 0.11);
    graphics
      .moveTo(origin.x, origin.y)
      .lineTo(
        origin.x + direction * width * length * 0.55,
        origin.y + height * randomBetween(0.025, 0.052),
      )
      .lineTo(
        origin.x + direction * width * length,
        origin.y + height * randomBetween(0.065, 0.12),
      );
    graphics.stroke({ color, width: randomBetween(1.2, 2.6), alpha: randomBetween(0.48, 0.9) });
  }
}

function drawLightning(graphics: Graphics, width: number, height: number) {
  graphics.clear();

  const leftGhost = createBoltPoints(width, height, 0.31, 0.43, 0.13);
  const rightGhost = createBoltPoints(width, height, 0.72, 0.47, 0.14);
  const mainBolt = createBoltPoints(width, height, 0.58, 0.45, 0.18);

  strokeLayeredBolt(graphics, leftGhost, 0x735cff, 0xc8c0ff, 0.48);
  strokeLayeredBolt(graphics, rightGhost, 0x3fdcff, 0xdafcff, 0.58);
  strokeLayeredBolt(graphics, mainBolt, 0x20cfff, 0xffffff, 1);

  drawBranches(graphics, leftGhost, width, height, 0x917fff);
  drawBranches(graphics, rightGhost, width, height, 0x75efff);
  drawBranches(graphics, mainBolt, width, height, 0xc7f8ff);
}

function createSparks(layer: Container, width: number, height: number): Spark[] {
  const centerX = width * 0.45;
  const centerY = height * 0.8;

  return Array.from({ length: SPARK_COUNT }, (_, index) => {
    const angle = randomBetween(Math.PI * 1.05, Math.PI * 1.95);
    const speed = randomBetween(220, 920);
    const length = randomBetween(5, 18);
    const thickness = randomBetween(1.2, 3.6);
    const color = index % 5 === 0 ? 0xa882ff : index % 3 === 0 ? 0x54ddff : 0xffffff;
    const view = new Graphics()
      .roundRect(-length / 2, -thickness / 2, length, thickness, thickness / 2)
      .fill({ color, alpha: 0.95 })
      .circle(0, 0, thickness * 2.4)
      .fill({ color, alpha: 0.16 });
    view.blendMode = 'add';
    layer.addChild(view);

    const maxLife = randomBetween(360, 1050);
    return {
      view,
      x: centerX + randomBetween(-26, 26),
      y: centerY + randomBetween(-16, 16),
      vx: Math.cos(angle) * speed,
      vy: Math.sin(angle) * speed - randomBetween(40, 230),
      rotationSpeed: randomBetween(-8, 8),
      life: maxLife,
      maxLife,
    };
  });
}

function createSpeedLines(layer: Container, width: number, height: number): SpeedLine[] {
  const centerX = width * 0.45;
  const centerY = height * 0.8;
  const diagonal = Math.hypot(width, height);

  return Array.from({ length: SPEED_LINE_COUNT }, (_, index) => {
    const angle = (Math.PI * 2 * index) / SPEED_LINE_COUNT + randomBetween(-0.08, 0.08);
    const inner = randomBetween(diagonal * 0.09, diagonal * 0.19);
    const outer = inner + randomBetween(diagonal * 0.07, diagonal * 0.2);
    const view = new Graphics()
      .moveTo(centerX + Math.cos(angle) * inner, centerY + Math.sin(angle) * inner)
      .lineTo(centerX + Math.cos(angle) * outer, centerY + Math.sin(angle) * outer)
      .stroke({
        color: index % 4 === 0 ? 0x9c7dff : 0xa9f5ff,
        width: randomBetween(1, 4),
        alpha: randomBetween(0.18, 0.68),
      });
    view.blendMode = 'add';
    view.alpha = 0;
    layer.addChild(view);
    return {
      view,
      delay: randomBetween(0, 180),
      duration: randomBetween(180, 430),
    };
  });
}

function drawImpact(
  graphics: Graphics,
  width: number,
  height: number,
  elapsedAfterStrike: number,
) {
  graphics.clear();
  const centerX = width * 0.45;
  const centerY = height * 0.8;
  const minDimension = Math.min(width, height);

  const coreLife = Math.max(0, 1 - elapsedAfterStrike / 780);
  if (coreLife > 0) {
    const pulse = 1 + Math.sin(elapsedAfterStrike * 0.045) * 0.18;
    graphics
      .circle(centerX, centerY, minDimension * 0.105 * pulse)
      .fill({ color: 0x24cfff, alpha: coreLife * 0.08 })
      .circle(centerX, centerY, minDimension * 0.054 * pulse)
      .fill({ color: 0x9f85ff, alpha: coreLife * 0.2 })
      .circle(centerX, centerY, minDimension * 0.021 * pulse)
      .fill({ color: 0xffffff, alpha: coreLife * 0.92 });
  }

  const ringDelays = [0, 100, 220];
  for (let index = 0; index < ringDelays.length; index += 1) {
    const ringProgress = Math.min(
      1,
      Math.max(0, (elapsedAfterStrike - ringDelays[index]) / (640 + index * 90)),
    );
    if (ringProgress <= 0 || ringProgress >= 1) continue;
    const radius = minDimension * (0.04 + ringProgress * (0.54 + index * 0.08));
    graphics
      .ellipse(centerX, centerY, radius, radius * 0.34)
      .stroke({
        color: index === 1 ? 0x957aff : 0x68eaff,
        width: Math.max(1, 7 - ringProgress * 5),
        alpha: (1 - ringProgress) * 0.84,
      });
  }

  const crossProgress = Math.min(1, elapsedAfterStrike / 520);
  if (crossProgress < 1) {
    const rayAlpha = (1 - crossProgress) * 0.82;
    const rayLength = minDimension * (0.16 + crossProgress * 0.55);
    graphics
      .moveTo(centerX - rayLength, centerY)
      .lineTo(centerX + rayLength, centerY)
      .moveTo(centerX, centerY - rayLength * 0.72)
      .lineTo(centerX, centerY + rayLength * 0.72)
      .stroke({ color: 0xe8fdff, width: 2.4, alpha: rayAlpha });
  }
}

// 첫 번째 PixiJS 스킬 이펙트: 번개.
// 다중 낙뢰, 충돌 코어, 충격파, 방사형 속도선, 스파크와 화면 흔들림을 1.95초간 재생한다.
export function NinjaEffectOverlay({ effect: _effect }: { effect?: EffectDto } = {}) {
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
        antialias: true,
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
      const speedLineLayer = new Container();
      const bolt = new Graphics();
      const impact = new Graphics();
      const sparkLayer = new Container();
      bolt.blendMode = 'add';
      impact.blendMode = 'add';
      nextApp.stage.addChild(flash, speedLineLayer, bolt, impact, sparkLayer);

      let width = nextApp.screen.width;
      let height = nextApp.screen.height;
      let sparks = createSparks(sparkLayer, width, height);
      let speedLines = createSpeedLines(speedLineLayer, width, height);
      let elapsed = 0;
      let boltElapsed = BOLT_REFRESH_MS;

      const rebuildResponsiveLayers = () => {
        sparkLayer.removeChildren().forEach((child) => child.destroy());
        speedLineLayer.removeChildren().forEach((child) => child.destroy());
        sparks = createSparks(sparkLayer, width, height);
        speedLines = createSpeedLines(speedLineLayer, width, height);
        boltElapsed = BOLT_REFRESH_MS;
      };

      const update = (time: { deltaMS: number }) => {
        elapsed += time.deltaMS;
        boltElapsed += time.deltaMS;

        if (width !== nextApp.screen.width || height !== nextApp.screen.height) {
          width = nextApp.screen.width;
          height = nextApp.screen.height;
          rebuildResponsiveLayers();
        }

        const progress = Math.min(elapsed / EFFECT_DURATION_MS, 1);
        const afterStrike = Math.max(0, elapsed - STRIKE_AT_MS);
        const firstFlash = elapsed < 85 ? 0.48 * (1 - elapsed / 85) : 0;
        const impactFlash =
          afterStrike < 120 ? 0.72 * (1 - afterStrike / 120) : Math.max(0, 0.09 * (1 - progress));
        flash
          .clear()
          .rect(0, 0, width, height)
          .fill({
            color: elapsed < STRIKE_AT_MS ? 0x5d4bca : 0xc5f8ff,
            alpha: Math.max(firstFlash, impactFlash),
          });

        if (elapsed >= STRIKE_AT_MS && elapsed < BOLT_END_MS && boltElapsed >= BOLT_REFRESH_MS) {
          drawLightning(bolt, width, height);
          boltElapsed = 0;
        }
        bolt.alpha =
          elapsed < STRIKE_AT_MS
            ? 0
            : elapsed < 760
              ? 1
              : Math.max(0, (BOLT_END_MS - elapsed) / (BOLT_END_MS - 760));

        drawImpact(impact, width, height, afterStrike);

        for (const line of speedLines) {
          const localProgress = (afterStrike - line.delay) / line.duration;
          line.view.alpha =
            localProgress > 0 && localProgress < 1
              ? Math.sin(localProgress * Math.PI) * 0.9
              : 0;
          line.view.scale.set(0.76 + Math.max(0, localProgress) * 0.55);
        }

        if (afterStrike > 0) {
          const deltaSeconds = time.deltaMS / 1000;
          for (const spark of sparks) {
            spark.life -= time.deltaMS;
            if (spark.life <= 0) {
              spark.view.visible = false;
              continue;
            }
            spark.x += spark.vx * deltaSeconds;
            spark.y += spark.vy * deltaSeconds;
            spark.vy += 760 * deltaSeconds;
            spark.view.position.set(spark.x, spark.y);
            spark.view.rotation += spark.rotationSpeed * deltaSeconds;
            spark.view.alpha = Math.max(0, spark.life / spark.maxLife);
            spark.view.scale.set(0.5 + spark.view.alpha * 0.9);
          }
        } else {
          sparkLayer.alpha = 0;
        }
        if (afterStrike > 0) sparkLayer.alpha = 1;

        const shakeLife = Math.max(0, 1 - afterStrike / 520);
        nextApp.stage.position.set(
          randomBetween(-9, 9) * shakeLife,
          randomBetween(-6, 6) * shakeLife,
        );

        nextApp.stage.alpha =
          progress < 0.72 ? 1 : Math.max(0, (1 - progress) / 0.28);
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

  return <div ref={hostRef} className="ninja-effect-overlay" aria-hidden />;
}
