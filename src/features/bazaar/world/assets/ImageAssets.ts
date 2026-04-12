// src/features/bazaar/world/assets/ImageAssets.ts
// PNG 图片素材加载器 — 加载预生成的 spritesheet PNG 文件
// 未来用于 mmx-cli 生成的素材

import type { AssetProvider, AppearanceParams, Facing } from './AssetProvider';
import { ProceduralAssets } from './ProceduralAssets';

export class ImageAssets implements AssetProvider {
  private loaded = false;
  private fallback = new ProceduralAssets();

  // Spritesheet images (loaded from PNG files)
  private buildingSheets = new Map<string, HTMLImageElement>();

  /** Load all assets. Returns true if all loaded successfully. */
  async load(_basePath: string): Promise<boolean> {
    // Future: load from basePath/generated/ directory
    this.loaded = false;
    return false;
  }

  getAgentSprite(appearance: AppearanceParams, facing: Facing, frame: number): OffscreenCanvas | HTMLCanvasElement {
    return this.fallback.getAgentSprite(appearance, facing, frame);
  }

  getBuildingSprite(type: string): OffscreenCanvas | HTMLCanvasElement {
    const img = this.buildingSheets.get(type);
    if (img) return this.cropBuilding(img, type);
    return this.fallback.getBuildingSprite(type);
  }

  getTileSprite(tileId: number, variant: number): OffscreenCanvas | HTMLCanvasElement {
    return this.fallback.getTileSprite(tileId, variant);
  }

  isReady(): boolean {
    return this.loaded;
  }

  private cropBuilding(img: HTMLImageElement, type: string): OffscreenCanvas {
    const isLarge = type === 'reputation' || type === 'bounty';
    const size = isLarge ? 96 : 64;
    const canvas = new OffscreenCanvas(size, size);
    const ctx = canvas.getContext('2d')!;
    ctx.drawImage(img, 0, 0, size, size);
    return canvas;
  }
}
