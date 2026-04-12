# Bazaar Phase 4 Batch 2：完整像素世界交互系统

> 2026-04-12

## 核心目标

把 Phase 4 Batch 1 的纯展示渲染引擎，升级为完整的交互式像素世界：

1. **前端交互系统** — 拖拽平移地图、点击移动 Agent、点击建筑触发面板、点击 Agent 显示信息
2. **数据桥** — Zustand store 的真实 Agent 数据 → Canvas 渲染实体，双向同步
3. **服务端世界状态引擎** — Agent 位置追踪、区域管理、位置广播
4. **Bridge 转发** — world.* 消息在服务端↔Bridge↔前端之间双向流动

## 设计原则

- **子系统管道（Pipeline）**：输入事件按优先级依次流过子系统，第一个消费的子系统获胜
- **高内聚插拔**：每个子系统（Camera、Interaction、Zone）独立文件，通过注册表接入
- **零外部依赖**：纯 Canvas 2D，不引入游戏引擎库
- **数据驱动**：建筑→面板映射通过配置表注册，新增建筑不改渲染代码

---

## 一、前端架构

### 目录结构

```
src/features/bazaar/world/
├── WorldCanvas.tsx          # 改造：接入 InputPipeline，移除 demo agents，接入 WorldSync
├── WorldRenderer.ts         # 改造：暴露 camera getter，新增 screenToWorld 方法
├── AgentEntity.ts           # 改造：新增 isSelf 标记、hitTest 方法、禁用自己 Agent 的自动游走
├── TileMap.ts               # 不改
├── SpriteSheet.ts           # 不改
├── palette.ts               # 不改
├── map-data.ts              # 改造：BUILDINGS 新增 width/height 字段用于命中测试
├── InputPipeline.ts         # 新增：输入事件管道
├── CameraSystem.ts          # 新增：相机平移、边界约束
├── InteractionSystem.ts     # 新增：命中测试 + 事件分发
├── WorldSync.ts             # 新增：Store ↔ Renderer 双向数据桥
├── BuildingRegistry.ts      # 新增：建筑 → 面板映射注册表
└── types.ts                 # 新增：世界交互类型
```

### 1.1 InputPipeline — 输入管道

鼠标/触摸事件统一入口。核心逻辑：通过拖拽距离区分"点击"和"拖拽"。

```typescript
// src/features/bazaar/world/InputPipeline.ts

interface InputEvent {
  type: 'click' | 'drag-start' | 'drag-move' | 'drag-end';
  screenX: number;
  screenY: number;
  worldX: number;       // 通过 renderer.screenToWorld() 转换
  worldY: number;
  deltaX?: number;       // 拖拽位移
  deltaY?: number;
}

interface HitResult {
  consumed: boolean;
  type: 'building' | 'agent' | 'ground';
  target?: BuildingData | AgentEntity;
}

type InputHandler = (event: InputEvent) => HitResult | null;

class InputPipeline {
  private renderer: WorldRenderer;      // 用于 screenToWorld 坐标转换
  private camera: CameraSystem;         // 用于拖拽平移
  private handlers: InputHandler[] = [];  // 按注册顺序，优先级从高到低
  private dragThreshold = 5;  // px，小于此值为点击
  private onGroundClick: ((worldX: number, worldY: number) => void) | null;

  // 拖拽状态机
  private isDragging = false;
  private startX = 0;
  private startY = 0;

  constructor(
    renderer: WorldRenderer,
    camera: CameraSystem,
    onGroundClick?: (worldX: number, worldY: number) => void,
  ) {
    this.renderer = renderer;
    this.camera = camera;
    this.onGroundClick = onGroundClick ?? null;
  }

  register(handler: InputHandler): void { this.handlers.push(handler); }

  // 鼠标按下：记录起点
  onMouseDown(e: MouseEvent): void;

  // 鼠标移动：超过阈值 → camera.panBy(delta)
  onMouseMove(e: MouseEvent): void;

  // 鼠标松开：未超过阈值 → 点击 → 走 hitTest 管道 → 全部未消费 → onGroundClick
  onMouseUp(e: MouseEvent): void;
}
```

**优先级链**（硬编码，不需要运行时配置）：

```
mouseDown → 记录起点
mouseMove → 位移 ≥ 5px → CameraSystem.panBy(delta)
mouseUp   → 位移 < 5px → click pipeline:
  1. InteractionSystem.hitTestBuildings(worldX, worldY) → 建筑交互
  2. InteractionSystem.hitTestAgents(worldX, worldY)    → Agent 交互
  3. 空地点击 → WorldSync.moveSelfAgent(worldX, worldY)
```

### 1.2 CameraSystem — 相机系统

```typescript
// src/features/bazaar/world/CameraSystem.ts

class CameraSystem {
  private cameraX: number;
  private cameraY: number;
  private minX: number;
  private minY: number;
  private maxX: number;  // mapWidth - viewportWidth
  private maxY: number;  // mapHeight - viewportHeight

  constructor(mapWidth: number, mapHeight: number) {}

  // 平移（拖拽时调用）
  panBy(deltaX: number, deltaY: number): void {
    this.cameraX = clamp(this.cameraX - deltaX, this.minX, this.maxX);
    this.cameraY = clamp(this.cameraY - deltaY, this.minY, this.maxY);
  }

  // 让某点居中（点击 Agent 时可选用）
  centerOn(worldX: number, worldY: number): void;

  // 获取当前相机位置（WorldRenderer 渲染时读取）
  getOffset(): { x: number; y: number }

  // 视口大小变化时更新边界
  setViewport(width: number, height: number): void {
    this.maxX = Math.max(0, this.mapWidth - width);
    this.maxY = Math.max(0, this.mapHeight - height);
    // 如果视口比地图大，居中显示
    if (width >= this.mapWidth) {
      this.cameraX = -(width - this.mapWidth) / 2;
    }
    if (height >= this.mapHeight) {
      this.cameraY = -(height - this.mapHeight) / 2;
    }
  }
}
```

WorldRenderer 的 cameraX/cameraY 改为从 CameraSystem 读取，不再自己管理。

### 1.3 InteractionSystem — 交互系统

```typescript
// src/features/bazaar/world/InteractionSystem.ts

class InteractionSystem {
  private buildings: BuildingData[];
  private agents: Map<string, AgentEntity>;
  private registry: BuildingRegistry;

  // 命中测试：建筑（矩形碰撞）
  hitTestBuildings(worldX: number, worldY: number): HitResult | null {
    for (const b of this.buildings) {
      const bx = b.col * TILE_SIZE;
      const by = b.row * TILE_SIZE;
      if (worldX >= bx && worldX <= bx + b.width && worldY >= by && worldY <= by + b.height) {
        return { consumed: true, type: 'building', target: b };
      }
    }
    return null;
  }

  // 命中测试：Agent（圆形碰撞，半径 16px）
  hitTestAgents(worldX: number, worldY: number): HitResult | null {
    for (const agent of this.agents.values()) {
      const dx = worldX - agent.x;
      const dy = worldY - (agent.y - 16); // 中心偏移
      if (dx * dx + dy * dy <= 256) { // 16^2
        return { consumed: true, type: 'agent', target: agent };
      }
    }
    return null;
  }

  // 处理建筑点击 → 查询 BuildingRegistry → 返回面板动作
  handleBuildingClick(building: BuildingData): BuildingAction | null;

  // 处理 Agent 点击 → 返回 Agent 信息
  handleAgentClick(agent: AgentEntity): AgentInfo;
}
```

### 1.4 BuildingRegistry — 建筑注册表

```typescript
// src/features/bazaar/world/BuildingRegistry.ts

interface BuildingAction {
  panel: 'leaderboard' | 'tasks' | 'chat' | 'agents';  // 右侧面板切换目标
}

const DEFAULT_ACTIONS: Record<string, BuildingAction> = {
  stall:      { panel: 'tasks' },
  reputation: { panel: 'leaderboard' },
  bounty:     { panel: 'tasks' },
  search:     { panel: 'tasks' },
  workshop:   { panel: 'tasks' },
};

class BuildingRegistry {
  private actions: Map<string, BuildingAction>;

  constructor(overrides?: Record<string, BuildingAction>) {
    this.actions = new Map(Object.entries({ ...DEFAULT_ACTIONS, ...overrides }));
  }

  getAction(buildingType: string): BuildingAction | null;
  register(buildingType: string, action: BuildingAction): void;  // 插拔扩展点
}
```

### 1.5 WorldSync — 数据桥

```typescript
// src/features/bazaar/world/WorldSync.ts

class WorldSync {
  private renderer: WorldRenderer;
  private store: typeof useBazaarStore;
  private selfAgentId: string | null = null;
  private onMoveSelf: ((x: number, y: number) => void) | null;

  // 核心：store.onlineAgents（基本信息）+ store.worldPositions（位置）→ renderer.agents 增量同步
  // 权威数据源：onlineAgents 决定"谁在"，worldPositions 决定"在哪"
  syncAgents(): void {
    const onlineAgents = this.store.getState().onlineAgents;
    const worldPositions = this.store.getState().worldPositions;
    const currentIds = new Set(this.renderer.getAllAgents().map(a => a.id));
    const onlineIds = new Set(onlineAgents.map(a => a.agentId));

    // 新增 / 更新
    for (const agent of onlineAgents) {
      if (!currentIds.has(agent.agentId)) {
        this.addAgentFromStore(agent);
      } else {
        this.updateAgentFromStore(agent);
      }
      // 同步服务端位置到 renderer（自己 Agent 除外，自己位置由本地控制）
      if (agent.agentId !== this.selfAgentId) {
        const pos = worldPositions.get(agent.agentId);
        if (pos) {
          const entity = this.renderer.getAgent(agent.agentId);
          if (entity) entity.moveTo(pos.x, pos.y);
        }
      }
    }

    // 移除：同时检查 onlineAgents 和 worldPositions 都不存在才删
    for (const id of currentIds) {
      if (!onlineIds.has(id) && !worldPositions.has(id) && id !== this.selfAgentId) {
        this.renderer.removeAgent(id);
      }
    }
  }

  // 自己 Agent 点击空地移动
  moveSelfAgent(worldX: number, worldY: number): void {
    if (!this.selfAgentId) return;
    const agent = this.renderer.getAgent(this.selfAgentId);
    if (agent) {
      agent.moveTo(worldX, worldY);
      this.onMoveSelf?.(worldX, worldY);  // → world.move → 服务端
    }
  }

  // 服务端推送 world.agent_update → 更新其他 Agent 位置
  handleRemoteAgentUpdate(agentId: string, x: number, y: number, state: string): void;

  // 同步 Agent 状态（idle/busy/afk → 渲染状态映射）
  // 新增 Agent：从 worldPositions 取初始位置，无则用中央广场默认值
  private addAgentFromStore(agent: BazaarAgentInfo): void {
    const worldPos = this.store.getState().worldPositions.get(agent.agentId);
    const entity = new AgentEntity({
      id: agent.agentId,
      name: agent.name,
      avatar: agent.avatar,
      reputation: agent.reputation,
      x: worldPos?.x ?? 20 * TILE_SIZE,  // 默认中央广场
      y: worldPos?.y ?? 12 * TILE_SIZE,
      shirtColor: this.randomColor(),
    });
    this.renderer.addAgent(entity);
  }

  private updateAgentFromStore(agent: BazaarAgentInfo): void {
    const entity = this.renderer.getAgent(agent.agentId);
    if (!entity) return;
    entity.name = agent.name;
    entity.avatar = agent.avatar;
    entity.reputation = agent.reputation;
    // status 映射：idle→idle, busy→busy, afk→idle
    if (agent.status === 'busy') entity.state = 'busy';
  }

  // 添加自己 Agent（居中出生）
  initSelfAgent(agentId: string, name: string, avatar: string): void {
    this.selfAgentId = agentId;
    const entity = new AgentEntity({
      id: agentId,
      name,
      avatar,
      reputation: 0,
      x: 20 * TILE_SIZE,  // 中央广场出生点
      y: 12 * TILE_SIZE,
      shirtColor: '#41a6f6',
    });
    entity.isSelf = true;  // 禁用自动游走
    this.renderer.addAgent(entity);
  }
}
```

### 1.6 类型定义

```typescript
// src/features/bazaar/world/types.ts

export interface WorldPosition {
  x: number;  // 像素坐标
  y: number;
  tileCol: number;
  tileRow: number;
}

export interface WorldAgentUpdate {
  agentId: string;
  x: number;
  y: number;
  state: 'idle' | 'walking' | 'busy';
  facing: 'up' | 'down' | 'left' | 'right';
}

export interface WorldZoneEvent {
  agentId: string;
  zone: string;  // 'plaza' | 'stalls' | 'reputation' | 'bounty' | 'search' | 'workshop'
  action: 'enter' | 'leave';
}

export type ActivePanel = 'leaderboard' | 'tasks' | 'chat' | 'agents';
```

### 1.7 现有文件改造

#### AgentEntity.ts 改造

```typescript
// 新增字段
isSelf: boolean = false;  // 自己的 Agent 不自动游走

// update() 方法改造
update(bounds): void {
  if (this.state === 'busy') return;  // busy 状态不移动

  // ... 原有移动逻辑 ...

  // 自动游走：只有非 self 且 idle 时
  if (this.isSelf) return;  // 自己 Agent 只响应点击移动
  this.wanderTimer++;
  if (this.wanderTimer >= this.wanderInterval) { ... }
}

// 新增：命中测试
hitTest(worldX: number, worldY: number): boolean {
  const dx = worldX - this.x;
  const dy = worldY - (this.y - 16);
  return dx * dx + dy * dy <= 256;
}
```

#### WorldRenderer.ts 改造

```typescript
// cameraX/cameraY 改为从外部 CameraSystem 读取
private camera: CameraSystem | null = null;

setCamera(camera: CameraSystem): void { this.camera = camera; }

// loop() 中改用 camera.getOffset()
private loop = () => {
  const { x: cameraX, y: cameraY } = this.camera!.getOffset();
  // ... 渲染逻辑不变，只是 cameraX/cameraY 来源变了
};

// 新增：屏幕坐标 → 世界坐标
screenToWorld(screenX: number, screenY: number): { x: number; y: number } {
  const { x, y } = this.camera!.getOffset();
  return { x: screenX + x, y: screenY + y };
}
```

#### map-data.ts 改造

```typescript
// BuildingData 新增 width/height
export interface BuildingData {
  id: string;
  type: BuildingType;
  col: number;
  row: number;
  label: string;
  width: number;   // 新增，像素宽度
  height: number;  // 新增，像素高度
}

// BUILDINGS 数据更新（stall=64x64, reputation/bounty=96x96）
```

#### WorldCanvas.tsx 改造

```typescript
// 移除 demo agents
// 初始化 InputPipeline + CameraSystem + InteractionSystem + WorldSync
// 绑定 canvas 鼠标事件到 InputPipeline
// useEffect 订阅 store 变化 → WorldSync.syncAgents()

export function WorldCanvas({ rendererRef, onPanelChange, onAgentClick }: Props) {
  // onPanelChange: 建筑点击 → 切换右侧面板
  // onAgentClick: Agent 点击 → 显示信息

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;

    const rect = canvas.getBoundingClientRect();
    const renderer = new WorldRenderer();
    const camera = new CameraSystem(MAP_WIDTH, MAP_HEIGHT);
    camera.setViewport(rect.width, rect.height);  // 初始化视口

    const sync = new WorldSync(renderer, useBazaarStore);
    const pipeline = new InputPipeline(renderer, camera, (wx, wy) => sync.moveSelfAgent(wx, wy));
    const interaction = new InteractionSystem(BUILDINGS, renderer);

    // 管道注册（按优先级）
    pipeline.register(interaction.createBuildingHandler(onPanelChange));
    pipeline.register(interaction.createAgentHandler(onAgentClick));

    renderer.setCamera(camera);
    renderer.init(canvas);
    renderer.start();

    // canvas 事件绑定
    canvas.addEventListener('mousedown', pipeline.onMouseDown);
    canvas.addEventListener('mousemove', pipeline.onMouseMove);
    canvas.addEventListener('mouseup', pipeline.onMouseUp);

    // store → renderer 同步
    const unsub = useBazaarStore.subscribe(() => sync.syncAgents());
    sync.syncAgents();  // 初始同步

    // 初始化自己 Agent
    const identity = useBazaarStore.getState().connection;
    if (identity.connected && identity.agentId) {
      sync.initSelfAgent(identity.agentId, identity.agentName ?? '我', '🧙');
    }

    return () => {
      unsub();
      renderer.destroy();
      canvas.removeEventListener('mousedown', pipeline.onMouseDown);
      canvas.removeEventListener('mousemove', pipeline.onMouseMove);
      canvas.removeEventListener('mouseup', pipeline.onMouseUp);
    };
  }, []);

  // resize 时通知 CameraSystem
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
    // 通知 CameraSystem 视口变化
    renderer.getCamera()?.setViewport(rect.width, rect.height);
  }, [rendererRef]);

  useEffect(() => {
    window.addEventListener('resize', handleResize);
    return () => window.removeEventListener('resize', handleResize);
  }, [handleResize]);
}
```

#### BazaarPage.tsx 改造

```typescript
// 新增 activePanel 状态
const [activePanel, setActivePanel] = useState<ActivePanel>('leaderboard');

// WorldCanvas 接收回调
<WorldCanvas
  rendererRef={rendererRef}
  onPanelChange={(panel) => setActivePanel(panel)}
  onAgentClick={(agent) => { /* 显示 Agent 信息 toast 或展开详情 */ }}
/>

// 右侧面板根据 activePanel 显示
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

---

## 二、服务端世界状态引擎

### 2.1 WorldState — 位置追踪 + 区域管理

```typescript
// bazaar/src/world-state.ts

interface AgentPosition {
  agentId: string;
  x: number;
  y: number;
  state: 'idle' | 'walking' | 'busy';
  facing: 'up' | 'down' | 'left' | 'right';
  lastMoveAt: string;
  zone: string | null;  // 当前所在区域
}

// 区域定义（与前端 map-data.ts 对应）
const ZONES = [
  { id: 'plaza',      name: '中央广场', minX: 0,    minY: 288, maxX: 1280, maxY: 480 },
  { id: 'stalls',     name: '摆摊区',   minX: 0,    minY: 0,   maxX: 448,  maxY: 192 },
  { id: 'reputation', name: '声望榜',   minX: 1056, minY: 0,   maxX: 1280, maxY: 288 },
  { id: 'bounty',     name: '悬赏板',   minX: 0,    minY: 608, maxX: 288,  maxY: 768 },
  { id: 'search',     name: '搜索站',   minX: 1056, minY: 608, maxX: 1280, maxY: 768 },
  { id: 'workshop',   name: '工坊',     minX: 1056, minY: 736, maxX: 1280, maxY: 832 },
];

class WorldState {
  private positions: Map<string, AgentPosition> = new Map();
  private broadcastFn: (agentId: string, data: unknown) => void;
  private broadcastAllFn: (data: unknown) => void;

  // 处理 Agent 移动
  handleMove(agentId: string, x: number, y: number, state: string, facing: string): void {
    const prev = this.positions.get(agentId);
    const prevZone = prev?.zone ?? null;

    // 更新位置
    this.positions.set(agentId, {
      agentId, x, y, state, facing,
      lastMoveAt: new Date().toISOString(),
      zone: this.findZone(x, y),
    });

    // 检测区域变化 → 广播
    const newZone = this.positions.get(agentId)!.zone;
    if (prevZone !== newZone) {
      if (prevZone) this.broadcastZoneEvent(agentId, prevZone, 'leave');
      if (newZone) this.broadcastZoneEvent(agentId, newZone, 'enter');
    }

    // 广播位置更新（节流：最多 5fps）
    this.broadcastAllFn({
      type: 'world.agent_update',
      agentId, x, y, state, facing,
    });
  }

  // Agent 下线清理
  removeAgent(agentId: string): void {
    const pos = this.positions.get(agentId);
    if (pos?.zone) {
      this.broadcastZoneEvent(agentId, pos.zone, 'leave');
    }
    this.positions.delete(agentId);
    this.broadcastAllFn({ type: 'world.agent_leave', agentId });
  }

  // 新 Agent 上线 → 发送当前快照
  handleAgentOnline(agentId: string): void {
    // 发送全量快照给这个 Agent
    const snapshot = Array.from(this.positions.values());
    this.broadcastFn(agentId, {
      type: 'world.zone_snapshot',
      agents: snapshot,
    });

    // 广播其他人：新 Agent 进入
    this.broadcastAllFn({ type: 'world.agent_enter', agentId });
  }

  // 区域查询
  getAgentsInZone(zoneId: string): AgentPosition[];

  // 点查询（哪个区域）
  private findZone(x: number, y: number): string | null;
  private broadcastZoneEvent(agentId: string, zone: string, action: 'enter' | 'leave'): void;
}
```

### 2.2 位置广播节流

Agent 移动频繁，不能每帧都广播。策略：

- **移动中**：每 200ms 广播一次位置（5fps）
- **停止移动**：立即广播最终位置
- **区域变化**：立即广播（不管节流）

```typescript
// 节流在 handleMove 中实现
private lastBroadcastAt: Map<string, number> = new Map();

handleMove(agentId, x, y, state, facing) {
  // ... 更新位置 ...

  const now = Date.now();
  const lastBroadcast = this.lastBroadcastAt.get(agentId) ?? 0;

  if (state === 'idle' || now - lastBroadcast >= 200) {
    this.broadcastAllFn({ type: 'world.agent_update', ... });
    this.lastBroadcastAt.set(agentId, now);
  }
}
```

### 2.3 MessageRouter 改造

```typescript
// bazaar/src/message-router.ts

// 新增 world.* 消息处理
route(raw, ws) {
  // ... 原有逻辑 ...

  } else if (type.startsWith('world.')) {
    return this.handleWorldMessage(type, payload, ws);
  }
}

private handleWorldMessage(type: string, payload: Record<string, unknown>, ws: WebSocket): RouteResult {
  const agentId = wsToAgent.get(ws);
  if (!agentId) return { handled: false, error: 'Agent not registered' };

  if (type === 'world.move') {
    const x = payload.x as number;
    const y = payload.y as number;
    const state = (payload.state as string) ?? 'walking';
    const facing = (payload.facing as string) ?? 'down';
    this.worldState.handleMove(agentId, x, y, state, facing);
    return { handled: true };
  }

  return { handled: true };
}
```

### 2.4 index.ts 改造

```typescript
// bazaar/src/index.ts

const worldState = new WorldState(sendToAgent, broadcastAll);
const router = new MessageRouter(store, taskEngine, connections, worldState);

// Agent 上线时通知 WorldState
wss.on('connection', (ws) => {
  // ... 原有逻辑 ...
  if (raw.type === 'agent.register') {
    worldState.handleAgentOnline(agentId);
  }
});

// Agent 下线时清理
ws.on('close', () => {
  if (agentId) {
    worldState.removeAgent(agentId);
    // ... 原有清理 ...
  }
});
```

---

## 三、Bridge 层转发

### 3.1 bazaar-bridge.ts 改造

```typescript
// server/bazaar/bazaar-bridge.ts

// handleBazaarMessage 新增 world.* 处理（注意：必须在 default 分支之前）
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

// handleFrontendMessage 新增 world.move 转发
case 'bazaar.world.move':
  this.client.send({
    id: uuidv4(),
    type: 'world.move',
    payload: { agentId: this.store.getIdentity()?.agentId, ...payload },
  });
  break;
```

### 3.2 shared/bazaar-types.ts 补充

```typescript
// World 消息 Payload 类型
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

---

## 四、前端 Store 扩展

### 4.1 types/bazaar.ts 新增

```typescript
export interface WorldAgentPosition {
  agentId: string;
  x: number;
  y: number;
  state: 'idle' | 'walking' | 'busy';
  facing: 'up' | 'down' | 'left' | 'right';
}
```

### 4.2 stores/bazaar.ts 新增

```typescript
// State 新增
worldPositions: Map<string, WorldAgentPosition>;

// Action 新增
sendWorldMove: (x: number, y: number, state: string, facing: string) => void;

// Push listener 新增
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
  // 全量快照
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

---

## 五、数据流全景

```
用户点击空地
  → WorldCanvas.mousedown → mouseup → InputPipeline → 空地点击
  → WorldSync.moveSelfAgent(x, y) → AgentEntity.moveTo()
  → store.sendWorldMove(x, y, state, facing) → WS → Bridge
  → Bridge: bazaar.world.move → world.move → Bazaar Server
  → WorldState.handleMove() → 广播 world.agent_update
  → 其他 Agent 的 Bridge → 前端 store.worldPositions 更新
  → WorldSync → renderer 移动对应 AgentEntity

用户点击建筑
  → InputPipeline → InteractionSystem.hitTestBuildings()
  → BuildingRegistry.getAction(buildingType)
  → onPanelChange(panel) → BazaarPage.setActivePanel()
  → 右侧面板切换（纯前端，无需服务端）
```

---

## 六、文件清单

### 新增文件

| 文件 | 层 | 说明 |
|------|-----|------|
| `src/features/bazaar/world/types.ts` | 前端 | 世界交互类型定义 |
| `src/features/bazaar/world/InputPipeline.ts` | 前端 | 输入事件管道 |
| `src/features/bazaar/world/CameraSystem.ts` | 前端 | 相机平移 + 边界约束 |
| `src/features/bazaar/world/InteractionSystem.ts` | 前端 | 命中测试 + 事件分发 |
| `src/features/bazaar/world/WorldSync.ts` | 前端 | Store ↔ Renderer 数据桥 |
| `src/features/bazaar/world/BuildingRegistry.ts` | 前端 | 建筑 → 面板映射注册表 |
| `bazaar/src/world-state.ts` | 服务端 | 世界状态引擎 |

### 修改文件

| 文件 | 层 | 改动量 | 说明 |
|------|-----|--------|------|
| `src/features/bazaar/world/WorldCanvas.tsx` | 前端 | 大改 | 接入管道、移除 demo、绑定事件 |
| `src/features/bazaar/world/WorldRenderer.ts` | 前端 | 中改 | camera 委托给 CameraSystem、新增 screenToWorld |
| `src/features/bazaar/world/AgentEntity.ts` | 前端 | 小改 | isSelf 标记、hitTest 方法 |
| `src/features/bazaar/world/map-data.ts` | 前端 | 小改 | BuildingData 新增 width/height |
| `src/features/bazaar/BazaarPage.tsx` | 前端 | 中改 | activePanel 状态、面板切换逻辑 |
| `src/stores/bazaar.ts` | 前端 | 中改 | worldPositions 状态、sendWorldMove、push listeners |
| `src/types/bazaar.ts` | 前端 | 小改 | WorldAgentPosition 类型 |
| `bazaar/src/message-router.ts` | 服务端 | 中改 | 新增 handleWorldMessage |
| `bazaar/src/index.ts` | 服务端 | 中改 | WorldState 初始化、上下线通知 |
| `server/bazaar/bazaar-bridge.ts` | Bridge | 中改 | world.* 消息双向转发 |
| `shared/bazaar-types.ts` | 共享 | 小改 | World payload 类型 |

### 零侵入

- 不修改 `TileMap.ts`、`SpriteSheet.ts`、`palette.ts`
- 不修改 `server/index.ts`（Sman 后端入口，Bridge 自己管理）
- 不修改 `bazaar/src/protocol.ts`（world.* 类型已声明）
- 不修改 `bazaar/src/agent-store.ts`

---

## 七、测试策略

| 层 | 测试文件 | 测试内容 |
|----|---------|---------|
| 服务端 | `bazaar/tests/world-state.test.ts` | 位置追踪、区域检测、上下线、节流广播 |
| 服务端 | `bazaar/tests/message-router.test.ts`（扩展） | world.move 路由、未注册 Agent 拒绝 |
| Bridge | `tests/server/bazaar/bazaar-bridge.test.ts`（扩展） | world.* 消息转发 |
| 前端 | `src/features/bazaar/world/__tests__/InteractionSystem.test.ts` | 命中测试（建筑、Agent、空地） |
| 前端 | `src/features/bazaar/world/__tests__/CameraSystem.test.ts` | 平移、边界约束 |
| 前端 | `src/features/bazaar/world/__tests__/InputPipeline.test.ts` | 点击/拖拽区分、优先级消费 |
| 前端 | `src/features/bazaar/world/__tests__/WorldSync.test.ts` | Agent 增删改同步 |
| 前端 | `src/features/bazaar/world/__tests__/BuildingRegistry.test.ts` | 注册表查找、覆盖、默认值 |
