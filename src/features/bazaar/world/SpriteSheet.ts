// src/features/bazaar/world/SpriteSheet.ts
// 精灵帧入口 — 委托给 AssetProvider，不自己绘制

import type { AssetProvider, AppearanceParams, Facing } from './assets/AssetProvider';
import { ProceduralAssets } from './assets/ProceduralAssets';

// 默认使用程序化绘制
let provider: AssetProvider = new ProceduralAssets();

/** 切换素材提供者（换皮入口） */
export function setAssetProvider(p: AssetProvider): void {
  provider = p;
}

/** 获取当前素材提供者 */
export function getAssetProvider(): AssetProvider {
  return provider;
}

/** 获取 Agent 精灵帧 */
export function getAgentSprite(facing: Facing, frame: number, appearance: AppearanceParams): OffscreenCanvas | HTMLCanvasElement {
  return provider.getAgentSprite(appearance, facing, frame);
}

/** 获取建筑精灵 */
export function getBuildingSprite(type: string): OffscreenCanvas | HTMLCanvasElement {
  return provider.getBuildingSprite(type);
}

/** 获取地面瓦片精灵 */
export function getTileSprite(tileId: number, variant: number): OffscreenCanvas | HTMLCanvasElement {
  return provider.getTileSprite(tileId, variant);
}
