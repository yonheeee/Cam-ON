import { useEffect, useRef } from 'react';
import { Application, Container, Graphics } from 'pixi.js';

const DURATION_MS = 2300;
const PARTICLE_COUNT = 90;

interface ChakraParticle {
  view: Graphics;
  angle: number;
  radius: number;
  speed: number;
  size: number;
  drift: number;
}

function randomBetween(min: number, max: number) {
  return min + Math.random() * (max - min);
}

function snap(value: number, grid: number) {
  return Math.round(value / grid) * grid;
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
      const distance = Math.sqrt(x * x + y * y);
      if (distance > radius) continue;
      const edge = Math.max(0, Math.min(1, (radius - distance) / (grid * 2)));
      graphics
        .rect(snap(centerX + x, grid), snap(centerY + y, grid), grid, grid)
        .fill({ color, alpha: alpha * (0.45 + edge * 0.55) });
    }
  }
}

function drawOrbit(
  graphics: Graphics,
  centerX: number,
  centerY: number,
  radiusX: number,
  radiusY: number,
  rotation: number,
  grid: number,
  color: number,
  alpha: number,
  dashOffset: number,
) {
  const steps = 90;
  for (let index = 0; index < steps; index += 1) {
    if ((index + dashOffset) % 7 > 3) continue;
    const angle = (index / steps) * Math.PI * 2;
    const localX = Math.cos(angle) * radiusX;
    const localY = Math.sin(angle) * radiusY;
    const x =
      centerX + localX * Math.cos(rotation) - localY * Math.sin(rotation);
    const y =
      centerY + localX * Math.sin(rotation) + localY * Math.cos(rotation);
    graphics
      .rect(snap(x, grid), snap(y, grid), grid, grid)
      .fill({ color, alpha });
  }
}

function drawRasengan(
  orb: Graphics,
  trails: Graphics,
  width: number,
  height: number,
  grid: number,
  elapsed: number,
) {
  orb.clear();
  trails.clear();

  const centerX = width * 0.5;
  const centerY = height * 0.5;
  const minDimension = Math.min(width, height);
  const form = Math.min(1, elapsed / 520);
  const pulse = 1 + Math.sin(elapsed * 0.018) * 0.045;
  const fade =
    elapsed < 1900 ? 1 : Math.max(0, (DURATION_MS - elapsed) / 400);
  const radius = minDimension * 0.155 * form * pulse;
  if (radius < grid) return;

  drawPixelCircle(
    orb,
    centerX,
    centerY,
    radius * 1.08,
    grid,
    0x168cff,
    0.22 * fade,
  );
  drawPixelCircle(
    orb,
    centerX,
    centerY,
    radius * 0.88,
    grid,
    0x45caff,
    0.46 * fade,
  );
  drawPixelCircle(
    orb,
    centerX,
    centerY,
    radius * 0.48,
    grid,
    0xc9f8ff,
    0.72 * fade,
  );

  const spin = elapsed * 0.006;
  for (let arm = 0; arm < 4; arm += 1) {
    const armOffset = spin + (arm / 4) * Math.PI * 2;
    for (let step = 0; step < 34; step += 1) {
      const progress = step / 34;
      const angle = armOffset + progress * Math.PI * 2.25;
      const spiralRadius = radius * progress * 0.88;
      const x = centerX + Math.cos(angle) * spiralRadius;
      const y = centerY + Math.sin(angle) * spiralRadius;
      const size = grid * (progress < 0.3 ? 1.35 : 0.8);
      orb
        .rect(
          snap(x, grid) - size / 2,
          snap(y, grid) - size / 2,
          size,
          size,
        )
        .fill({
          color: arm % 2 === 0 ? 0xffffff : 0x84eaff,
          alpha: fade * (0.88 - progress * 0.28),
        });
    }
  }

  drawOrbit(
    trails,
    centerX,
    centerY,
    radius * 1.42,
    radius * 0.52,
    spin * 0.72,
    grid,
    0xcdf9ff,
    0.8 * form * fade,
    Math.floor(elapsed / 45),
  );
  drawOrbit(
    trails,
    centerX,
    centerY,
    radius * 1.28,
    radius * 0.68,
    -spin * 0.54 + 1.1,
    grid,
    0x48cfff,
    0.65 * form * fade,
    Math.floor(elapsed / 35),
  );
  drawOrbit(
    trails,
    centerX,
    centerY,
    radius * 1.18,
    radius * 0.82,
    spin * 0.38 - 0.8,
    grid,
    0x8eecff,
    0.48 * form * fade,
    Math.floor(elapsed / 55),
  );

  const burst = Math.max(0, Math.min(1, (elapsed - 1580) / 420));
  if (burst > 0) {
    for (let ring = 0; ring < 3; ring += 1) {
      const ringRadius = radius * (1.1 + burst * (1.8 + ring * 0.65));
      const ringAlpha = (1 - burst) * (0.72 - ring * 0.15);
      drawOrbit(
        trails,
        centerX,
        centerY,
        ringRadius,
        ringRadius * (0.76 + ring * 0.06),
        ring * 0.7,
        grid,
        ring === 1 ? 0xffffff : 0x42cfff,
        ringAlpha,
        ring * 2,
      );
    }
  }
}

function createParticles(
  layer: Container,
  grid: number,
): ChakraParticle[] {
  const colors = [0xffffff, 0xa9f1ff, 0x5bdcff, 0x268eff];
  return Array.from({ length: PARTICLE_COUNT }, (_, index) => {
    const size = grid * randomBetween(0.55, 1.25);
    const view = new Graphics()
      .rect(-size / 2, -size / 2, size, size)
      .fill({ color: colors[index % colors.length] });
    view.blendMode = 'add';
    layer.addChild(view);
    return {
      view,
      angle: randomBetween(0, Math.PI * 2),
      radius: randomBetween(0.22, 0.46),
      speed: randomBetween(1.7, 4.8) * (index % 2 === 0 ? 1 : -1),
      size,
      drift: randomBetween(0, Math.PI * 2),
    };
  });
}

export function PixelRasenganEffect() {
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

      const trails = new Graphics();
      const orb = new Graphics();
      const particleLayer = new Container();
      trails.blendMode = 'add';
      orb.blendMode = 'add';
      nextApp.stage.addChild(trails, orb, particleLayer);

      let width = nextApp.screen.width;
      let height = nextApp.screen.height;
      let grid = Math.max(5, Math.min(10, Math.round(Math.min(width, height) / 88)));
      let particles = createParticles(particleLayer, grid);
      let elapsed = 0;

      const rebuild = () => {
        grid = Math.max(5, Math.min(10, Math.round(Math.min(width, height) / 88)));
        particleLayer.removeChildren().forEach((child) => child.destroy());
        particles = createParticles(particleLayer, grid);
      };

      const update = (time: { deltaMS: number }) => {
        elapsed += time.deltaMS;
        if (width !== nextApp.screen.width || height !== nextApp.screen.height) {
          width = nextApp.screen.width;
          height = nextApp.screen.height;
          rebuild();
        }

        drawRasengan(orb, trails, width, height, grid, elapsed);

        const form = Math.min(1, elapsed / 520);
        const burst = Math.max(0, Math.min(1, (elapsed - 1580) / 500));
        const minDimension = Math.min(width, height);
        const centerX = width * 0.5;
        const centerY = height * 0.5;
        for (const particle of particles) {
          particle.angle += particle.speed * (time.deltaMS / 1000);
          particle.drift += time.deltaMS * 0.004;
          const orbitRadius =
            minDimension *
            (particle.radius * (1 - form * 0.52) + burst * 0.72);
          const x =
            centerX +
            Math.cos(particle.angle) * orbitRadius +
            Math.sin(particle.drift) * grid * 2;
          const y =
            centerY +
            Math.sin(particle.angle) * orbitRadius * 0.72 +
            Math.cos(particle.drift) * grid * 2;
          particle.view.position.set(snap(x, grid), snap(y, grid));
          particle.view.rotation += particle.speed * 0.02;
          particle.view.alpha =
            elapsed < 1950 ? Math.min(1, form + 0.15) : Math.max(0, 1 - burst);
          particle.view.scale.set(1 + burst * 0.7);
        }

        nextApp.stage.alpha =
          elapsed < 2050 ? 1 : Math.max(0, (DURATION_MS - elapsed) / 250);
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
