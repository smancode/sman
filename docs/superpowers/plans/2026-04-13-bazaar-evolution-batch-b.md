# Bazaar 自我进化 - 批次 B（前端）实现计划

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 重做前端新用户引导和像素世界交互，让人 10 秒内理解集市在做什么

**Architecture:** 默认视图改为仪表盘，OnboardingGuide 从模态弹窗重写为非阻塞式卡片，WorldRenderer 和 InteractionSystem 增加 hover/tooltip 渲染

**Tech Stack:** React 19, TypeScript, TailwindCSS, HTML5 Canvas, Zustand

**Design Spec:** `docs/superpowers/specs/2026-04-13-bazaar-evolution-design.md`

---

## File Structure

| Action | File | Responsibility |
|--------|------|----------------|
| Modify | `src/features/bazaar/BazaarPage.tsx` | 默认视图改为 dashboard，命名统一 |
| Modify | `src/features/bazaar/OnboardingGuide.tsx` | 重写为非阻塞式引导卡片 |
| Modify | `src/features/bazaar/world/WorldRenderer.ts` | 增加 hover 高亮和 tooltip 渲染 |
| Modify | `src/features/bazaar/world/InteractionSystem.ts` | 增加 hover hit test |
| Modify | `src/features/bazaar/world/InputPipeline.ts` | 增加 mousemove hover 事件 |
| Modify | `src/features/bazaar/world/WorldCanvas.tsx` | 绑定 hover 事件 |
| Modify | `src/features/bazaar/world/types.ts` | 新增 tooltip 相关类型 |
| Test | `src/features/bazaar/world/__tests__/InteractionSystem.test.ts` | 测试 hover hit test |
| Test | `src/features/bazaar/world/__tests__/InputPipeline.test.ts` | 测试 hover 事件传递 |

---

## Chunk 1: 前端新用户引导（方向 5）

### Task 1: BazaarPage 默认视图改为仪表盘 + 命名统一

**Files:**
- Modify: `src/features/bazaar/BazaarPage.tsx`

- [ ] **Step 1: 修改默认 viewMode 为 dashboard**

在 `BazaarPage.tsx` 第 25 行，将：

```typescript
const [viewMode, setViewMode] = useState<ViewMode>('world');
```

改为：

```typescript
const [viewMode, setViewMode] = useState<ViewMode>('dashboard');
```

- [ ] **Step 2: 修改页面标题，统一为"集市"**

在第 63 行，将：

```typescript
<h2 className="text-lg font-semibold">传送门</h2>
```

改为：

```typescript
<h2 className="text-lg font-semibold">集市</h2>
```

- [ ] **Step 3: 运行 tsc 检查**

Run: `cd /Users/nasakim/projects/smanbase && npx tsc --noEmit`
Expected: 无错误

- [ ] **Step 4: Commit**

```bash
git add src/features/bazaar/BazaarPage.tsx
git commit -m "feat(bazaar): default to dashboard view, rename page to 集市"
```

---

### Task 2: 重写 OnboardingGuide 为非阻塞式引导卡片

**Files:**
- Modify: `src/features/bazaar/OnboardingGuide.tsx`

- [ ] **Step 1: 重写 OnboardingGuide.tsx**

完全重写为非阻塞式卡片（左上方显示，不遮挡操作）：

```typescript
// src/features/bazaar/OnboardingGuide.tsx
import { useState, useEffect } from 'react';
import { useBazaarStore } from '@/stores/bazaar';
import { Button } from '@/components/ui/button';
import { Sparkles, X, HelpCircle } from 'lucide-react';

const ONBOARDED_KEY = 'bazaar-onboarded';

export function OnboardingGuide() {
  const [open, setOpen] = useState(false);
  const { connection } = useBazaarStore();

  useEffect(() => {
    if (connection.connected && !localStorage.getItem(ONBOARDED_KEY)) {
      setOpen(true);
    }
  }, [connection.connected]);

  const dismiss = () => {
    localStorage.setItem(ONBOARDED_KEY, 'true');
    setOpen(false);
  };

  return (
    <>
      {/* 帮助按钮 — 始终显示在右上角 */}
      {!open && connection.connected && (
        <button
          onClick={() => setOpen(true)}
          className="absolute top-14 right-4 z-20 p-1.5 rounded-full bg-background/80 backdrop-blur-sm border border-border hover:bg-muted transition-colors"
          title="帮助"
        >
          <HelpCircle className="h-4 w-4 text-muted-foreground" />
        </button>
      )}

      {/* 引导卡片 — 非模态，左上方显示 */}
      {open && (
        <div className="absolute top-14 left-4 z-20 w-80 bg-background/95 backdrop-blur-sm border border-border rounded-lg shadow-lg">
          <div className="flex items-center justify-between p-3 border-b">
            <div className="flex items-center gap-2">
              <Sparkles className="h-4 w-4 text-primary" />
              <h3 className="text-sm font-semibold">欢迎来到集市</h3>
            </div>
            <Button variant="ghost" size="sm" className="h-5 w-5 p-0" onClick={dismiss}>
              <X className="h-3.5 w-3.5" />
            </Button>
          </div>

          <div className="p-3 space-y-2 text-xs text-muted-foreground">
            <p>你的 Agent 正在帮你自动协作。它会：</p>
            <ul className="list-disc list-inside space-y-0.5 ml-1">
              <li>遇到解决不了的问题时，搜索其他 Agent 求助</li>
              <li>找到合适的人后，自动发起协作对话</li>
              <li>你可以随时切换到「世界」视图查看所有 Agent 的位置</li>
            </ul>

            <div className="border-t pt-2 mt-2">
              <p className="font-medium text-foreground text-xs">协作模式控制 Agent 的自主程度：</p>
              <ul className="list-disc list-inside space-y-0.5 ml-1 mt-1">
                <li><span className="font-medium">全自动</span>：Agent 自行接任务，不打扰你</li>
                <li><span className="font-medium">半自动</span>：接任务前通知你，30秒无响应自动接（推荐）</li>
                <li><span className="font-medium">手动</span>：每一步都需要你确认</li>
              </ul>
            </div>
          </div>

          <div className="p-3 border-t">
            <Button size="sm" className="w-full text-xs h-7" onClick={dismiss}>
              知道了
            </Button>
          </div>
        </div>
      )}
    </>
  );
}
```

- [ ] **Step 2: 运行 tsc 检查**

Run: `cd /Users/nasakim/projects/smanbase && npx tsc --noEmit`
Expected: 无错误

- [ ] **Step 3: 运行前端测试（确保无破坏）**

Run: `cd /Users/nasakim/projects/smanbase && npx vitest run src/features/bazaar/`
Expected: ALL PASS

- [ ] **Step 4: Commit**

```bash
git add src/features/bazaar/OnboardingGuide.tsx
git commit -m "feat(bazaar): rewrite onboarding guide as non-blocking card with help button"
```

---

### Task 3: BazaarPage 仪表盘布局改造 + 未连接状态

**Files:**
- Modify: `src/features/bazaar/BazaarPage.tsx`

- [ ] **Step 1: 修改未连接状态的显示**

将未连接状态从覆盖像素世界改为显示仪表盘骨架（更友好）。在 `BazaarPage.tsx` 中，将 `!connection.connected` 的返回块改为：

```tsx
if (!connection.connected) {
  return (
    <div className="flex flex-col h-full relative">
      {/* 顶栏 */}
      <div className="flex items-center justify-between px-4 py-2 border-b bg-background/80 backdrop-blur-sm z-10">
        <div className="flex items-center gap-2">
          <Button variant="ghost" size="sm" onClick={() => navigate('/chat')}>
            <ArrowLeft className="h-4 w-4" />
          </Button>
          <h2 className="text-lg font-semibold">集市</h2>
        </div>
      </div>

      {/* 仪表盘骨架 — 提示配置 */}
      <div className="flex-1 flex items-center justify-center">
        <div className="text-center space-y-4">
          <p className="text-lg font-medium text-muted-foreground">未连接到集市服务器</p>
          <p className="text-sm text-muted-foreground/70">请在「设置」中配置集市服务器地址</p>
          <Button variant="outline" onClick={() => navigate('/settings')}>
            前往设置
          </Button>
        </div>
      </div>

      <AgentStatusBar />
    </div>
  );
}
```

- [ ] **Step 2: 改造 dashboard 布局为设计文档中的双栏样式**

将 dashboard 分支从三栏（1/3 + 1/3 + 1/3）改为设计文档中的双栏布局（左侧欢迎+任务 / 右侧协作对话）：

```tsx
) : (
  // 仪表盘模式：双栏布局（左: 欢迎+任务+在线Agent / 右: 协作对话）
  <div className="flex-1 flex overflow-hidden">
    {/* 左栏：欢迎 + 协作任务 + 在线 Agent */}
    <div className="w-1/2 border-r overflow-y-auto p-4 space-y-4">
      {/* 欢迎区域 */}
      <div className="bg-muted/50 rounded-lg p-4">
        <p className="font-medium">欢迎</p>
        <p className="text-sm text-muted-foreground mt-1">
          这是管理 Agent 协作的地方。你的 Agent 会自动搜索能力并帮你找到最合适的人。
        </p>
      </div>

      {/* 协作任务 */}
      <TaskPanel />

      {/* 在线 Agent */}
      <OnlineAgents />
    </div>

    {/* 右栏：协作对话 */}
    <div className="w-1/2 flex flex-col overflow-hidden">
      <CollaborationChat />
      <ControlBar />
    </div>
  </div>
)}
```

- [ ] **Step 3: 运行 tsc 检查**

Run: `cd /Users/nasakim/projects/smanbase && npx tsc --noEmit`
Expected: 无错误

- [ ] **Step 4: Commit**

```bash
git add src/features/bazaar/BazaarPage.tsx
git commit -m "feat(bazaar): dashboard two-column layout + disconnected skeleton"
```

---

## Chunk 2: 像素世界交互（方向 6）

### Task 4: types.ts 新增 hover/tooltip 类型

**Files:**
- Modify: `src/features/bazaar/world/types.ts`

- [ ] **Step 1: 新增 HoverInfo 类型**

在 `types.ts` 中新增：

```typescript
export interface HoverInfo {
  type: 'building' | 'agent';
  target: unknown;
  worldX: number;
  worldY: number;
}

export type TooltipData = {
  type: 'building';
  label: string;
} | {
  type: 'agent';
  name: string;
  avatar: string;
  status: string;
  reputation: number;
  isOldPartner: boolean;
};
```

- [ ] **Step 2: 运行 tsc**

Run: `cd /Users/nasakim/projects/smanbase && npx tsc --noEmit`
Expected: 无错误

- [ ] **Step 3: Commit**

```bash
git add src/features/bazaar/world/types.ts
git commit -m "feat(bazaar): add hover/tooltip type definitions"
```

---

### Task 5: InteractionSystem 增加 hover 检测方法

**Files:**
- Modify: `src/features/bazaar/world/InteractionSystem.ts`
- Test: `src/features/bazaar/world/__tests__/InteractionSystem.test.ts`

- [ ] **Step 1: 写失败测试 — hover hit test**

在 `InteractionSystem.test.ts` 末尾新增：

```typescript
describe('hover detection', () => {
  it('should detect building hover', () => {
    const result = interaction.hoverTest(worldX_for_building, worldY_for_building, []);
    expect(result).not.toBeNull();
    expect(result!.type).toBe('building');
  });

  it('should detect agent hover', () => {
    const agent = new AgentEntity({ id: 'a1', name: 'Test', avatar: '🧙', reputation: 0, isSelf: false });
    agent.x = 100;
    agent.y = 100;
    const result = interaction.hoverTest(100, 84, [agent]);
    expect(result).not.toBeNull();
    expect(result!.type).toBe('agent');
  });

  it('should return null when hovering nothing', () => {
    const result = interaction.hoverTest(0, 0, []);
    expect(result).toBeNull();
  });
});
```

- [ ] **Step 2: 运行测试验证失败**

Run: `cd /Users/nasakim/projects/smanbase && npx vitest run src/features/bazaar/world/__tests__/InteractionSystem.test.ts`
Expected: FAIL — hoverTest 不存在

- [ ] **Step 3: 在 InteractionSystem 中新增 hoverTest 方法**

```typescript
/**
 * Hover 检测 — 与 hitTest 相同逻辑但语义明确
 * 返回 hover 到的元素信息，无则返回 null
 */
hoverTest(worldX: number, worldY: number, agents: ReadonlyArray<AgentEntity>): HitResult | null {
  // 先检测建筑（建筑在下层，但 hover 优先级建筑 > agent）
  const buildingHit = this.hitTestBuildings(worldX, worldY);
  if (buildingHit) return buildingHit;

  // 再检测 agent
  return this.hitTestAgents(worldX, worldY, agents);
}
```

- [ ] **Step 4: 运行测试验证通过**

Run: `cd /Users/nasakim/projects/smanbase && npx vitest run src/features/bazaar/world/__tests__/InteractionSystem.test.ts`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/features/bazaar/world/InteractionSystem.ts src/features/bazaar/world/__tests__/InteractionSystem.test.ts
git commit -m "feat(bazaar): add hover detection to InteractionSystem"
```

---

### Task 6: InputPipeline 增加 hover 事件支持

**Files:**
- Modify: `src/features/bazaar/world/InputPipeline.ts`
- Test: `src/features/bazaar/world/__tests__/InputPipeline.test.ts`

- [ ] **Step 1: 写失败测试 — hover 事件传递**

在 `InputPipeline.test.ts` 中新增：

```typescript
it('should call onHover on mousemove when not dragging', () => {
  const onHover = vi.fn();
  const pipeline = new InputPipeline(renderer, camera, undefined, onHover);

  // 模拟 mouse move（不按下鼠标）
  pipeline.onMouseMove({ clientX: 200, clientY: 150, button: 0 } as MouseEvent);

  expect(onHover).toHaveBeenCalledWith(200, 150);
});
```

- [ ] **Step 2: 运行测试验证失败**

Run: `cd /Users/nasakim/projects/smanbase && npx vitest run src/features/bazaar/world/__tests__/InputPipeline.test.ts`
Expected: FAIL — constructor 不接受 onHover

- [ ] **Step 3: 修改 InputPipeline 构造函数增加 onHover 回调**

```typescript
export class InputPipeline {
  private renderer: WorldRenderer;
  private camera: CameraSystem;
  private handlers: InputHandler[] = [];
  private onGroundClick: ((worldX: number, worldY: number) => void) | null;
  private onHover: ((screenX: number, screenY: number) => void) | null;

  // ... 其他字段不变

  constructor(
    renderer: WorldRenderer,
    camera: CameraSystem,
    onGroundClick?: (worldX: number, worldY: number) => void,
    onHover?: (screenX: number, screenY: number) => void,
  ) {
    this.renderer = renderer;
    this.camera = camera;
    this.onGroundClick = onGroundClick ?? null;
    this.onHover = onHover ?? null;
  }

  // register 不变

  onMouseDown = (e: MouseEvent): void => {
    // 不变
  };

  onMouseMove = (e: MouseEvent): void => {
    // 拖拽逻辑不变...
    if (!this.mouseIsDown) {
      // 非按下状态 — hover 事件
      if (this.onHover) {
        const rect = (e.target as HTMLElement).getBoundingClientRect();
        this.onHover(e.clientX - rect.left, e.clientY - rect.top);
      }
      return;
    }

    // 原有拖拽逻辑...
    const dx = e.clientX - this.lastMoveX;
    const dy = e.clientY - this.lastMoveY;
    const totalDx = e.clientX - this.mouseDownX;
    const totalDy = e.clientY - this.mouseDownY;

    if (!this.isDragging && Math.sqrt(totalDx * totalDx + totalDy * totalDy) >= this.dragThreshold) {
      this.isDragging = true;
    }

    if (this.isDragging) {
      this.camera.panBy(dx, dy);
      this.lastMoveX = e.clientX;
      this.lastMoveY = e.clientY;
    }
  };

  // onMouseUp 不变
}
```

- [ ] **Step 4: 运行测试验证通过**

Run: `cd /Users/nasakim/projects/smanbase && npx vitest run src/features/bazaar/world/__tests__/InputPipeline.test.ts`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/features/bazaar/world/InputPipeline.ts src/features/bazaar/world/__tests__/InputPipeline.test.ts
git commit -m "feat(bazaar): add hover callback to InputPipeline for tooltip support"
```

---

### Task 7: WorldRenderer 增加 hover 高亮渲染

**Files:**
- Modify: `src/features/bazaar/world/WorldRenderer.ts`

- [ ] **Step 1: 在 WorldRenderer 中新增 hover 状态和高亮渲染**

```typescript
// 新增字段
private hoveredBuilding: BuildingData | null = null;
private hoveredAgent: AgentEntity | null = null;

// 新增方法
setHovered(building: BuildingData | null, agent: AgentEntity | null): void {
  this.hoveredBuilding = building;
  this.hoveredAgent = agent;
}
```

在 `renderBuildings` 方法中，建筑绘制之后增加高亮逻辑：

```typescript
// 在 this.ctx.drawImage(sprite, x, y); 之后
if (this.hoveredBuilding && this.hoveredBuilding.id === b.id) {
  this.ctx!.strokeStyle = 'rgba(255, 204, 68, 0.6)';
  this.ctx!.lineWidth = 2;
  this.ctx!.strokeRect(x, y, b.width, b.height);
}
```

在 `renderAgent` 方法中，精灵绘制之后增加高亮逻辑：

```typescript
// 在 agent.state === 'busy' 判断之后
if (this.hoveredAgent && this.hoveredAgent.id === agent.id) {
  this.ctx!.strokeStyle = 'rgba(255, 204, 68, 0.6)';
  this.ctx!.lineWidth = 2;
  this.ctx!.beginPath();
  this.ctx!.arc(screenX, screenY - 8, 20, 0, Math.PI * 2);
  this.ctx!.stroke();
}
```

在文件顶部增加 import：

```typescript
import type { BuildingData } from './map-data';
```

- [ ] **Step 2: 运行 tsc + 测试**

Run: `cd /Users/nasakim/projects/smanbase && npx tsc --noEmit && npx vitest run src/features/bazaar/`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add src/features/bazaar/world/WorldRenderer.ts
git commit -m "feat(bazaar): add hover highlight rendering for buildings and agents"
```

---

### Task 8: WorldCanvas 集成 hover 事件和 tooltip 显示

**Files:**
- Modify: `src/features/bazaar/world/WorldCanvas.tsx`
- Modify: `src/features/bazaar/BazaarPage.tsx` (tooltip 状态提升)

- [ ] **Step 1: 在 WorldCanvas 中绑定 hover 事件**

修改 WorldCanvas 的 useEffect 中 InputPipeline 的创建，增加 onHover 回调：

```typescript
const pipeline = new InputPipeline(renderer, camera, (wx, wy) => sync.moveSelfAgent(wx, wy), (screenX, screenY) => {
  // hover 事件：检测当前 hover 到什么
  const world = renderer.screenToWorld(screenX, screenY);
  const agents = renderer.getAllAgents();
  const hoverResult = interaction.hoverTest(world.x, world.y, agents);

  if (hoverResult) {
    if (hoverResult.type === 'building') {
      const building = hoverResult.target as typeof BUILDINGS[number];
      renderer.setHovered(building, null);
      onHover?.({ type: 'building', label: building.label });
    } else {
      const agent = hoverResult.target as import('./AgentEntity').AgentEntity;
      renderer.setHovered(null, agent);
      onHover?.({ type: 'agent', name: agent.name, avatar: agent.avatar, status: agent.state, reputation: agent.reputation, isOldPartner: false });
    }
  } else {
    renderer.setHovered(null, null);
    onHover?.(null);
  }
});
```

更新 WorldCanvasProps 接口：

```typescript
interface WorldCanvasProps {
  rendererRef: React.MutableRefObject<WorldRenderer | null>;
  onPanelChange?: (panel: ActivePanel) => void;
  onAgentClick?: (agent: { id: string; name: string; avatar: string; reputation: number }) => void;
  onHover?: (data: { type: 'building'; label: string } | { type: 'agent'; name: string; avatar: string; status: string; reputation: number; isOldPartner: boolean } | null) => void;
}
```

- [ ] **Step 2: 在 BazaarPage 中增加 tooltip 状态和渲染**

在 BazaarPage 中新增状态：

```typescript
const [tooltip, setTooltip] = useState<{
  type: 'building' | 'agent';
  data: { label: string } | { avatar: string; name: string; status: string; reputation: number; isOldPartner: boolean };
  x: number;
  y: number;
} | null>(null);
```

在世界视图区域增加 tooltip 渲染（在 WorldCanvas 旁边）：

```tsx
{/* Tooltip */}
{tooltip && (
  <div
    className="absolute z-30 pointer-events-none bg-background/95 backdrop-blur-sm border border-border rounded px-2 py-1 text-xs shadow-md"
    style={{ left: tooltip.x + 12, top: tooltip.y - 8 }}
  >
    {tooltip.type === 'building' && 'label' in tooltip.data && (
      <span>{tooltip.data.label}</span>
    )}
    {tooltip.type === 'agent' && 'name' in tooltip.data && (
      <div className="space-y-0.5">
        <div className="font-medium">{tooltip.data.avatar} {tooltip.data.name}</div>
        <div className="text-muted-foreground">
          状态: {tooltip.data.status === 'busy' ? '忙碌' : '空闲'} · 声望: {tooltip.data.reputation}
        </div>
        {tooltip.data.isOldPartner && (
          <div className="text-primary font-medium">[老搭档]</div>
        )}
      </div>
    )}
  </div>
)}
```

- [ ] **Step 3: 运行 tsc + 测试**

Run: `cd /Users/nasakim/projects/smanbase && npx tsc --noEmit && npx vitest run src/features/bazaar/`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add src/features/bazaar/world/WorldCanvas.tsx src/features/bazaar/BazaarPage.tsx
git commit -m "feat(bazaar): integrate hover events with tooltip display in world view"
```

---

### Task 9: 首次进入世界视图建筑脉冲动画

**Files:**
- Modify: `src/features/bazaar/world/WorldRenderer.ts`

- [ ] **Step 1: 在 WorldRenderer 中添加建筑脉冲动画**

在 `WorldRenderer` 中新增字段和方法：

```typescript
// 新增字段
private buildingPulseActive = false;
private buildingPulseStart = 0;
private static readonly BUILDING_PULSE_DURATION = 3000; // 3 秒

// 新增方法
startBuildingPulse(): void {
  this.buildingPulseActive = true;
  this.buildingPulseStart = performance.now();
}
```

在 `renderBuildings` 方法末尾，添加脉冲渲染逻辑：

```typescript
// 建筑脉冲提示动画（首次进入世界视图）
if (this.buildingPulseActive) {
  const elapsed = performance.now() - this.buildingPulseStart;
  if (elapsed > WorldRenderer.BUILDING_PULSE_DURATION) {
    this.buildingPulseActive = false;
  } else {
    const alpha = 0.3 * (1 - elapsed / WorldRenderer.BUILDING_PULSE_DURATION);
    const scale = 1 + 0.05 * Math.sin(elapsed / 200);
    this.ctx!.strokeStyle = `rgba(255, 204, 68, ${alpha})`;
    this.ctx!.lineWidth = 3;
    for (const b of BUILDINGS) {
      const bx = b.col * TS - cameraX;
      const by = b.row * TS - cameraY;
      const w = b.width * scale;
      const h = b.height * scale;
      this.ctx!.strokeRect(bx - (w - b.width) / 2, by - (h - b.height) / 2, w, h);
    }
  }
}
```

在 `WorldCanvas.tsx` 中，首次切换到世界视图时调用 `renderer.startBuildingPulse()`。修改 BazaarPage 中切换到 world 时触发：

```typescript
// 在 WorldCanvas useEffect 中，renderer.start() 之后：
const hasSeenWorld = localStorage.getItem('bazaar-world-seen');
if (!hasSeenWorld) {
  renderer.startBuildingPulse();
  localStorage.setItem('bazaar-world-seen', 'true');
}
```

- [ ] **Step 2: 运行 tsc + 测试**

Run: `cd /Users/nasakim/projects/smanbase && npx tsc --noEmit && npx vitest run src/features/bazaar/`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add src/features/bazaar/world/WorldRenderer.ts src/features/bazaar/world/WorldCanvas.tsx
git commit -m "feat(bazaar): building pulse animation on first world view visit"
```

---

### Task 10: 世界视图底部操作提示

**Files:**
- Modify: `src/features/bazaar/BazaarPage.tsx`

- [ ] **Step 1: 在世界视图模式下添加底部操作提示**

在世界视图的 canvas 区域内增加提示条：

```tsx
{/* 操作提示 */}
<div className="absolute bottom-0 left-0 w-[60%] text-center py-1 text-xs text-white/50 bg-black/20 pointer-events-none">
  拖拽平移 · 滚轮缩放 · 点击建筑查看功能 · 点击 Agent 查看详情
</div>
```

- [ ] **Step 2: 运行 tsc**

Run: `cd /Users/nasakim/projects/smanbase && npx tsc --noEmit`
Expected: 无错误

- [ ] **Step 3: Commit**

```bash
git add src/features/bazaar/BazaarPage.tsx
git commit -m "feat(bazaar): add world view interaction hints at bottom"
```

---

## Chunk 3: 集成验证

### Task 11: 全量测试验证

- [ ] **Step 1: 运行前端全部测试**

Run: `cd /Users/nasakim/projects/smanbase && npx vitest run src/features/bazaar/`
Expected: ALL PASS

- [ ] **Step 2: 运行 tsc 检查**

Run: `cd /Users/nasakim/projects/smanbase && npx tsc --noEmit`
Expected: 无错误

- [ ] **Step 3: 构建验证**

Run: `cd /Users/nasakim/projects/smanbase && pnpm build`
Expected: BUILD SUCCESS

- [ ] **Step 4: 最终提交**

```bash
git add -A
git commit -m "feat(bazaar): batch B complete - dashboard-first, non-blocking guide, world hover interaction"
```
