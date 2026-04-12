# Bazaar 像素世界美术升级 实现计划

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将像素世界美术升级为开罗Q版风，建立素材层解耦架构，Agent 拥有组合式独特外观，4帧弹跳走路动画。

**Architecture:** AssetProvider 接口层将素材获取与渲染解耦。ProceduralAssets 作为 fallback 用 Canvas API 程序化绘制开罗风素材。appearance.ts 根据 Agent ID 哈希决定外观组合（发型×发色×肤色×服装色 = 1568种）。SpriteSheet 改为委托模式。

**Tech Stack:** TypeScript, Canvas 2D, mmx-cli (图片生成), Vitest

---

## File Structure

### New Files (5)

| File | Responsibility |
|------|---------------|
| `src/features/bazaar/world/assets/AssetProvider.ts` | 素材提供者接口 + 类型定义 |
| `src/features/bazaar/world/assets/ProceduralAssets.ts` | 开罗风程序化绘制实现 |
| `src/features/bazaar/world/assets/ImageAssets.ts` | PNG 图片素材加载器 |
| `src/features/bazaar/world/assets/appearance.ts` | Agent 外观组合系统（ID → 外观参数） |
| `src/features/bazaar/world/assets/__tests__/appearance.test.ts` | 外观系统单元测试 |

### Modified Files (6)

| File | Change |
|------|--------|
| `src/features/bazaar/world/palette.ts` | 大改：开罗风配色方案 |
| `src/features/bazaar/world/assets/ProceduralAssets.ts` | 新建：开罗风 Agent/建筑/地面程序化绘制 |
| `src/features/bazaar/world/SpriteSheet.ts` | 大改：委托给 AssetProvider |
| `src/features/bazaar/world/AgentEntity.ts` | 中改：4帧动画 + 弹跳 + appearance |
| `src/features/bazaar/world/TileMap.ts` | 中改：开罗风地面渲染 |
| `src/features/bazaar/world/WorldRenderer.ts` | 小改：弹跳偏移 + 新精灵尺寸 |
| `src/features/bazaar/world/WorldSync.ts` | 小改：appearance 参数传入 |

---

## Chunk 1: 基础架构（接口 + 外观系统 + 配色）

独立模块，无外部依赖。

### Task 1: palette.ts — 开罗风配色方案

**Files:**
- Modify: `src/features/bazaar/world/palette.ts`

- [ ] **Step 1: Replace entire palette.ts with Kairo-style colors**

```typescript
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
```

- [ ] **Step 2: Verify tsc**

Run: `npx tsc --noEmit`
Expected: Errors in SpriteSheet.ts, AgentEntity.ts, WorldRenderer.ts due to DESIGN.AGENT_H change — these will be fixed in later tasks. Note errors for reference.

- [ ] **Step 3: Commit**

```bash
git add src/features/bazaar/world/palette.ts
git commit -m "feat(bazaar): switch to Kairo-style warm color palette"
```

---

### Task 2: AssetProvider.ts — 素材提供者接口

**Files:**
- Create: `src/features/bazaar/world/assets/AssetProvider.ts`

- [ ] **Step 1: Create AssetProvider interface**

```typescript
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
```

- [ ] **Step 2: Verify tsc**

Run: `npx tsc --noEmit`
Expected: No new errors (file is standalone)

- [ ] **Step 3: Commit**

```bash
git add src/features/bazaar/world/assets/AssetProvider.ts
git commit -m "feat(bazaar): add AssetProvider interface for decoupled asset system"
```

---

### Task 3: appearance.ts — 外观组合系统

**Files:**
- Create: `src/features/bazaar/world/assets/appearance.ts`
- Create: `src/features/bazaar/world/assets/__tests__/appearance.test.ts`

- [ ] **Step 1: Write failing tests**

```typescript
// src/features/bazaar/world/assets/__tests__/appearance.test.ts
import { describe, it, expect } from 'vitest';
import { getAppearance, hashAgentId } from '../appearance';

describe('appearance', () => {
  describe('hashAgentId', () => {
    it('should return a non-negative integer', () => {
      const hash = hashAgentId('test-agent');
      expect(hash).toBeGreaterThanOrEqual(0);
      expect(Number.isInteger(hash)).toBe(true);
    });

    it('should be deterministic', () => {
      expect(hashAgentId('abc')).toBe(hashAgentId('abc'));
    });

    it('should differ for different IDs', () => {
      expect(hashAgentId('a')).not.toBe(hashAgentId('b'));
    });
  });

  describe('getAppearance', () => {
    it('should return valid ranges', () => {
      const ap = getAppearance('any-id');
      expect(ap.hairStyle).toBeGreaterThanOrEqual(0);
      expect(ap.hairStyle).toBeLessThan(8);
      expect(ap.hairColor).toBeGreaterThanOrEqual(0);
      expect(ap.hairColor).toBeLessThan(7);
      expect(ap.skinTone).toBeGreaterThanOrEqual(0);
      expect(ap.skinTone).toBeLessThan(4);
      expect(ap.outfitColor).toBeGreaterThanOrEqual(0);
      expect(ap.outfitColor).toBeLessThan(7);
    });

    it('should be deterministic', () => {
      expect(getAppearance('agent-1')).toEqual(getAppearance('agent-1'));
    });

    it('should produce different appearances for different IDs', () => {
      const a = getAppearance('agent-x');
      const b = getAppearance('agent-y');
      // At least one dimension should differ
      const same = a.hairStyle === b.hairStyle && a.hairColor === b.hairColor &&
                   a.skinTone === b.skinTone && a.outfitColor === b.outfitColor;
      expect(same).toBe(false);
    });
  });
});
```

- [ ] **Step 2: Run tests to verify failure**

Run: `npx vitest run src/features/bazaar/world/assets/__tests__/appearance.test.ts`
Expected: FAIL

- [ ] **Step 3: Implement appearance system**

```typescript
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
```

- [ ] **Step 4: Run tests to verify pass**

Run: `npx vitest run src/features/bazaar/world/assets/__tests__/appearance.test.ts`
Expected: ALL PASS

- [ ] **Step 5: Commit**

```bash
git add src/features/bazaar/world/assets/appearance.ts src/features/bazaar/world/assets/__tests__/appearance.test.ts
git commit -m "feat(bazaar): add appearance system with ID-based hash composition"
```

---

## Chunk 2: 开罗风程序化绘制

核心绘制逻辑，Chunk 3 的 SpriteSheet 委托模式依赖此 Chunk。

### Task 4: ProceduralAssets.ts — 开罗风 Agent 绘制

**Files:**
- Create: `src/features/bazaar/world/assets/ProceduralAssets.ts`

- [ ] **Step 1: Implement ProceduralAssets with Kairo-style drawing**

```typescript
// src/features/bazaar/world/assets/ProceduralAssets.ts
// 开罗风程序化绘制 — 大头小身子、点眼、圆润、暖色调
// Canvas API fallback，不依赖外部图片

import type { AssetProvider, AppearanceParams, Facing } from './AssetProvider';
import { facingToRow } from './AssetProvider';
import {
  HAIR_STYLES, HAIR_COLORS, SKIN_TONES, OUTFIT_COLORS,
  BUILDING_COLORS, TILE_COLORS,
} from '../palette';

const W = 32; // Agent sprite width
const H = 32; // Agent sprite height (Q版正方形)

type PixelColor = string | null;

export class ProceduralAssets implements AssetProvider {
  private agentCache = new Map<string, OffscreenCanvas>();
  private buildingCache = new Map<string, OffscreenCanvas>();
  private tileCache = new Map<string, OffscreenCanvas>();

  getAgentSprite(appearance: AppearanceParams, facing: Facing, frame: number): OffscreenCanvas {
    const key = `${appearance.hairStyle}:${appearance.hairColor}:${appearance.skinTone}:${appearance.outfitColor}:${facing}:${frame}`;
    let cached = this.agentCache.get(key);
    if (cached) return cached;

    cached = this.drawAgent(appearance, facing, frame);
    this.agentCache.set(key, cached);
    return cached;
  }

  getBuildingSprite(type: string): OffscreenCanvas {
    let cached = this.buildingCache.get(type);
    if (cached) return cached;
    cached = this.drawBuilding(type);
    this.buildingCache.set(type, cached);
    return cached;
  }

  getTileSprite(tileId: number, variant: number): OffscreenCanvas {
    const key = `${tileId}:${variant}`;
    let cached = this.tileCache.get(key);
    if (cached) return cached;
    cached = this.drawTile(tileId, variant);
    this.tileCache.set(key, cached);
    return cached;
  }

  isReady(): boolean { return true; }

  // ── Agent Drawing (Kairo Style) ──

  private drawAgent(ap: AppearanceParams, facing: Facing, frame: number): OffscreenCanvas {
    const canvas = new OffscreenCanvas(W, H);
    const ctx = canvas.getContext('2d')!;
    ctx.imageSmoothingEnabled = false;

    const skin = SKIN_TONES[ap.skinTone] as string;
    const hair = HAIR_COLORS[ap.hairColor] as string;
    const outfit = OUTFIT_COLORS[ap.outfitColor] as string;
    const eye = '#212121';
    const blush = '#FFB6C1';
    const outline = '#4E342E';
    const shoes = '#4E342E';

    // 弹跳偏移：帧1和帧3上移1像素
    const bounceY = (frame === 1 || frame === 3) ? -1 : 0;

    // ── 大圆头 (占上半部分 16px) ──
    // 头部轮廓（行1-8, 列5-10）
    const headRows: PixelColor[][] = [
      [null, null, null, hair, hair, hair, hair, null, null, null],   // row 0
      [null, null, hair, hair, hair, hair, hair, hair, null, null],   // row 1
      [null, outline, skin, skin, skin, skin, skin, skin, outline, null], // row 2
      [null, skin, skin, skin, skin, skin, skin, skin, skin, null],   // row 3
      [null, skin, skin, eye, skin, skin, eye, skin, skin, null],    // row 4: 点眼!
      [null, skin, skin, skin, skin, skin, skin, skin, skin, null],   // row 5
      [null, null, skin, skin, blush, skin, blush, skin, null, null], // row 6: 腮红
      [null, null, null, skin, skin, skin, skin, null, null, null],   // row 7
    ];

    // 根据方向微调眼睛位置
    if (facing === 'left') {
      headRows[4] = [null, skin, skin, null, skin, eye, skin, skin, skin, null];
    } else if (facing === 'right') {
      headRows[4] = [null, skin, skin, skin, eye, skin, null, skin, skin, null];
    } else if (facing === 'up') {
      headRows[4] = [null, skin, null, hair, skin, skin, hair, null, skin, null]; // 背面看到头发
    }

    // 绘制头部
    for (let row = 0; row < headRows.length; row++) {
      for (let col = 0; col < headRows[row].length; col++) {
        if (headRows[row][col]) {
          ctx.fillStyle = headRows[row][col]!;
          ctx.fillRect((col + 4) * 2, (row + 1 + bounceY) * 2, 2, 2);
        }
      }
    }

    // 发型覆盖（根据 hairStyle）
    this.drawHairOverlay(ctx, ap.hairStyle, hair, facing, bounceY);

    // ── 小身体 (下半部分 8px) ──
    const bodyStart = 18 + bounceY;
    // 服装（小身体）
    ctx.fillStyle = outline;
    ctx.fillRect(14, (bodyStart) * 2, 6, 2); // 领口线

    ctx.fillStyle = outfit;
    ctx.fillRect(12, (bodyStart + 1) * 2, 10, 2);
    ctx.fillRect(12, (bodyStart + 2) * 2, 10, 2);

    // 腿（走路动画）
    const legSpread = frame === 1 ? 2 : frame === 3 ? -2 : 0;
    // 左腿
    ctx.fillStyle = shoes;
    ctx.fillRect(14, (bodyStart + 3) * 2, 2, 2);
    ctx.fillRect(14 + legSpread, (bodyStart + 4) * 2, 2, 2);
    // 右腿
    ctx.fillRect(18, (bodyStart + 3) * 2, 2, 2);
    ctx.fillRect(18 - legSpread, (bodyStart + 4) * 2, 2, 2);

    return canvas;
  }

  private drawHairOverlay(ctx: OffscreenCanvasRenderingContext2D, style: number, color: string, facing: Facing, bounceY: number): void {
    const y0 = (1 + bounceY) * 2;
    ctx.fillStyle = color;

    switch (style) {
      case 0: // spiky - 尖刺
        ctx.fillRect(10, y0, 2, 2);
        ctx.fillRect(20, y0, 2, 2);
        ctx.fillRect(14, y0 - 2, 4, 2);
        break;
      case 1: // long - 长发（垂到肩膀）
        ctx.fillRect(8, y0 + 2, 2, 6);
        ctx.fillRect(22, y0 + 2, 2, 6);
        break;
      case 2: // curly - 卷发
        ctx.fillRect(8, y0, 2, 2);
        ctx.fillRect(22, y0, 2, 2);
        ctx.fillRect(12, y0 - 2, 2, 2);
        ctx.fillRect(18, y0 - 2, 2, 2);
        break;
      case 3: // twin - 双马尾
        ctx.fillRect(6, y0 + 2, 2, 8);
        ctx.fillRect(24, y0 + 2, 2, 8);
        break;
      case 4: // cap - 鸭舌帽
        ctx.fillRect(8, y0 + 2, 16, 2);
        ctx.fillRect(6, y0 + 4, 2, 2);
        break;
      case 5: // helmet - 头盔
        ctx.fillRect(8, y0, 16, 4);
        break;
      case 6: // pointed - 尖尖帽
        ctx.fillRect(14, y0 - 4, 4, 2);
        ctx.fillRect(12, y0 - 2, 8, 2);
        break;
      case 7: // bald - 光头（不画额外头发）
        break;
    }
  }

  // ── Building Drawing (Kairo Style) ──

  private drawBuilding(type: string): OffscreenCanvas {
    const isLarge = type === 'reputation' || type === 'bounty';
    const size = isLarge ? 96 : 64;
    const canvas = new OffscreenCanvas(size, size);
    const ctx = canvas.getContext('2d')!;
    ctx.imageSmoothingEnabled = false;

    const bc = BUILDING_COLORS;

    if (type === 'stall') {
      this.drawStall(ctx, bc);
    } else if (type === 'reputation') {
      this.drawReputation(ctx, bc);
    } else if (type === 'bounty') {
      this.drawBounty(ctx, bc);
    } else if (type === 'search') {
      this.drawSearch(ctx, bc);
    } else if (type === 'workshop') {
      this.drawWorkshop(ctx, bc);
    }

    return canvas;
  }

  private drawStall(ctx: OffscreenCanvasRenderingContext2D, bc: typeof BUILDING_COLORS): void {
    // 圆润顶棚（开罗风：圆弧形）
    ctx.fillStyle = bc.roof;
    // 圆弧顶
    ctx.beginPath();
    ctx.ellipse(32, 16, 30, 14, 0, Math.PI, 0);
    ctx.fill();
    ctx.fillStyle = bc.roofDark;
    ctx.fillRect(4, 16, 56, 4);

    // 条纹装饰
    ctx.fillStyle = bc.banner;
    for (let i = 0; i < 8; i++) {
      ctx.fillRect(i * 8 + 4, 6, 4, 8);
    }

    // 圆润柜台
    ctx.fillStyle = bc.wood;
    this.roundRect(ctx, 6, 28, 52, 20, 4);
    ctx.fillStyle = bc.gold;
    ctx.fillRect(6, 28, 52, 2); // 高光线

    // 可爱的小物品
    ctx.fillStyle = '#FF7043';
    ctx.fillRect(12, 22, 6, 6); // 小物件
    ctx.fillStyle = '#4CAF50';
    ctx.fillRect(26, 22, 6, 6);
    ctx.fillStyle = '#FFD700';
    ctx.fillRect(40, 22, 6, 6);

    // 小腿
    ctx.fillStyle = bc.woodDark;
    ctx.fillRect(10, 48, 4, 8);
    ctx.fillRect(50, 48, 4, 8);
  }

  private drawReputation(ctx: OffscreenCanvasRenderingContext2D, bc: typeof BUILDING_COLORS): void {
    // 圆润石质基座
    ctx.fillStyle = bc.stoneDark;
    this.roundRect(ctx, 12, 68, 72, 20, 6);
    ctx.fillStyle = bc.stone;
    this.roundRect(ctx, 16, 58, 64, 14, 4);

    // 圆角告示板
    ctx.fillStyle = '#FFF8E1';
    this.roundRect(ctx, 18, 10, 60, 48, 6);
    ctx.fillStyle = bc.stoneDark;
    ctx.fillRect(16, 6, 64, 4);
    ctx.fillRect(16, 56, 64, 4);

    // 金色皇冠装饰
    ctx.fillStyle = bc.gold;
    ctx.fillRect(38, 0, 20, 8);
    ctx.fillRect(42, 0, 12, 4);
    // 皇冠宝石
    ctx.fillStyle = '#FF5252';
    ctx.fillRect(46, 2, 4, 4);

    // 可爱星星
    ctx.fillStyle = bc.gold;
    const stars = [[30, 24], [50, 20], [40, 34], [56, 30], [30, 42], [50, 40]];
    stars.forEach(([x, y]) => {
      ctx.fillRect(x, y, 4, 4);
      ctx.fillRect(x + 1, y - 1, 2, 1);
    });

    // 圆柱
    ctx.fillStyle = bc.stoneDark;
    ctx.fillRect(18, 56, 6, 24);
    ctx.fillRect(72, 56, 6, 24);
  }

  private drawBounty(ctx: OffscreenCanvasRenderingContext2D, bc: typeof BUILDING_COLORS): void {
    // 圆润木质框架
    ctx.fillStyle = bc.woodDark;
    this.roundRect(ctx, 8, 6, 80, 62, 8);

    // 内板
    ctx.fillStyle = bc.wood;
    this.roundRect(ctx, 12, 10, 72, 54, 6);

    // 可爱小纸条
    ctx.fillStyle = '#FFF8E1';
    this.roundRect(ctx, 18, 16, 22, 16, 3);
    this.roundRect(ctx, 48, 14, 26, 18, 3);
    this.roundRect(ctx, 22, 40, 18, 16, 3);

    // 小钉子
    ctx.fillStyle = bc.gold;
    ctx.fillRect(27, 14, 2, 2);
    ctx.fillRect(59, 12, 2, 2);
    ctx.fillRect(29, 38, 2, 2);

    // 底座
    ctx.fillStyle = bc.woodDark;
    this.roundRect(ctx, 14, 68, 68, 20, 6);
    ctx.fillRect(18, 66, 6, 24);
    ctx.fillRect(72, 66, 6, 24);
  }

  private drawSearch(ctx: OffscreenCanvasRenderingContext2D, bc: typeof BUILDING_COLORS): void {
    // 圆润底座
    ctx.fillStyle = bc.stone;
    this.roundRect(ctx, 14, 46, 36, 14, 4);

    // 圆球（搜索站标志）
    ctx.fillStyle = bc.blue;
    ctx.beginPath();
    ctx.ellipse(32, 30, 16, 18, 0, 0, Math.PI * 2);
    ctx.fill();
    ctx.fillStyle = bc.blueDark;
    ctx.fillRect(16, 36, 32, 8);

    // 可爱高光
    ctx.fillStyle = '#B3E5FC';
    ctx.fillRect(24, 20, 8, 6);

    // 顶部星星
    ctx.fillStyle = bc.gold;
    ctx.fillRect(28, 4, 8, 6);
    ctx.fillRect(30, 2, 4, 2);
  }

  private drawWorkshop(ctx: OffscreenCanvasRenderingContext2D, bc: typeof BUILDING_COLORS): void {
    // 圆润底座
    ctx.fillStyle = bc.blueDark;
    this.roundRect(ctx, 14, 46, 36, 14, 4);

    // 工作台
    ctx.fillStyle = bc.blue;
    this.roundRect(ctx, 10, 18, 44, 28, 4);
    ctx.fillStyle = bc.stone;
    this.roundRect(ctx, 14, 22, 36, 20, 3);

    // 可爱工具
    ctx.fillStyle = bc.gold;
    ctx.fillRect(20, 26, 8, 4);
    ctx.fillRect(36, 30, 4, 8);

    // 齿轮
    ctx.fillStyle = '#757575';
    ctx.fillRect(28, 4, 8, 8);
    ctx.fillRect(30, 2, 4, 12);
    ctx.fillRect(26, 6, 12, 4);
  }

  private roundRect(ctx: OffscreenCanvasRenderingContext2D, x: number, y: number, w: number, h: number, r: number): void {
    ctx.beginPath();
    ctx.moveTo(x + r, y);
    ctx.lineTo(x + w - r, y);
    ctx.quadraticCurveTo(x + w, y, x + w, y + r);
    ctx.lineTo(x + w, y + h - r);
    ctx.quadraticCurveTo(x + w, y + h, x + w - r, y + h);
    ctx.lineTo(x + r, y + h);
    ctx.quadraticCurveTo(x, y + h, x, y + h - r);
    ctx.lineTo(x, y + r);
    ctx.quadraticCurveTo(x, y, x + r, y);
    ctx.closePath();
    ctx.fill();
  }

  // ── Tile Drawing (Kairo Style) ──

  private drawTile(tileId: number, variant: number): OffscreenCanvas {
    const canvas = new OffscreenCanvas(32, 32);
    const ctx = canvas.getContext('2d')!;
    ctx.imageSmoothingEnabled = false;

    const tc = TILE_COLORS;

    if (tileId === 0) {
      // 草地（3种变体）
      const grassColors = [tc.grass, tc.grass2, tc.grass3];
      ctx.fillStyle = grassColors[variant % 3];
      ctx.fillRect(0, 0, 32, 32);
      // 可爱草丛点缀
      ctx.fillStyle = '#7CB342';
      if (variant === 0) { ctx.fillRect(8, 10, 2, 4); ctx.fillRect(22, 20, 2, 4); }
      if (variant === 1) { ctx.fillRect(14, 6, 2, 4); ctx.fillRect(6, 24, 2, 4); }
      if (variant === 2) { ctx.fillRect(18, 14, 2, 4); ctx.fillRect(4, 8, 2, 4); }
      // 小花
      if (variant === 0) { ctx.fillStyle = '#FFD700'; ctx.fillRect(26, 8, 2, 2); }
      if (variant === 1) { ctx.fillStyle = '#FF8A80'; ctx.fillRect(10, 18, 2, 2); }
    } else if (tileId === 1) {
      // 路径（2种变体）
      ctx.fillStyle = variant === 0 ? tc.path : tc.path2;
      ctx.fillRect(0, 0, 32, 32);
      // 可爱纹理
      ctx.fillStyle = variant === 0 ? '#C8B8AE' : '#B0A094';
      ctx.fillRect(4, 4, 2, 2);
      ctx.fillRect(20, 16, 2, 2);
      ctx.fillRect(12, 24, 2, 2);
    } else if (tileId === 2) {
      // 石砖
      ctx.fillStyle = tc.stone;
      ctx.fillRect(0, 0, 32, 32);
      ctx.fillStyle = '#A0A0A0';
      ctx.fillRect(0, 15, 32, 2);
      ctx.fillRect(15, 0, 2, 15);
      ctx.fillRect(8, 17, 2, 15);
      ctx.fillRect(24, 17, 2, 15);
    } else if (tileId === 3) {
      // 水
      ctx.fillStyle = tc.water;
      ctx.fillRect(0, 0, 32, 32);
      ctx.fillStyle = '#81D4FA';
      ctx.fillRect(4, 4, 8, 2);
      ctx.fillRect(20, 14, 6, 2);
    } else {
      // 深色装饰
      ctx.fillStyle = tc.dark;
      ctx.fillRect(0, 0, 32, 32);
    }

    return canvas;
  }
}
```

- [ ] **Step 2: Verify tsc**

Run: `npx tsc --noEmit`
Expected: No errors from this file (it only depends on palette.ts and AssetProvider.ts)

- [ ] **Step 3: Commit**

```bash
git add src/features/bazaar/world/assets/ProceduralAssets.ts
git commit -m "feat(bazaar): add Kairo-style procedural asset drawing"
```

---

### Task 5: ImageAssets.ts — PNG 图片加载器

**Files:**
- Create: `src/features/bazaar/world/assets/ImageAssets.ts`

- [ ] **Step 1: Create ImageAssets**

```typescript
// src/features/bazaar/world/assets/ImageAssets.ts
// PNG 图片素材加载器 — 加载预生成的 spritesheet PNG 文件
// 未来用于 mmx-cli 生成的素材

import type { AssetProvider, AppearanceParams, Facing } from './AssetProvider';
import { facingToRow } from './AssetProvider';
import { ProceduralAssets } from './ProceduralAssets';

const SPRITE_W = 32;
const SPRITE_H = 32;
const FRAMES = 4;
const DIRECTIONS = 4;

export class ImageAssets implements AssetProvider {
  private loaded = false;
  private fallback = new ProceduralAssets();

  // Spritesheet images (loaded from PNG files)
  private agentSheets = new Map<string, HTMLImageElement>();
  private buildingSheets = new Map<string, HTMLImageElement>();
  private tileSheets = new Map<string, HTMLImageElement>();

  /** Load all assets. Returns true if all loaded successfully. */
  async load(basePath: string): Promise<boolean> {
    // Future: load from basePath/generated/ directory
    // For now, mark as loaded and use fallback
    this.loaded = false;
    return false;
  }

  getAgentSprite(appearance: AppearanceParams, facing: Facing, frame: number): OffscreenCanvas | HTMLCanvasElement {
    // If images not loaded, use procedural fallback
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
```

- [ ] **Step 2: Verify tsc**

Run: `npx tsc --noEmit`
Expected: No errors

- [ ] **Step 3: Commit**

```bash
git add src/features/bazaar/world/assets/ImageAssets.ts
git commit -m "feat(bazaar): add ImageAssets loader with procedural fallback"
```

---

## Chunk 3: SpriteSheet 委托 + Agent/Tile 适配

将 ProceduralAssets 接入现有系统。

### Task 6: SpriteSheet.ts — 委托给 AssetProvider

**Files:**
- Modify: `src/features/bazaar/world/SpriteSheet.ts`

- [ ] **Step 1: Rewrite SpriteSheet to delegate to AssetProvider**

Replace entire file content:

```typescript
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
```

- [ ] **Step 2: Verify tsc**

Run: `npx tsc --noEmit`
Expected: Errors in WorldRenderer.ts (calls getAgentSprite with old signature) — will fix in Task 8

- [ ] **Step 3: Commit**

```bash
git add src/features/bazaar/world/SpriteSheet.ts
git commit -m "feat(bazaar): delegate SpriteSheet to pluggable AssetProvider"
```

---

### Task 7: AgentEntity.ts — 4帧动画 + 弹跳 + appearance

**Files:**
- Modify: `src/features/bazaar/world/AgentEntity.ts`

- [ ] **Step 1: Add appearance field and update animation**

The key changes to `AgentEntity.ts`:

1. Add `import { getAppearance, type AppearanceParams } from './assets/appearance';` at top
2. Add `appearance: AppearanceParams;` field after `isSelf` field
3. In constructor, add `this.appearance = getAppearance(opts.id);`
4. Change animation: frame cycles 0-3 (was 0-1), timer threshold from 8 to 6
5. Add `bounceOffset` getter property

Update the constructor to initialize appearance:
```typescript
  appearance: AppearanceParams;

  constructor(opts: {
    id: string;
    name: string;
    avatar: string;
    reputation: number;
    x: number;
    y: number;
    shirtColor?: string;
  }) {
    this.id = opts.id;
    this.name = opts.name;
    this.avatar = opts.avatar;
    this.reputation = opts.reputation;
    this.x = opts.x;
    this.y = opts.y;
    this.targetX = opts.x;
    this.targetY = opts.y;
    this.shirtColor = opts.shirtColor ?? '#41a6f6';
    this.appearance = getAppearance(opts.id);
  }
```

Update the walk animation frame cycling (inside `if (dist > 2)` block, around line 96-99):
```typescript
      // 走路动画帧（4帧循环）
      this.animTimer++;
      if (this.animTimer >= 6) {  // was 8
        this.frame = (this.frame + 1) % 4;  // was 2
        this.animTimer = 0;
      }
```

Add bounceOffset getter after hitTest method:
```typescript
  /** 弹跳偏移（开罗风：走路时微弹跳） */
  get bounceOffset(): number {
    return (this.frame === 1 || this.frame === 3) ? -1 : 0;
  }
```

- [ ] **Step 2: Verify tsc**

Run: `npx tsc --noEmit`
Expected: Errors in WorldRenderer.ts (needs to pass appearance to getAgentSprite) — will fix in Task 8

- [ ] **Step 3: Commit**

```bash
git add src/features/bazaar/world/AgentEntity.ts
git commit -m "feat(bazaar): add 4-frame walk animation, bounce, and appearance system to AgentEntity"
```

---

### Task 8: WorldRenderer.ts + TileMap.ts — 适配新素材系统

**Files:**
- Modify: `src/features/bazaar/world/WorldRenderer.ts`
- Modify: `src/features/bazaar/world/TileMap.ts`

- [ ] **Step 1: Update WorldRenderer.renderAgent to use appearance and bounce**

In `renderAgent` method, change the `getAgentSprite` call:

From:
```typescript
    const sprite = getAgentSprite(agent.facing, agent.frame, agent.shirtColor);
    this.ctx.drawImage(
      sprite,
      screenX - DESIGN.AGENT_W / 2,
      screenY - DESIGN.AGENT_H + 8, // 脚底对齐
    );
```

To:
```typescript
    const sprite = getAgentSprite(agent.facing, agent.frame, agent.appearance);
    this.ctx.drawImage(
      sprite,
      screenX - DESIGN.AGENT_W / 2,
      screenY - DESIGN.AGENT_H + agent.bounceOffset, // Q版脚底对齐 + 弹跳
    );
```

- [ ] **Step 2: Update TileMap to use getTileSprite**

In `TileMap.ts`, replace the `render()` method. Import `getTileSprite` from `./SpriteSheet` and change the tile rendering loop:

Add import:
```typescript
import { getTileSprite } from './SpriteSheet';
```

Replace the render method body:
```typescript
  render(): OffscreenCanvas {
    if (!this.dirty) return this.offscreen;

    const ctx = this.offscreen.getContext('2d')!;
    ctx.imageSmoothingEnabled = false;

    for (let row = 0; row < this.rows; row++) {
      for (let col = 0; col < this.cols; col++) {
        const tileId = this.mapData[row]?.[col] ?? 0;
        const x = col * TS;
        const y = row * TS;

        const hash = ((col * 7 + row * 13) & 0xFF) / 255;
        const variant = hash < 0.3 ? 0 : hash < 0.7 ? 1 : 2;
        const sprite = getTileSprite(tileId, variant);
        ctx.drawImage(sprite as OffscreenCanvas, x, y);
      }
    }

    this.dirty = false;
    return this.offscreen;
  }
```

Remove the `TILE_COLOR_MAP`, `GRASS_VARIANTS`, `PATH_VARIANTS` constants (now handled by ProceduralAssets).

- [ ] **Step 3: Verify tsc**

Run: `npx tsc --noEmit`
Expected: No errors

- [ ] **Step 4: Commit**

```bash
git add src/features/bazaar/world/WorldRenderer.ts src/features/bazaar/world/TileMap.ts
git commit -m "feat(bazaar): adapt WorldRenderer and TileMap to new asset system"
```

---

## Chunk 4: WorldSync 适配 + 最终验证

### Task 9: WorldSync.ts — appearance 参数适配

**Files:**
- Modify: `src/features/bazaar/world/WorldSync.ts`

- [ ] **Step 1: Remove AGENT_COLORS and shirtColor usage**

In `WorldSync.ts`, remove the `AGENT_COLORS` constant (line 12). The AgentEntity constructor no longer needs `shirtColor` for appearance — it uses ID-based hashing now. But keep the field in constructor for backward compat.

No other changes needed — AgentEntity constructor already handles appearance via `getAppearance(opts.id)` internally.

- [ ] **Step 2: Verify tsc**

Run: `npx tsc --noEmit`
Expected: No errors

- [ ] **Step 3: Commit**

```bash
git add src/features/bazaar/world/WorldSync.ts
git commit -m "refactor(bazaar): remove hardcoded agent colors from WorldSync"
```

---

### Task 10: 最终验证

- [ ] **Step 1: Run all bazaar tests**

Run: `cd bazaar && npx vitest run`
Expected: ALL PASS

- [ ] **Step 2: Run main project tests**

Run: `npx vitest run`
Expected: ALL PASS

- [ ] **Step 3: Run tsc checks**

Run: `npx tsc --noEmit && cd bazaar && npx tsc --noEmit`
Expected: No errors

- [ ] **Step 4: Build check**

Run: `pnpm build`
Expected: Build succeeds

- [ ] **Step 5: Visual check — run dev server and verify**

Run: `pnpm dev`
Expected: Open browser, verify pixel world shows Kairo-style characters with big heads, dot eyes, warm colors, bouncy walk animation.

---

## File Dependency Graph

```
Task 1 (palette) ──────────────────────────────────┐
Task 2 (AssetProvider interface) ───────────────────┤
Task 3 (appearance system) ─────────────────────────┤
                                                     ↓
Task 4 (ProceduralAssets) ← depends on Task 1,2     │
Task 5 (ImageAssets) ← depends on Task 2,4          │
                                                     │
Task 6 (SpriteSheet delegate) ← depends on Task 2,4 │
Task 7 (AgentEntity) ← depends on Task 3             │
Task 8 (Renderer+TileMap) ← depends on Task 6,7     │
                                                     │
Task 9 (WorldSync) ← depends on Task 7               │
Task 10 (Final verify) ← depends on all              │
```

Chunk 1 (Tasks 1-3) are independent and can be parallelized.
Chunk 2 (Tasks 4-5) depends on Chunk 1.
Chunk 3 (Tasks 6-8) depends on Chunk 1-2.
Chunk 4 (Tasks 9-10) depends on all prior.
