// src/features/bazaar/world/assets/AssetProvider.ts
// 素材提供者接口 — 渲染层只依赖此接口，不绑定素材来源

export type Facing = 'down' | 'up' | 'left' | 'right';

export interface AppearanceParams {
  hairStyle: number;   // 0-7
  hairColor: number;   // 0-6
  skinTone: number;    // 0-3
  outfitColor: number; // 0-6
}

/** 素材提供者接口 — 可插拔 */
export interface AssetProvider {
  /** Agent 精灵帧 */
  getAgentSprite(appearance: AppearanceParams, facing: Facing, frame: number): OffscreenCanvas | HTMLCanvasElement;

  /** 建筑精灵 */
  getBuildingSprite(type: string): OffscreenCanvas | HTMLCanvasElement;

  /** 地面瓦片 */
  getTileSprite(tileId: number, variant: number): OffscreenCanvas | HTMLCanvasElement;

  /** 素材是否就绪 */
  isReady(): boolean;
}

/** 方向 → spritesheet 行索引 */
export function facingToRow(facing: Facing): number {
  switch (facing) {
    case 'down': return 0;
    case 'up': return 1;
    case 'left': return 2;
    case 'right': return 3;
  }
}

/** 帧索引 → 是否弹跳（开罗风：帧1和帧3上移1px） */
export function isBounceFrame(frame: number): boolean {
  return frame === 1 || frame === 3;
}
