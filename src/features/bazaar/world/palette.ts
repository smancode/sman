// src/features/bazaar/world/palette.ts
// 冷色奇幻学院配色 — Hogwarts 式深蓝+银+紫+冰蓝+金
// 设计文档: docs/superpowers/specs/2026-04-14-bazaar-world-visual-design.md

// ── 基础色板 ──

export const PALETTE = {
  // 深蓝基调
  base: ['#10141E', '#1A2332', '#1E2A3C', '#2A3448', '#34495E'],
  // 冷色系（建筑、路径）
  cool: ['#4A5568', '#4A5E6E', '#5A6E7E', '#6B5A7A', '#7B8FA8'],
  // 紫色系（神秘装饰）
  purple: ['#6B4E8D', '#8B6EAD', '#9B59B6', '#B88FA8'],
  // 冰蓝系（高光、魔法）
  ice: ['#4A6FA5', '#7BC0D4', '#A8D8EA', '#C8E8F4', '#D8F0F8'],
  // 强调色
  accent: ['#FFD700', '#E8C460', '#E8E8E8', '#9E9E9E'],
  // 冷调点缀（允许的低饱和暖色）
  muted: ['#2A4A42', '#34584E', '#5A4A3A', '#6B3A4A'],
} as const;

// ── 地面瓦片颜色（Hue-Shifting 色坡） ──
// 草地色坡: 暗青绿(170°) → 亮青(190°) — hue-shift +20°
// 路径色坡: 暗灰蓝(200°) → 亮银蓝(210°)
// 水面色坡: 暗靛(220°) → 亮冰蓝(195°) — 反向hue-shift，增加活力

export const TILE_COLORS = {
  // 草地：冷青绿 hue-shift 色坡（暗→亮，色相从170°偏移到190°）
  grass:  '#1E3E38',   // 明度18%，色相172°（最深）
  grass2: '#2A4E45',   // 明度24%，色相178°
  grass3: '#386558',   // 明度32%，色相183°
  grass4: '#4A7A6A',   // 明度40%，色相188°（最亮，用于点缀）
  // 路径：冷灰蓝 hue-shift 色坡
  path:   '#3A4E5E',   // 明度30%，色相205°
  path2:  '#4A5E6E',   // 明度36%，色相205°
  path3:  '#5A7080',   // 明度44%，色相205°（路径高光）
  // 石砖（广场）：银灰（最亮的地面）
  stone:  '#7B8FA8',   // 明度56%
  stone2: '#8598B0',   // 明度60%（石砖变体）
  // 水面：靛蓝 hue-shift（暗靛→亮冰蓝）
  water:  '#1A3A60',   // 明度22%，色相215°（深水）
  water2: '#2C5078',   // 明度32%，色相210°（浅水）
  // 深色装饰（边界/围栏）
  dark:   '#0E141E',   // 明度8%（极深蓝黑边界）
} as const;

// 统一暗色 infill — 所有地形内部填充共享此暗色
// 来源: Celeste 瓦片设计规则 — 统一infill让不同地形"属于同一世界"
export const INFILL_COLOR = '#162832';  // 明度14%，深青灰蓝

// ── 分区地面色（按瓦片坐标选择） ──

export const ZONE_COLORS = {
  // 北·市场街（偏紫灰）
  north: ['#2A3048', '#2E344A'],
  // 中央广场（银灰石砖）
  plaza: ['#7B8FA8', '#8595A8'],
  // 西·魔法水池（靛蓝，水面用 TILE_COLORS.water）
  west: ['#2C4E7B', '#345080'],
  // 南·任务区（冷灰蓝）
  south: ['#3A4F5F', '#3E5565'],
  // 东·基础草地（冷青绿）
  east: ['#2A4A42', '#305A50'],
} as const;

// ── Agent 外观 ──

export const HAIR_STYLES = ['spiky', 'long', 'curly', 'twin', 'cap', 'helmet', 'pointed', 'bald'] as const;
export const HAIR_COLORS = ['#2A3448', '#4A5568', '#6B4E8D', '#A8D8EA', '#C0C0C0', '#E8C460', '#8B6EAD'] as const;
export const SKIN_TONES = ['#E8D8C8', '#D4C4B4', '#C0B0A0', '#A89888'] as const;
export const OUTFIT_COLORS = ['#2C3E6B', '#6B4E8D', '#4A6FA5', '#34495E', '#A0AAB8', '#6B3A4A', '#7B8FA8'] as const;

export const AGENT_COLORS = {
  outline: '#1E2A3C',
  skin:    '#E8D8C8',
  eye:     '#1A2332',
  blush:   '#B88FA8',
  shirt:   '#2C3E6B',
  pants:   '#34495E',
  shoes:   '#1E2A3C',
  defaultShirt: '#2C3E6B',
} as const;

// ── 建筑颜色 ──

export const BUILDING_COLORS = {
  // 木质/暗色
  wood:     '#5A4A3A',
  woodDark: '#3A3A3A',
  // 屋顶
  roof:     '#6B4E8D',
  roofDark: '#4A3A6A',
  // 石材
  stone:    '#7B8FA8',
  stoneDark:'#5A6E7E',
  // 金属/装饰
  gold:     '#E8C460',
  goldBright:'#FFD700',
  // 布料/标签
  cloth:    '#E8E8E8',
  banner:   '#6B4E8D',
  // 蓝色系（搜索站、工坊）
  blue:     '#4A6FA5',
  blueDark: '#3A5A8A',
  blueLight:'#7BC0D4',
  // 工坊色
  workshop:    '#6B5A7A',
  workshopLight:'#B0C0D0',
  // 摊位帘幔（6种交替）
  stallCanopy: ['#6B4E8D', '#4A6FA5', '#A8D8EA', '#6B4E8D', '#7B8FA8', '#4A6FA5'] as const,
  // 摊位小物品（3种冷色）
  stallItem1: '#4A6FA5', // 蓝
  stallItem2: '#6B4E8D', // 紫
  stallItem3: '#E8C460', // 金
} as const;

// ── 瓦片绘制细节色（ProceduralAssets 用） ──

export const TILE_DETAIL_COLORS = {
  // 草地微光点（比草地稍亮）
  grassDot:  '#4A7A6A',
  grassDot2: '#5A8A7A',
  // 路径石缝（比路径稍暗）
  pathLine:  '#2E4050',
  pathLine2: '#3A5060',
  // 石砖缝（比石砖稍暗）
  stoneLine: '#6A7E98',
  // 水面高光像素
  waterHighlight: '#A8D8EA',
  waterHighlight2:'#C8E8F4',
  // 草地小花（冷色）
  flowerCold1: '#B88FA8',
  flowerCold2: '#A8D8EA',
  // 深色边
  darkBorder: '#1A2332',
} as const;

// ── UI 颜色（WorldRenderer 用） ──

export const UI_COLORS = {
  // 建筑标签
  labelBg:      '#1A2332',
  labelText:    '#E8E8E8',
  labelEmoji:   '#E8E8E8',
  // Agent 名字
  nameBg:       'rgba(26,35,50,0.9)',
  nameText:     '#E8E8E8',
  nameBorder:   'rgba(30,42,60,0.3)',
  nameFont:     'bold 11px monospace',
  avatarEmoji:  '#E8E8E8',
  avatarFont:   '12px serif',
  // hover
  hoverBuildingFill: 'rgba(168,216,234,0.2)',
  hoverBuildingStroke: 'rgba(255,215,0,0.7)',
  hoverAgentFill: 'rgba(168,216,234,0.1)',
  hoverAgentStroke: 'rgba(168,216,234,0.5)',
  hoverAgentRing: 'rgba(168,216,234,0.5)',
  // 脉冲动画（首次访问）
  pulseColor: 'rgba(255,255,255,0.3)',
  // 状态气泡
  bubbleBusyText:    '#9B59B6',
  bubbleBusyBg:      '#1A2332',
  bubbleBusyBorder:  '#9B59B6',
  bubbleIdleText:    '#7B8FA8',
  bubbleIdleBg:      'rgba(26,35,50,0.9)',
  bubbleIdleBorder:  '#4A5568',
  // 建筑emoji标签字体
  buildingEmojiFont: '14px serif',
  buildingLabelFont: 'bold 9px monospace',
} as const;

// ── 装饰物颜色（DecoLayer 用） ──

export const DECO_COLORS = {
  tree:     { main: '#2A4A3E', dark: '#1A3430', accent: '#3A6A5E' },
  lantern:  { main: '#4A5568', dark: '#2A3648', accent: '#A8D8EA' },
  flowers:  { main: '#B88FA8', dark: '#6B4E8D', accent: '#A8D8EA' },
  bush:     { main: '#2A4A3E', dark: '#1A3430', accent: '#B88FA8' },
  barrel:   { main: '#5A4A3A', dark: '#3A3A3A', accent: '#7B8FA8' },
  signpost: { main: '#5A4A3A', dark: '#3A3A3A', accent: '#7B8FA8' },
} as const;

// 装饰物描边
export const DECO_OUTLINE = '#1E2A3C';
// 树干
export const DECO_TREE_TRUNK = '#5A4A3A';
// 灯柱
export const DECO_LANTERN_POLE = '#4A5568';
// 花色（3色冷系）
export const DECO_FLOWER_COLORS = ['#B88FA8', '#A8D8EA', '#6B4E8D'] as const;
// 灯笼光晕
export const LANTERN_GLOW_COLOR = '#A8D8EA';
export const LANTERN_GLOW_INNER_ALPHA = 0.5;
export const LANTERN_GLOW_OUTER_ALPHA = 0.2;
export const LANTERN_GLOW_RADIUS_INNER = 4;
export const LANTERN_GLOW_RADIUS_OUTER = 10;

// ── 粒子颜色（ParticleLayer 用） ──

export const PARTICLE_COLORS = {
  // 环境冰晶
  ambient: ['#A8D8EA', '#C8E8F4', '#7BC0D4', '#B0C8D8'] as const,
  // 点击爆发
  burst: ['#A8D8EA', '#6B4E8D', '#C8E8F4', '#9B59B6'] as const,
} as const;

// ── Canvas 背景 ──

export const CANVAS_BG = '#10141E';

// ── 设计常量 ──

export const DESIGN = {
  TILE_SIZE: 32,
  MAP_COLS: 40,
  MAP_ROWS: 30,
  AGENT_W: 32,
  AGENT_H: 32,
  SPRITE_SCALE: 1,
  FPS: 30,
  WALK_FRAMES: 4,
} as const;
