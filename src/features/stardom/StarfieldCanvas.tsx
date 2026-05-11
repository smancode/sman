import { useEffect, useRef } from 'react';

interface Star {
  x: number;
  y: number;
  z: number;
  charIdx: number;
}

const STAR_COUNT = 75;
const SPEED = 0.02;
const BG_COLOR = '#050510';
const STAR_COLOR = '#00ff41';
const TRAIL_ALPHA = 0.15;
const CHARS = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789@#$%&*+=~';
const CHAR_COUNT = CHARS.length;
const ATLAS_FONT_SIZE = 28;
const ATLAS_CELL = 32;

function buildAtlas(): HTMLCanvasElement {
  const canvas = document.createElement('canvas');
  canvas.width = ATLAS_CELL * CHAR_COUNT;
  canvas.height = ATLAS_CELL;
  const ctx = canvas.getContext('2d')!;
  ctx.font = `${ATLAS_FONT_SIZE}px monospace`;
  ctx.fillStyle = STAR_COLOR;
  ctx.textBaseline = 'middle';
  ctx.textAlign = 'center';
  for (let i = 0; i < CHAR_COUNT; i++) {
    ctx.fillText(CHARS[i], i * ATLAS_CELL + ATLAS_CELL / 2, ATLAS_CELL / 2);
  }
  return canvas;
}

function createStar(width: number, height: number, resetZ = false): Star {
  return {
    x: (Math.random() - 0.5) * width * 2,
    y: (Math.random() - 0.5) * height * 2,
    z: resetZ ? Math.random() * 0.01 : Math.random() * 2,
    charIdx: Math.floor(Math.random() * CHAR_COUNT),
  };
}

export function StarfieldCanvas() {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const animRef = useRef<number>(0);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext('2d')!;
    const atlas = buildAtlas();

    const dpr = window.devicePixelRatio || 1;
    let width = window.innerWidth;
    let height = window.innerHeight;
    canvas.width = width * dpr;
    canvas.height = height * dpr;
    ctx.scale(dpr, dpr);

    const stars: Star[] = Array.from({ length: STAR_COUNT }, () => createStar(width, height));
    let frame = 0;

    const resize = () => {
      width = window.innerWidth;
      height = window.innerHeight;
      canvas.width = width * dpr;
      canvas.height = height * dpr;
      ctx.scale(dpr, dpr);
    };
    window.addEventListener('resize', resize);

    const draw = () => {
      frame++;
      ctx.fillStyle = `rgba(5, 5, 16, ${TRAIL_ALPHA})`;
      ctx.fillRect(0, 0, width, height);

      const cx = width / 2;
      const cy = height / 2;

      const pulse = 0.6 + 0.4 * Math.sin(frame * 0.002);
      const grad = ctx.createRadialGradient(cx, cy, 0, cx, cy, 120 * pulse);
      grad.addColorStop(0, `rgba(0, 255, 65, ${0.08 * pulse})`);
      grad.addColorStop(0.5, `rgba(0, 255, 65, ${0.03 * pulse})`);
      grad.addColorStop(1, 'rgba(0, 255, 65, 0)');
      ctx.fillStyle = grad;
      ctx.fillRect(cx - 150, cy - 150, 300, 300);

      for (const star of stars) {
        star.z -= SPEED;
        if (star.z <= 0.001) {
          Object.assign(star, createStar(width, height, false));
          star.z = 2;
        }

        const sx = cx + (star.x / star.z) * 0.5;
        const sy = cy + (star.y / star.z) * 0.5;

        if (sx < -20 || sx > width + 20 || sy < -20 || sy > height + 20) {
          Object.assign(star, createStar(width, height, false));
          star.z = 2;
          continue;
        }

        const progress = 1 - star.z / 2;
        const size = Math.max(6, Math.min(20, progress * 22));
        const alpha = Math.min(1, progress * 1.5);

        ctx.globalAlpha = alpha;
        ctx.drawImage(
          atlas,
          star.charIdx * ATLAS_CELL, 0, ATLAS_CELL, ATLAS_CELL,
          sx - size / 2, sy - size / 2, size, size,
        );
      }
      ctx.globalAlpha = 1;

      animRef.current = requestAnimationFrame(draw);
    };

    ctx.fillStyle = BG_COLOR;
    ctx.fillRect(0, 0, width, height);
    animRef.current = requestAnimationFrame(draw);

    return () => {
      window.removeEventListener('resize', resize);
      cancelAnimationFrame(animRef.current);
    };
  }, []);

  return (
    <canvas
      ref={canvasRef}
      style={{
        position: 'fixed',
        top: 0,
        left: 0,
        width: '100vw',
        height: '100vh',
        background: BG_COLOR,
      }}
    />
  );
}
