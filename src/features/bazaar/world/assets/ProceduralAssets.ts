// src/features/bazaar/world/assets/ProceduralAssets.ts
// 冷色奇幻学院程序化绘制 — 深蓝+银+紫+冰蓝
// Canvas API fallback，不依赖外部图片

import type { AssetProvider, AppearanceParams, Facing } from './AssetProvider';
import {
  HAIR_STYLES, HAIR_COLORS, SKIN_TONES, OUTFIT_COLORS,
  BUILDING_COLORS, TILE_COLORS, TILE_DETAIL_COLORS, ZONE_COLORS,
  AGENT_COLORS,
} from '../palette';

const W = 32;
const H = 32;

type PixelColor = string | null;

export class ProceduralAssets implements AssetProvider {
  private agentCache = new Map<string, OffscreenCanvas>();
  private buildingCache = new Map<string, OffscreenCanvas>();
  private tileCache = new Map<string, OffscreenCanvas>();

  getAgentSprite(appearance: AppearanceParams, facing: Facing, frame: number): OffscreenCanvas {
    const key = `${appearance.hairStyle}:${appearance.hairColor}:${appearance.skinTone}:${appearance.outfitColor}:${facing}:${frame}`;
    let cached = this.agentCache.get(key);
    if (cached) return cached;

    cached = this.drawAgent(appearance, facing, frame);
    this.agentCache.set(key, cached);
    return cached;
  }

  getBuildingSprite(type: string): OffscreenCanvas {
    let cached = this.buildingCache.get(type);
    if (cached) return cached;
    cached = this.drawBuilding(type);
    this.buildingCache.set(type, cached);
    return cached;
  }

  getTileSprite(tileId: number, variant: number): OffscreenCanvas {
    const key = `${tileId}:${variant}`;
    let cached = this.tileCache.get(key);
    if (cached) return cached;
    cached = this.drawTile(tileId, variant);
    this.tileCache.set(key, cached);
    return cached;
  }

  isReady(): boolean { return true; }

  // ── Agent Drawing ──

  private drawAgent(ap: AppearanceParams, facing: Facing, frame: number): OffscreenCanvas {
    const canvas = new OffscreenCanvas(W, H);
    const ctx = canvas.getContext('2d')!;
    ctx.imageSmoothingEnabled = false;

    const skin = SKIN_TONES[ap.skinTone] as string;
    const hair = HAIR_COLORS[ap.hairColor] as string;
    const outfit = OUTFIT_COLORS[ap.outfitColor] as string;
    const eye = AGENT_COLORS.eye;
    const blush = AGENT_COLORS.blush;
    const outline = AGENT_COLORS.outline;
    const shoes = AGENT_COLORS.shoes;

    const bounceY = (frame === 1 || frame === 3) ? -1 : 0;

    // ── 大圆头 ──
    const headRows: PixelColor[][] = [
      [null, null, null, hair, hair, hair, hair, null, null, null],
      [null, null, hair, hair, hair, hair, hair, hair, null, null],
      [null, outline, skin, skin, skin, skin, skin, skin, outline, null],
      [null, skin, skin, skin, skin, skin, skin, skin, skin, null],
      [null, skin, skin, eye, skin, skin, eye, skin, skin, null],
      [null, skin, skin, skin, skin, skin, skin, skin, skin, null],
      [null, null, skin, skin, blush, skin, blush, skin, null, null],
      [null, null, null, skin, skin, skin, skin, null, null, null],
    ];

    if (facing === 'left') {
      headRows[4] = [null, skin, skin, null, skin, eye, skin, skin, skin, null];
    } else if (facing === 'right') {
      headRows[4] = [null, skin, skin, skin, eye, skin, null, skin, skin, null];
    } else if (facing === 'up') {
      headRows[4] = [null, skin, null, hair, skin, skin, hair, null, skin, null];
    }

    for (let row = 0; row < headRows.length; row++) {
      for (let col = 0; col < headRows[row].length; col++) {
        if (headRows[row][col]) {
          ctx.fillStyle = headRows[row][col]!;
          ctx.fillRect((col + 4) * 2, (row + 1 + bounceY) * 2, 2, 2);
        }
      }
    }

    this.drawHairOverlay(ctx, ap.hairStyle, hair, facing, bounceY);

    // ── 小身体 ──
    const bodyStart = 18 + bounceY;
    ctx.fillStyle = outline;
    ctx.fillRect(14, (bodyStart) * 2, 6, 2);

    ctx.fillStyle = outfit;
    ctx.fillRect(12, (bodyStart + 1) * 2, 10, 2);
    ctx.fillRect(12, (bodyStart + 2) * 2, 10, 2);

    const legSpread = frame === 1 ? 2 : frame === 3 ? -2 : 0;
    ctx.fillStyle = shoes;
    ctx.fillRect(14, (bodyStart + 3) * 2, 2, 2);
    ctx.fillRect(14 + legSpread, (bodyStart + 4) * 2, 2, 2);
    ctx.fillRect(18, (bodyStart + 3) * 2, 2, 2);
    ctx.fillRect(18 - legSpread, (bodyStart + 4) * 2, 2, 2);

    return canvas;
  }

  private drawHairOverlay(ctx: OffscreenCanvasRenderingContext2D, style: number, color: string, facing: Facing, bounceY: number): void {
    const y0 = (1 + bounceY) * 2;
    ctx.fillStyle = color;

    switch (style) {
      case 0: ctx.fillRect(10, y0, 2, 2); ctx.fillRect(20, y0, 2, 2); ctx.fillRect(14, y0 - 2, 4, 2); break;
      case 1: ctx.fillRect(8, y0 + 2, 2, 6); ctx.fillRect(22, y0 + 2, 2, 6); break;
      case 2: ctx.fillRect(8, y0, 2, 2); ctx.fillRect(22, y0, 2, 2); ctx.fillRect(12, y0 - 2, 2, 2); ctx.fillRect(18, y0 - 2, 2, 2); break;
      case 3: ctx.fillRect(6, y0 + 2, 2, 8); ctx.fillRect(24, y0 + 2, 2, 8); break;
      case 4: ctx.fillRect(8, y0 + 2, 16, 2); ctx.fillRect(6, y0 + 4, 2, 2); break;
      case 5: ctx.fillRect(8, y0, 16, 4); break;
      case 6: ctx.fillRect(14, y0 - 4, 4, 2); ctx.fillRect(12, y0 - 2, 8, 2); break;
      case 7: break;
    }
  }

  // ── Building Drawing ──

  private drawBuilding(type: string): OffscreenCanvas {
    const isLarge = type === 'reputation' || type === 'bounty';
    const size = isLarge ? 96 : 64;
    const canvas = new OffscreenCanvas(size, size);
    const ctx = canvas.getContext('2d')!;
    ctx.imageSmoothingEnabled = false;

    const bc = BUILDING_COLORS;

    if (type === 'stall') {
      this.drawStall(ctx, bc);
    } else if (type === 'reputation') {
      this.drawReputation(ctx, bc);
    } else if (type === 'bounty') {
      this.drawBounty(ctx, bc);
    } else if (type === 'search') {
      this.drawSearch(ctx, bc);
    } else if (type === 'workshop') {
      this.drawWorkshop(ctx, bc);
    }

    return canvas;
  }

  private drawStall(ctx: OffscreenCanvasRenderingContext2D, bc: typeof BUILDING_COLORS): void {
    // 圆润顶棚（紫色）
    ctx.fillStyle = bc.roof;
    ctx.beginPath();
    ctx.ellipse(32, 16, 30, 14, 0, Math.PI, 0);
    ctx.fill();
    ctx.fillStyle = bc.roofDark;
    ctx.fillRect(4, 16, 56, 4);

    // 条纹装饰（紫色系）
    ctx.fillStyle = bc.banner;
    for (let i = 0; i < 8; i++) {
      ctx.fillRect(i * 8 + 4, 6, 4, 8);
    }

    // 圆润柜台
    ctx.fillStyle = bc.wood;
    this.roundRect(ctx, 6, 28, 52, 20, 4);
    ctx.fillStyle = bc.gold;
    ctx.fillRect(6, 28, 52, 2);

    // 冷色小物品
    ctx.fillStyle = bc.stallItem1;
    ctx.fillRect(12, 22, 6, 6);
    ctx.fillStyle = bc.stallItem2;
    ctx.fillRect(26, 22, 6, 6);
    ctx.fillStyle = bc.stallItem3;
    ctx.fillRect(40, 22, 6, 6);

    // 小腿
    ctx.fillStyle = bc.woodDark;
    ctx.fillRect(10, 48, 4, 8);
    ctx.fillRect(50, 48, 4, 8);
  }

  private drawReputation(ctx: OffscreenCanvasRenderingContext2D, bc: typeof BUILDING_COLORS): void {
    // 圆润石质基座
    ctx.fillStyle = bc.stoneDark;
    this.roundRect(ctx, 12, 68, 72, 20, 6);
    ctx.fillStyle = bc.stone;
    this.roundRect(ctx, 16, 58, 64, 14, 4);

    // 圆角告示板（浅银白）
    ctx.fillStyle = '#C8D8E8';
    this.roundRect(ctx, 18, 10, 60, 48, 6);
    ctx.fillStyle = bc.stoneDark;
    ctx.fillRect(16, 6, 64, 4);
    ctx.fillRect(16, 56, 64, 4);

    // 金色皇冠装饰
    ctx.fillStyle = bc.gold;
    ctx.fillRect(38, 0, 20, 8);
    ctx.fillRect(42, 0, 12, 4);
    // 紫色宝石（替代红宝石）
    ctx.fillStyle = bc.roof;
    ctx.fillRect(46, 2, 4, 4);

    // 银色星星
    ctx.fillStyle = bc.gold;
    const stars = [[30, 24], [50, 20], [40, 34], [56, 30], [30, 42], [50, 40]];
    stars.forEach(([x, y]) => {
      ctx.fillRect(x, y, 4, 4);
      ctx.fillRect(x + 1, y - 1, 2, 1);
    });

    // 圆柱
    ctx.fillStyle = bc.stoneDark;
    ctx.fillRect(18, 56, 6, 24);
    ctx.fillRect(72, 56, 6, 24);
  }

  private drawBounty(ctx: OffscreenCanvasRenderingContext2D, bc: typeof BUILDING_COLORS): void {
    // 木质框架（冷灰，无紫色）
    ctx.fillStyle = bc.woodDark;
    this.roundRect(ctx, 8, 6, 80, 62, 8);

    // 内板
    ctx.fillStyle = bc.wood;
    this.roundRect(ctx, 12, 10, 72, 54, 6);

    // 纸条（冷白）
    ctx.fillStyle = '#C8D8E8';
    this.roundRect(ctx, 18, 16, 22, 16, 3);
    this.roundRect(ctx, 48, 14, 26, 18, 3);
    this.roundRect(ctx, 22, 40, 18, 16, 3);

    // 小钉子（金色）
    ctx.fillStyle = bc.gold;
    ctx.fillRect(27, 14, 2, 2);
    ctx.fillRect(59, 12, 2, 2);
    ctx.fillRect(29, 38, 2, 2);

    // 底座
    ctx.fillStyle = bc.woodDark;
    this.roundRect(ctx, 14, 68, 68, 20, 6);
    ctx.fillRect(18, 66, 6, 24);
    ctx.fillRect(72, 66, 6, 24);
  }

  private drawSearch(ctx: OffscreenCanvasRenderingContext2D, bc: typeof BUILDING_COLORS): void {
    // 底座
    ctx.fillStyle = bc.stone;
    this.roundRect(ctx, 14, 46, 36, 14, 4);

    // 水晶球（搜索站标志 — 偏蓝）
    ctx.fillStyle = bc.blue;
    ctx.beginPath();
    ctx.ellipse(32, 30, 16, 18, 0, 0, Math.PI * 2);
    ctx.fill();
    ctx.fillStyle = bc.blueDark;
    ctx.fillRect(16, 36, 32, 8);

    // 高光（深冰蓝，非 #A8D8EA）
    ctx.fillStyle = bc.blueLight;
    ctx.fillRect(24, 20, 8, 6);

    // 顶部星星
    ctx.fillStyle = bc.gold;
    ctx.fillRect(28, 4, 8, 6);
    ctx.fillRect(30, 2, 4, 2);
  }

  private drawWorkshop(ctx: OffscreenCanvasRenderingContext2D, bc: typeof BUILDING_COLORS): void {
    // 底座
    ctx.fillStyle = bc.workshop;
    this.roundRect(ctx, 14, 46, 36, 14, 4);

    // 工作台
    ctx.fillStyle = bc.workshop;
    this.roundRect(ctx, 10, 18, 44, 28, 4);
    ctx.fillStyle = bc.stone;
    this.roundRect(ctx, 14, 22, 36, 20, 3);

    // 工具（金色+浅银蓝）
    ctx.fillStyle = bc.gold;
    ctx.fillRect(20, 26, 8, 4);
    ctx.fillStyle = bc.workshopLight;
    ctx.fillRect(36, 30, 4, 8);

    // 齿轮
    ctx.fillStyle = '#5A6E7E';
    ctx.fillRect(28, 4, 8, 8);
    ctx.fillRect(30, 2, 4, 12);
    ctx.fillRect(26, 6, 12, 4);
  }

  private roundRect(ctx: OffscreenCanvasRenderingContext2D, x: number, y: number, w: number, h: number, r: number): void {
    ctx.beginPath();
    ctx.moveTo(x + r, y);
    ctx.lineTo(x + w - r, y);
    ctx.quadraticCurveTo(x + w, y, x + w, y + r);
    ctx.lineTo(x + w, y + h - r);
    ctx.quadraticCurveTo(x + w, y + h, x + w - r, y + h);
    ctx.lineTo(x + r, y + h);
    ctx.quadraticCurveTo(x, y + h, x, y + h - r);
    ctx.lineTo(x, y + r);
    ctx.quadraticCurveTo(x, y, x + r, y);
    ctx.closePath();
    ctx.fill();
  }

  // ── Tile Drawing ──

  private drawTile(tileId: number, variant: number): OffscreenCanvas {
    const canvas = new OffscreenCanvas(32, 32);
    const ctx = canvas.getContext('2d')!;
    ctx.imageSmoothingEnabled = false;

    const tc = TILE_COLORS;
    const dc = TILE_DETAIL_COLORS;

    if (tileId === 0) {
      // 草地：冷青绿（3种变体）
      const grassColors = [tc.grass, tc.grass2, tc.grass3];
      ctx.fillStyle = grassColors[variant % 3];
      ctx.fillRect(0, 0, 32, 32);
      // 冷色微光点（替代暖绿草丛）
      ctx.fillStyle = dc.grassDot;
      if (variant === 0) { ctx.fillRect(8, 10, 2, 4); ctx.fillRect(22, 20, 2, 4); }
      if (variant === 1) { ctx.fillRect(14, 6, 2, 4); ctx.fillRect(6, 24, 2, 4); }
      if (variant === 2) { ctx.fillRect(18, 14, 2, 4); ctx.fillRect(4, 8, 2, 4); }
      // 冷色小花
      if (variant === 0) { ctx.fillStyle = dc.flowerCold1; ctx.fillRect(26, 8, 2, 2); }
      if (variant === 1) { ctx.fillStyle = dc.flowerCold2; ctx.fillRect(10, 18, 2, 2); }
    } else if (tileId === 1) {
      // 路径：冷灰蓝石板
      ctx.fillStyle = variant === 0 ? tc.path : tc.path2;
      ctx.fillRect(0, 0, 32, 32);
      ctx.fillStyle = dc.pathLine;
      ctx.fillRect(4, 4, 2, 2);
      ctx.fillRect(20, 16, 2, 2);
      ctx.fillRect(12, 24, 2, 2);
    } else if (tileId === 2) {
      // 石砖：银灰
      ctx.fillStyle = tc.stone;
      ctx.fillRect(0, 0, 32, 32);
      ctx.fillStyle = dc.stoneLine;
      ctx.fillRect(0, 15, 32, 2);
      ctx.fillRect(15, 0, 2, 15);
      ctx.fillRect(8, 17, 2, 15);
      ctx.fillRect(24, 17, 2, 15);
    } else if (tileId === 3) {
      // 水面：靛蓝 + 冰蓝高光
      ctx.fillStyle = tc.water;
      ctx.fillRect(0, 0, 32, 32);
      ctx.fillStyle = dc.waterHighlight;
      ctx.fillRect(4, 4, 8, 2);
      ctx.fillRect(20, 14, 6, 2);
      ctx.fillStyle = dc.waterHighlight2;
      ctx.fillRect(12, 22, 4, 2);
    } else {
      // 深色装饰
      ctx.fillStyle = tc.dark;
      ctx.fillRect(0, 0, 32, 32);
    }

    return canvas;
  }
}
