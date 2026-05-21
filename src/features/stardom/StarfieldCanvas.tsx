import { useEffect, useRef, useState } from 'react';

interface Star {
  x: number;
  y: number;
  z: number;
  charIdx: number;
  colorIdx: number;
  twinkleOffset: number;
}

const STAR_COUNT = 90;
const SPEED = 0.015;
const TRAIL_ALPHA = 0.12;
const CHARS = '★☆✦✧✶✷✸✹✺✻✼✽✾❋❊❇❈❅❆♠♣♥♦✿❀❁❂❃';
const CHAR_COUNT = CHARS.length;
const ATLAS_FONT_SIZE = 36;
const ATLAS_CELL = 40;

const CANDY_COLORS = [
  '#FF6B9D', '#FF8A5C', '#FFD93D', '#6BCB77', '#4D96FF', '#9B59B6',
  '#FF6EC7', '#00D2FF', '#FF4757', '#7BED9F', '#ECCC68', '#A29BFE',
];

function buildAtlases(): HTMLCanvasElement[] {
  return CANDY_COLORS.map((color) => {
    const canvas = document.createElement('canvas');
    canvas.width = ATLAS_CELL * CHAR_COUNT;
    canvas.height = ATLAS_CELL;
    const ctx = canvas.getContext('2d')!;
    ctx.font = `${ATLAS_FONT_SIZE}px serif`;
    ctx.fillStyle = color;
    ctx.textBaseline = 'middle';
    ctx.textAlign = 'center';
    for (let i = 0; i < CHAR_COUNT; i++) {
      ctx.fillText(CHARS[i], i * ATLAS_CELL + ATLAS_CELL / 2, ATLAS_CELL / 2);
    }
    return canvas;
  });
}

function createStar(width: number, height: number, resetZ = false): Star {
  return {
    x: (Math.random() - 0.5) * width * 2,
    y: (Math.random() - 0.5) * height * 2,
    z: resetZ ? Math.random() * 0.01 : Math.random() * 2,
    charIdx: Math.floor(Math.random() * CHAR_COUNT),
    colorIdx: Math.floor(Math.random() * CANDY_COLORS.length),
    twinkleOffset: Math.random() * Math.PI * 2,
  };
}

export function StarfieldCanvas() {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const animRef = useRef<number>(0);
  const [isDark, setIsDark] = useState(() =>
    typeof document !== 'undefined' && document.documentElement.classList.contains('dark'),
  );

  useEffect(() => {
    const observer = new MutationObserver(() => {
      setIsDark(document.documentElement.classList.contains('dark'));
    });
    observer.observe(document.documentElement, { attributes: true, attributeFilter: ['class'] });
    return () => observer.disconnect();
  }, []);

  const bgColor = isDark ? '#0a0a0f' : '#ffffff';
  const trailRgb = isDark ? '10, 10, 15' : '255, 255, 255';

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext('2d')!;
    const atlases = buildAtlases();

    const dpr = window.devicePixelRatio || 1;
    let width = canvas.parentElement?.clientWidth || window.innerWidth;
    let height = canvas.parentElement?.clientHeight || window.innerHeight;
    canvas.width = width * dpr;
    canvas.height = height * dpr;
    ctx.scale(dpr, dpr);

    const stars: Star[] = Array.from({ length: STAR_COUNT }, () => createStar(width, height));
    let frame = 0;

    const resize = () => {
      width = canvas.parentElement?.clientWidth || window.innerWidth;
      height = canvas.parentElement?.clientHeight || window.innerHeight;
      canvas.width = width * dpr;
      canvas.height = height * dpr;
      ctx.scale(dpr, dpr);
    };
    window.addEventListener('resize', resize);

    const draw = () => {
      frame++;
      ctx.fillStyle = `rgba(${trailRgb}, ${TRAIL_ALPHA})`;
      ctx.fillRect(0, 0, width, height);

      const cx = width / 2;
      const cy = height / 2;

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
        const size = Math.max(8, Math.min(30, progress * 34));
        const baseAlpha = Math.min(1, progress * 1.4);
        const twinkle = 0.6 + 0.4 * Math.sin(frame * 0.008 + star.twinkleOffset);
        const alpha = baseAlpha * twinkle;

        const glowSize = size * 2.2;
        ctx.globalAlpha = alpha * 0.25;
        const atlas = atlases[star.colorIdx];
        ctx.drawImage(
          atlas,
          star.charIdx * ATLAS_CELL, 0, ATLAS_CELL, ATLAS_CELL,
          sx - glowSize / 2, sy - glowSize / 2, glowSize, glowSize,
        );

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

    ctx.fillStyle = bgColor;
    ctx.fillRect(0, 0, width, height);
    animRef.current = requestAnimationFrame(draw);

    return () => {
      window.removeEventListener('resize', resize);
      cancelAnimationFrame(animRef.current);
    };
  }, [bgColor, trailRgb]);

  return (
    <canvas
      ref={canvasRef}
      style={{
        position: 'absolute',
        inset: 0,
        width: '100%',
        height: '100%',
        background: bgColor,
      }}
    />
  );
}
