import { useEffect, useRef } from 'react';

interface Star {
  x: number;
  y: number;
  z: number;
  color: string;
}

const STAR_COUNT = 300;
const SPEED = 0.02;
const BG_COLOR = '#050510';
const CYAN_COLORS = ['#00f0ff', '#00c8ff', '#00a0ff', '#40e0ff', '#80f0ff'];
const MAGENTA_COLORS = ['#ff00ff', '#ff40ff', '#ff80ff', '#ffffff'];
const TRAIL_ALPHA = 0.15;
const ENGINE_PULSE_SPEED = 0.002;

function createStar(width: number, height: number, resetZ = false): Star {
  const isCyan = Math.random() < 0.8;
  const palette = isCyan ? CYAN_COLORS : MAGENTA_COLORS;
  return {
    x: (Math.random() - 0.5) * width * 2,
    y: (Math.random() - 0.5) * height * 2,
    z: resetZ ? Math.random() * 0.01 : Math.random() * 2,
    color: palette[Math.floor(Math.random() * palette.length)],
  };
}

export function StarfieldCanvas() {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const animRef = useRef<number>(0);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;

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

      const pulse = 0.6 + 0.4 * Math.sin(frame * ENGINE_PULSE_SPEED);
      const grad = ctx.createRadialGradient(cx, cy, 0, cx, cy, 120 * pulse);
      grad.addColorStop(0, `rgba(0, 240, 255, ${0.08 * pulse})`);
      grad.addColorStop(0.5, `rgba(0, 200, 255, ${0.03 * pulse})`);
      grad.addColorStop(1, 'rgba(0, 200, 255, 0)');
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
        const size = Math.min(3, (1 - star.z / 2) * 4);
        const alpha = Math.min(1, (1 - star.z / 2) * 1.5);

        if (sx < -10 || sx > width + 10 || sy < -10 || sy > height + 10) {
          Object.assign(star, createStar(width, height, false));
          star.z = 2;
          continue;
        }

        ctx.beginPath();
        ctx.arc(sx, sy, size, 0, Math.PI * 2);
        ctx.fillStyle = star.color;
        ctx.globalAlpha = alpha;
        ctx.fill();
        ctx.globalAlpha = 1;
      }

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
