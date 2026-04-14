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
  // ── 北部市场街（摊位周围缓冲带，3-5个一簇） ──
  { type: 'lantern', col: 4, row: 2 },
  { type: 'lantern', col: 9, row: 2 },
  { type: 'lantern', col: 14, row: 2 },
  { type: 'barrel', col: 8, row: 4 },
  { type: 'barrel', col: 14, row: 6 },
  { type: 'flowers', col: 3, row: 4 },
  { type: 'flowers', col: 14, row: 4 },
  { type: 'bush', col: 1, row: 3 },
  { type: 'bush', col: 20, row: 3 },

  // ── 北部缓冲区（声望榜周围） ──
  { type: 'tree', col: 20, row: 2 },
  { type: 'tree', col: 25, row: 3 },
  { type: 'tree', col: 1, row: 7 },
  { type: 'tree', col: 27, row: 7 },
  { type: 'lantern', col: 19, row: 5 },
  { type: 'lantern', col: 25, row: 5 },
  { type: 'flowers', col: 23, row: 6 },

  // ── 中央广场（四角灯笼 + 中央花坛 + 广场入口树） ──
  { type: 'lantern', col: 17, row: 11 },
  { type: 'lantern', col: 23, row: 11 },
  { type: 'lantern', col: 17, row: 16 },
  { type: 'lantern', col: 23, row: 16 },
  { type: 'flowers', col: 19, row: 13 },
  { type: 'flowers', col: 21, row: 13 },
  { type: 'flowers', col: 20, row: 14 },
  { type: 'bush', col: 18, row: 12 },
  { type: 'bush', col: 22, row: 15 },
  { type: 'tree', col: 14, row: 10 },
  { type: 'tree', col: 25, row: 10 },
  { type: 'tree', col: 14, row: 17 },
  { type: 'tree', col: 25, row: 17 },

  // ── 西侧池塘岸边（成簇排列） ──
  { type: 'bush', col: 1, row: 11 },
  { type: 'bush', col: 1, row: 13 },
  { type: 'bush', col: 1, row: 15 },
  { type: 'flowers', col: 0, row: 12 },
  { type: 'flowers', col: 0, row: 14 },
  { type: 'tree', col: 0, row: 10 },
  { type: 'tree', col: 0, row: 16 },

  // ── 南部任务区（悬赏板+搜索站+工坊周围） ──
  { type: 'tree', col: 3, row: 19 },
  { type: 'tree', col: 3, row: 22 },
  { type: 'tree', col: 1, row: 21 },
  { type: 'lantern', col: 5, row: 18 },
  { type: 'lantern', col: 10, row: 18 },
  { type: 'barrel', col: 4, row: 21 },
  { type: 'barrel', col: 10, row: 21 },
  { type: 'flowers', col: 2, row: 20 },
  { type: 'flowers', col: 10, row: 23 },
  { type: 'bush', col: 3, row: 23 },
  { type: 'bush', col: 9, row: 19 },

  // ── 东南技术区（搜索站+工坊周围） ──
  { type: 'lantern', col: 26, row: 19 },
  { type: 'lantern', col: 33, row: 19 },
  { type: 'barrel', col: 31, row: 21 },
  { type: 'barrel', col: 31, row: 24 },
  { type: 'tree', col: 35, row: 20 },
  { type: 'tree', col: 35, row: 24 },
  { type: 'flowers', col: 33, row: 22 },
  { type: 'bush', col: 30, row: 18 },

  // ── 东部填充区 ──
  { type: 'tree', col: 37, row: 12 },
  { type: 'tree', col: 37, row: 15 },
  { type: 'tree', col: 35, row: 8 },
  { type: 'bush', col: 38, row: 10 },
  { type: 'bush', col: 38, row: 14 },
  { type: 'flowers', col: 36, row: 11 },

  // ── 南部边缘缓冲 ──
  { type: 'tree', col: 5, row: 26 },
  { type: 'tree', col: 15, row: 27 },
  { type: 'tree', col: 25, row: 27 },
  { type: 'tree', col: 35, row: 26 },
  { type: 'bush', col: 10, row: 26 },
  { type: 'bush', col: 20, row: 26 },
  { type: 'bush', col: 30, row: 26 },
  { type: 'bush', col: 38, row: 25 },
  { type: 'flowers', col: 12, row: 25 },
  { type: 'flowers', col: 22, row: 25 },
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
        // 三色冷系花（冷粉+冰蓝+紫）+ 微摆动
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
