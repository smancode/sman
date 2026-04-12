// src/features/bazaar/world/assets/appearance.ts
// Agent 外观组合系统 — 根据 Agent ID 哈希决定外观参数
// 8发型 × 7发色 × 4肤色 × 7服装色 = 1568 种组合

import type { AppearanceParams } from './AssetProvider';

const HAIR_STYLES = 8;
const HAIR_COLORS = 7;
const SKIN_TONES = 4;
const OUTFIT_COLORS = 7;

/** 简单哈希：将字符串转为非负整数 */
export function hashAgentId(id: string): number {
  let hash = 0;
  for (let i = 0; i < id.length; i++) {
    const char = id.charCodeAt(i);
    hash = ((hash << 5) - hash) + char;
    hash = hash & hash; // Convert to 32bit integer
  }
  return Math.abs(hash);
}

/** 根据 Agent ID 获取外观参数 */
export function getAppearance(agentId: string): AppearanceParams {
  const h = hashAgentId(agentId);
  return {
    hairStyle: h % HAIR_STYLES,
    hairColor: (h >> 3) % HAIR_COLORS,
    skinTone: (h >> 6) % SKIN_TONES,
    outfitColor: (h >> 8) % OUTFIT_COLORS,
  };
}
