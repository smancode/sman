# 像素世界视觉升级设计

> **核心思路**: 美术是皮——用 mmx 生成 PNG 替换程序化矩形。渲染引擎已有 `AssetProvider` 换皮机制，只需生成素材 + 增加粒子/动画层。快速迭代，先出效果。

## 目标

让像素世界从"程序化硬画的丑方块"变成"AI 生成像素画，一眼想玩"。

## 架构（已就绪）

```
SpriteSheet (入口)
  → AssetProvider (接口)
    → ImageAssets (PNG 加载，fallback 到 ProceduralAssets)
    → ProceduralAssets (当前程序化渲染，作为 fallback)
```

`setAssetProvider()` 已实现，`ImageAssets` 已有 PNG 加载逻辑。**零架构改动**。

## 实现步骤

### Step 1: 生成像素美术资源

用 mmx image generate 批量生成，存入 `public/bazaar/assets/`。

**资源清单（最小可用集）**:

| 资源 | Prompt 关键词 | 尺寸 | 文件 |
|------|-------------|------|------|
| 草地瓦片×3变体 | "pixel art grass tile 32x32 top-down warm green with tiny flower dots" | 32×32 | `tile_0_0.png` ~ `tile_0_2.png` |
| 石路瓦片×3变体 | "pixel art stone path tile 32x32 top-down warm sandy cobblestone" | 32×32 | `tile_1_0.png` ~ `tile_1_2.png` |
| 水面瓦片×3变体 | "pixel art water tile 32x32 top-down blue shimmer wave" | 32×32 | `tile_2_0.png` ~ `tile_2_2.png` |
| 5种建筑精灵 | "pixel art top-down cozy building, 64x64, warm wood+roof, glowing window, chimney smoke, Kairo style" | 64×64 / 96×96 | `building_{type}.png` |
| Agent 精灵表 | "pixel art 32x32 spritesheet, 4 columns (frames) × 4 rows (directions), cute chibi character, big head, warm brown outline" | 128×128 | `agent_h{h}_o{o}.png` |
| 装饰物 | "pixel art tree/lantern/flower/bush/barrel, 16-32px, warm tones" | 16-32px | `deco_{name}.png` |

**生成流程**:
1. 每种资源用 mmx 生成 3-4 张，挑选最佳
2. 用图片编辑工具切成所需尺寸（spritesheet 用 canvas 切帧）
3. 放入 `public/bazaar/assets/`

### Step 2: 增加渲染层

在 `WorldRenderer` 的渲染循环中增加两个新层：

```
Layer 0: 地面瓦片 (已有)
Layer 0.5: 环境粒子 (新增 — 金色尘埃、落叶)
Layer 1: 建筑 (已有)
Layer 1.5: 装饰物 (新增 — 树/灯笼/花丛)
Layer 2: Agent 精灵 (已有)
Layer 2.5: Agent 状态气泡 + 老搭档标记 (新增)
Layer 3: 点击粒子爆发 (新增)
```

**粒子系统** (`ParticleLayer.ts`):
- 轻量数组管理，每帧更新位置+透明度
- 3种粒子: 金尘(slow float)、落叶(swirl down)、火花(burst on click)
- 最多 30 个常驻粒子 + 20 个临时爆发粒子
- 零依赖，纯 Canvas API

**Agent 状态气泡** (在 `renderAgent` 中):
- idle: "···" 小气泡，白色背景深棕描边
- busy: "⚙" 橙色气泡
- oldPartner: "★" 金色闪烁

**装饰物渲染** (`DecoLayer.ts`):
- 从 map-data 中读取装饰物位置
- 渲染装饰物 PNG，按 Y 排序与 Agent 混合

### Step 3: 交互增强

**Hover** (已有骨架，增强):
- 建筑: 黄色半透明填充高亮 + 描边 (当前只有描边)
- Agent: 放大 10% + 金色光环 (当前只有圆环)

**点击爆发** (新增):
- 点击 Agent 时在点击位置生成 8-12 个像素粒子
- 粒子 4 种颜色 (金/橙/黄/红)，向外扩散后下落
- 持续 0.5 秒，自动回收

### Step 4: 切换到 ImageAssets

在 `WorldCanvas.tsx` 初始化时:
```typescript
const imageAssets = new ImageAssets();
const ok = await imageAssets.load();
if (ok) {
  setAssetProvider(imageAssets);
}
// 失败自动 fallback 到 ProceduralAssets
```

## 文件改动清单

| 文件 | 动作 | 说明 |
|------|------|------|
| `public/bazaar/assets/*.png` | 新增 | mmx 生成的像素美术资源 |
| `src/features/bazaar/world/ParticleLayer.ts` | 新增 | 粒子系统 |
| `src/features/bazaar/world/DecoLayer.ts` | 新增 | 装饰物渲染 |
| `src/features/bazaar/world/WorldRenderer.ts` | 修改 | 集成粒子层+装饰层+状态气泡+点击爆发 |
| `src/features/bazaar/world/WorldCanvas.tsx` | 修改 | 切换 ImageAssets + 点击爆发触发 |
| `src/features/bazaar/world/map-data.ts` | 修改 | 增加装饰物位置数据 |
| `src/features/bazaar/world/assets/ImageAssets.ts` | 修改 | 适配新资源路径/尺寸 |

## 不做的事

- 不改 AssetProvider 接口
- 不改 SpriteSheet 入口
- 不做时间色调系统（P2，后续迭代）
- 不做水面波光动画（P2）
- 不做建筑阴影（P2）
- 美术资源不满意随时用 mmx 重新生成替换

## 验收标准

1. 像素世界使用 AI 生成的 PNG 资源渲染（不再是程序化矩形）
2. 有金色尘埃粒子在空中飘动
3. Agent 头顶显示状态气泡（idle/busy/老搭档）
4. Hover 建筑有半透明金色填充高亮
5. 点击 Agent 有像素粒子爆发
6. ProceduralAssets 作为 fallback 仍可用
7. 编译通过，现有测试不回归
