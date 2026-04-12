// src/features/bazaar/world/SpriteSheet.ts
// Agent 精灵帧 — 用 Canvas API 程序化绘制像素小人
// 不依赖外部图片文件，纯代码绘制

import { AGENT_COLORS, BUILDING_COLORS, DESIGN } from './palette';

const TS = DESIGN.TILE_SIZE;

type Facing = 'down' | 'up' | 'left' | 'right';

// 程序化生成 Agent 精灵到离屏 Canvas
export function createAgentSprite(facing: Facing, frame: number, shirtColor: string = AGENT_COLORS.shirt): OffscreenCanvas {
  const w = DESIGN.AGENT_W;
  const h = DESIGN.AGENT_H;
  const canvas = new OffscreenCanvas(w, h);
  const ctx = canvas.getContext('2d')!;
  ctx.imageSmoothingEnabled = false;

  const c = {
    outline: AGENT_COLORS.outline,
    hair: AGENT_COLORS.hair,
    skin: AGENT_COLORS.skin,
    shirt: shirtColor,
    pants: AGENT_COLORS.pants,
    shoes: AGENT_COLORS.shoes,
  };

  const pixel = (x: number, y: number, color: string) => {
    ctx.fillStyle = color;
    ctx.fillRect(x, y, 2, 2);
  };

  // 32×48 像素，用 2px 为单位绘制 (16×24 格)
  const s = 2; // 像素单位大小

  // 头部 (行 0-5, 列 5-10)
  const drawHead = (ox: number) => {
    // 头发
    for (let i = 0; i < 5; i++) pixel(ox + 4*s + i*s, 0, c.hair);
    for (let i = 1; i < 4; i++) pixel(ox + 4*s + i*s, s, c.hair);
    // 脸
    for (let row = 2; row < 5; row++) {
      for (let col = 0; col < 5; col++) {
        pixel(ox + 4*s + col*s, row*s, c.skin);
      }
    }
    // 眼睛
    if (facing === 'down' || facing === 'left' || facing === 'right') {
      pixel(ox + 5*s, 3*s, c.outline);
      pixel(ox + 7*s, 3*s, c.outline);
    }
    // 轮廓
    pixel(ox + 3*s, 2*s, c.outline);
    pixel(ox + 9*s, 2*s, c.outline);
  };

  // 身体 (行 6-13)
  const drawBody = (ox: number) => {
    for (let row = 6; row < 12; row++) {
      for (let col = 1; col < 6; col++) {
        pixel(ox + 3*s + col*s, row*s, c.shirt);
      }
    }
    // 手臂
    const armOffset = frame === 1 ? s : 0;
    for (let row = 7; row < 11; row++) {
      pixel(ox + 3*s, (row + (row % 2 === 0 ? 0 : armOffset / s)) * s, c.skin);
      pixel(ox + 9*s, (row - (row % 2 === 0 ? 0 : armOffset / s)) * s, c.skin);
    }
  };

  // 腿 (行 14-19)
  const drawLegs = (ox: number) => {
    const legSpread = frame === 1 ? s : 0;
    for (let row = 14; row < 18; row++) {
      pixel(ox + 5*s - legSpread, row*s, c.pants);
      pixel(ox + 7*s + legSpread, row*s, c.pants);
    }
    // 鞋
    pixel(ox + 5*s - legSpread, 18*s, c.shoes);
    pixel(ox + 6*s - legSpread, 18*s, c.shoes);
    pixel(ox + 7*s + legSpread, 18*s, c.shoes);
    pixel(ox + 8*s + legSpread, 18*s, c.shoes);
  };

  drawHead(0);
  drawBody(0);
  drawLegs(0);

  return canvas;
}

// 预生成所有精灵帧缓存
const spriteCache = new Map<string, OffscreenCanvas>();

function cacheKey(facing: Facing, frame: number, color: string): string {
  return `${facing}:${frame}:${color}`;
}

export function getAgentSprite(facing: Facing, frame: number, shirtColor?: string): OffscreenCanvas {
  const color = shirtColor ?? AGENT_COLORS.shirt;
  const key = cacheKey(facing, frame, color);
  let sprite = spriteCache.get(key);
  if (!sprite) {
    sprite = createAgentSprite(facing, frame, color);
    spriteCache.set(key, sprite);
  }
  return sprite;
}

// 建筑渲染 — 程序化绘制
export function createBuildingSprite(type: string): OffscreenCanvas {
  const isLarge = type === 'reputation' || type === 'bounty';
  const size = isLarge ? 96 : 64;
  const canvas = new OffscreenCanvas(size, size);
  const ctx = canvas.getContext('2d')!;
  ctx.imageSmoothingEnabled = false;

  const bc = BUILDING_COLORS;
  const s = 2; // 像素单位

  if (type === 'stall') {
    // 木质摊位：顶棚 + 柜台
    // 顶棚
    ctx.fillStyle = bc.roof;
    ctx.fillRect(0, 0, 64, 20);
    ctx.fillStyle = bc.roofDark;
    ctx.fillRect(0, 20, 64, 4);
    // 条纹
    ctx.fillStyle = bc.banner;
    for (let i = 0; i < 8; i++) {
      ctx.fillRect(i * 8 + 2, 4, 4, 12);
    }
    // 柜台
    ctx.fillStyle = bc.wood;
    ctx.fillRect(4, 32, 56, 24);
    ctx.fillStyle = bc.woodDark;
    ctx.fillRect(4, 56, 56, 4);
    // 腿
    ctx.fillStyle = bc.woodDark;
    ctx.fillRect(8, 56, 4, 8);
    ctx.fillRect(52, 56, 4, 8);
  } else if (type === 'reputation') {
    // 声望榜：石质告示板 + 金色装饰
    // 底座
    ctx.fillStyle = bc.stoneDark;
    ctx.fillRect(8, 72, 80, 24);
    ctx.fillStyle = bc.stone;
    ctx.fillRect(12, 60, 72, 16);
    // 告示板
    ctx.fillStyle = '#f4f4f4';
    ctx.fillRect(16, 8, 64, 52);
    ctx.fillStyle = bc.stoneDark;
    ctx.fillRect(14, 4, 68, 4);
    ctx.fillRect(14, 60, 68, 4);
    // 金色装饰
    ctx.fillStyle = bc.gold;
    ctx.fillRect(40, 0, 16, 8);
    ctx.fillRect(44, 0, 8, 4);
    // 星星
    ctx.fillStyle = bc.gold;
    ctx.fillRect(30, 24, 4, 4);
    ctx.fillRect(48, 20, 4, 4);
    ctx.fillRect(38, 36, 4, 4);
    ctx.fillRect(56, 32, 4, 4);
    ctx.fillRect(30, 44, 4, 4);
    // 柱子
    ctx.fillStyle = bc.stoneDark;
    ctx.fillRect(16, 60, 6, 24);
    ctx.fillRect(74, 60, 6, 24);
  } else if (type === 'bounty') {
    // 悬赏板：木质公告栏
    // 底座
    ctx.fillStyle = bc.woodDark;
    ctx.fillRect(12, 72, 72, 24);
    // 公告板
    ctx.fillStyle = bc.wood;
    ctx.fillRect(8, 8, 80, 64);
    ctx.fillStyle = bc.woodDark;
    ctx.fillRect(6, 4, 84, 6);
    ctx.fillRect(6, 68, 84, 6);
    // 纸条
    ctx.fillStyle = '#f4f4f4';
    ctx.fillRect(16, 16, 24, 20);
    ctx.fillRect(48, 14, 28, 24);
    ctx.fillRect(20, 42, 20, 18);
    // 钉子
    ctx.fillStyle = '#7a7a8a';
    ctx.fillRect(26, 14, 2, 2);
    ctx.fillRect(60, 12, 2, 2);
    ctx.fillRect(28, 40, 2, 2);
    // 柱子
    ctx.fillStyle = bc.woodDark;
    ctx.fillRect(16, 68, 6, 24);
    ctx.fillRect(74, 68, 6, 24);
  } else if (type === 'search') {
    // 搜索站：蓝色水晶球
    // 底座
    ctx.fillStyle = bc.stone;
    ctx.fillRect(12, 48, 40, 16);
    // 球体
    ctx.fillStyle = bc.blue;
    ctx.fillRect(16, 12, 32, 36);
    ctx.fillStyle = bc.blueDark;
    ctx.fillRect(16, 40, 32, 8);
    // 光芒
    ctx.fillStyle = '#a8e4f0';
    ctx.fillRect(24, 20, 8, 8);
    ctx.fillRect(36, 24, 4, 4);
    // 顶部光芒
    ctx.fillStyle = bc.gold;
    ctx.fillRect(28, 4, 8, 8);
    ctx.fillRect(30, 0, 4, 4);
  } else if (type === 'workshop') {
    // 工坊：蓝色系工作台
    // 底座
    ctx.fillStyle = bc.blueDark;
    ctx.fillRect(12, 48, 40, 16);
    // 台面
    ctx.fillStyle = bc.blue;
    ctx.fillRect(8, 16, 48, 32);
    ctx.fillStyle = bc.stone;
    ctx.fillRect(12, 20, 40, 24);
    // 工具
    ctx.fillStyle = bc.gold;
    ctx.fillRect(20, 24, 8, 4);
    ctx.fillRect(36, 28, 4, 8);
    // 顶部齿轮
    ctx.fillStyle = '#7a7a8a';
    ctx.fillRect(28, 4, 8, 8);
    ctx.fillRect(30, 2, 4, 12);
    ctx.fillRect(26, 6, 12, 4);
  }

  return canvas;
}

// 建筑缓存
const buildingCache = new Map<string, OffscreenCanvas>();

export function getBuildingSprite(type: string): OffscreenCanvas {
  let sprite = buildingCache.get(type);
  if (!sprite) {
    sprite = createBuildingSprite(type);
    buildingCache.set(type, sprite);
  }
  return sprite;
}
