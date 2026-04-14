// src/features/bazaar/world/DecoLayer.ts
// 装饰物渲染层 — 冷色奇幻学院风格（魔法树/冰蓝灯笼/冷色花丛）

import {
  DECO_COLORS, DECO_OUTLINE, DECO_TREE_TRUNK, DECO_LANTERN_POLE,
  DECO_FLOWER_COLORS, LANTERN_GLOW_COLOR,
  LANTERN_GLOW_INNER_ALPHA, LANTERN_GLOW_OUTER_ALPHA,
  LANTERN_GLOW_RADIUS_INNER, LANTERN_GLOW_RADIUS_OUTER,
} from './palette';

export interface DecoData {
  type: 'tree' | 'lantern' | 'flowers' | 'bush' | 'barrel' | 'signpost';
  col: number;
  row: number;
}

export const DECORATIONS: DecoData[] = [
  // 北部市场街装饰
  { type: 'lantern', col: 3, row: 3 },
  { type: 'lantern', col: 8, row: 3 },
  { type: 'lantern', col: 13, row: 3 },
  { type: 'flowers', col: 16, row: 5 },
  { type: 'bush', col: 1, row: 5 },
  { type: 'tree', col: 18, row: 2 },
  { type: 'tree', col: 6, row: 6 },

  // 中央广场装饰
  { type: 'lantern', col: 18, row: 11 },
  { type: 'lantern', col: 22, row: 11 },
  { type: 'lantern', col: 18, row: 16 },
  { type: 'lantern', col: 22, row: 16 },
  { type: 'flowers', col: 20, row: 13 },
  { type: 'tree', col: 16, row: 10 },
  { type: 'tree', col: 24, row: 10 },

  // 西侧池塘岸边
  { type: 'bush', col: 1, row: 12 },
  { type: 'bush', col: 7, row: 13 },
  { type: 'bush', col: 3, row: 15 },
  { type: 'flowers', col: 8, row: 12 },
  { type: 'flowers', col: 1, row: 14 },

  // 南部区域
  { type: 'tree', col: 6, row: 20 },
  { type: 'lantern', col: 14, row: 19 },
  { type: 'lantern', col: 30, row: 19 },
  { type: 'flowers', col: 20, row: 22 },
  { type: 'bush', col: 8, row: 26 },
  { type: 'tree', col: 20, row: 24 },
  { type: 'flowers', col: 36, row: 23 },

  // 边缘装饰
  { type: 'tree', col: 10, row: 27 },
  { type: 'tree', col: 30, row: 27 },
  { type: 'bush', col: 38, row: 26 },
];

const TS = 32;

const DECO_SIZES: Record<string, { w: number; h: number }> = {
  tree: { w: 24, h: 28 },
  lantern: { w: 10, h: 20 },
  flowers: { w: 14, h: 10 },
  bush: { w: 14, h: 12 },
  barrel: { w: 12, h: 14 },
  signpost: { w: 8, h: 20 },
};

export class DecoLayer {
  private images = new Map<string, HTMLImageElement>();
  private loaded = false;

  async loadImages(): Promise<void> {
    const types = ['tree', 'lantern', 'flowers', 'bush'];
    const promises = types.map(async (type) => {
      try {
        const img = new Image();
        img.src = `/bazaar/assets/deco_${type}.png`;
        await img.decode();
        this.images.set(type, this.makeTransparent(img));
      } catch {
        // PNG 不存在，用程序化 fallback
      }
    });
    await Promise.allSettled(promises);
    this.loaded = true;
  }

  render(ctx: CanvasRenderingContext2D, deco: DecoData, cameraX: number, cameraY: number, frame: number): void {
    const px = deco.col * TS + TS / 2 - cameraX;
    const py = deco.row * TS + TS - cameraY;
    const size = DECO_SIZES[deco.type] ?? { w: 16, h: 16 };

    const img = this.images.get(deco.type);
    if (img) {
      ctx.drawImage(img, px - size.w / 2, py - size.h, size.w, size.h);
    } else {
      this.drawProcedural(ctx, deco, px, py, frame);
    }
  }

  getSortY(deco: DecoData): number {
    return deco.row * TS;
  }

  private drawProcedural(ctx: CanvasRenderingContext2D, deco: DecoData, px: number, py: number, frame: number): void {
    const colors = DECO_COLORS[deco.type] ?? { main: '#4A5568', dark: '#2A3448' };

    switch (deco.type) {
      case 'tree': {
        // 树干（冷棕）
        ctx.fillStyle = DECO_TREE_TRUNK;
        ctx.fillRect(px - 2, py - 10, 5, 10);
        // 树冠（冷青绿叠加圆）
        ctx.fillStyle = colors.main;
        ctx.beginPath(); ctx.arc(px, py - 16, 9, 0, Math.PI * 2); ctx.fill();
        ctx.fillStyle = colors.accent;
        ctx.beginPath(); ctx.arc(px - 3, py - 20, 7, 0, Math.PI * 2); ctx.fill();
        ctx.fillStyle = colors.dark;
        ctx.beginPath(); ctx.arc(px + 4, py - 18, 6, 0, Math.PI * 2); ctx.fill();
        // 描边
        ctx.strokeStyle = DECO_OUTLINE;
        ctx.lineWidth = 1;
        ctx.beginPath(); ctx.arc(px, py - 16, 9, 0, Math.PI * 2); ctx.stroke();
        break;
      }
      case 'lantern': {
        // 灯柱（冷灰）
        ctx.fillStyle = DECO_LANTERN_POLE;
        ctx.fillRect(px - 1, py - 16, 3, 16);
        // 灯体（冰蓝）
        ctx.fillStyle = colors.accent;
        ctx.fillRect(px - 4, py - 20, 9, 6);
        // 光晕（screen 合成 + 双层）
        const glowBase = 0.35 + Math.sin(frame * 0.05) * 0.15;
        ctx.save();
        ctx.globalCompositeOperation = 'screen';
        // 内圈
        ctx.fillStyle = `rgba(168,216,234,${LANTERN_GLOW_INNER_ALPHA * glowBase})`;
        ctx.beginPath(); ctx.arc(px, py - 17, LANTERN_GLOW_RADIUS_INNER, 0, Math.PI * 2); ctx.fill();
        // 外圈
        ctx.fillStyle = `rgba(168,216,234,${LANTERN_GLOW_OUTER_ALPHA * glowBase})`;
        ctx.beginPath(); ctx.arc(px, py - 17, LANTERN_GLOW_RADIUS_OUTER, 0, Math.PI * 2); ctx.fill();
        ctx.restore();
        // 描边
        ctx.strokeStyle = DECO_OUTLINE;
        ctx.lineWidth = 1;
        ctx.strokeRect(px - 4, py - 20, 9, 6);
        break;
      }
      case 'flowers': {
        // 三色冷系花（冷粉+冰蓝+紫）
        for (let i = 0; i < 3; i++) {
          const fx = px - 5 + i * 5;
          const fy = py - 3 - Math.random() * 2;
          ctx.fillStyle = DECO_FLOWER_COLORS[i];
          ctx.fillRect(fx, fy, 4, 4);
        }
        break;
      }
      case 'bush': {
        ctx.fillStyle = colors.main;
        ctx.beginPath(); ctx.arc(px, py - 6, 7, 0, Math.PI * 2); ctx.fill();
        ctx.fillStyle = colors.dark;
        ctx.beginPath(); ctx.arc(px + 3, py - 4, 4, 0, Math.PI * 2); ctx.fill();
        // 冷粉果
        ctx.fillStyle = colors.accent ?? '#B88FA8';
        ctx.fillRect(px - 2, py - 8, 2, 2);
        ctx.fillRect(px + 3, py - 6, 2, 2);
        ctx.strokeStyle = DECO_OUTLINE;
        ctx.lineWidth = 1;
        ctx.beginPath(); ctx.arc(px, py - 6, 7, 0, Math.PI * 2); ctx.stroke();
        break;
      }
      default: {
        ctx.fillStyle = colors.main;
        ctx.fillRect(px - 5, py - 12, 10, 12);
        ctx.strokeStyle = DECO_OUTLINE;
        ctx.lineWidth = 1;
        ctx.strokeRect(px - 5, py - 12, 10, 12);
      }
    }
  }

  private makeTransparent(img: HTMLImageElement): HTMLImageElement {
    const canvas = document.createElement('canvas');
    canvas.width = img.naturalWidth;
    canvas.height = img.naturalHeight;
    const ctx = canvas.getContext('2d')!;
    ctx.drawImage(img, 0, 0);

    const imageData = ctx.getImageData(0, 0, canvas.width, canvas.height);
    const data = imageData.data;
    const threshold = 240;

    for (let i = 0; i < data.length; i += 4) {
      if (data[i] > threshold && data[i + 1] > threshold && data[i + 2] > threshold) {
        data[i + 3] = 0;
      }
    }

    ctx.putImageData(imageData, 0, 0);
    const result = new Image();
    result.src = canvas.toDataURL('image/png');
    return result;
  }
}
