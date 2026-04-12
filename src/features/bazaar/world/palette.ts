// src/features/bazaar/world/palette.ts
// 开罗游戏风格配色 — 暖黄色系基调，圆润可爱

export const PALETTE = {
  // 暖黄色系基调
  base: ['#FFF8E1', '#FFECB3', '#FFE082', '#FFD54F', '#FFC107', '#FF8F00'],
  // 暖色（建筑、木质）
  warm: ['#D7CCC8', '#BCAAA4', '#A1887F', '#8D6E63', '#6D4C41', '#4E342E'],
  // 活力色（UI、强调）
  accent: ['#FF7043', '#FF5252', '#E91E63', '#4CAF50', '#29B6F6', '#FFD700'],
  // 柔和色（环境）
  soft: ['#C8E6C9', '#B2DFDB', '#B3E5FC', '#F8BBD0', '#F0F4C3', '#FFF9C4'],
  // UI 色
  ui: ['#212121', '#757575', '#BDBDBD', '#FF5252', '#FFD700', '#4CAF50'],
} as const;

// 地面瓦片颜色（开罗风：暖绿+暖沙）
export const TILE_COLORS = {
  grass:  '#8BC34A',
  grass2: '#9CCC65',
  grass3: '#AED581',
  path:   '#D7CCC8',
  path2:  '#BCAAA4',
  stone:  '#BDBDBD',
  water:  '#4FC3F7',
  dark:   '#5D4037',
} as const;

// Agent 外观维度（组合式系统）
export const HAIR_STYLES = ['spiky', 'long', 'curly', 'twin', 'cap', 'helmet', 'pointed', 'bald'] as const;
export const HAIR_COLORS = ['#5D4037', '#212121', '#FFB74D', '#F48FB1', '#7B68EE', '#4FC3F7', '#E52521'] as const;
export const SKIN_TONES = ['#FFE0BD', '#FFDAB9', '#D2A679', '#8D6E63'] as const;
export const OUTFIT_COLORS = ['#4CAF50', '#2196F3', '#E52521', '#FF9800', '#9C27B0', '#FFEB3B', '#00BCD4'] as const;

// Agent 默认精灵色（fallback，非组合式）
export const AGENT_COLORS = {
  outline: '#4E342E',
  skin:    '#FFE0BD',
  eye:     '#212121',
  blush:   '#FFB6C1',
  shirt:   '#4CAF50',
  pants:   '#6D4C41',
  shoes:   '#4E342E',
} as const;

// 建筑颜色（开罗风：圆润暖色）
export const BUILDING_COLORS = {
  wood:     '#A1887F',
  woodDark: '#6D4C41',
  roof:     '#FF7043',
  roofDark: '#E64A19',
  stone:    '#BDBDBD',
  stoneDark:'#757575',
  gold:     '#FFD700',
  cloth:    '#FFF8E1',
  banner:   '#FF5252',
  blue:     '#4FC3F7',
  blueDark: '#0288D1',
} as const;

// 设计常量
export const DESIGN = {
  TILE_SIZE: 32,
  MAP_COLS: 40,
  MAP_ROWS: 30,
  AGENT_W: 32,
  AGENT_H: 32,     // Q版：32x32（之前48）
  SPRITE_SCALE: 1,
  FPS: 30,
  WALK_FRAMES: 4,  // 4帧走路动画
} as const;
