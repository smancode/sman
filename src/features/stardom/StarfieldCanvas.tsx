import { useEffect, useRef } from 'react';

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
const BG_COLOR = '#ffffff';
const TRAIL_ALPHA = 0.12;
const CHARS = '★☆✦✧✶✷✸✹✺✻✼✽✾❋❊❇❈❅❆♠♣♥♦✿❀❁❂❃';
const CHAR_COUNT = CHARS.length;
const ATLAS_FONT_SIZE = 36;
const ATLAS_CELL = 40;

const CANDY_COLORS = [
  '#FF6B9D', // 粉红
  '#FF8A5C', // 珊瑚橙
  '#FFD93D', // 柠檬黄
  '#6BCB77', // 薄荷绿
  '#4D96FF', // 天蓝
  '#9B59B6', // 薰衣草紫
  '#FF6EC7', // 亮粉
  '#00D2FF', // 冰蓝
  '#FF4757', // 草莓红
  '#7BED9F', // 青苹果绿
  '#ECCC68', // 奶黄
  '#A29BFE', // 丁香紫
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

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext('2d')!;
    const atlases = buildAtlases();

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
      ctx.fillStyle = `rgba(255, 255, 255, ${TRAIL_ALPHA})`;
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

        // 发光层：先画一个更大、更透明的副本作为光晕
        const glowSize = size * 2.2;
        ctx.globalAlpha = alpha * 0.25;
        const atlas = atlases[star.colorIdx];
        ctx.drawImage(
          atlas,
          star.charIdx * ATLAS_CELL, 0, ATLAS_CELL, ATLAS_CELL,
          sx - glowSize / 2, sy - glowSize / 2, glowSize, glowSize,
        );

        // 实体层
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
