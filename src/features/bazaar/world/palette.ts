// src/features/bazaar/world/palette.ts
// Classic 皮肤调色板 — 暖棕色 Zelda-like 风格

export const PALETTE = {
  // 基础色
  base: ['#1a1c2c', '#333c57', '#566c86', '#94b0c2', '#c2c3c7', '#f4f4f4'],
  // 暖色（角色、生活区）
  warm: ['#5a4a3a', '#7a6852', '#a88c6a', '#c2a882', '#e8d4b2', '#fff3e6'],
  // 冷色（工坊、搜索站）
  cool: ['#253c58', '#3a5a7c', '#4a8aaa', '#68b8d8', '#a8e4f0', '#c0f0ff'],
  // 强调色（任务、声望）
  accent: ['#b13e53', '#ef7d57', '#ffcd75', '#a7f070', '#38b764', '#41a6f6'],
  // UI 色
  ui: ['#000000', '#3a3a4a', '#7a7a8a', '#ff4444', '#ffcc44', '#44cc44'],
} as const;

// 快捷访问 — 地面瓦片颜色
export const TILE_COLORS = {
  grass:  '#5a8a3c',
  grass2: '#4a7a2c',
  path:   '#c2a882',
  path2:  '#b89870',
  stone:  '#94b0c2',
  water:  '#4a8aaa',
  dark:   '#333c57',
} as const;

// Agent 精灵颜色
export const AGENT_COLORS = {
  hair:    '#5a4a3a',
  skin:    '#e8d4b2',
  shirt:   '#41a6f6',
  shirt2:  '#38b764',
  pants:   '#333c57',
  shoes:   '#5a4a3a',
  outline: '#1a1c2c',
} as const;

// 建筑颜色
export const BUILDING_COLORS = {
  wood:     '#7a6852',
  woodDark: '#5a4a3a',
  roof:     '#b13e53',
  roofDark: '#8a2e3e',
  stone:    '#94b0c2',
  stoneDark:'#566c86',
  gold:     '#ffcd75',
  blue:     '#4a8aaa',
  blueDark: '#253c58',
  banner:   '#ef7d57',
} as const;

// 设计常量
export const DESIGN = {
  TILE_SIZE: 32,
  MAP_COLS: 40,
  MAP_ROWS: 30,
  AGENT_W: 32,
  AGENT_H: 48,
  SPRITE_SCALE: 1,
  FPS: 30,
} as const;
