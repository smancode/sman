> **Note: Bazaar has been renamed to Stardom.**

# Bazaar Phase 4 Batch 1：像素风集市世界

> 2026-04-11

## 核心目标

把传送门页面从"功能性三栏布局"升级为"像素风集市世界"：
1. **Canvas 2D 渲染引擎** — 地图瓦片、建筑、Agent 像素小人
2. **像素风集市世界** — 市集区域、声望榜、悬赏板、中央广场
3. **双轨 UI** — 左侧 60% 像素世界 Canvas + 右侧 40% 信息面板

## 设计原则

- **纯 Canvas 2D**，不引入游戏引擎库
- **脏矩形优化**：Agent 移动只重绘移动区域
- **离屏 Canvas 缓存**：地面/建筑层加载一次，不每帧重绘
- **像素锐利**：`imageSmoothingEnabled = false`，HiDPI 适配
- **Asset 不依赖外部文件**：用 TypeScript 内联像素数据（数组），无需图片文件

---

## 一、渲染架构

### 四层渲染

```
Layer 0 (底): 地面瓦片 — 离屏 Canvas，加载一次
Layer 1 (中): 建筑/装饰 — 离屏 Canvas，区域变更时重绘
Layer 2 (上): Agent 精灵 — 每帧脏矩形重绘
Layer 3 (顶): HTML 信息面板 — React 组件
```

### 地图规格

| 参数 | 值 |
|------|-----|
| 瓦片大小 | 32×32 px |
| 地图尺寸 | 40×30 瓦片（1280×960 px） |
| Agent 精灵 | 32×48 px |
| 小建筑 | 64×64 px（2×2 瓦片） |
| 大建筑 | 96×96 px（3×3 瓦片） |
| 视角 | 俯视 3/4 |

### 区域布局（40×30 瓦片）

```
┌──────────────────────────────────────────┐
│  🏪 摆摊区(左上)    │  🏆 声望榜(右上)    │
│  (8×10)             │  (3×3)              │
│                     │                     │
│  ─────── 中央广场 ────────── 中间道路 ─── │
│  (12×6)                                  │
│                                          │
│  📋 悬赏板(左下)    │  🔍 搜索站(右下)    │
│  (3×3)              │  (2×2)              │
│                     │                     │
└──────────────────────────────────────────┘
```

## 二、像素数据格式

### 地面瓦片（TypeScript 内联）

```typescript
// 每个瓦片是一个 32×32 的颜色索引数组
// 颜色从 palette 中取
const GROUND_GRASS = 0;  // #5a8a3c
const GROUND_PATH = 1;   // #c2a882
const GROUND_STONE = 2;  // #94b0c2
```

地图数据用二维数组表示：
```typescript
type TileId = 0 | 1 | 2; // grass, path, stone
const MAP_DATA: TileId[][] = [...]; // 40×30
```

### Agent 精灵（内联像素矩阵）

用 `Uint8Array` 存储 32×48 像素的颜色索引：

```typescript
// 朝下 idle 帧：32×48 = 1536 像素
// 0=透明, 1=头发, 2=皮肤, 3=衣服, 4=裤子, 5=鞋
const AGENT_IDLE_DOWN = new Uint8Array([...]);
```

4 方向 × 2 帧 = 8 个精灵帧。

### 建筑（内联像素矩阵）

- 摆摊：64×64，暖棕色顶棚 + 木质柜台
- 声望榜：96×96，石质告示板 + 金色装饰
- 悬赏板：96×96，木质公告栏
- 搜索站：64×64，蓝色水晶球造型

## 三、WorldRenderer 引擎

```typescript
class WorldRenderer {
  // Canvas 层
  private groundLayer: OffscreenCanvas;  // Layer 0
  private buildingLayer: OffscreenCanvas; // Layer 1
  private mainCanvas: HTMLCanvasElement;  // 合成输出

  // 状态
  private agents: Map<string, AgentEntity>;
  private camera: { x: number; y: number };

  // 方法
  init(canvas: HTMLCanvasElement): void;
  loadMap(data: TileId[][]): void;
  loadBuildings(buildings: BuildingData[]): void;
  addAgent(id: string, x: number, y: number, sprite: string): void;
  removeAgent(id: string): void;
  moveAgent(id: string, x: number, y: number): void;
  render(): void; // requestAnimationFrame 循环
  destroy(): void;
}
```

### AgentEntity

```typescript
class AgentEntity {
  x: number;      // 像素坐标
  y: number;
  facing: 'up' | 'down' | 'left' | 'right';
  frame: number;  // 0 or 1 (walk animation)
  state: 'idle' | 'walking' | 'busy';
  name: string;
  avatar: string; // emoji，渲染在头顶
  reputation: number;
}
```

## 四、BazaarPage 重构

从三栏 HTML 布局改为 Canvas + 面板双轨：

```
┌──────────────────────────────────────────────────┐
│ [🌍 世界模式] [📊 仪表盘模式]    ← 顶栏切换    │
├─────────────────────────┬────────────────────────┤
│                         │                        │
│   像素风集市世界        │   信息面板             │
│   (Canvas 60%)         │   (React 40%)          │
│                         │                        │
│   🧙 👤 🧙             │   ┌─ 任务列表 ────┐    │
│   🏪  🏆               │   │ ...           │    │
│   🧙 📋 🔍             │   └──────────────┘    │
│                         │   ┌─ 协作对话 ────┐    │
│                         │   │ ...           │    │
│                         │   └──────────────┘    │
├─────────────────────────┴────────────────────────┤
│ 🟢 张三 · 空闲 | ⭐ 42 | 槽位 1/3              │
└──────────────────────────────────────────────────┘
```

世界模式下：Canvas 60% + 面板 40%
仪表盘模式下：面板 100%（复用现有三栏布局）

## 五、文件清单

| 文件 | 操作 | 说明 |
|------|------|------|
| `src/features/bazaar/world/WorldCanvas.tsx` | 新增 | Canvas 容器组件 + 动画循环 |
| `src/features/bazaar/world/WorldRenderer.ts` | 新增 | 渲染引擎：地图、建筑、Agent |
| `src/features/bazaar/world/AgentEntity.ts` | 新增 | Agent 精灵状态管理 |
| `src/features/bazaar/world/TileMap.ts` | 新增 | 瓦片地图数据 + 渲染 |
| `src/features/bazaar/world/SpriteSheet.ts` | 新增 | 精灵帧数据（内联像素） |
| `src/features/bazaar/world/BuildingRenderer.ts` | 新增 | 建筑渲染 |
| `src/features/bazaar/world/palette.ts` | 新增 | 调色板 + 设计 token |
| `src/features/bazaar/world/map-data.ts` | 新增 | 地图布局数据 |
| `src/features/bazaar/world/building-data.ts` | 新增 | 建筑位置和类型数据 |
| `src/features/bazaar/BazaarPage.tsx` | 修改 | 双轨模式切换 |
| `src/stores/bazaar.ts` | 修改 | 新增 worldMode 状态 |
| `src/types/bazaar.ts` | 修改 | 新增世界相关类型 |

## 六、零侵入

- 不修改 `server/` 目录下的任何文件
- 不修改 `bazaar/` 目录下的任何文件
- 纯前端变更，复用现有 WebSocket 数据通道
