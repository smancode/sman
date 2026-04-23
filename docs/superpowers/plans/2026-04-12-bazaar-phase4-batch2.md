> **Note: Bazaar has been renamed to Stardom.**

# Bazaar Phase 4 Batch 2 Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a complete interactive pixel world with drag-to-pan camera, click-to-move agents, building interactions, server-side position tracking, and full data synchronization.

**Architecture:** Subsystem pipeline pattern — InputPipeline dispatches events through priority-ordered handlers. CameraSystem manages viewport, InteractionSystem handles hit testing, WorldSync bridges Zustand store to renderer. Server-side WorldState tracks positions with throttled broadcasting. Bridge forwards world.* messages end-to-end.

**Tech Stack:** TypeScript, Canvas 2D, Vitest, better-sqlite3, WebSocket, Zustand, React

---

## File Structure

### New Files (7)

| File | Responsibility |
|------|---------------|
| `src/features/bazaar/world/types.ts` | World interaction type definitions |
| `src/features/bazaar/world/CameraSystem.ts` | Camera pan, boundary clamp, viewport resize |
| `src/features/bazaar/world/BuildingRegistry.ts` | Building type → panel action mapping (pluggable) |
| `src/features/bazaar/world/InteractionSystem.ts` | Hit test: buildings (rect) + agents (circle) |
| `src/features/bazaar/world/InputPipeline.ts` | Mouse event → drag/click detection → handler pipeline |
| `src/features/bazaar/world/WorldSync.ts` | Store ↔ Renderer bidirectional data bridge |
| `bazaar/src/world-state.ts` | Server-side position tracking + zone management + throttled broadcast |

### Modified Files (11)

| File | Change |
|------|--------|
| `src/features/bazaar/world/map-data.ts` | BuildingData +width/height, BUILDINGS data update |
| `src/features/bazaar/world/AgentEntity.ts` | +isSelf, +hitTest(), disable auto-wander for self |
| `src/features/bazaar/world/WorldRenderer.ts` | Camera delegation, +screenToWorld(), +getCamera() |
| `src/features/bazaar/world/WorldCanvas.tsx` | Full rewrite: pipeline, sync, events, no demo agents |
| `src/features/bazaar/BazaarPage.tsx` | +activePanel state, panel switching on building click |
| `src/stores/bazaar.ts` | +worldPositions, +sendWorldMove, world.* push listeners |
| `src/types/bazaar.ts` | +WorldAgentPosition interface |
| `shared/bazaar-types.ts` | +World payload interfaces |
| `bazaar/src/world-state.ts` | (new) |
| `bazaar/src/message-router.ts` | +worldState param, +handleWorldMessage |
| `bazaar/src/index.ts` | +WorldState init, online/offline hooks |
| `server/bazaar/bazaar-bridge.ts` | +world.* forwarding (inbound + outbound) |

---

## Chunk 1: 基础模块（CameraSystem + BuildingRegistry + map-data + AgentEntity）

独立、无依赖的前端基础模块。

### Task 1: map-data.ts 新增 width/height

**Files:**
- Modify: `src/features/bazaar/world/map-data.ts`

- [ ] **Step 1: Add width/height to BuildingData and BUILDINGS**

In `map-data.ts`, update the interface and data:

```typescript
export interface BuildingData {
  id: string;
  type: 'stall' | 'reputation' | 'bounty' | 'search' | 'workshop' | 'mailbox';
  col: number;
  row: number;
  label: string;
  width: number;   // pixel width
  height: number;  // pixel height
}
```

Update BUILDINGS array — stalls/search/workshop = 64×64, reputation/bounty = 96×96:

```typescript
export const BUILDINGS: BuildingData[] = [
  { id: 'stall-1', type: 'stall', col: 2, row: 1, label: '摊位', width: 64, height: 64 },
  { id: 'stall-2', type: 'stall', col: 7, row: 1, label: '摊位', width: 64, height: 64 },
  { id: 'stall-3', type: 'stall', col: 12, row: 1, label: '摊位', width: 64, height: 64 },
  { id: 'stall-4', type: 'stall', col: 2, row: 4, label: '摊位', width: 64, height: 64 },
  { id: 'stall-5', type: 'stall', col: 7, row: 4, label: '摊位', width: 64, height: 64 },
  { id: 'stall-6', type: 'stall', col: 12, row: 4, label: '摊位', width: 64, height: 64 },
  { id: 'reputation', type: 'reputation', col: 33, row: 1, label: '🏆 声望榜', width: 96, height: 96 },
  { id: 'bounty', type: 'bounty', col: 3, row: 19, label: '📋 悬赏板', width: 96, height: 96 },
  { id: 'search', type: 'search', col: 33, row: 19, label: '🔍 搜索站', width: 64, height: 64 },
  { id: 'workshop', type: 'workshop', col: 33, row: 23, label: '🔧 工坊', width: 64, height: 64 },
];
```

- [ ] **Step 2: Verify build**

Run: `npx tsc --noEmit`
Expected: No errors

- [ ] **Step 3: Commit**

```bash
git add src/features/bazaar/world/map-data.ts
git commit -m "feat(bazaar): add width/height to BuildingData for hit testing"
```

---

### Task 2: types.ts — 世界交互类型

**Files:**
- Create: `src/features/bazaar/world/types.ts`

- [ ] **Step 1: Create types file**

```typescript
// src/features/bazaar/world/types.ts

export interface WorldAgentUpdate {
  agentId: string;
  x: number;
  y: number;
  state: 'idle' | 'walking' | 'busy';
  facing: 'up' | 'down' | 'left' | 'right';
}

export interface WorldZoneEvent {
  agentId: string;
  zone: string;
  action: 'enter' | 'leave';
}

export type ActivePanel = 'leaderboard' | 'tasks' | 'chat' | 'agents';
```

- [ ] **Step 2: Commit**

```bash
git add src/features/bazaar/world/types.ts
git commit -m "feat(bazaar): add world interaction type definitions"
```

---

### Task 3: CameraSystem — 相机平移 + 边界约束

**Files:**
- Create: `src/features/bazaar/world/CameraSystem.ts`
- Create: `src/features/bazaar/world/__tests__/CameraSystem.test.ts`

- [ ] **Step 1: Write failing tests**

Create `src/features/bazaar/world/__tests__/CameraSystem.test.ts`:

```typescript
import { describe, it, expect } from 'vitest';
import { CameraSystem } from '../CameraSystem';

describe('CameraSystem', () => {
  it('should clamp pan to map boundaries', () => {
    const cam = new CameraSystem(1280, 960);
    cam.setViewport(800, 600);
    // maxX = 1280-800 = 480, maxY = 960-600 = 360
    cam.panBy(9999, 9999);
    const { x, y } = cam.getOffset();
    expect(x).toBe(480);
    expect(y).toBe(360);
  });

  it('should not go below 0', () => {
    const cam = new CameraSystem(1280, 960);
    cam.setViewport(800, 600);
    cam.panBy(-9999, -9999);
    const { x, y } = cam.getOffset();
    expect(x).toBe(0);
    expect(y).toBe(0);
  });

  it('should center when viewport larger than map', () => {
    const cam = new CameraSystem(1280, 960);
    cam.setViewport(1600, 1200);
    const { x, y } = cam.getOffset();
    expect(x).toBe(-160); // -(1600-1280)/2
    expect(y).toBe(-120); // -(1200-960)/2
  });

  it('should center on a world point', () => {
    const cam = new CameraSystem(1280, 960);
    cam.setViewport(800, 600);
    cam.centerOn(640, 480);
    const { x, y } = cam.getOffset();
    expect(x).toBe(240); // 640 - 800/2
    expect(y).toBe(180); // 480 - 600/2
  });

  it('should update bounds on viewport resize', () => {
    const cam = new CameraSystem(1280, 960);
    cam.setViewport(800, 600);
    cam.panBy(9999, 0);
    expect(cam.getOffset().x).toBe(480);
    cam.setViewport(1000, 600);
    // maxX changed to 1280-1000 = 280, camera should clamp
    expect(cam.getOffset().x).toBe(280);
  });
});
```

- [ ] **Step 2: Run tests to verify failure**

Run: `npx vitest run src/features/bazaar/world/__tests__/CameraSystem.test.ts`
Expected: FAIL

- [ ] **Step 3: Implement CameraSystem**

Create `src/features/bazaar/world/CameraSystem.ts`:

```typescript
// src/features/bazaar/world/CameraSystem.ts

function clamp(value: number, min: number, max: number): number {
  return Math.max(min, Math.min(max, value));
}

export class CameraSystem {
  private mapWidth: number;
  private mapHeight: number;
  private cameraX = 0;
  private cameraY = 0;
  private viewportWidth = 0;
  private viewportHeight = 0;
  private maxX = 0;
  private maxY = 0;

  constructor(mapWidth: number, mapHeight: number) {
    this.mapWidth = mapWidth;
    this.mapHeight = mapHeight;
  }

  panBy(deltaX: number, deltaY: number): void {
    this.cameraX = clamp(this.cameraX - deltaX, 0, this.maxX);
    this.cameraY = clamp(this.cameraY - deltaY, 0, this.maxY);
  }

  centerOn(worldX: number, worldY: number): void {
    this.cameraX = clamp(worldX - this.viewportWidth / 2, 0, this.maxX);
    this.cameraY = clamp(worldY - this.viewportHeight / 2, 0, this.maxY);
  }

  getOffset(): { x: number; y: number } {
    return { x: this.cameraX, y: this.cameraY };
  }

  setViewport(width: number, height: number): void {
    this.viewportWidth = width;
    this.viewportHeight = height;
    this.maxX = Math.max(0, this.mapWidth - width);
    this.maxY = Math.max(0, this.mapHeight - height);

    if (width >= this.mapWidth) {
      this.cameraX = -(width - this.mapWidth) / 2;
    } else {
      this.cameraX = clamp(this.cameraX, 0, this.maxX);
    }

    if (height >= this.mapHeight) {
      this.cameraY = -(height - this.mapHeight) / 2;
    } else {
      this.cameraY = clamp(this.cameraY, 0, this.maxY);
    }
  }
}
```

- [ ] **Step 4: Run tests to verify pass**

Run: `npx vitest run src/features/bazaar/world/__tests__/CameraSystem.test.ts`
Expected: ALL PASS

- [ ] **Step 5: Commit**

```bash
git add src/features/bazaar/world/CameraSystem.ts src/features/bazaar/world/__tests__/CameraSystem.test.ts
git commit -m "feat(bazaar): add CameraSystem with pan, clamp, and viewport resize"
```

---

### Task 4: BuildingRegistry — 建筑注册表

**Files:**
- Create: `src/features/bazaar/world/BuildingRegistry.ts`
- Create: `src/features/bazaar/world/__tests__/BuildingRegistry.test.ts`

- [ ] **Step 1: Write failing tests**

Create `src/features/bazaar/world/__tests__/BuildingRegistry.test.ts`:

```typescript
import { describe, it, expect } from 'vitest';
import { BuildingRegistry } from '../BuildingRegistry';

describe('BuildingRegistry', () => {
  it('should return action for known building type', () => {
    const reg = new BuildingRegistry();
    expect(reg.getAction('reputation')).toEqual({ panel: 'leaderboard' });
  });

  it('should return null for unknown building type', () => {
    const reg = new BuildingRegistry();
    expect(reg.getAction('unknown')).toBeNull();
  });

  it('should allow overrides via constructor', () => {
    const reg = new BuildingRegistry({ search: { panel: 'chat' } });
    expect(reg.getAction('search')).toEqual({ panel: 'chat' });
    // others unchanged
    expect(reg.getAction('reputation')).toEqual({ panel: 'leaderboard' });
  });

  it('should allow runtime registration', () => {
    const reg = new BuildingRegistry();
    reg.register('mailbox', { panel: 'chat' });
    expect(reg.getAction('mailbox')).toEqual({ panel: 'chat' });
  });
});
```

- [ ] **Step 2: Run tests to verify failure**

Run: `npx vitest run src/features/bazaar/world/__tests__/BuildingRegistry.test.ts`
Expected: FAIL

- [ ] **Step 3: Implement BuildingRegistry**

Create `src/features/bazaar/world/BuildingRegistry.ts`:

```typescript
// src/features/bazaar/world/BuildingRegistry.ts

export interface BuildingAction {
  panel: 'leaderboard' | 'tasks' | 'chat' | 'agents';
}

const DEFAULT_ACTIONS: Record<string, BuildingAction> = {
  stall: { panel: 'tasks' },
  reputation: { panel: 'leaderboard' },
  bounty: { panel: 'tasks' },
  search: { panel: 'tasks' },
  workshop: { panel: 'tasks' },
};

export class BuildingRegistry {
  private actions: Map<string, BuildingAction>;

  constructor(overrides?: Record<string, BuildingAction>) {
    this.actions = new Map(Object.entries({ ...DEFAULT_ACTIONS, ...overrides }));
  }

  getAction(buildingType: string): BuildingAction | null {
    return this.actions.get(buildingType) ?? null;
  }

  register(buildingType: string, action: BuildingAction): void {
    this.actions.set(buildingType, action);
  }
}
```

- [ ] **Step 4: Run tests to verify pass**

Run: `npx vitest run src/features/bazaar/world/__tests__/BuildingRegistry.test.ts`
Expected: ALL PASS

- [ ] **Step 5: Commit**

```bash
git add src/features/bazaar/world/BuildingRegistry.ts src/features/bazaar/world/__tests__/BuildingRegistry.test.ts
git commit -m "feat(bazaar): add BuildingRegistry for pluggable building-to-panel mapping"
```

---

### Task 5: AgentEntity 改造 — isSelf + hitTest

**Files:**
- Modify: `src/features/bazaar/world/AgentEntity.ts`

- [ ] **Step 1: Add isSelf field and hitTest method**

In `AgentEntity.ts`, add after line 6 (`type AgentState = 'idle' | 'walking' | 'busy';`):

Add `isSelf` field after `shirtColor` field (after line 12):

```typescript
  isSelf: boolean = false;
```

Add `hitTest` method after `moveTo` method (after line 57):

```typescript
  /** 命中测试（圆形碰撞，半径 16px，中心在脚底上方 16px） */
  hitTest(worldX: number, worldY: number): boolean {
    const dx = worldX - this.x;
    const dy = worldY - (this.y - 16);
    return dx * dx + dy * dy <= 256;
  }
```

Modify `update()` method — insert after line 98 (`this.frame = 0;`) and before the wander timer block:

```typescript
      // 自己 Agent 不自动游走
      if (this.isSelf) return;
```

So the idle block becomes:

```typescript
    } else {
      this.x = this.targetX;
      this.y = this.targetY;
      this.state = 'idle';
      this.frame = 0;

      if (this.isSelf) return;

      this.wanderTimer++;
      if (this.wanderTimer >= this.wanderInterval) {
        this.wanderTimer = 0;
        this.wanderInterval = 120 + Math.random() * 180;
        this.wander(bounds);
      }
    }
```

- [ ] **Step 2: Verify build**

Run: `npx tsc --noEmit`
Expected: No errors

- [ ] **Step 3: Commit**

```bash
git add src/features/bazaar/world/AgentEntity.ts
git commit -m "feat(bazaar): add isSelf flag and hitTest to AgentEntity"
```

---

## Chunk 2: 交互系统（InteractionSystem + InputPipeline）

依赖 Chunk 1 的 BuildingRegistry、CameraSystem、AgentEntity.hitTest。

### Task 6: InteractionSystem — 命中测试

**Files:**
- Create: `src/features/bazaar/world/InteractionSystem.ts`
- Create: `src/features/bazaar/world/__tests__/InteractionSystem.test.ts`

- [ ] **Step 1: Write failing tests**

Create `src/features/bazaar/world/__tests__/InteractionSystem.test.ts`:

```typescript
import { describe, it, expect, beforeEach } from 'vitest';
import { InteractionSystem } from '../InteractionSystem';
import { BuildingRegistry } from '../BuildingRegistry';
import type { BuildingData } from '../map-data';

const TEST_BUILDINGS: BuildingData[] = [
  { id: 'stall-1', type: 'stall', col: 2, row: 1, label: '摊位', width: 64, height: 64 },
  { id: 'reputation', type: 'reputation', col: 33, row: 1, label: '🏆 声望榜', width: 96, height: 96 },
];

describe('InteractionSystem', () => {
  let system: InteractionSystem;

  beforeEach(() => {
    const registry = new BuildingRegistry();
    system = new InteractionSystem(TEST_BUILDINGS, registry);
  });

  describe('hitTestBuildings', () => {
    it('should hit a building at its tile position', () => {
      // stall-1 at col=2, row=1 → pixel (64, 32) to (128, 96)
      const result = system.hitTestBuildings(96, 64);
      expect(result).not.toBeNull();
      expect(result!.type).toBe('building');
      expect((result!.target as BuildingData).id).toBe('stall-1');
    });

    it('should hit building at edge', () => {
      const result = system.hitTestBuildings(64, 32);
      expect(result).not.toBeNull();
      expect((result!.target as BuildingData).id).toBe('stall-1');
    });

    it('should miss outside building', () => {
      const result = system.hitTestBuildings(10, 10);
      expect(result).toBeNull();
    });

    it('should hit reputation board (96x96)', () => {
      // col=33 → pixel 1056, row=1 → pixel 32
      const result = system.hitTestBuildings(1100, 80);
      expect(result).not.toBeNull();
      expect((result!.target as BuildingData).id).toBe('reputation');
    });
  });

  describe('handleBuildingClick', () => {
    it('should return panel action from registry', () => {
      const action = system.handleBuildingClick(TEST_BUILDINGS[0]);
      expect(action).toEqual({ panel: 'tasks' });
    });

    it('should return leaderboard for reputation', () => {
      const action = system.handleBuildingClick(TEST_BUILDINGS[1]);
      expect(action).toEqual({ panel: 'leaderboard' });
    });
  });
});
```

- [ ] **Step 2: Run tests to verify failure**

Run: `npx vitest run src/features/bazaar/world/__tests__/InteractionSystem.test.ts`
Expected: FAIL

- [ ] **Step 3: Implement InteractionSystem**

Create `src/features/bazaar/world/InteractionSystem.ts`:

```typescript
// src/features/bazaar/world/InteractionSystem.ts

import { DESIGN } from './palette';
import type { BuildingData } from './map-data';
import { BuildingRegistry, type BuildingAction } from './BuildingRegistry';
import type { AgentEntity } from './AgentEntity';

const TS = DESIGN.TILE_SIZE;

export interface HitResult {
  consumed: boolean;
  type: 'building' | 'agent';
  target: BuildingData | AgentEntity;
}

export class InteractionSystem {
  private buildings: BuildingData[];
  private registry: BuildingRegistry;

  constructor(buildings: BuildingData[], registry: BuildingRegistry) {
    this.buildings = buildings;
    this.registry = registry;
  }

  hitTestBuildings(worldX: number, worldY: number): HitResult | null {
    for (const b of this.buildings) {
      const bx = b.col * TS;
      const by = b.row * TS;
      if (worldX >= bx && worldX <= bx + b.width && worldY >= by && worldY <= by + b.height) {
        return { consumed: true, type: 'building', target: b };
      }
    }
    return null;
  }

  hitTestAgents(worldX: number, worldY: number, agents: ReadonlyArray<AgentEntity>): HitResult | null {
    for (const agent of agents) {
      if (agent.hitTest(worldX, worldY)) {
        return { consumed: true, type: 'agent', target: agent };
      }
    }
    return null;
  }

  handleBuildingClick(building: BuildingData): BuildingAction | null {
    return this.registry.getAction(building.type);
  }
}
```

- [ ] **Step 4: Run tests to verify pass**

Run: `npx vitest run src/features/bazaar/world/__tests__/InteractionSystem.test.ts`
Expected: ALL PASS

- [ ] **Step 5: Commit**

```bash
git add src/features/bazaar/world/InteractionSystem.ts src/features/bazaar/world/__tests__/InteractionSystem.test.ts
git commit -m "feat(bazaar): add InteractionSystem with building hit testing"
```

---

### Task 7: InputPipeline — 输入管道

**Files:**
- Create: `src/features/bazaar/world/InputPipeline.ts`
- Create: `src/features/bazaar/world/__tests__/InputPipeline.test.ts`

- [ ] **Step 1: Write failing tests**

Create `src/features/bazaar/world/__tests__/InputPipeline.test.ts`:

```typescript
import { describe, it, expect, beforeEach } from 'vitest';
import { InputPipeline } from '../InputPipeline';
import { CameraSystem } from '../CameraSystem';
import type { WorldRenderer } from '../WorldRenderer';

describe('InputPipeline', () => {
  let pipeline: InputPipeline;
  let camera: CameraSystem;
  let groundClicks: Array<{ x: number; y: number }>;
  let handlerResults: string[];

  function createMockRenderer(): WorldRenderer {
    return {
      screenToWorld: (sx: number, sy: number) => ({ x: sx, y: sy }),
    } as unknown as WorldRenderer;
  }

  beforeEach(() => {
    camera = new CameraSystem(1280, 960);
    camera.setViewport(800, 600);
    groundClicks = [];
    handlerResults = [];
    const renderer = createMockRenderer();
    pipeline = new InputPipeline(renderer, camera, (wx, wy) => {
      groundClicks.push({ x: wx, y: wy });
    });
  });

  function fakeMouseDown(x: number, y: number) {
    pipeline.onMouseDown({ clientX: x, clientY: y, button: 0, target: document.body } as MouseEvent);
  }
  function fakeMouseMove(x: number, y: number) {
    pipeline.onMouseMove({ clientX: x, clientY: y, target: document.body } as MouseEvent);
  }
  function fakeMouseUp(x: number, y: number) {
    pipeline.onMouseUp({ clientX: x, clientY: y, button: 0, target: document.body } as MouseEvent);
  }

  it('should detect click and trigger ground click', () => {
    fakeMouseDown(100, 100);
    fakeMouseUp(102, 101); // moved 2px < threshold
    expect(groundClicks).toHaveLength(1);
  });

  it('should NOT trigger ground click on drag', () => {
    fakeMouseDown(100, 100);
    fakeMouseMove(150, 150);
    fakeMouseUp(150, 150);
    expect(groundClicks).toHaveLength(0);
  });

  it('should pan camera on drag', () => {
    const before = { ...camera.getOffset() };
    fakeMouseDown(100, 100);
    fakeMouseMove(150, 150);
    const after = camera.getOffset();
    expect(after.x).not.toBe(before.x);
    expect(after.y).not.toBe(before.y);
  });

  it('should let first handler consume and prevent ground click', () => {
    pipeline.register(() => {
      handlerResults.push('h1');
      return { consumed: true, type: 'building' as const, target: {} as any };
    });
    fakeMouseDown(100, 100);
    fakeMouseUp(101, 100);
    expect(handlerResults).toEqual(['h1']);
    expect(groundClicks).toHaveLength(0);
  });

  it('should pass to next handler if first does not consume', () => {
    pipeline.register(() => {
      handlerResults.push('h1');
      return null;
    });
    pipeline.register(() => {
      handlerResults.push('h2');
      return { consumed: true, type: 'agent' as const, target: {} as any };
    });
    fakeMouseDown(100, 100);
    fakeMouseUp(101, 100);
    expect(handlerResults).toEqual(['h1', 'h2']);
    expect(groundClicks).toHaveLength(0);
  });
});
```

- [ ] **Step 2: Run tests to verify failure**

Run: `npx vitest run src/features/bazaar/world/__tests__/InputPipeline.test.ts`
Expected: FAIL

- [ ] **Step 3: Implement InputPipeline**

Create `src/features/bazaar/world/InputPipeline.ts`:

```typescript
// src/features/bazaar/world/InputPipeline.ts

import type { WorldRenderer } from './WorldRenderer';
import type { CameraSystem } from './CameraSystem';

export interface HitResult {
  consumed: boolean;
  type: 'building' | 'agent';
  target: unknown;
}

export type InputHandler = (worldX: number, worldY: number) => HitResult | null;

export class InputPipeline {
  private renderer: WorldRenderer;
  private camera: CameraSystem;
  private handlers: InputHandler[] = [];
  private onGroundClick: ((worldX: number, worldY: number) => void) | null;
  private dragThreshold = 5;

  private isDragging = false;
  private mouseDownX = 0;
  private mouseDownY = 0;
  private lastMoveX = 0;
  private lastMoveY = 0;
  private mouseIsDown = false;

  constructor(
    renderer: WorldRenderer,
    camera: CameraSystem,
    onGroundClick?: (worldX: number, worldY: number) => void,
  ) {
    this.renderer = renderer;
    this.camera = camera;
    this.onGroundClick = onGroundClick ?? null;
  }

  register(handler: InputHandler): void {
    this.handlers.push(handler);
  }

  onMouseDown = (e: MouseEvent): void => {
    if (e.button !== 0) return;
    this.mouseIsDown = true;
    this.isDragging = false;
    this.mouseDownX = e.clientX;
    this.mouseDownY = e.clientY;
    this.lastMoveX = e.clientX;
    this.lastMoveY = e.clientY;
  };

  onMouseMove = (e: MouseEvent): void => {
    if (!this.mouseIsDown) return;

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

  onMouseUp = (e: MouseEvent): void => {
    if (!this.mouseIsDown) return;
    this.mouseIsDown = false;

    if (this.isDragging) return;

    const rect = (e.target as HTMLElement).getBoundingClientRect();
    const screenX = e.clientX - rect.left;
    const screenY = e.clientY - rect.top;
    const world = this.renderer.screenToWorld(screenX, screenY);

    for (const handler of this.handlers) {
      const result = handler(world.x, world.y);
      if (result?.consumed) return;
    }

    this.onGroundClick?.(world.x, world.y);
  };
}
```

- [ ] **Step 4: Run tests to verify pass**

Run: `npx vitest run src/features/bazaar/world/__tests__/InputPipeline.test.ts`
Expected: ALL PASS

- [ ] **Step 5: Commit**

```bash
git add src/features/bazaar/world/InputPipeline.ts src/features/bazaar/world/__tests__/InputPipeline.test.ts
git commit -m "feat(bazaar): add InputPipeline with drag/click detection and handler pipeline"
```

---

## Chunk 3: 服务端世界状态（WorldState + MessageRouter + index.ts）

纯服务端改动，不依赖前端 Chunk 1-2。

### Task 8: WorldState — 服务端位置追踪引擎

**Files:**
- Create: `bazaar/src/world-state.ts`
- Create: `bazaar/tests/world-state.test.ts`

- [ ] **Step 1: Write failing tests**

Create `bazaar/tests/world-state.test.ts`:

```typescript
import { describe, it, expect, beforeEach } from 'vitest';
import { WorldState } from '../src/world-state.js';

describe('WorldState', () => {
  let state: WorldState;
  let broadcasts: unknown[];
  let agentMessages: Map<string, unknown[]>;

  beforeEach(() => {
    broadcasts = [];
    agentMessages = new Map();
    state = new WorldState(
      (agentId: string, data: unknown) => {
        const list = agentMessages.get(agentId) ?? [];
        list.push(data);
        agentMessages.set(agentId, list);
      },
      (data: unknown) => broadcasts.push(data),
    );
  });

  describe('handleMove', () => {
    it('should track agent position', () => {
      state.handleMove('a1', 100, 200, 'walking', 'right');
      const agents = state.getAgentsInZone('plaza');
      expect(agents.length).toBeGreaterThan(0);
      expect(agents[0].agentId).toBe('a1');
    });

    it('should broadcast position update', () => {
      state.handleMove('a1', 100, 200, 'idle', 'down');
      expect(broadcasts.length).toBe(1);
      expect(broadcasts[0]).toMatchObject({ type: 'world.agent_update', agentId: 'a1' });
    });

    it('should throttle broadcasts to 5fps', () => {
      state.handleMove('a1', 100, 200, 'walking', 'right');
      state.handleMove('a1', 101, 201, 'walking', 'right'); // < 200ms later
      expect(broadcasts.length).toBe(1); // throttled
    });

    it('should broadcast immediately on idle (stop)', () => {
      state.handleMove('a1', 100, 200, 'walking', 'right');
      state.handleMove('a1', 101, 201, 'walking', 'right'); // throttled
      state.handleMove('a1', 101, 201, 'idle', 'right');    // immediate
      expect(broadcasts.length).toBe(2);
    });
  });

  describe('zones', () => {
    it('should detect zone change and broadcast', () => {
      // Start in plaza area
      state.handleMove('a1', 640, 400, 'idle', 'down');
      broadcasts.length = 0;
      // Move to stalls area (y < 192)
      state.handleMove('a1', 100, 100, 'idle', 'up');
      // Should have zone leave + enter events
      const zoneEvents = broadcasts.filter((m: any) => m.type === 'world.enter_zone' || m.type === 'world.leave_zone');
      expect(zoneEvents.length).toBeGreaterThanOrEqual(1);
    });
  });

  describe('handleAgentOnline', () => {
    it('should send snapshot to new agent', () => {
      state.handleMove('a1', 100, 200, 'idle', 'down');
      state.handleAgentOnline('a2');
      const msgs = agentMessages.get('a2');
      expect(msgs).toBeDefined();
      expect(msgs![0]).toMatchObject({ type: 'world.zone_snapshot' });
    });

    it('should broadcast agent_enter to all', () => {
      state.handleAgentOnline('a1');
      expect(broadcasts).toContainEqual(expect.objectContaining({ type: 'world.agent_enter', agentId: 'a1' }));
    });
  });

  describe('removeAgent', () => {
    it('should remove position and broadcast leave', () => {
      state.handleMove('a1', 100, 200, 'idle', 'down');
      broadcasts.length = 0;
      state.removeAgent('a1');
      expect(broadcasts).toContainEqual(expect.objectContaining({ type: 'world.agent_leave', agentId: 'a1' }));
    });
  });
});
```

- [ ] **Step 2: Run tests to verify failure**

Run: `cd bazaar && npx vitest run tests/world-state.test.ts`
Expected: FAIL

- [ ] **Step 3: Implement WorldState**

Create `bazaar/src/world-state.ts`:

```typescript
// bazaar/src/world-state.ts

interface AgentPosition {
  agentId: string;
  x: number;
  y: number;
  state: string;
  facing: string;
  lastMoveAt: number;
  zone: string | null;
}

interface Zone {
  id: string;
  minX: number;
  minY: number;
  maxX: number;
  maxY: number;
}

const ZONES: Zone[] = [
  { id: 'plaza',      minX: 0,    minY: 288, maxX: 1280, maxY: 480 },
  { id: 'stalls',     minX: 0,    minY: 0,   maxX: 448,  maxY: 192 },
  { id: 'reputation', minX: 1056, minY: 0,   maxX: 1280, maxY: 288 },
  { id: 'bounty',     minX: 0,    minY: 608, maxX: 288,  maxY: 768 },
  { id: 'search',     minX: 1056, minY: 608, maxX: 1280, maxY: 768 },
  { id: 'workshop',   minX: 1056, minY: 736, maxX: 1280, maxY: 832 },
];

const BROADCAST_INTERVAL_MS = 200;

export class WorldState {
  private positions = new Map<string, AgentPosition>();
  private lastBroadcastAt = new Map<string, number>();
  private broadcastToAgent: (agentId: string, data: unknown) => void;
  private broadcastToAll: (data: unknown) => void;

  constructor(
    broadcastToAgent: (agentId: string, data: unknown) => void,
    broadcastToAll: (data: unknown) => void,
  ) {
    this.broadcastToAgent = broadcastToAgent;
    this.broadcastToAll = broadcastToAll;
  }

  handleMove(agentId: string, x: number, y: number, state: string, facing: string): void {
    const prev = this.positions.get(agentId);
    const prevZone = prev?.zone ?? null;
    const now = Date.now();

    const zone = this.findZone(x, y);
    this.positions.set(agentId, { agentId, x, y, state, facing, lastMoveAt: now, zone });

    // Zone change — broadcast immediately
    if (prevZone !== zone) {
      if (prevZone) this.broadcastToAll({ type: 'world.leave_zone', agentId, zone: prevZone });
      if (zone) this.broadcastToAll({ type: 'world.enter_zone', agentId, zone });
    }

    // Position broadcast — throttled
    const lastBroadcast = this.lastBroadcastAt.get(agentId) ?? 0;
    if (state === 'idle' || now - lastBroadcast >= BROADCAST_INTERVAL_MS) {
      this.broadcastToAll({ type: 'world.agent_update', agentId, x, y, state, facing });
      this.lastBroadcastAt.set(agentId, now);
    }
  }

  removeAgent(agentId: string): void {
    const pos = this.positions.get(agentId);
    if (pos?.zone) {
      this.broadcastToAll({ type: 'world.leave_zone', agentId, zone: pos.zone });
    }
    this.positions.delete(agentId);
    this.lastBroadcastAt.delete(agentId);
    this.broadcastToAll({ type: 'world.agent_leave', agentId });
  }

  handleAgentOnline(agentId: string): void {
    const snapshot = Array.from(this.positions.values());
    this.broadcastToAgent(agentId, { type: 'world.zone_snapshot', agents: snapshot });
    this.broadcastToAll({ type: 'world.agent_enter', agentId });
  }

  getAgentsInZone(zoneId: string): AgentPosition[] {
    return Array.from(this.positions.values()).filter(p => p.zone === zoneId);
  }

  private findZone(x: number, y: number): string | null {
    for (const z of ZONES) {
      if (x >= z.minX && x <= z.maxX && y >= z.minY && y <= z.maxY) return z.id;
    }
    return null;
  }
}
```

- [ ] **Step 4: Run tests to verify pass**

Run: `cd bazaar && npx vitest run tests/world-state.test.ts`
Expected: ALL PASS

- [ ] **Step 5: Commit**

```bash
git add bazaar/src/world-state.ts bazaar/tests/world-state.test.ts
git commit -m "feat(bazaar): add WorldState engine with position tracking and throttled broadcasting"
```

---

### Task 9: MessageRouter 集成 world.* 消息

**Files:**
- Modify: `bazaar/src/message-router.ts`

- [ ] **Step 1: Add WorldState import and constructor parameter**

In `bazaar/src/message-router.ts`:

Add import after line 5:
```typescript
import type { WorldState } from './world-state.js';
```

Update constructor (line 23) to accept optional WorldState:
```typescript
constructor(store: AgentStore, taskEngine?: TaskEngine, connections?: Map<string, WebSocket>, worldState?: WorldState) {
    this.store = store;
    this.taskEngine = taskEngine ?? null;
    this.connections = connections ?? new Map();
    this.worldState = worldState ?? null;
    this.log = createLogger('MessageRouter');
  }
```

Add private field after line 22:
```typescript
  private worldState: WorldState | null;
```

- [ ] **Step 2: Add world.* routing in route() method**

In `route()` method, replace the `else` block (line 64-66) that handles unknown types:

Before:
```typescript
      } else {
        this.log.warn(`Unhandled message type: ${type}`);
        return { handled: true }; // 已知类型但当前 phase 未实现
      }
```

After:
```typescript
      } else if (type.startsWith('world.')) {
        return this.handleWorldMessage(type, payload, ws);
      } else {
        this.log.warn(`Unhandled message type: ${type}`);
        return { handled: true };
      }
```

- [ ] **Step 3: Add handleWorldMessage method**

Add before the closing brace of the class:

```typescript
  private handleWorldMessage(type: string, payload: Record<string, unknown>, ws: WebSocket): RouteResult {
    if (!this.worldState) {
      this.log.warn('WorldState not initialized, ignoring world message');
      return { handled: false, error: 'WorldState not available' };
    }

    const agentId = wsToAgent.get(ws);
    if (!agentId) return { handled: false, error: 'Agent not registered' };

    if (type === 'world.move') {
      this.worldState.handleMove(
        agentId,
        payload.x as number,
        payload.y as number,
        (payload.state as string) ?? 'walking',
        (payload.facing as string) ?? 'down',
      );
      return { handled: true };
    }

    return { handled: true };
  }
```

- [ ] **Step 4: Verify bazaar tests pass**

Run: `cd bazaar && npx vitest run`
Expected: ALL PASS

- [ ] **Step 5: Commit**

```bash
git add bazaar/src/message-router.ts
git commit -m "feat(bazaar): route world.* messages to WorldState engine"
```

---

### Task 10: index.ts 集成 WorldState

**Files:**
- Modify: `bazaar/src/index.ts`

- [ ] **Step 1: Import and initialize WorldState**

After the `TaskEngine` import (line 8), add:
```typescript
import { WorldState } from './world-state.js';
```

After the `router` creation (line 42), add a `broadcastAll` helper and create WorldState:
```typescript
// 世界状态广播
const broadcastAll = (data: unknown) => {
  for (const [, ws] of connections) {
    if (ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify(data));
    }
  }
};

const worldState = new WorldState(sendToAgent, broadcastAll);
```

Update the router creation to pass worldState:
```typescript
const router = new MessageRouter(store, taskEngine, connections, worldState);
```

- [ ] **Step 2: Hook WorldState into connection lifecycle**

In the `wss.on('connection')` handler, inside the `ws.on('message')` callback, after the existing `if (raw.type === 'agent.register')` block (after line 77), add:
```typescript
        // 通知 WorldState 新 Agent 上线
        if (raw.type === 'agent.register' && raw.payload?.agentId) {
          worldState.handleAgentOnline(raw.payload.agentId as string);
        }
```

In the `ws.on('close')` handler (line 84), add before `store.setAgentOffline(agentId)`:
```typescript
      worldState.removeAgent(agentId);
```

- [ ] **Step 3: Verify bazaar tests + tsc**

Run: `cd bazaar && npx tsc --noEmit && npx vitest run`
Expected: No errors, ALL PASS

- [ ] **Step 4: Commit**

```bash
git add bazaar/src/index.ts
git commit -m "feat(bazaar): integrate WorldState into server lifecycle"
```

---

## Chunk 4: Bridge + Store + 前端集成

串联前后端：Bridge 转发、Store 扩展、WorldSync 数据桥、WorldRenderer 改造、WorldCanvas 重写、BazaarPage 面板切换。

### Task 11: shared/bazaar-types.ts — World payload 类型

**Files:**
- Modify: `shared/bazaar-types.ts`

- [ ] **Step 1: Add World payload interfaces**

Append to end of file:

```typescript
// ── World 消息 Payload ──

export interface WorldMovePayload {
  agentId: string;
  x: number;
  y: number;
  state: 'idle' | 'walking' | 'busy';
  facing: 'up' | 'down' | 'left' | 'right';
}

export interface WorldAgentUpdatePayload {
  agentId: string;
  x: number;
  y: number;
  state: 'idle' | 'walking' | 'busy';
  facing: 'up' | 'down' | 'left' | 'right';
}

export interface WorldZoneSnapshotPayload {
  agents: Array<{
    agentId: string;
    x: number;
    y: number;
    state: string;
    facing: string;
    zone: string | null;
  }>;
}

export interface WorldZoneEventPayload {
  agentId: string;
  zone: string;
  action: 'enter' | 'leave';
}
```

- [ ] **Step 2: Verify bazaar tsc**

Run: `cd bazaar && npx tsc --noEmit`
Expected: No errors

- [ ] **Step 3: Commit**

```bash
git add shared/bazaar-types.ts
git commit -m "feat(bazaar): add World payload type definitions"
```

---

### Task 12: bazaar-bridge.ts — world.* 双向转发

**Files:**
- Modify: `server/bazaar/bazaar-bridge.ts`

- [ ] **Step 1: Add world.* cases to handleBazaarMessage**

In `handleBazaarMessage` switch (line 164), add BEFORE the `default` case (line 214):

```typescript
      case 'world.agent_update':
      case 'world.agent_enter':
      case 'world.agent_leave':
      case 'world.zone_snapshot':
      case 'world.enter_zone':
      case 'world.leave_zone':
      case 'world.event':
        this.deps.broadcast(JSON.stringify({
          type: `bazaar.${msg.type}`,
          ...msg.payload,
        }));
        break;
```

- [ ] **Step 2: Add bazaar.world.move to handleFrontendMessage**

In `handleFrontendMessage` switch (line 98), add BEFORE the `default` case:

```typescript
      case 'bazaar.world.move': {
        const identity = this.store.getIdentity();
        if (!identity) break;
        this.client.send({
          id: uuidv4(),
          type: 'world.move',
          payload: { agentId: identity.agentId, ...payload },
        });
        break;
      }
```

- [ ] **Step 3: Verify tsc**

Run: `npx tsc --noEmit`
Expected: No errors

- [ ] **Step 4: Commit**

```bash
git add server/bazaar/bazaar-bridge.ts
git commit -m "feat(bazaar): bridge forwards world.* messages bidirectionally"
```

---

### Task 13: types/bazaar.ts + stores/bazaar.ts — 前端 World 状态

**Files:**
- Modify: `src/types/bazaar.ts`
- Modify: `src/stores/bazaar.ts`

- [ ] **Step 1: Add WorldAgentPosition type**

Append to `src/types/bazaar.ts`:

```typescript
export interface WorldAgentPosition {
  agentId: string;
  x: number;
  y: number;
  state: 'idle' | 'walking' | 'busy';
  facing: 'up' | 'down' | 'left' | 'right';
}
```

- [ ] **Step 2: Add WorldAgentPosition import to store**

In `src/stores/bazaar.ts`, add `WorldAgentPosition` to the import from `@/types/bazaar`.

- [ ] **Step 3: Add worldPositions to BazaarState**

In the `BazaarState` interface, add:

```typescript
  worldPositions: Map<string, WorldAgentPosition>;
  sendWorldMove: (x: number, y: number, state: string, facing: string) => void;
```

- [ ] **Step 4: Add initial state and action**

In the store initial state, add:

```typescript
    worldPositions: new Map(),
```

Add action:

```typescript
    sendWorldMove: (x: number, y: number, state: string, facing: string) => {
      const client = getWsClient();
      if (!client) return;
      client.send({ type: 'bazaar.world.move', payload: { x, y, state, facing } });
    },
```

- [ ] **Step 5: Add world.* push listeners**

In `registerPushListeners` handle function, before the closing of the `bazaar.digest` else-if block, add:

```typescript
    } else if (type === 'bazaar.world.agent_update') {
      const positions = new Map(get().worldPositions);
      positions.set(msg.agentId as string, {
        agentId: msg.agentId as string,
        x: msg.x as number,
        y: msg.y as number,
        state: msg.state as 'idle' | 'walking' | 'busy',
        facing: msg.facing as 'up' | 'down' | 'left' | 'right',
      });
      set({ worldPositions: positions });
    } else if (type === 'bazaar.world.zone_snapshot') {
      const positions = new Map<string, WorldAgentPosition>();
      for (const a of (msg.agents as WorldAgentPosition[])) {
        positions.set(a.agentId, a);
      }
      set({ worldPositions: positions });
    } else if (type === 'bazaar.world.agent_leave') {
      const positions = new Map(get().worldPositions);
      positions.delete(msg.agentId as string);
      set({ worldPositions: positions });
    }
```

- [ ] **Step 6: Verify tsc**

Run: `npx tsc --noEmit`
Expected: No errors

- [ ] **Step 7: Commit**

```bash
git add src/types/bazaar.ts src/stores/bazaar.ts
git commit -m "feat(bazaar): add worldPositions state and world.* push listeners to store"
```

---

### Task 14: WorldRenderer.ts 改造 — 相机委托 + screenToWorld

**Files:**
- Modify: `src/features/bazaar/world/WorldRenderer.ts`

- [ ] **Step 1: Add CameraSystem import and field**

Add import at top:

```typescript
import type { CameraSystem } from './CameraSystem';
```

Replace `private cameraX = 0;` and `private cameraY = 0;` with:

```typescript
  private camera: CameraSystem | null = null;

  setCamera(camera: CameraSystem): void { this.camera = camera; }

  getCamera(): CameraSystem | null { return this.camera; }
```

- [ ] **Step 2: Update init() to use camera**

Replace the camera centering logic in `init()` (lines 52-53):

```typescript
    // 相机初始居中（由 CameraSystem 管理）
    if (!this.camera) {
      this.camera = new CameraSystem(DESIGN.MAP_COLS * TS, DESIGN.MAP_ROWS * TS);
    }
    this.camera.setViewport(rect.width, rect.height);
```

Note: need to import CameraSystem non-type for the fallback. Actually simpler: just require setCamera to be called before init. Remove the fallback.

```typescript
  init(canvas: HTMLCanvasElement): void {
    this.canvas = canvas;
    this.ctx = canvas.getContext('2d')!;
    this.ctx.imageSmoothingEnabled = false;

    const dpr = window.devicePixelRatio || 1;
    const rect = canvas.getBoundingClientRect();
    canvas.width = rect.width * dpr;
    canvas.height = rect.height * dpr;
    this.ctx.scale(dpr, dpr);
  }
```

- [ ] **Step 3: Update loop() to read camera offset**

In `loop()`, replace the hardcoded cameraX/cameraY usage:

```typescript
  private loop = (): void => {
    if (!this.running || !this.ctx || !this.canvas || !this.camera) return;

    const rect = this.canvas.getBoundingClientRect();
    const { x: cameraX, y: cameraY } = this.camera.getOffset();

    // ... rest unchanged ...
```

- [ ] **Step 4: Add screenToWorld method**

Add before `destroy()`:

```typescript
  screenToWorld(screenX: number, screenY: number): { x: number; y: number } {
    if (!this.camera) return { x: screenX, y: screenY };
    const { x, y } = this.camera.getOffset();
    return { x: screenX + x, y: screenY + y };
  }
```

- [ ] **Step 5: Verify tsc**

Run: `npx tsc --noEmit`
Expected: No errors

- [ ] **Step 6: Commit**

```bash
git add src/features/bazaar/world/WorldRenderer.ts
git commit -m "feat(bazaar): delegate camera to CameraSystem, add screenToWorld"
```

---

### Task 15: WorldSync — 数据桥

**Files:**
- Create: `src/features/bazaar/world/WorldSync.ts`

- [ ] **Step 1: Implement WorldSync**

Create `src/features/bazaar/world/WorldSync.ts`:

```typescript
// src/features/bazaar/world/WorldSync.ts

import type { WorldRenderer } from './WorldRenderer';
import { AgentEntity } from './AgentEntity';
import { DESIGN } from './palette';
import type { useBazaarStore } from '@/stores/bazaar';
import type { BazaarAgentInfo } from '@/types/bazaar';

const TS = DESIGN.TILE_SIZE;

const AGENT_COLORS = ['#41a6f6', '#38b764', '#ef7d57', '#b13e53', '#ffcd75', '#a7f070', '#73eff7'];

export class WorldSync {
  private renderer: WorldRenderer;
  private store: typeof useBazaarStore;
  private selfAgentId: string | null = null;
  private colorIndex = 0;

  constructor(renderer: WorldRenderer, store: typeof useBazaarStore) {
    this.renderer = renderer;
    this.store = store;
  }

  syncAgents(): void {
    const state = this.store.getState();
    const onlineAgents = state.onlineAgents;
    const worldPositions = state.worldPositions;
    const currentIds = new Set(this.renderer.getAllAgents().map(a => a.id));
    const onlineIds = new Set(onlineAgents.map(a => a.agentId));

    // Add / update
    for (const agent of onlineAgents) {
      if (!currentIds.has(agent.agentId)) {
        this.addAgentFromStore(agent);
      } else {
        this.updateAgentFromStore(agent);
      }
      // Sync server position to renderer (skip self — local control)
      if (agent.agentId !== this.selfAgentId) {
        const pos = worldPositions.get(agent.agentId);
        if (pos) {
          const entity = this.renderer.getAgent(agent.agentId);
          if (entity) entity.moveTo(pos.x, pos.y);
        }
      }
    }

    // Remove: both sources must be absent
    for (const id of currentIds) {
      if (!onlineIds.has(id) && !worldPositions.has(id) && id !== this.selfAgentId) {
        this.renderer.removeAgent(id);
      }
    }
  }

  moveSelfAgent(worldX: number, worldY: number): void {
    if (!this.selfAgentId) return;
    const agent = this.renderer.getAgent(this.selfAgentId);
    if (!agent) return;
    agent.moveTo(worldX, worldY);
    // Send to server
    const client = this.store.getState();
    // Use store action
    const { sendWorldMove } = this.store.getState();
    sendWorldMove(Math.round(worldX), Math.round(worldY), 'walking', agent.facing);
  }

  initSelfAgent(agentId: string, name: string, avatar: string): void {
    this.selfAgentId = agentId;
    const entity = new AgentEntity({
      id: agentId,
      name,
      avatar,
      reputation: 0,
      x: 20 * TS,
      y: 12 * TS,
      shirtColor: '#41a6f6',
    });
    entity.isSelf = true;
    this.renderer.addAgent(entity);
  }

  private addAgentFromStore(agent: BazaarAgentInfo): void {
    const worldPos = this.store.getState().worldPositions.get(agent.agentId);
    const entity = new AgentEntity({
      id: agent.agentId,
      name: agent.name,
      avatar: agent.avatar,
      reputation: agent.reputation,
      x: worldPos?.x ?? 20 * TS,
      y: worldPos?.y ?? 12 * TS,
      shirtColor: AGENT_COLORS[this.colorIndex++ % AGENT_COLORS.length],
    });
    this.renderer.addAgent(entity);
  }

  private updateAgentFromStore(agent: BazaarAgentInfo): void {
    const entity = this.renderer.getAgent(agent.agentId);
    if (!entity) return;
    entity.name = agent.name;
    entity.avatar = agent.avatar;
    entity.reputation = agent.reputation;
  }
}
```

- [ ] **Step 2: Verify tsc**

Run: `npx tsc --noEmit`
Expected: No errors

- [ ] **Step 3: Commit**

```bash
git add src/features/bazaar/world/WorldSync.ts
git commit -m "feat(bazaar): add WorldSync store-to-renderer data bridge"
```

---

### Task 16: WorldCanvas.tsx 重写 + BazaarPage.tsx 面板切换

**Files:**
- Modify: `src/features/bazaar/world/WorldCanvas.tsx`
- Modify: `src/features/bazaar/BazaarPage.tsx`

- [ ] **Step 1: Rewrite WorldCanvas.tsx**

Replace entire file content with:

```typescript
// src/features/bazaar/world/WorldCanvas.tsx

import { useEffect, useRef, useCallback } from 'react';
import { WorldRenderer } from './WorldRenderer';
import { CameraSystem } from './CameraSystem';
import { InputPipeline } from './InputPipeline';
import { InteractionSystem } from '../InteractionSystem';
import { WorldSync } from './WorldSync';
import { BuildingRegistry } from './BuildingRegistry';
import { BUILDINGS } from './map-data';
import { DESIGN } from './palette';
import { useBazaarStore } from '@/stores/bazaar';
import type { ActivePanel } from './types';

interface WorldCanvasProps {
  rendererRef: React.MutableRefObject<WorldRenderer | null>;
  onPanelChange?: (panel: ActivePanel) => void;
  onAgentClick?: (agent: { id: string; name: string; avatar: string; reputation: number }) => void;
}

export function WorldCanvas({ rendererRef, onPanelChange, onAgentClick }: WorldCanvasProps) {
  const canvasRef = useRef<HTMLCanvasElement>(null);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;

    const rect = canvas.getBoundingClientRect();
    const renderer = new WorldRenderer();
    const camera = new CameraSystem(DESIGN.MAP_COLS * DESIGN.TILE_SIZE, DESIGN.MAP_ROWS * DESIGN.TILE_SIZE);
    camera.setViewport(rect.width, rect.height);

    const sync = new WorldSync(renderer, useBazaarStore);
    const pipeline = new InputPipeline(renderer, camera, (wx, wy) => sync.moveSelfAgent(wx, wy));
    const registry = new BuildingRegistry();
    const interaction = new InteractionSystem(BUILDINGS, registry);

    // Register handlers in priority order
    pipeline.register((worldX, worldY) => {
      const hit = interaction.hitTestBuildings(worldX, worldY);
      if (hit && hit.type === 'building') {
        const building = hit.target as typeof BUILDINGS[number];
        const action = interaction.handleBuildingClick(building);
        if (action) onPanelChange?.(action.panel);
        return hit;
      }
      return null;
    });

    pipeline.register((worldX, worldY) => {
      const hit = interaction.hitTestAgents(worldX, worldY, renderer.getAllAgents());
      if (hit && hit.type === 'agent') {
        const agent = hit.target as import('./AgentEntity').AgentEntity;
        onAgentClick?.({ id: agent.id, name: agent.name, avatar: agent.avatar, reputation: agent.reputation });
        return hit;
      }
      return null;
    });

    renderer.setCamera(camera);
    renderer.init(canvas);
    rendererRef.current = renderer;
    renderer.start();

    // Canvas events
    canvas.addEventListener('mousedown', pipeline.onMouseDown);
    canvas.addEventListener('mousemove', pipeline.onMouseMove);
    canvas.addEventListener('mouseup', pipeline.onMouseUp);

    // Store → renderer sync
    const unsub = useBazaarStore.subscribe(() => sync.syncAgents());
    sync.syncAgents();

    // Init self agent
    const identity = useBazaarStore.getState().connection;
    if (identity.connected && identity.agentId) {
      sync.initSelfAgent(identity.agentId, identity.agentName ?? '我', '🧙');
    }

    return () => {
      unsub();
      renderer.destroy();
      rendererRef.current = null;
      canvas.removeEventListener('mousedown', pipeline.onMouseDown);
      canvas.removeEventListener('mousemove', pipeline.onMouseMove);
      canvas.removeEventListener('mouseup', pipeline.onMouseUp);
    };
  }, []);

  const handleResize = useCallback(() => {
    const canvas = canvasRef.current;
    const renderer = rendererRef.current;
    if (!canvas || !renderer) return;

    const dpr = window.devicePixelRatio || 1;
    const rect = canvas.getBoundingClientRect();
    canvas.width = rect.width * dpr;
    canvas.height = rect.height * dpr;
    const ctx = canvas.getContext('2d');
    if (ctx) {
      ctx.scale(dpr, dpr);
      ctx.imageSmoothingEnabled = false;
    }
    renderer.getCamera()?.setViewport(rect.width, rect.height);
  }, [rendererRef]);

  useEffect(() => {
    window.addEventListener('resize', handleResize);
    return () => window.removeEventListener('resize', handleResize);
  }, [handleResize]);

  return (
    <canvas
      ref={canvasRef}
      style={{
        width: '100%',
        height: '100%',
        display: 'block',
        imageRendering: 'pixelated',
        background: '#1a1c2c',
      }}
    />
  );
}
```

- [ ] **Step 2: Update BazaarPage.tsx**

Add import:

```typescript
import type { ActivePanel } from './world/types';
```

Add state after `viewMode`:

```typescript
  const [activePanel, setActivePanel] = useState<ActivePanel>('leaderboard');
```

Update WorldCanvas props in both world mode and disconnected mode:

```typescript
            <WorldCanvas
              rendererRef={rendererRef}
              onPanelChange={(panel) => setActivePanel(panel)}
              onAgentClick={(agent) => { /* TODO: show agent info toast */ }}
            />
```

Update right panel content in world mode to use activePanel:

```typescript
          <div className="w-[40%] flex flex-col overflow-hidden">
            <div className="flex-1 overflow-y-auto p-4 space-y-4">
              {activePanel === 'leaderboard' && <LeaderboardPanel />}
              {activePanel === 'tasks' && <TaskPanel />}
              {activePanel === 'chat' && <CollaborationChat />}
              {activePanel === 'agents' && <OnlineAgents />}
            </div>
            <ControlBar />
          </div>
```

- [ ] **Step 3: Verify build**

Run: `pnpm build`
Expected: Build succeeds

- [ ] **Step 4: Commit**

```bash
git add src/features/bazaar/world/WorldCanvas.tsx src/features/bazaar/BazaarPage.tsx
git commit -m "feat(bazaar): wire up interaction pipeline, data sync, and panel switching"
```

---

### Task 17: 最终验证

- [ ] **Step 1: Run all bazaar tests**

Run: `cd bazaar && npx vitest run`
Expected: ALL PASS

- [ ] **Step 2: Run main project tests**

Run: `npx vitest run`
Expected: ALL PASS

- [ ] **Step 3: Build check**

Run: `pnpm build`
Expected: Build succeeds

- [ ] **Step 4: Bazaar tsc check**

Run: `cd bazaar && npx tsc --noEmit`
Expected: No errors

---

## 文件依赖关系

```
Task 1 (map-data) ─┐
Task 2 (types.ts)  │
Task 3 (Camera)    ├─→ Task 6 (Interaction) ─→ Task 7 (InputPipeline) ─┐
Task 4 (Registry)  │                                                    │
Task 5 (AgentEntity)┘                                                   │
                                                                        ↓
Task 8  (WorldState)     ─→ Task 9  (Router)  ─→ Task 10 (index.ts)    │
                                                                        │
Task 11 (shared types)   ─→ Task 12 (Bridge)                           │
                                                                        │
Task 13 (Store)          ─→ Task 14 (Renderer) ─→ Task 15 (WorldSync) ─┤
                                                                        │
                                                           Task 16 (Canvas + Page)
                                                                        │
                                                           Task 17 (Final verify)
```

Chunk 1-2（前端基础）和 Chunk 3（服务端）可并行执行。
Chunk 4（集成）依赖前面所有 Chunk。
