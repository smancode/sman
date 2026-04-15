// src/features/bazaar/world/DecoLayer.ts
// 装饰物渲染层 — 游戏大厅布局

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
  // ── 北部声望榜两侧（庄重感） ──
  { type: 'tree', col: 14, row: 2 },
  { type: 'tree', col: 14, row: 4 },
  { type: 'tree', col: 24, row: 2 },
  { type: 'tree', col: 24, row: 4 },
  { type: 'lantern', col: 16, row: 1 },
  { type: 'lantern', col: 23, row: 1 },

  // ── 市场街两侧（热闹感） ──
  { type: 'lantern', col: 13, row: 5 },
  { type: 'lantern', col: 13, row: 7 },
  { type: 'lantern', col: 28, row: 5 },
  { type: 'lantern', col: 28, row: 7 },
  { type: 'barrel', col: 13, row: 6 },
  { type: 'barrel', col: 28, row: 6 },
  { type: 'flowers', col: 13, row: 8 },
  { type: 'flowers', col: 28, row: 8 },

  // ── 市场街到广场之间（缓冲带） ──
  { type: 'tree', col: 12, row: 9 },
  { type: 'tree', col: 30, row: 9 },
  { type: 'bush', col: 14, row: 8 },
  { type: 'bush', col: 28, row: 8 },

  // ── 西侧池塘岸边（自然感） ──
  { type: 'bush', col: 3, row: 10 },
  { type: 'bush', col: 3, row: 12 },
  { type: 'bush', col: 3, row: 14 },
  { type: 'bush', col: 3, row: 16 },
  { type: 'flowers', col: 2, row: 11 },
  { type: 'flowers', col: 2, row: 15 },
  { type: 'tree', col: 2, row: 9 },
  { type: 'tree', col: 2, row: 17 },

  // ── 中央广场四角灯笼 ──
  { type: 'lantern', col: 16, row: 11 },
  { type: 'lantern', col: 23, row: 11 },
  { type: 'lantern', col: 16, row: 16 },
  { type: 'lantern', col: 23, row: 16 },
  // 广场中央花坛
  { type: 'flowers', col: 18, row: 13 },
  { type: 'flowers', col: 21, row: 13 },
  { type: 'flowers', col: 19, row: 15 },
  { type: 'bush', col: 17, row: 12 },
  { type: 'bush', col: 23, row: 15 },

  // ── 西南悬赏板周围（野外感） ──
  { type: 'tree', col: 5, row: 19 },
  { type: 'tree', col: 5, row: 22 },
  { type: 'barrel', col: 11, row: 21 },
  { type: 'barrel', col: 11, row: 23 },
  { type: 'bush', col: 6, row: 20 },
  { type: 'lantern', col: 9, row: 19 },

  // ── 东南搜索站周围 ──
  { type: 'lantern', col: 27, row: 19 },
  { type: 'lantern', col: 31, row: 19 },
  { type: 'tree', col: 32, row: 21 },
  { type: 'tree', col: 32, row: 23 },
  { type: 'flowers', col: 30, row: 22 },
  { type: 'bush', col: 31, row: 20 },

  // ── 正南工坊周围 ──
  { type: 'barrel', col: 16, row: 22 },
  { type: 'barrel', col: 22, row: 22 },
  { type: 'tree', col: 15, row: 24 },
  { type: 'tree', col: 23, row: 24 },
  { type: 'lantern', col: 17, row: 22 },
  { type: 'lantern', col: 22, row: 22 },

  // ── 东部填充区 ──
  { type: 'tree', col: 36, row: 10 },
  { type: 'tree', col: 37, row: 13 },
  { type: 'tree', col: 36, row: 16 },
  { type: 'bush', col: 37, row: 11 },
  { type: 'bush', col: 37, row: 15 },
  { type: 'flowers', col: 35, row: 12 },

  // ── 南部边缘缓冲 ──
  { type: 'tree', col: 8, row: 26 },
  { type: 'tree', col: 20, row: 27 },
  { type: 'tree', col: 32, row: 26 },
  { type: 'bush', col: 14, row: 26 },
  { type: 'bush', col: 26, row: 27 },
  { type: 'flowers', col: 12, row: 25 },
  { type: 'flowers', col: 24, row: 25 },
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
        ctx.fillStyle = DECO_TREE_TRUNK;
        ctx.fillRect(px - 2, py - 10, 5, 10);
        ctx.fillStyle = colors.main;
        ctx.beginPath(); ctx.arc(px, py - 16, 9, 0, Math.PI * 2); ctx.fill();
        ctx.fillStyle = colors.accent;
        ctx.beginPath(); ctx.arc(px - 3, py - 20, 7, 0, Math.PI * 2); ctx.fill();
        ctx.fillStyle = colors.dark;
        ctx.beginPath(); ctx.arc(px + 4, py - 18, 6, 0, Math.PI * 2); ctx.fill();
        ctx.strokeStyle = DECO_OUTLINE;
        ctx.lineWidth = 1;
        ctx.beginPath(); ctx.arc(px, py - 16, 9, 0, Math.PI * 2); ctx.stroke();
        break;
      }
      case 'lantern': {
        ctx.fillStyle = DECO_LANTERN_POLE;
        ctx.fillRect(px - 1, py - 16, 3, 16);
        ctx.fillStyle = colors.accent;
        ctx.fillRect(px - 4, py - 20, 9, 6);
        const glowBase = 0.35 + Math.sin(frame * 0.05) * 0.15;
        ctx.save();
        ctx.globalCompositeOperation = 'screen';
        ctx.fillStyle = `rgba(168,216,234,${LANTERN_GLOW_INNER_ALPHA * glowBase})`;
        ctx.beginPath(); ctx.arc(px, py - 17, LANTERN_GLOW_RADIUS_INNER, 0, Math.PI * 2); ctx.fill();
        ctx.fillStyle = `rgba(168,216,234,${LANTERN_GLOW_OUTER_ALPHA * glowBase})`;
        ctx.beginPath(); ctx.arc(px, py - 17, LANTERN_GLOW_RADIUS_OUTER, 0, Math.PI * 2); ctx.fill();
        ctx.restore();
        ctx.strokeStyle = DECO_OUTLINE;
        ctx.lineWidth = 1;
        ctx.strokeRect(px - 4, py - 20, 9, 6);
        break;
      }
      case 'flowers': {
        const sway = Math.sin(frame * 0.08 + px * 0.1) * 0.8;
        for (let i = 0; i < 3; i++) {
          const fx = px - 5 + i * 5 + sway * (i === 1 ? -1 : 1);
          const fy = py - 3;
          ctx.fillStyle = DECO_FLOWER_COLORS[i];
          ctx.fillRect(fx, fy, 4, 4);
        }
        break;
      }
      case 'bush': {
        const bushSway = Math.sin(frame * 0.06 + py * 0.1) * 0.5;
        ctx.fillStyle = colors.main;
        ctx.beginPath(); ctx.arc(px + bushSway, py - 6, 7, 0, Math.PI * 2); ctx.fill();
        ctx.fillStyle = colors.dark;
        ctx.beginPath(); ctx.arc(px + 3, py - 4, 4, 0, Math.PI * 2); ctx.fill();
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
