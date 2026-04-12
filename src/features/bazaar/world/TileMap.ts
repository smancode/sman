// src/features/bazaar/world/TileMap.ts
// 地面瓦片渲染 — 离屏 Canvas 缓存

import { DESIGN } from './palette';
import { getTileSprite } from './SpriteSheet';

const TS = DESIGN.TILE_SIZE;

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

        const hash = ((col * 7 + row * 13) & 0xFF) / 255;
        const variant = hash < 0.3 ? 0 : hash < 0.7 ? 1 : 2;
        const sprite = getTileSprite(tileId, variant);
        ctx.drawImage(sprite as OffscreenCanvas, x, y);
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
