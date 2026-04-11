// src/features/bazaar/world/TileMap.ts
// 地面瓦片渲染 — 离屏 Canvas 缓存

import { TILE_COLORS, DESIGN } from './palette';
import type { MAP_DATA } from './map-data';

const TS = DESIGN.TILE_SIZE;

// 瓦片颜色映射
const TILE_COLOR_MAP: Record<number, string> = {
  0: TILE_COLORS.grass,
  1: TILE_COLORS.path,
  2: TILE_COLORS.stone,
  3: TILE_COLORS.water,
  4: TILE_COLORS.dark,
};

// 草地变化色（增加自然感）
const GRASS_VARIANTS = [TILE_COLORS.grass, TILE_COLORS.grass2, TILE_COLORS.grass];
const PATH_VARIANTS = [TILE_COLORS.path, TILE_COLORS.path2, TILE_COLORS.path];

export class TileMap {
  private offscreen: OffscreenCanvas;
  private dirty = true;
  private cols: number;
  private rows: number;
  private mapData: number[][];

  constructor(mapData: number[][]) {
    this.cols = mapData[0]?.length ?? DESIGN.MAP_COLS;
    this.rows = mapData.length;
    this.mapData = mapData;
    this.offscreen = new OffscreenCanvas(this.cols * TS, this.rows * TS);
  }

  render(): OffscreenCanvas {
    if (!this.dirty) return this.offscreen;

    const ctx = this.offscreen.getContext('2d')!;
    ctx.imageSmoothingEnabled = false;

    for (let row = 0; row < this.rows; row++) {
      for (let col = 0; col < this.cols; col++) {
        const tileId = this.mapData[row]?.[col] ?? 0;
        const x = col * TS;
        const y = row * TS;

        // 基础颜色
        const variants = tileId === 0 ? GRASS_VARIANTS : tileId === 1 ? PATH_VARIANTS : null;
        if (variants) {
          // 用坐标哈希选择变化色，产生自然纹理
          const hash = ((col * 7 + row * 13) & 0xFF) / 255;
          ctx.fillStyle = variants[hash < 0.3 ? 0 : hash < 0.7 ? 1 : 2];
        } else {
          ctx.fillStyle = TILE_COLOR_MAP[tileId] ?? TILE_COLORS.grass;
        }
        ctx.fillRect(x, y, TS, TS);

        // 草地点缀小像素
        if (tileId === 0) {
          const rng = ((col * 31 + row * 17) & 0xFF) / 255;
          if (rng < 0.3) {
            ctx.fillStyle = '#4a7a2c';
            ctx.fillRect(x + 8 + (rng * 16) | 0, y + 8 + (rng * 8) | 0, 2, 4);
          }
          if (rng > 0.7) {
            ctx.fillStyle = '#6a9a4c';
            ctx.fillRect(x + 4 + (rng * 12) | 0, y + 12, 2, 2);
          }
        }
      }
    }

    this.dirty = false;
    return this.offscreen;
  }

  invalidate(): void {
    this.dirty = true;
  }

  get width(): number {
    return this.cols * TS;
  }

  get height(): number {
    return this.rows * TS;
  }
}
