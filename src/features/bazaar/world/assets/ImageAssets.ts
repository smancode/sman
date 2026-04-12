// src/features/bazaar/world/assets/ImageAssets.ts
// PNG 图片素材加载器 — 加载预生成的 spritesheet PNG 文件

import type { AssetProvider, AppearanceParams, Facing } from './AssetProvider';
import { facingToRow } from './AssetProvider';
import { ProceduralAssets } from './ProceduralAssets';

const SPRITE_W = 32;
const SPRITE_H = 32;
const FRAMES = 4;
const DIRECTIONS = 4;

const BASE_PATH = '/bazaar/assets';

export class ImageAssets implements AssetProvider {
  private loaded = false;
  private fallback = new ProceduralAssets();

  // Spritesheet images
  private agentSheets = new Map<string, HTMLImageElement>();
  private buildingImages = new Map<string, HTMLImageElement>();
  private tileImages = new Map<string, HTMLImageElement>();

  /** Load all assets. Returns true if all loaded successfully. */
  async load(): Promise<boolean> {
    try {
      // Load building images
      const buildingTypes = ['stall', 'reputation', 'bounty', 'search', 'workshop'];
      const buildingPromises = buildingTypes.map(async (type) => {
        const img = new Image();
        img.src = `${BASE_PATH}/building_${type}.png`;
        await img.decode();
        this.buildingImages.set(type, img);
      });

      // Load tile images
      const tilePromises: Promise<void>[] = [];
      for (let tileId = 0; tileId < 5; tileId++) {
        for (let variant = 0; variant < 3; variant++) {
          const p = (async () => {
            const img = new Image();
            img.src = `${BASE_PATH}/tile_${tileId}_${variant}.png`;
            await img.decode();
            this.tileImages.set(`${tileId}:${variant}`, img);
          })();
          tilePromises.push(p);
        }
      }

      // Load agent spritesheets (8 hair × 7 outfit)
      const agentPromises: Promise<void>[] = [];
      for (let h = 0; h < 8; h++) {
        for (let o = 0; o < 7; o++) {
          const p = (async () => {
            const img = new Image();
            img.src = `${BASE_PATH}/agent_h${h}_o${o}.png`;
            await img.decode();
            this.agentSheets.set(`${h}:${o}`, img);
          })();
          agentPromises.push(p);
        }
      }

      await Promise.all([...buildingPromises, ...tilePromises, ...agentPromises]);
      this.loaded = true;
      return true;
    } catch {
      this.loaded = false;
      return false;
    }
  }

  getAgentSprite(appearance: AppearanceParams, facing: Facing, frame: number): OffscreenCanvas | HTMLCanvasElement {
    const key = `${appearance.hairStyle}:${appearance.outfitColor}`;
    const img = this.agentSheets.get(key);
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
    if (img) return this.cropBuilding(img, type);
    return this.fallback.getBuildingSprite(type);
  }

  getTileSprite(tileId: number, variant: number): OffscreenCanvas | HTMLCanvasElement {
    const img = this.tileImages.get(`${tileId}:${variant}`);
    if (img) return this.cropTile(img);
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

  private cropTile(img: HTMLImageElement): OffscreenCanvas {
    const canvas = new OffscreenCanvas(32, 32);
    const ctx = canvas.getContext('2d')!;
    ctx.drawImage(img, 0, 0, 32, 32);
    return canvas;
  }
}
