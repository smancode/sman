> **Note: Bazaar has been renamed to Stardom.**

# Bazaar 像素世界美术升级：开罗Q版风设计文档

> 2026-04-12

## 核心目标

将当前简陋的程序化像素风升级为开罗游戏风格的Q版像素美术，同时建立素材层解耦架构，使美术素材可随时替换。

## 设计原则

- **素材层解耦**：SpriteSheet 只定义接口，不绑定素材来源。程序化绘制作为 fallback，AI 生成图片作为正式素材。
- **组合式外观**：Agent 外观由发型+发色+肤色+服装色 组合决定，通过 ID 哈希自动分配，1568 种组合。
- **开罗Q版风**：大头小身子（头占身高 1/2），点状眼睛，圆润造型，暖黄色系配色。
- **4帧走路动画**：增加弹跳感（开罗特色——走路时整体微微上下弹跳）。

## 一、素材规格

### Agent 精灵

| 类型 | 尺寸 | 帧数 | 说明 |
|------|------|------|------|
| Agent 完整精灵 | 32×32 | 4帧/方向 | spritesheet: 4列(帧) × 4行(方向) = 128×128 |
| 忙碌气泡 | 16×16 | 2帧 | 💬动画，叠加在头顶 |

4个方向：down(0), up(1), left(2), right(3)
4帧走路：stand(0), step-right-up(1), stand(2), step-left-up(3)

### 建筑精灵

| 建筑 | 尺寸 | 说明 |
|------|------|------|
| 摊位 | 64×64 | 6个摊位共享，颜色变体 |
| 声望榜 | 96×96 | 独特造型 |
| 悬赏板 | 96×96 | 独特造型 |
| 搜索站 | 64×64 | 独特造型 |
| 工坊 | 64×64 | 独特造型 |

### 地面瓦片

| 瓦片 | 尺寸 | 变体数 |
|------|------|--------|
| 草地 | 32×32 | 3种变化 |
| 路径 | 32×32 | 2种变化 |
| 石砖 | 32×32 | 1种 |
| 水 | 32×32 | 2帧动画 |
| 深色装饰 | 32×32 | 1种 |

## 二、组合式外观系统

### Agent 外观维度

```
外观 = hash(agentId) → {
  hairStyle: 0-7,    // 8种发型
  hairColor: 0-6,    // 7种发色
  skinTone: 0-3,     // 4种肤色
  outfitColor: 0-6,  // 7种服装色
}
```

组合数 = 8 × 7 × 4 × 7 = 1568 种

### 外观参数

```typescript
// 发型
const HAIR_STYLES = ['spiky', 'long', 'curly', 'twin', 'cap', 'helmet', 'pointed', 'bald'];

// 发色
const HAIR_COLORS = ['#5D4037', '#212121', '#FFB74D', '#F48FB1', '#7B68EE', '#4FC3F7', '#E52521'];

// 肤色
const SKIN_TONES = ['#FFE0BD', '#FFDAB9', '#D2A679', '#8D6E63'];

// 服装色
const OUTFIT_COLORS = ['#4CAF50', '#2196F3', '#E52521', '#FF9800', '#9C27B0', '#FFEB3B', '#00BCD4'];
```

## 三、素材架构

```
AssetProvider（接口）
  ├── getAgentSprite(appearance, facing, frame) → OffscreenCanvas | HTMLImageElement
  ├── getBuildingSprite(type) → OffscreenCanvas | HTMLImageElement
  ├── getTileSprite(tileId, variant) → OffscreenCanvas | HTMLImageElement
  └── isReady() → boolean

ProceduralAssets implements AssetProvider
  ← 当前：Canvas API 程序化绘制（改为开罗风），作为 fallback

ImageAssets implements AssetProvider
  ← 加载 mmx-cli 生成的 PNG 素材，按需组合
```

SpriteSheet 改为委托模式：

```typescript
// Before: SpriteSheet 自己绘制
export function getAgentSprite(facing, frame, color) { ... }

// After: SpriteSheet 委托给 AssetProvider
let provider: AssetProvider = new ProceduralAssets();
export function setAssetProvider(p: AssetProvider) { provider = p; }
export function getAgentSprite(facing, frame, appearance) { return provider.getAgentSprite(appearance, facing, frame); }
```

## 四、走路动画升级

### 从 2 帧提升到 4 帧

```
帧0: 站立（双脚并拢）
帧1: 右脚前（整体上移 1px —— 弹跳感）
帧2: 站立（双脚并拢）
帧3: 左脚前（整体上移 1px —— 弹跳感）
```

AgentEntity 的 frame 计时器间隔从 8 帧改为 6 帧（更快节奏感）。

### 动画帧索引

```typescript
// spritesheet 布局: 4列(帧) × 4行(方向)
// 方向: row 0=down, 1=up, 2=left, 3=right
// 帧: col 0,1,2,3
```

## 五、开罗风配色方案

```typescript
export const PALETTE = {
  // 暖黄色系基调
  base: ['#FFF8E1', '#FFECB3', '#FFE082', '#FFD54F', '#FFC107', '#FF8F00'],
  // 暖色建筑
  warm: ['#D7CCC8', '#BCAAA4', '#A1887F', '#8D6E63', '#6D4C41', '#4E342E'],
  // 活力色（UI、强调）
  accent: ['#FF7043', '#FF5252', '#E91E63', '#4CAF50', '#29B6F6', '#FFD700'],
  // 柔和色（环境）
  soft: ['#C8E6C9', '#B2DFDB', '#B3E5FC', '#F8BBD0', '#F0F4C3', '#FFF9C4'],
};

// 地面
export const TILE_COLORS = {
  grass:  '#8BC34A',
  grass2: '#9CCC65',
  grass3: '#AED581',
  path:   '#D7CCC8',
  path2:  '#BCAAA4',
  stone:  '#BDBDBD',
  water:  '#4FC3F7',
  dark:   '#5D4037',
};

// Agent
export const AGENT_COLORS = {
  outline: '#4E342E',
  skin:    '#FFE0BD',
  eye:     '#212121',
  blush:   '#FFB6C1',
  shirt:   '#4CAF50',
  pants:   '#6D4C41',
  shoes:   '#4E342E',
};

// 建筑
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
};
```

## 六、mmx-cli 素材生成策略

### Agent 素材生成

对每种组合维度单独生成 spritesheet，运行时按 appearance 参数组装：

**基础身体 spritesheet**（4肤色 × 4方向 × 4帧）：
```
prompt: "Pixel art character sprite sheet, Kairosoft chibi style, 
big round head tiny body dot eyes, 32x32 per frame, 
4 columns (walk cycle frames) x 4 rows (down up left right facing),
[skin_tone] skin, cute and round, transparent background, 
no anti-aliasing, clean pixel art"
```

**发型覆盖层 spritesheet**（8发型 × 7发色）：
```
prompt: "Pixel art hair overlay sprite sheet for chibi character,
Kairosoft style, 32x32 per frame, 
4 columns x 4 rows matching character layout,
[hair_style] [hair_color] hair, transparent background except hair pixels,
no anti-aliasing, clean pixel art"
```

**服装覆盖层**（7色）：
```
prompt: "Pixel art outfit overlay sprite sheet for chibi character,
Kairosoft style, 32x32 per frame,
4 columns x 4 rows matching character layout,
[outfit_color] cute outfit, transparent background except outfit pixels,
no anti-aliasing, clean pixel art"
```

运行时组装：body + hair overlay + outfit overlay → 最终 sprite

### 建筑素材生成

每种建筑单独生成一张 PNG。

### 地面瓦片生成

每种瓦片类型生成一张，变体通过代码旋转/色调偏移实现。

## 七、文件结构

### 新增文件

| 文件 | 职责 |
|------|------|
| `src/features/bazaar/world/assets/AssetProvider.ts` | 素材提供者接口 |
| `src/features/bazaar/world/assets/ProceduralAssets.ts` | 程序化绘制（开罗风 fallback） |
| `src/features/bazaar/world/assets/ImageAssets.ts` | PNG 图片加载器 |
| `src/features/bazaar/world/assets/appearance.ts` | 外观组合系统（ID→外观参数） |
| `src/features/bazaar/world/assets/__tests__/appearance.test.ts` | 外观系统测试 |

### 修改文件

| 文件 | 改动 |
|------|------|
| `src/features/bazaar/world/SpriteSheet.ts` | 大改：委托给 AssetProvider |
| `src/features/bazaar/world/palette.ts` | 大改：开罗风配色 |
| `src/features/bazaar/world/TileMap.ts` | 中改：支持图片瓦片 + 开罗风程序化 |
| `src/features/bazaar/world/AgentEntity.ts` | 中改：4帧动画 + 弹跳 + appearance 参数 |
| `src/features/bazaar/world/WorldRenderer.ts` | 小改：弹跳偏移、新的精灵尺寸 |
| `src/features/bazaar/world/WorldSync.ts` | 小改：appearance 参数传入 |

### 零侵入

- 不改 `CameraSystem.ts`、`InputPipeline.ts`、`InteractionSystem.ts`、`BuildingRegistry.ts`
- 不改 `WorldCanvas.tsx`、`BazaarPage.tsx`
- 不改服务端代码

## 八、数据流

```
AgentEntity(appearance: AppearanceParams)
  → SpriteSheet.getAgentSprite(facing, frame, appearance)
    → AssetProvider.getAgentSprite(appearance, facing, frame)
      → ImageAssets: 加载预组合的 PNG spritesheet → 裁剪对应帧
      → ProceduralAssets: Canvas API 实时绘制（开罗风）

WorldRenderer.renderAgent(agent)
  → agent.frame (0-3 循环)
  → agent.bounceOffset (帧1/3 时 = -1px)
  → getAgentSprite(agent.facing, agent.frame, agent.appearance)
  → drawImage(sprite, screenX, screenY + bounceOffset)
```
