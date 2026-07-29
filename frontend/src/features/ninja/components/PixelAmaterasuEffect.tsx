import { useEffect, useRef } from 'react';
import { Application, Graphics } from 'pixi.js';

const DURATION_MS = 2500;

function snap(value: number, grid: number) {
  return Math.round(value / grid) * grid;
}

function easeOutCubic(value: number) {
  return 1 - Math.pow(1 - value, 3);
}

function drawPixelAlmond(
  graphics: Graphics,
  centerX: number,
  centerY: number,
  radiusX: number,
  radiusY: number,
  grid: number,
  color: number,
  alpha: number,
) {
  if (radiusY < grid) return;
  for (let x = -radiusX; x <= radiusX; x += grid) {
    const normalizedX = Math.max(-1, Math.min(1, x / radiusX));
    const yLimit =
      radiusY * Math.sin(((normalizedX + 1) * Math.PI) / 2);
    for (let y = -yLimit; y <= yLimit; y += grid) {
      graphics
        .rect(
          snap(centerX + x, grid),
          snap(centerY + y, grid),
          grid,
          grid,
        )
        .fill({ color, alpha });
    }
  }
}

function drawPixelCircle(
  graphics: Graphics,
  centerX: number,
  centerY: number,
  radius: number,
  grid: number,
  color: number,
  alpha: number,
) {
  for (let y = -radius; y <= radius; y += grid) {
    for (let x = -radius; x <= radius; x += grid) {
      if (x * x + y * y > radius * radius) continue;
      graphics
        .rect(
          snap(centerX + x, grid),
          snap(centerY + y, grid),
          grid,
          grid,
        )
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
  width = 1,
) {
  const distance = Math.hypot(endX - startX, endY - startY);
  const steps = Math.max(1, Math.ceil(distance / grid));
  for (let index = 0; index <= steps; index += 1) {
    const progress = index / steps;
    graphics
      .rect(
        snap(startX + (endX - startX) * progress, grid),
        snap(startY + (endY - startY) * progress, grid),
        grid * width,
        grid * width,
      )
      .fill({ color, alpha });
  }
}

function drawRotatedPetal(
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
  for (let localY = -width; localY <= width; localY += grid) {
    for (let localX = -length; localX <= length; localX += grid) {
      const normalized =
        (localX * localX) / (length * length) +
        (localY * localY) / (width * width);
      if (normalized > 1) continue;
      const taper = 0.72 + 0.28 * (1 - Math.abs(localX / length));
      if (Math.abs(localY) > width * taper) continue;
      const x = centerX + localX * cos - localY * sin;
      const y = centerY + localX * sin + localY * cos;
      graphics
        .rect(snap(x, grid), snap(y, grid), grid, grid)
        .fill({ color, alpha });
    }
  }
}

function drawEye(
  eye: Graphics,
  sigil: Graphics,
  aura: Graphics,
  width: number,
  height: number,
  grid: number,
  elapsed: number,
) {
  eye.clear();
  sigil.clear();
  aura.clear();

  const centerX = width * 0.5;
  const centerY = height * 0.49;
  const minDimension = Math.min(width, height);
  const opening = easeOutCubic(Math.min(1, elapsed / 440));
  const focus = Math.min(1, Math.max(0, (elapsed - 260) / 520));
  const fade =
    elapsed < 2140 ? 1 : Math.max(0, (DURATION_MS - elapsed) / 360);
  const pulse = 1 + Math.sin(elapsed * 0.015) * 0.035;
  const radiusX = minDimension * 0.37;
  const radiusY = minDimension * 0.19 * opening;
  const irisRadius = minDimension * 0.122 * focus * pulse;

  aura
    .ellipse(centerX, centerY, radiusX * 1.08, radiusY * 1.52)
    .fill({ color: 0x150008, alpha: 0.58 * opening * fade })
    .ellipse(centerX, centerY, irisRadius * 1.5, irisRadius * 1.5)
    .fill({ color: 0xe31335, alpha: 0.17 * focus * fade });

  drawPixelAlmond(
    eye,
    centerX,
    centerY,
    radiusX + grid * 3,
    radiusY + grid * 3,
    grid,
    0x020207,
    fade,
  );
  drawPixelAlmond(
    eye,
    centerX,
    centerY,
    radiusX,
    radiusY,
    grid,
    0xe8e5e5,
    fade,
  );
  drawPixelAlmond(
    eye,
    centerX,
    centerY + grid,
    radiusX * 0.97,
    radiusY * 0.9,
    grid,
    0xfaf7f4,
    fade,
  );

  if (focus <= 0) return;

  const veinAlpha = Math.max(0, Math.min(0.34, focus * 0.34)) * fade;
  for (let index = 0; index < 18; index += 1) {
    const angle = (index / 18) * Math.PI * 2;
    const startRadius = irisRadius * 1.18;
    const endRadiusX = radiusX * (0.73 + (index % 3) * 0.06);
    const endRadiusY = radiusY * (0.55 + (index % 4) * 0.08);
    const startX = centerX + Math.cos(angle) * startRadius;
    const startY = centerY + Math.sin(angle) * startRadius;
    const middleX =
      centerX + Math.cos(angle + (index % 2 === 0 ? 0.08 : -0.08)) * endRadiusX * 0.7;
    const middleY =
      centerY + Math.sin(angle) * endRadiusY * 0.7;
    const endX = centerX + Math.cos(angle) * endRadiusX;
    const endY = centerY + Math.sin(angle) * endRadiusY;
    drawPixelLine(
      eye,
      startX,
      startY,
      middleX,
      middleY,
      grid,
      0x8c7a82,
      veinAlpha,
    );
    drawPixelLine(
      eye,
      middleX,
      middleY,
      endX,
      endY,
      grid,
      0x8c7a82,
      veinAlpha * 0.78,
    );
  }

  drawPixelCircle(
    sigil,
    centerX,
    centerY,
    irisRadius * 1.13,
    grid,
    0x08090d,
    fade,
  );

  const rotation =
    -0.18 + Math.min(1, Math.max(0, (elapsed - 550) / 850)) * 0.52;
  const petalLength = irisRadius * 0.63;
  const petalWidth = irisRadius * 0.27;
  for (let index = 0; index < 6; index += 1) {
    const angle = rotation + (index / 6) * Math.PI * 2;
    const petalCenterX =
      centerX + Math.cos(angle) * petalLength * 0.56;
    const petalCenterY =
      centerY + Math.sin(angle) * petalLength * 0.56;
    drawRotatedPetal(
      sigil,
      petalCenterX,
      petalCenterY,
      angle,
      petalLength,
      petalWidth,
      grid,
      index % 2 === 0 ? 0xd51e3f : 0xb91434,
      fade,
    );
  }

  for (let index = 0; index < 6; index += 1) {
    const angle = rotation + (index / 6) * Math.PI * 2;
    const nextAngle = rotation + ((index + 2) / 6) * Math.PI * 2;
    drawPixelLine(
      sigil,
      centerX + Math.cos(angle) * irisRadius * 0.76,
      centerY + Math.sin(angle) * irisRadius * 0.76,
      centerX + Math.cos(nextAngle) * irisRadius * 0.76,
      centerY + Math.sin(nextAngle) * irisRadius * 0.76,
      grid,
      0x650a20,
      0.62 * fade,
    );
  }

  drawPixelCircle(
    sigil,
    centerX,
    centerY,
    irisRadius * 0.16,
    grid,
    0x07060a,
    fade,
  );

  const activation = Math.max(0, Math.min(1, (elapsed - 1180) / 500));
  if (activation > 0) {
    const ringRadius = irisRadius * (1.15 + activation * 1.7);
    const steps = 72;
    for (let index = 0; index < steps; index += 1) {
      if (index % 5 > 2) continue;
      const angle = (index / steps) * Math.PI * 2 + elapsed * 0.001;
      aura
        .rect(
          snap(centerX + Math.cos(angle) * ringRadius, grid),
          snap(centerY + Math.sin(angle) * ringRadius, grid),
          grid,
          grid,
        )
        .fill({
          color: index % 2 === 0 ? 0xe62645 : 0x25000b,
          alpha: (1 - activation) * 0.78 * fade,
        });
    }
  }
}

export function PixelAmaterasuEffect() {
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

      const aura = new Graphics();
      const eye = new Graphics();
      const sigil = new Graphics();
      aura.blendMode = 'add';
      nextApp.stage.addChild(aura, eye, sigil);

      let width = nextApp.screen.width;
      let height = nextApp.screen.height;
      let grid = Math.max(5, Math.min(10, Math.round(Math.min(width, height) / 92)));
      let elapsed = 0;

      const update = (time: { deltaMS: number }) => {
        elapsed += time.deltaMS;
        if (width !== nextApp.screen.width || height !== nextApp.screen.height) {
          width = nextApp.screen.width;
          height = nextApp.screen.height;
          grid = Math.max(5, Math.min(10, Math.round(Math.min(width, height) / 92)));
        }

        drawEye(eye, sigil, aura, width, height, grid, elapsed);
        nextApp.stage.alpha =
          elapsed < 2200 ? 1 : Math.max(0, (DURATION_MS - elapsed) / 300);
        if (elapsed >= DURATION_MS) {
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
