// src/features/bazaar/world/assets/ImageAssets.ts
// PNG 图片素材加载器 — 加载 mmx 生成的 spritesheet PNG

import type { AssetProvider, AppearanceParams, Facing } from './AssetProvider';
import { facingToRow } from './AssetProvider';
import { ProceduralAssets } from './ProceduralAssets';

const SPRITE_W = 32;
const SPRITE_H = 32;
const FRAMES = 4;

const BASE_PATH = '/bazaar/assets';

// Agent 外观组合 → spritesheet 文件映射
// 只加载 5 种预设 spritesheet，fallback 到 ProceduralAssets
const AGENT_SHEETS = [
  'agent_h0_o0', // blue + brown hair
  'agent_h1_o1', // green + black hair
  'agent_h2_o2', // red + blonde hair
  'agent_h3_o3', // purple + pink hair
  'agent_h4_o4', // orange + blue hair
] as const;

// outfitColor → spritesheet 索引 (循环)
function outfitToSheetIndex(outfitColor: number): number {
  return outfitColor % AGENT_SHEETS.length;
}

export class ImageAssets implements AssetProvider {
  private loaded = false;
  private fallback = new ProceduralAssets();

  private agentSheets = new Map<string, HTMLImageElement>();
  private buildingImages = new Map<string, HTMLImageElement>();
  private tileImages = new Map<string, HTMLImageElement>();

  async load(): Promise<boolean> {
    try {
      const promises: Promise<void>[] = [];

      // Load building images
      const buildingTypes = ['stall', 'reputation', 'bounty', 'search', 'workshop'];
      for (const type of buildingTypes) {
        promises.push(this.loadImage(`${BASE_PATH}/building_${type}.png`, (img) => {
          this.buildingImages.set(type, img);
        }));
      }

      // Load tile images (tileId 0-4, variant 0-2)
      for (let tileId = 0; tileId < 5; tileId++) {
        for (let variant = 0; variant < 3; variant++) {
          promises.push(this.loadImage(`${BASE_PATH}/tile_${tileId}_${variant}.png`, (img) => {
            this.tileImages.set(`${tileId}:${variant}`, img);
          }));
        }
      }

      // Load agent spritesheets (5 preset combos)
      for (const name of AGENT_SHEETS) {
        promises.push(this.loadImage(`${BASE_PATH}/${name}.png`, (img) => {
          this.agentSheets.set(name, img);
        }));
      }

      await Promise.all(promises);
      this.loaded = true;
      return true;
    } catch {
      this.loaded = false;
      return false;
    }
  }

  getAgentSprite(appearance: AppearanceParams, facing: Facing, frame: number): OffscreenCanvas | HTMLCanvasElement {
    const idx = outfitToSheetIndex(appearance.outfitColor);
    const sheetName = AGENT_SHEETS[idx];
    const img = this.agentSheets.get(sheetName);
    if (img) {
      const row = facingToRow(facing);
      const col = frame % FRAMES;
      const canvas = new OffscreenCanvas(SPRITE_W, SPRITE_H);
      const ctx = canvas.getContext('2d')!;
      ctx.drawImage(img, col * SPRITE_W, row * SPRITE_H, SPRITE_W, SPRITE_H, 0, 0, SPRITE_W, SPRITE_H);
      return canvas;
    }
    return this.fallback.getAgentSprite(appearance, facing, frame);
  }

  getBuildingSprite(type: string): OffscreenCanvas | HTMLCanvasElement {
    const img = this.buildingImages.get(type);
    if (img) {
      const isLarge = type === 'reputation' || type === 'bounty';
      const size = isLarge ? 96 : 64;
      const canvas = new OffscreenCanvas(size, size);
      const ctx = canvas.getContext('2d')!;
      ctx.drawImage(img, 0, 0, size, size);
      return canvas;
    }
    return this.fallback.getBuildingSprite(type);
  }

  getTileSprite(tileId: number, variant: number): OffscreenCanvas | HTMLCanvasElement {
    const img = this.tileImages.get(`${tileId}:${variant}`);
    if (img) {
      const canvas = new OffscreenCanvas(32, 32);
      const ctx = canvas.getContext('2d')!;
      ctx.drawImage(img, 0, 0, 32, 32);
      return canvas;
    }
    return this.fallback.getTileSprite(tileId, variant);
  }

  isReady(): boolean {
    return this.loaded;
  }

  private loadImage(src: string, onLoad: (img: HTMLImageElement) => void): Promise<void> {
    return new Promise((resolve) => {
      const img = new Image();
      img.onload = () => {
        // 预处理：把白色/近白色背景变透明
        const processed = this.makeTransparent(img);
        onLoad(processed);
        resolve();
      };
      img.onerror = () => resolve(); // skip missing assets silently
      img.src = src;
    });
  }

  /** 将图片中的白色/近白色像素变为透明 */
  private makeTransparent(img: HTMLImageElement): HTMLImageElement {
    const canvas = document.createElement('canvas');
    canvas.width = img.naturalWidth;
    canvas.height = img.naturalHeight;
    const ctx = canvas.getContext('2d')!;
    ctx.drawImage(img, 0, 0);

    const imageData = ctx.getImageData(0, 0, canvas.width, canvas.height);
    const data = imageData.data;
    const threshold = 240; // RGB 都 > 240 视为白色

    for (let i = 0; i < data.length; i += 4) {
      const r = data[i];
      const g = data[i + 1];
      const b = data[i + 2];
      if (r > threshold && g > threshold && b > threshold) {
        data[i + 3] = 0; // alpha = 0
      }
    }

    ctx.putImageData(imageData, 0, 0);

    // 转回 HTMLImageElement
    const result = new Image();
    result.src = canvas.toDataURL('image/png');
    return result;
  }
}
