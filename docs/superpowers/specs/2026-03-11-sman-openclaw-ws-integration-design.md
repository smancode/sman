# SMAN + OpenClaw WebSocket 集成设计规范

**日期**: 2026-03-11
**状态**: 设计完成，待实施
**目标**: 将 SMAN 的 OpenClaw 客户端从 HTTP 改为 WebSocket RPC，实现完整聊天功能

---

## 1. 背景与问题

### 1.1 当前状态

- **SMAN**: Tauri 桌面应用，SvelteKit 前端
- **OpenClaw**: 独立的 AI Gateway，通过 WebSocket 提供 RPC 服务
- **问题**: SMAN 的 `OpenClawClient` 设计为 HTTP 客户端（期望 `/api/chat` 等 REST 端点），但 OpenClaw 实际提供的是 WebSocket RPC Gateway

### 1.2 目标

- 让 SMAN 能够通过 WebSocket 调用 OpenClaw 的 `chat.send` 方法
- 实现流式响应（`chat.event` 事件订阅）
- 保持架构清晰，易于维护

---

## 2. 架构设计

### 2.1 整体架构

```
┌─────────────────────────────────────────────────────────────┐
│                     SMAN Desktop App                         │
│                                                              │
│  ┌──────────────┐    ┌──────────────┐    ┌──────────────┐   │
│  │  SvelteKit   │    │  OpenClaw    │    │  WebSocket   │   │
│  │  Frontend    │───▶│  WS Client   │───▶│  Connection  │   │
│  │              │    │  (新建)      │    │              │   │
│  └──────────────┘    └──────────────┘    └───────┬──────┘   │
│                                                  │           │
│  ┌──────────────┐    ┌──────────────┐           │           │
│  │  Tauri       │    │  Sidecar     │           │           │
│  │  Shell       │    │  Manager     │───────────┤           │
│  └──────────────┘    └──────────────┘           │           │
│                                                  │           │
└──────────────────────────────────────────────────┼───────────┘
                                                   │
                                                   │ ws://127.0.0.1:18789
                                                   │
                                                   ▼
                              ┌─────────────────────────────────────┐
                              │         OpenClaw Gateway            │
                              │         (Sidecar Process)           │
                              │                                     │
                              │  RPC Methods:                       │
                              │  • connect (认证)                   │
                              │  • chat.send (发送消息)             │
                              │  • chat.history (获取历史)          │
                              │  • chat.abort (中止对话)            │
                              │  • health (健康检查)                │
                              │                                     │
                              │  Events (服务器推送):               │
                              │  • chat.event (流式响应)            │
                              │  • agent.* (Agent 事件)             │
                              └─────────────────────────────────────┘
```

### 2.2 数据流

```
用户输入消息
    │
    ▼
┌─────────────────┐
│ ChatWindow      │ handleSubmit()
│ (Svelte)        │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ OpenClawAPI     │ sendMessage(sessionKey, message)
│ (简化 API)      │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ OpenClawWSClient│ chat.send RPC 调用
│ (WebSocket)     │ ─────────────────────▶ OpenClaw
└────────┬────────┘
         │                              │
         │                              │ 返回 { runId, status: "started" }
         │◀─────────────────────────────┘
         │
         │  监听 chat.event 事件
         │◀─────────────────────────────┐
         │                              │ { state: "delta", message: "..." }
         │                              │ { state: "delta", message: "..." }
         │                              │ { state: "final", message: "..." }
         │◀─────────────────────────────┘
         │
         ▼
┌─────────────────┐
│ ChatWindow      │ 更新 UI
│ (Svelte)        │
└─────────────────┘
```

---

## 3. 协议规范

### 3.1 WebSocket 连接

- **URL**: `ws://127.0.0.1:18789`（默认端口）
- **协议**: JSON over WebSocket

### 3.2 消息格式

#### 请求 (Request)

```typescript
interface GatewayRequest {
  type: "req";
  id: string;        // UUID，用于匹配响应
  method: string;    // RPC 方法名
  params?: object;   // 方法参数
}
```

#### 响应 (Response)

```typescript
interface GatewayResponse {
  type: "res";
  id: string;        // 对应请求的 id
  ok: boolean;       // 是否成功
  payload?: object;  // 成功时的返回数据
  error?: {          // 失败时的错误信息
    code: string;
    message: string;
  };
}
```

#### 事件 (Event)

```typescript
interface GatewayEvent {
  type: "event";
  event: string;     // 事件名，如 "chat.event"
  payload: object;   // 事件数据
  seq: number;       // 序列号
}
```

### 3.3 核心方法

#### connect (认证)

```typescript
// 请求
{
  type: "req",
  id: "uuid-1",
  method: "connect",
  params: {
    token: "optional-token",
    password: "optional-password"
  }
}

// 响应
{
  type: "res",
  id: "uuid-1",
  ok: true,
  payload: {
    sessionId: "xxx",
    role: "operator"
  }
}
```

#### chat.send (发送消息)

```typescript
// 请求
{
  type: "req",
  id: "uuid-2",
  method: "chat.send",
  params: {
    sessionKey: "main",           // 会话标识
    message: "你好",              // 用户消息
    idempotencyKey: "uuid-3",     // 幂等键（必需）
    timeoutMs: 120000             // 可选，超时时间
  }
}

// 响应
{
  type: "res",
  id: "uuid-2",
  ok: true,
  payload: {
    runId: "uuid-4",
    status: "started"
  }
}
```

#### chat.event (流式响应事件)

```typescript
// 服务器推送
{
  type: "event",
  event: "chat.event",
  seq: 1,
  payload: {
    runId: "uuid-4",
    sessionKey: "main",
    state: "delta",              // "delta" | "final" | "error" | "aborted"
    message: {
      role: "assistant",
      content: "你好！有什么可以帮助你的？"
    }
  }
}

// 完成时
{
  type: "event",
  event: "chat.event",
  seq: 5,
  payload: {
    runId: "uuid-4",
    sessionKey: "main",
    state: "final",
    message: {
      role: "assistant",
      content: "完整回复内容..."
    },
    stopReason: "end_turn"
  }
}
```

#### chat.history (获取历史)

```typescript
// 请求
{
  type: "req",
  id: "uuid-5",
  method: "chat.history",
  params: {
    sessionKey: "main",
    limit: 100
  }
}

// 响应
{
  type: "res",
  id: "uuid-5",
  ok: true,
  payload: {
    messages: [
      { role: "user", content: "你好" },
      { role: "assistant", content: "你好！" }
    ]
  }
}
```

#### health (健康检查)

```typescript
// 请求
{
  type: "req",
  id: "uuid-6",
  method: "health"
}

// 响应
{
  type: "res",
  id: "uuid-6",
  ok: true,
  payload: {
    status: "ok"
  }
}
```

---

## 4. 组件设计

### 4.1 OpenClawWSClient

**文件**: `src/core/openclaw/client-ws.ts`

**职责**:
- 管理 WebSocket 连接（连接、断线重连、关闭）
- 提供请求-响应语义（自动匹配 id）
- 事件订阅机制

**核心接口**:

```typescript
export class OpenClawWSClient {
  private ws: WebSocket | null = null;
  private pendingRequests: Map<string, PendingRequest> = new Map();
  private eventListeners: Map<string, Set<EventHandler>> = new Map();

  // 连接
  async connect(url?: string): Promise<void>;

  // 请求-响应
  async request<T>(method: string, params?: object): Promise<T>;

  // 事件订阅
  on(event: string, handler: EventHandler): () => void;
  off(event: string, handler: EventHandler): void;

  // 连接状态
  isConnected(): boolean;
  disconnect(): void;
}
```

### 4.2 OpenClawAPI

**文件**: `src/core/openclaw/api.ts`

**职责**:
- 提供高级语义化 API
- 封装 WebSocket 细节

**核心接口**:

```typescript
export class OpenClawAPI {
  private client: OpenClawWSClient;

  // 发送消息，返回 runId
  async sendMessage(
    sessionKey: string,
    message: string,
    options?: { timeoutMs?: number }
  ): Promise<{ runId: string }>;

  // 订阅聊天事件
  onChatEvent(handler: (event: ChatEventPayload) => void): () => void;

  // 获取历史记录
  async getHistory(sessionKey: string, limit?: number): Promise<ChatMessage[]>;

  // 中止对话
  async abort(sessionKey: string, runId?: string): Promise<void>;

  // 健康检查
  async healthCheck(): Promise<boolean>;
}
```

### 4.3 类型定义

**文件**: `src/core/openclaw/types.ts`（更新）

```typescript
// WebSocket 消息类型
export interface GatewayRequest {
  type: "req";
  id: string;
  method: string;
  params?: Record<string, unknown>;
}

export interface GatewayResponse {
  type: "res";
  id: string;
  ok: boolean;
  payload?: unknown;
  error?: { code: string; message: string };
}

export interface GatewayEvent {
  type: "event";
  event: string;
  payload: unknown;
  seq: number;
}

// chat.send 参数
export interface ChatSendParams {
  sessionKey: string;
  message: string;
  idempotencyKey: string;
  thinking?: string;
  deliver?: boolean;
  attachments?: Attachment[];
  timeoutMs?: number;
}

// chat.send 响应
export interface ChatSendResult {
  runId: string;
  status: "started" | "in_flight";
}

// chat.event 载荷
export interface ChatEventPayload {
  runId: string;
  sessionKey: string;
  seq: number;
  state: "delta" | "final" | "error" | "aborted";
  message?: {
    role: string;
    content: string;
  };
  errorMessage?: string;
  stopReason?: string;
  usage?: {
    inputTokens: number;
    outputTokens: number;
  };
}

// chat.history 消息
export interface ChatHistoryMessage {
  role: "user" | "assistant" | "system";
  content: string;
  timestamp?: string;
}
```

---

## 5. 前端集成

### 5.1 修改 ChatWindow.svelte

```typescript
// 导入 API
import { getOpenClawAPI } from "$lib/api/openclaw";

// 发送消息
async function handleSubmit(prompt: string) {
  const api = getOpenClawAPI();

  // 发送消息
  const { runId } = await api.sendMessage(sessionKey, prompt);

  // 订阅事件
  const unsubscribe = api.onChatEvent((event) => {
    if (event.runId !== runId) return;

    if (event.state === "delta") {
      // 增量更新
      updateAssistantMessage(event.message?.content);
    } else if (event.state === "final") {
      // 完成
      finalizeAssistantMessage(event.message?.content);
      unsubscribe();
    } else if (event.state === "error") {
      // 错误
      showError(event.errorMessage);
      unsubscribe();
    }
  });
}
```

### 5.2 修改 conversationApi

```typescript
// src/lib/api/tauri.ts - conversationApi

export const conversationApi = {
  // 保留本地存储方法用于历史记录
  async list(projectId: string) { ... },
  async create(projectId: string, title: string) { ... },

  // sendMessage 改为调用 OpenClaw
  async sendMessage(conversationId: string, content: string) {
    const api = getOpenClawAPI();
    return api.sendMessage(conversationId, content);
  },

  // 新增：订阅聊天事件
  onChatEvent(handler: (event: ChatEventPayload) => void) {
    return getOpenClawAPI().onChatEvent(handler);
  },
};
```

---

## 6. Sidecar 管理

### 6.1 配置打包策略（环境隔离）

**重要**：SMAN 使用环境变量隔离配置，不会影响用户本地的 OpenClaw 环境。

| 环境 | 配置目录 | 说明 |
|------|---------|------|
| 用户本地 OpenClaw | `~/.openclaw/` | 用户自己的开发环境，不受影响 |
| SMAN Sidecar | `~/.smanlocal/` | SMAN 专属目录，完全隔离 |

**隔离机制**：OpenClaw 源码支持环境变量覆盖：

```typescript
// OpenClaw paths.ts
export function resolveStateDir(env = process.env) {
  const override = env.OPENCLAW_STATE_DIR?.trim();
  if (override) return override;  // 环境变量优先
  return path.join(homedir(), ".openclaw");  // 默认
}
```

**SMAN Sidecar 启动命令**：
```bash
OPENCLAW_STATE_DIR=~/.smanlocal \
OPENCLAW_CONFIG_PATH=~/.smanlocal/openclaw.json \
./openclaw-server gateway --port 18789
```

**SMAN 专属目录结构** (`~/.smanlocal/`):
├── openclaw.json          # 主配置文件
├── workspace/             # 工作区（提示词、技能）
│   ├── AGENTS.md          # Agent 人格提示词
│   ├── SOUL.md            # 核心人格
│   ├── USER.md            # 用户信息
│   ├── TOOLS.md           # 工具说明
│   └── skills/            # 技能包目录
│       └── xxx/SKILL.md
└── agents/
    └── main/
        ├── agent/         # Agent 配置
        │   ├── auth-profiles.json
        │   └── models.json
        └── sessions/      # 会话数据（运行时生成）
```

**环境变量配置**：

| 变量 | 值 | 说明 |
|-----|---|------|
| `OPENCLAW_CONFIG_PATH` | `~/.smanlocal/openclaw.json` | 配置文件路径 |
| `OPENCLAW_STATE_DIR` | `~/.smanlocal` | 状态目录 |

**隔离保证**：
- 两个环境同时运行互不干扰
- 用户本地 `~/.openclaw/` 保持原样
- SMAN 卸载时只需删除 `~/.smanlocal/`

**跨平台支持**：

| 平台 | Home 目录 | SMAN 配置目录 |
|------|----------|--------------|
| macOS | `/Users/{user}` | `/Users/{user}/.smanlocal` |
| Linux | `/home/{user}` | `/home/{user}/.smanlocal` |
| Windows | `C:\Users\{user}` | `C:\Users\{user}\.smanlocal` |

**OpenClaw 跨平台支持**（源码验证）：
```typescript
// OpenClaw home-dir.ts
const envHome = normalize(env.HOME);           // macOS/Linux
const userProfile = normalize(env.USERPROFILE); // Windows
```

**配置初始化流程**：
1. Tauri 启动时检查 `~/.smanlocal/` 是否存在
2. 不存在则从内置模板创建默认配置
3. 启动 Sidecar 时传入环境变量

### 6.2 启动流程

1. Tauri 应用启动
2. 调用 `initialize_sman_config` 创建配置目录（如不存在）
3. 调用 `start_openclaw_server` 命令（带环境变量）
4. Sidecar 进程启动，监听 `127.0.0.1:18789`
5. 前端建立 WebSocket 连接
6. 发送 `connect` 认证

### 6.3 健康检查

```typescript
// 定期健康检查
setInterval(async () => {
  const healthy = await api.healthCheck();
  if (!healthy) {
    // 尝试重连
    await reconnect();
  }
}, 30000);
```

### 6.3 关闭流程

1. 前端关闭 WebSocket 连接
2. Tauri 调用 `stop_openclaw_server`
3. Sidecar 进程优雅退出

---

## 7. 错误处理

### 7.1 连接错误

- **问题**: Sidecar 未启动或端口被占用
- **处理**: 显示错误提示，提供重试按钮

### 7.2 请求超时

- **问题**: `chat.send` 长时间无响应
- **处理**: 设置默认超时（120s），超时后显示提示

### 7.3 流式响应中断

- **问题**: WebSocket 断开，`chat.event` 未收到 `final`
- **处理**: 断线重连，查询 `chat.history` 获取最新状态

---

## 8. 文件清单

### 8.1 新建文件

| 文件路径 | 说明 |
|---------|------|
| `src/core/openclaw/client-ws.ts` | WebSocket 客户端 |
| `src/core/openclaw/api.ts` | 高级 API 封装 |
| `src/core/openclaw/types.ts` | 类型定义（更新） |
| `src/lib/api/openclaw.ts` | Svelte Store 集成 |
| `src-tauri/src/commands/config.rs` | 配置初始化命令 |
| `src-tauri/resources/openclaw-default-config/` | 默认配置模板 |

### 8.2 修改文件

| 文件路径 | 修改内容 |
|---------|---------|
| `src-tauri/src/commands/sidecar.rs` | 端口改为 18789，添加环境变量 |
| `src-tauri/src/commands/conversation.rs` | `send_message` 调用 OpenClaw |
| `src-tauri/src/main.rs` | 注册配置命令 |
| `src/lib/api/tauri.ts` | conversationApi 更新 |
| `src/components/chat/ChatWindow.svelte` | 使用新的 API |

### 8.3 删除文件

| 文件路径 | 原因 |
|---------|------|
| `src/core/openclaw/client.ts` | HTTP 客户端不再需要 |

---

## 9. 实施顺序

1. **阶段 0**: Sidecar 编译与配置打包
   - 编译 OpenClaw 为 Sidecar
   - 创建默认配置模板
   - 实现配置初始化逻辑

2. **阶段 1**: WebSocket 客户端基础
   - 实现 `OpenClawWSClient`
   - 更新类型定义

3. **阶段 2**: 高级 API
   - 实现 `OpenClawAPI`
   - 测试基本功能

4. **阶段 3**: 前端集成
   - 修改 `ChatWindow.svelte`
   - 更新 `conversationApi`

5. **阶段 4**: Sidecar 配置
   - 更新端口配置
   - 测试完整流程

---

## 10. 验收标准

### 10.1 配置打包

- [ ] `~/.smanlocal/` 目录在首次启动时自动创建
- [ ] 默认 `openclaw.json` 配置正确生成
- [ ] 默认 Workspace 提示词文件正确创建
- [ ] Sidecar 启动时使用正确的环境变量

### 10.2 WebSocket 连接

- [ ] WebSocket 连接正常建立
- [ ] `chat.send` 发送消息成功
- [ ] `chat.event` 流式响应正常接收
- [ ] UI 正确显示增量更新
- [ ] 错误处理正常工作
- [ ] 断线重连机制有效

### 10.3 Sidecar 打包

- [ ] OpenClaw 编译为独立可执行文件
- [ ] Sidecar 随 Tauri 应用打包
- [ ] 首次启动时自动初始化配置
- [ ] 业务人员无需额外安装即可使用
