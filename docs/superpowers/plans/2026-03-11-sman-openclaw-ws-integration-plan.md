# SMAN + OpenClaw WebSocket 集成实施计划

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 SMAN 的 OpenClaw 客户端从 HTTP 改为 WebSocket RPC，实现与 OpenClaw Gateway 的完整聊天功能

**Architecture:**
- OpenClaw 编译为 Sidecar 可执行文件，打包进 SMAN
- Tauri 启动时自动启动 OpenClaw Sidecar
- 前端通过 WebSocket 连接 OpenClaw Gateway (ws://127.0.0.1:18789)
- 使用 JSON-RPC 风格协议调用 chat.send 方法，订阅 chat.event 事件实现流式响应

**Tech Stack:** TypeScript, WebSocket, SvelteKit, Tauri 2.x, Bun (编译 OpenClaw)

**Spec:** [设计规范](../specs/2026-03-11-sman-openclaw-ws-integration-design.md)

---

## Prerequisites

**重要**: OpenClaw 必须编译为 Sidecar 并打包进 SMAN，业务人员机器上不会有 OpenClaw。

### Sidecar 编译方式

```bash
# 在 OpenClaw 项目目录
cd /Users/nasakim/projects/openclaw
npm run build
bun build ./dist/entry.js --compile --outfile openclaw-server
```

**打包后的文件位置**:
- macOS: `src-tauri/binaries/openclaw-server-x86_64-apple-darwin`
- macOS ARM: `src-tauri/binaries/openclaw-server-aarch64-apple-darwin`
- Windows: `src-tauri/binaries/openclaw-server-x86_64-pc-windows-msvc.exe`
- Linux: `src-tauri/binaries/openclaw-server-x86_64-unknown-linux-gnu`

### 配置打包策略（环境隔离）

**重要**：SMAN 使用环境变量隔离配置，不会影响用户本地的 OpenClaw 环境。

| 环境 | 配置目录 | 说明 |
|------|---------|------|
| 用户本地 OpenClaw | `~/.openclaw/` | 用户自己的开发环境，**不受影响** |
| SMAN Sidecar | `~/.smanlocal/` | SMAN 专属目录，**完全隔离** |

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
```
~/.smanlocal/
├── openclaw.json          # 主配置 (LLM API、Gateway 设置)
├── workspace/             # 工作区
│   ├── AGENTS.md          # Agent 提示词
│   ├── SOUL.md            # 人格定义
│   ├── USER.md            # 用户信息
│   ├── TOOLS.md           # 工具说明
│   └── skills/            # 技能包 (SKILL.md)
└── agents/
    └── main/
        ├── agent/         # Agent 配置
        │   ├── auth-profiles.json
        │   └── models.json
        └── sessions/      # 会话数据 (运行时生成)
```

**隔离保证**：
- 两个环境可以同时运行，互不干扰
- 用户本地 `~/.openclaw/` 保持原样，**绝不会覆盖**
- SMAN 卸载时只需删除 `~/.smanlocal/`

---

## File Structure

```
src/
├── core/
│   └── openclaw/
│       ├── index.ts           # 模块导出 (修改)
│       ├── types.ts           # 类型定义 (更新)
│       ├── client-ws.ts       # WebSocket 客户端 (新建)
│       ├── api.ts             # 高级 API (新建)
│       └── client.ts          # HTTP 客户端 (删除)
├── lib/
│   └── api/
│       └── openclaw.ts        # Svelte Store 集成 (新建)
└── components/
    └── chat/
        └── ChatWindow.svelte  # 聊天窗口 (修改)

src-tauri/
└── src/
    └── commands/
        └── sidecar.rs         # Sidecar 管理 (修改端口)
```

---

## Chunk 0: OpenClaw Sidecar 编译与配置

### Task 0.1: 编译 OpenClaw Sidecar

**前置条件**: OpenClaw 项目已 build (`npm run build`)

- [ ] **Step 1: Build OpenClaw 项目**

Run: `cd /Users/nasakim/projects/openclaw && npm run build`

Expected: `dist/entry.js` 生成成功

- [ ] **Step 2: 创建 binaries 目录**

Run: `mkdir -p /Users/nasakim/projects/sman/src-tauri/binaries`

- [ ] **Step 3: 编译 macOS ARM64 Sidecar**

Run:
```bash
cd /Users/nasakim/projects/openclaw
bun build ./dist/entry.js --compile --outfile /Users/nasakim/projects/sman/src-tauri/binaries/openclaw-server-aarch64-apple-darwin
```

Expected: 生成 `src-tauri/binaries/openclaw-server-aarch64-apple-darwin` 可执行文件

- [ ] **Step 4: 验证 Sidecar 可执行**

Run: `/Users/nasakim/projects/sman/src-tauri/binaries/openclaw-server-aarch64-apple-darwin --help`

Expected: 显示帮助信息或启动 Gateway

- [ ] **Step 5: Commit**

```bash
cd /Users/nasakim/projects/sman
git add src-tauri/binaries/
git commit -m "build: add OpenClaw sidecar binary for macOS ARM64"
```

### Task 0.2: 配置 Tauri Sidecar

**Files:**
- Modify: `src-tauri/tauri.conf.json`
- Modify: `src-tauri/Cargo.toml`

- [ ] **Step 1: 添加 Sidecar 配置到 tauri.conf.json**

在 `bundle` 中添加 `externalBin`：

```json
{
  "bundle": {
    "active": true,
    "externalBin": [
      "binaries/openclaw-server"
    ],
    "targets": "all"
  }
}
```

- [ ] **Step 2: 验证 tauri-plugin-shell 依赖**

检查 `src-tauri/Cargo.toml` 确保有：

```toml
[dependencies]
tauri-plugin-shell = "2"
```

- [ ] **Step 3: 验证编译**

Run: `cd /Users/nasakim/projects/sman && cargo check --manifest-path src-tauri/Cargo.toml`

Expected: 编译通过

- [ ] **Step 4: Commit**

```bash
git add src-tauri/tauri.conf.json src-tauri/Cargo.toml
git commit -m "feat: configure OpenClaw as Tauri sidecar"
```

### Task 0.3: 更新 Sidecar 管理命令

**Files:**
- Modify: `src-tauri/src/commands/sidecar.rs`

- [ ] **Step 1: 更新端口为 18789**

将 `127.0.0.1:3000` 改为 `127.0.0.1:18789`：

```rust
let addr: SocketAddr = "127.0.0.1:18789"
    .parse()
    .map_err(|e: std::net::AddrParseError| e.to_string())?;
```

- [ ] **Step 2: 添加环境变量配置（确保隔离，跨平台支持）**

修改 Sidecar 启动命令，添加配置路径环境变量，**确保与用户本地 OpenClaw 完全隔离**：

```rust
use std::env;
use std::path::PathBuf;

/// 获取 SMAN 专属配置目录（跨平台，与用户本地 OpenClaw 完全隔离）
///
/// 平台支持：
/// - macOS/Linux: $HOME/.smanlocal
/// - Windows: %USERPROFILE%\.smanlocal
fn get_sman_local_dir() -> PathBuf {
    let home = env::var("HOME")
        .or_else(|_| env::var("USERPROFILE"))  // Windows
        .or_else(|_| env::var("HOMEPATH"))     // Windows 备选
        .unwrap_or_else(|_| ".".to_string());
    PathBuf::from(home).join(".smanlocal")
}

// 在启动 Sidecar 时设置环境变量，实现配置隔离
let sman_dir = get_sman_local_dir();
let config_path = sman_dir.join("openclaw.json");

// 关键：设置这两个环境变量让 OpenClaw 使用 SMAN 专属目录
// OpenClaw 源码已支持跨平台（HOME/USERPROFILE 都能识别）
command.env("OPENCLAW_CONFIG_PATH", config_path.to_string_lossy().to_string());
command.env("OPENCLAW_STATE_DIR", sman_dir.to_string_lossy().to_string());

// 调试日志（生产环境可移除）
println!("[SMAN] Sidecar using isolated config dir: {:?}", sman_dir);
```

**跨平台验证**：

| 平台 | Home 目录 | SMAN 配置目录 |
|------|----------|--------------|
| macOS | `/Users/{user}` | `/Users/{user}/.smanlocal` |
| Linux | `/home/{user}` | `/home/{user}/.smanlocal` |
| Windows | `C:\Users\{user}` | `C:\Users\{user}\.smanlocal` |

**OpenClaw 跨平台支持**（已验证源码）：
```typescript
// OpenClaw home-dir.ts - 已支持跨平台
const envHome = normalize(env.HOME);       // macOS/Linux
const userProfile = normalize(env.USERPROFILE);  // Windows
```

- [ ] **Step 3: 验证编译**

Run: `cd /Users/nasakim/projects/sman && cargo check --manifest-path src-tauri/Cargo.toml`

Expected: 编译通过

- [ ] **Step 4: Commit**

```bash
git add src-tauri/src/commands/sidecar.rs
git commit -m "fix: update OpenClaw Gateway port to 18789 and add config env vars"
```

### Task 0.4: 创建默认配置文件

**Files:**
- Create: `src-tauri/resources/openclaw-default-config/`

- [ ] **Step 1: 创建默认配置目录**

Run: `mkdir -p /Users/nasakim/projects/sman/src-tauri/resources/openclaw-default-config/workspace/skills`

- [ ] **Step 2: 创建默认 openclaw.json**

创建文件 `src-tauri/resources/openclaw-default-config/openclaw.json`：

```json
{
  "meta": {
    "lastTouchedVersion": "2026.3.0",
    "lastTouchedAt": null
  },
  "gateway": {
    "port": 18789,
    "mode": "local",
    "bind": "loopback",
    "auth": {
      "mode": "none"
    }
  },
  "agents": {
    "defaults": {
      "model": {
        "primary": null
      },
      "workspace": "~/.smanlocal/workspace",
      "compaction": {
        "mode": "safeguard"
      },
      "timeoutSeconds": 1800,
      "maxConcurrent": 4
    },
    "list": [
      {
        "id": "main",
        "name": "SMAN Agent"
      }
    ]
  },
  "skills": {
    "install": {
      "nodeManager": "npm"
    }
  },
  "commands": {
    "native": "auto",
    "nativeSkills": "auto"
  }
}
```

- [ ] **Step 3: 创建默认 Workspace 提示词**

创建文件 `src-tauri/resources/openclaw-default-config/workspace/AGENTS.md`：

```markdown
# SMAN Agent

你是 SMAN 的 AI 助手，帮助用户进行项目管理、代码开发和日常任务。

## 能力

- 代码编写和审查
- 项目分析
- 文档撰写
- 任务规划

## 行为准则

1. 清晰、简洁地回答问题
2. 提供可执行的建议
3. 遵循用户的技术偏好
```

创建文件 `src-tauri/resources/openclaw-default-config/workspace/SOUL.md`：

```markdown
# Soul

你是一个专业、友好的 AI 助手。

## 性格

- 专业但不生硬
- 乐于助人
- 注重效率
```

创建文件 `src-tauri/resources/openclaw-default-config/workspace/USER.md`：

```markdown
# User

用户通过 SMAN 桌面应用与你交互。

## 上下文

- 用户可能正在进行软件开发工作
- 用户可能需要项目管理支持
- 用户可能需要技术建议
```

- [ ] **Step 4: 创建 Rust 函数初始化配置**

在 `src-tauri/src/commands/` 创建 `config.rs`：

```rust
// src-tauri/src/commands/config.rs
use std::fs;
use std::io;
use std::path::PathBuf;

fn get_sman_local_dir() -> PathBuf {
    let home = std::env::var("HOME")
        .or_else(|_| std::env::var("USERPROFILE"))
        .unwrap_or_else(|_| ".".to_string());
    PathBuf::from(home).join(".smanlocal")
}

/// Initialize SMAN local configuration directory
#[tauri::command]
pub fn initialize_sman_config() -> Result<String, String> {
    let sman_dir = get_sman_local_dir();
    let workspace_dir = sman_dir.join("workspace");
    let agents_dir = sman_dir.join("agents").join("main").join("agent");

    // Create directories
    fs::create_dir_all(&workspace_dir).map_err(|e| e.to_string())?;
    fs::create_dir_all(&workspace_dir.join("skills")).map_err(|e| e.to_string())?;
    fs::create_dir_all(&agents_dir).map_err(|e| e.to_string())?;

    // Create default config if not exists
    let config_path = sman_dir.join("openclaw.json");
    if !config_path.exists() {
        let default_config = include_str!("../../resources/openclaw-default-config/openclaw.json");
        fs::write(&config_path, default_config).map_err(|e| e.to_string())?;
    }

    // Create default workspace files if not exist
    let workspace_files = [
        ("AGENTS.md", include_str!("../../resources/openclaw-default-config/workspace/AGENTS.md")),
        ("SOUL.md", include_str!("../../resources/openclaw-default-config/workspace/SOUL.md")),
        ("USER.md", include_str!("../../resources/openclaw-default-config/workspace/USER.md")),
    ];

    for (filename, content) in workspace_files {
        let path = workspace_dir.join(filename);
        if !path.exists() {
            fs::write(&path, content).map_err(|e| e.to_string())?;
        }
    }

    Ok(sman_dir.to_string_lossy().to_string())
}

/// Get SMAN local directory path
#[tauri::command]
pub fn get_sman_local_path() -> String {
    get_sman_local_dir().to_string_lossy().to_string()
}
```

- [ ] **Step 5: 注册命令到 main.rs**

在 `src-tauri/src/main.rs` 添加：

```rust
mod commands;

use commands::config::{initialize_sman_config, get_sman_local_path};

fn main() {
    tauri::Builder::default()
        .invoke_handler(tauri::generate_handler![
            // ... existing commands
            initialize_sman_config,
            get_sman_local_path,
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
```

- [ ] **Step 6: 在前端初始化时调用**

在前端应用初始化时（如 `App.svelte` 或布局组件）调用：

```typescript
import { invoke } from "@tauri-apps/api/core";

async function initializeApp() {
  try {
    const smanPath = await invoke<string>("initialize_sman_config");
    console.log("[SMAN] Config initialized at:", smanPath);
  } catch (error) {
    console.error("[SMAN] Failed to initialize config:", error);
  }
}
```

- [ ] **Step 7: 验证编译**

Run: `cd /Users/nasakim/projects/sman && cargo check --manifest-path src-tauri/Cargo.toml`

Expected: 编译通过

- [ ] **Step 8: Commit**

```bash
git add src-tauri/resources/ src-tauri/src/commands/config.rs src-tauri/src/main.rs
git commit -m "feat: add default OpenClaw config initialization"
```

---

## Chunk 1: WebSocket 类型定义

### Task 1.1: 更新 OpenClaw 类型定义

**Files:**
- Modify: `src/core/openclaw/types.ts`

- [ ] **Step 1: 添加 WebSocket 协议类型**

在 `src/core/openclaw/types.ts` 文件末尾添加以下类型：

```typescript
// ============================================
// WebSocket Gateway Protocol Types
// ============================================

/** Gateway 请求消息 */
export interface GatewayRequest {
  type: "req";
  id: string;
  method: string;
  params?: Record<string, unknown>;
}

/** Gateway 响应消息 */
export interface GatewayResponse<T = unknown> {
  type: "res";
  id: string;
  ok: boolean;
  payload?: T;
  error?: {
    code: string;
    message: string;
  };
}

/** Gateway 事件消息 */
export interface GatewayEvent<T = unknown> {
  type: "event";
  event: string;
  payload: T;
  seq: number;
}

/** Gateway 消息联合类型 */
export type GatewayMessage = GatewayRequest | GatewayResponse | GatewayEvent;

// ============================================
// Chat Method Types
// ============================================

/** chat.send 参数 */
export interface ChatSendParams {
  sessionKey: string;
  message: string;
  idempotencyKey: string;
  thinking?: string;
  deliver?: boolean;
  attachments?: ChatAttachment[];
  timeoutMs?: number;
}

/** chat.send 响应 */
export interface ChatSendResult {
  runId: string;
  status: "started" | "in_flight";
}

/** chat.event 载荷 */
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

/** chat.history 参数 */
export interface ChatHistoryParams {
  sessionKey: string;
  limit?: number;
}

/** chat.history 响应 */
export interface ChatHistoryResult {
  messages: ChatHistoryMessage[];
}

/** 聊天历史消息 */
export interface ChatHistoryMessage {
  role: "user" | "assistant" | "system";
  content: string;
  timestamp?: string;
}

/** chat.abort 参数 */
export interface ChatAbortParams {
  sessionKey: string;
  runId?: string;
}

/** 附件类型 */
export interface ChatAttachment {
  type?: string;
  mimeType?: string;
  fileName?: string;
  content?: unknown;
}

// ============================================
// Connection Types
// ============================================

/** connect 参数 */
export interface ConnectParams {
  token?: string;
  password?: string;
}

/** connect 响应 */
export interface ConnectResult {
  sessionId: string;
  role: string;
}

/** health 响应 */
export interface HealthResult {
  status: "ok" | "error";
  durationMs?: number;
}

// ============================================
// Client Types
// ============================================

/** WebSocket 客户端配置 */
export interface WSClientConfig {
  url: string;
  reconnectIntervalMs: number;
  maxReconnectAttempts: number;
  requestTimeoutMs: number;
}

/** 待处理请求 */
export interface PendingRequest<T = unknown> {
  resolve: (value: T) => void;
  reject: (error: Error) => void;
  timeout: ReturnType<typeof setTimeout>;
}

/** 事件处理器 */
export type EventHandler<T = unknown> = (payload: T) => void;

/** 客户端状态 */
export type WSClientState = "disconnected" | "connecting" | "connected" | "error";
```

- [ ] **Step 2: 运行类型检查**

Run: `cd /Users/nasakim/projects/sman && npm run typecheck`

Expected: 通过，无错误

- [ ] **Step 3: Commit**

```bash
git add src/core/openclaw/types.ts
git commit -m "feat(openclaw): add WebSocket protocol types"
```

---

## Chunk 2: WebSocket 客户端实现

### Task 2.1: 创建 WebSocket 客户端

**Files:**
- Create: `src/core/openclaw/client-ws.ts`

- [ ] **Step 1: 创建 WebSocket 客户端基础类**

创建文件 `src/core/openclaw/client-ws.ts`：

```typescript
// src/core/openclaw/client-ws.ts
/**
 * OpenClaw WebSocket Gateway Client
 *
 * Manages WebSocket connection to OpenClaw Gateway with:
 * - Automatic request-response matching
 * - Event subscription
 * - Reconnection handling
 */

import type {
  GatewayRequest,
  GatewayResponse,
  GatewayEvent,
  GatewayMessage,
  PendingRequest,
  EventHandler,
  WSClientConfig,
  WSClientState,
} from "./types";

const DEFAULT_CONFIG: WSClientConfig = {
  url: "ws://127.0.0.1:18789",
  reconnectIntervalMs: 1000,
  maxReconnectAttempts: 5,
  requestTimeoutMs: 120000,
};

export class OpenClawWSClient {
  private ws: WebSocket | null = null;
  private config: WSClientConfig;
  private pendingRequests: Map<string, PendingRequest> = new Map();
  private eventListeners: Map<string, Set<EventHandler>> = new Map();
  private _state: WSClientState = "disconnected";
  private reconnectAttempts = 0;
  private requestId = 0;

  // State change callbacks
  private onStateChange?: (state: WSClientState) => void;

  constructor(config: Partial<WSClientConfig> = {}) {
    this.config = { ...DEFAULT_CONFIG, ...config };
  }

  /** Get current connection state */
  get state(): WSClientState {
    return this._state;
  }

  /** Set state change callback */
  setStateChangeCallback(callback: (state: WSClientState) => void): void {
    this.onStateChange = callback;
  }

  /** Update state and notify */
  private setState(state: WSClientState): void {
    this._state = state;
    this.onStateChange?.(state);
  }

  /** Check if connected */
  isConnected(): boolean {
    return this.ws?.readyState === WebSocket.OPEN;
  }

  /** Connect to Gateway */
  async connect(): Promise<void> {
    if (this.isConnected()) {
      return;
    }

    return new Promise((resolve, reject) => {
      this.setState("connecting");

      try {
        this.ws = new WebSocket(this.config.url);
      } catch (err) {
        this.setState("error");
        reject(new Error(`Failed to create WebSocket: ${err}`));
        return;
      }

      this.ws.onopen = () => {
        this.setState("connected");
        this.reconnectAttempts = 0;
        resolve();
      };

      this.ws.onerror = (event) => {
        this.setState("error");
        if (this._state === "connecting") {
          reject(new Error("WebSocket connection failed"));
        }
      };

      this.ws.onclose = () => {
        this.handleDisconnect();
      };

      this.ws.onmessage = (event) => {
        this.handleMessage(event.data);
      };
    });
  }

  /** Handle incoming message */
  private handleMessage(data: string): void {
    let message: GatewayMessage;
    try {
      message = JSON.parse(data);
    } catch {
      console.error("[OpenClawWS] Failed to parse message:", data);
      return;
    }

    if (message.type === "res") {
      this.handleResponse(message as GatewayResponse);
    } else if (message.type === "event") {
      this.handleEvent(message as GatewayEvent);
    }
  }

  /** Handle response message */
  private handleResponse(response: GatewayResponse): void {
    const pending = this.pendingRequests.get(response.id);
    if (!pending) {
      return;
    }

    clearTimeout(pending.timeout);
    this.pendingRequests.delete(response.id);

    if (response.ok) {
      pending.resolve(response.payload);
    } else {
      const error = response.error
        ? new Error(`${response.error.code}: ${response.error.message}`)
        : new Error("Unknown error");
      pending.reject(error);
    }
  }

  /** Handle event message */
  private handleEvent(event: GatewayEvent): void {
    const listeners = this.eventListeners.get(event.event);
    if (listeners) {
      for (const handler of listeners) {
        try {
          handler(event.payload);
        } catch (err) {
          console.error(`[OpenClawWS] Event handler error for ${event.event}:`, err);
        }
      }
    }
  }

  /** Handle disconnection */
  private handleDisconnect(): void {
    // Reject all pending requests
    for (const [id, pending] of this.pendingRequests) {
      clearTimeout(pending.timeout);
      pending.reject(new Error("Connection closed"));
    }
    this.pendingRequests.clear();

    // Try to reconnect
    if (this.reconnectAttempts < this.config.maxReconnectAttempts) {
      this.reconnectAttempts++;
      this.setState("connecting");
      setTimeout(() => {
        this.connect().catch(() => {
          // Reconnection failed, will retry
        });
      }, this.config.reconnectIntervalMs);
    } else {
      this.setState("disconnected");
    }
  }

  /** Send RPC request */
  async request<T = unknown>(method: string, params?: Record<string, unknown>): Promise<T> {
    if (!this.isConnected()) {
      throw new Error("Not connected to OpenClaw Gateway");
    }

    const id = `${++this.requestId}`;
    const request: GatewayRequest = {
      type: "req",
      id,
      method,
      params,
    };

    return new Promise<T>((resolve, reject) => {
      // Set up timeout
      const timeout = setTimeout(() => {
        this.pendingRequests.delete(id);
        reject(new Error(`Request timeout: ${method}`));
      }, this.config.requestTimeoutMs);

      // Store pending request
      this.pendingRequests.set(id, {
        resolve: resolve as (value: unknown) => void,
        reject,
        timeout,
      });

      // Send request
      this.ws!.send(JSON.stringify(request));
    });
  }

  /** Subscribe to event */
  on<T = unknown>(event: string, handler: EventHandler<T>): () => void {
    if (!this.eventListeners.has(event)) {
      this.eventListeners.set(event, new Set());
    }
    this.eventListeners.get(event)!.add(handler as EventHandler);

    // Return unsubscribe function
    return () => {
      this.off(event, handler);
    };
  }

  /** Unsubscribe from event */
  off<T = unknown>(event: string, handler: EventHandler<T>): void {
    const listeners = this.eventListeners.get(event);
    if (listeners) {
      listeners.delete(handler as EventHandler);
    }
  }

  /** Disconnect from Gateway */
  disconnect(): void {
    if (this.ws) {
      this.ws.close();
      this.ws = null;
    }
    this.setState("disconnected");
  }
}

// Singleton instance
let _wsClient: OpenClawWSClient | null = null;

export function getOpenClawWSClient(): OpenClawWSClient {
  if (!_wsClient) {
    _wsClient = new OpenClawWSClient();
  }
  return _wsClient;
}

export function resetOpenClawWSClient(): void {
  if (_wsClient) {
    _wsClient.disconnect();
    _wsClient = null;
  }
}
```

- [ ] **Step 2: 运行类型检查**

Run: `cd /Users/nasakim/projects/sman && npm run typecheck`

Expected: 通过，无错误

- [ ] **Step 3: Commit**

```bash
git add src/core/openclaw/client-ws.ts
git commit -m "feat(openclaw): add WebSocket client with request-response and event support"
```

---

## Chunk 3: 高级 API 封装

### Task 3.1: 创建高级 API

**Files:**
- Create: `src/core/openclaw/api.ts`

- [ ] **Step 1: 创建 OpenClawAPI 类**

创建文件 `src/core/openclaw/api.ts`：

```typescript
// src/core/openclaw/api.ts
/**
 * OpenClaw High-Level API
 *
 * Provides semantic methods for chat operations,
 * hiding WebSocket protocol details.
 */

import { getOpenClawWSClient, OpenClawWSClient } from "./client-ws";
import type {
  ChatSendParams,
  ChatSendResult,
  ChatEventPayload,
  ChatHistoryParams,
  ChatHistoryResult,
  ChatHistoryMessage,
  ConnectParams,
  ConnectResult,
  HealthResult,
  EventHandler,
} from "./types";

export class OpenClawAPI {
  private client: OpenClawWSClient;
  private connected = false;

  constructor(client: OpenClawWSClient = getOpenClawWSClient()) {
    this.client = client;

    // Track connection state
    this.client.setStateChangeCallback((state) => {
      this.connected = state === "connected";
    });
  }

  /** Connect to Gateway and authenticate */
  async connect(options?: { token?: string; password?: string }): Promise<void> {
    await this.client.connect();

    // Authenticate
    const params: ConnectParams = {};
    if (options?.token) params.token = options.token;
    if (options?.password) params.password = options.password;

    await this.client.request<ConnectResult>("connect", params);
    this.connected = true;
  }

  /** Check if connected */
  isConnected(): boolean {
    return this.connected && this.client.isConnected();
  }

  /** Send chat message */
  async sendMessage(
    sessionKey: string,
    message: string,
    options?: {
      thinking?: string;
      timeoutMs?: number;
    },
  ): Promise<{ runId: string }> {
    const params: ChatSendParams = {
      sessionKey,
      message,
      idempotencyKey: crypto.randomUUID(),
    };

    if (options?.thinking) params.thinking = options.thinking;
    if (options?.timeoutMs) params.timeoutMs = options.timeoutMs;

    const result = await this.client.request<ChatSendResult>("chat.send", params);
    return { runId: result.runId };
  }

  /** Subscribe to chat events */
  onChatEvent(handler: EventHandler<ChatEventPayload>): () => void {
    return this.client.on<ChatEventPayload>("chat.event", handler);
  }

  /** Get chat history */
  async getHistory(sessionKey: string, limit = 100): Promise<ChatHistoryMessage[]> {
    const params: ChatHistoryParams = { sessionKey, limit };
    const result = await this.client.request<ChatHistoryResult>("chat.history", params);
    return result.messages || [];
  }

  /** Abort chat run */
  async abort(sessionKey: string, runId?: string): Promise<void> {
    await this.client.request("chat.abort", { sessionKey, runId });
  }

  /** Health check */
  async healthCheck(): Promise<boolean> {
    try {
      const result = await this.client.request<HealthResult>("health");
      return result.status === "ok";
    } catch {
      return false;
    }
  }

  /** Disconnect from Gateway */
  disconnect(): void {
    this.client.disconnect();
    this.connected = false;
  }
}

// Singleton instance
let _api: OpenClawAPI | null = null;

export function getOpenClawAPI(): OpenClawAPI {
  if (!_api) {
    _api = new OpenClawAPI();
  }
  return _api;
}

export function resetOpenClawAPI(): void {
  if (_api) {
    _api.disconnect();
    _api = null;
  }
}
```

- [ ] **Step 2: 更新模块导出**

修改 `src/core/openclaw/index.ts`：

```typescript
// src/core/openclaw/index.ts
export * from "./types";
export * from "./client-ws";
export * from "./api";
```

- [ ] **Step 3: 删除旧的 HTTP 客户端**

Run: `rm /Users/nasakim/projects/sman/src/core/openclaw/client.ts`

- [ ] **Step 4: 运行类型检查**

Run: `cd /Users/nasakim/projects/sman && npm run typecheck`

Expected: 通过，无错误

- [ ] **Step 5: Commit**

```bash
git add src/core/openclaw/
git commit -m "feat(openclaw): add high-level API and remove HTTP client"
```

---

## Chunk 4: Svelte Store 集成

### Task 4.1: 创建 Svelte Store

**Files:**
- Create: `src/lib/api/openclaw.ts`

- [ ] **Step 1: 创建 OpenClaw Store**

创建文件 `src/lib/api/openclaw.ts`：

```typescript
// src/lib/api/openclaw.ts
/**
 * OpenClaw Svelte Store Integration
 *
 * Provides reactive state management for OpenClaw connection
 * and automatic initialization.
 */

import { writable, derived, get } from "svelte/store";
import { getOpenClawAPI, OpenClawAPI } from "../../core/openclaw/api";
import type { WSClientState, ChatEventPayload } from "../../core/openclaw/types";

// Connection state store
export const connectionState = writable<WSClientState>("disconnected");

// Connection error store
export const connectionError = writable<string | null>(null);

// API instance store
export const openClawAPI = writable<OpenClawAPI | null>(null);

// Is connected derived store
export const isConnected = derived(
  connectionState,
  ($state) => $state === "connected",
);

/** Initialize OpenClaw connection */
export async function initializeOpenClaw(): Promise<void> {
  const api = getOpenClawAPI();
  openClawAPI.set(api);

  // Set up state tracking
  api["client"].setStateChangeCallback((state) => {
    connectionState.set(state);
    if (state === "error") {
      connectionError.set("Connection failed");
    } else {
      connectionError.set(null);
    }
  });

  try {
    connectionState.set("connecting");
    await api.connect();
    connectionState.set("connected");
  } catch (err) {
    connectionState.set("error");
    connectionError.set(err instanceof Error ? err.message : "Connection failed");
    throw err;
  }
}

/** Subscribe to chat events with auto-reconnect handling */
export function subscribeToChatEvents(
  handler: (event: ChatEventPayload) => void,
): () => void {
  const api = getOpenClawAPI();
  if (!api) {
    console.warn("[OpenClaw] Not connected, cannot subscribe to chat events");
    return () => {};
  }

  return api.onChatEvent(handler);
}

/** Send message with error handling */
export async function sendChatMessage(
  sessionKey: string,
  message: string,
  options?: { thinking?: string; timeoutMs?: number },
): Promise<{ runId: string }> {
  const api = getOpenClawAPI();
  if (!api || !api.isConnected()) {
    throw new Error("Not connected to OpenClaw");
  }

  return api.sendMessage(sessionKey, message, options);
}

/** Disconnect OpenClaw */
export function disconnectOpenClaw(): void {
  const api = getOpenClawAPI();
  if (api) {
    api.disconnect();
  }
  openClawAPI.set(null);
  connectionState.set("disconnected");
  connectionError.set(null);
}
```

- [ ] **Step 2: 运行类型检查**

Run: `cd /Users/nasakim/projects/sman && npm run typecheck`

Expected: 通过，无错误

- [ ] **Step 3: Commit**

```bash
git add src/lib/api/openclaw.ts
git commit -m "feat: add OpenClaw Svelte store integration"
```

---

## Chunk 5: Sidecar 配置更新

### Task 5.1: 更新 Sidecar 端口配置

**Files:**
- Modify: `src-tauri/src/commands/sidecar.rs`

- [ ] **Step 1: 更新端口为 18789**

修改 `src-tauri/src/commands/sidecar.rs` 第 43 行：

将 `"127.0.0.1:3000"` 改为 `"127.0.0.1:18789"`：

```rust
let addr: SocketAddr = "127.0.0.1:18789"
    .parse()
    .map_err(|e: std::net::AddrParseError| e.to_string())?;
```

- [ ] **Step 2: 验证编译**

Run: `cd /Users/nasakim/projects/sman && cargo check --manifest-path src-tauri/Cargo.toml`

Expected: 编译通过

- [ ] **Step 3: Commit**

```bash
git add src-tauri/src/commands/sidecar.rs
git commit -m "fix: update OpenClaw Gateway port to 18789"
```

---

## Chunk 6: ChatWindow 集成

### Task 6.1: 更新 ChatWindow 使用 WebSocket API

**Files:**
- Modify: `src/components/chat/ChatWindow.svelte`

- [ ] **Step 1: 添加 OpenClaw 导入**

在文件顶部导入区域添加：

```typescript
import {
  initializeOpenClaw,
  isConnected,
  connectionState,
  connectionError,
  sendChatMessage,
  subscribeToChatEvents,
} from "../../lib/api/openclaw";
import type { ChatEventPayload } from "../../core/openclaw/types";
```

- [ ] **Step 2: 添加 OpenClaw 初始化逻辑**

在 `onMount` 中添加 OpenClaw 初始化：

```typescript
onMount(() => {
  tasksStore.initialize();

  // Initialize OpenClaw connection
  initializeOpenClaw().catch((err) => {
    console.error("[Chat] Failed to initialize OpenClaw:", err);
  });

  // ... rest of existing onMount code
});
```

- [ ] **Step 3: 修改 handleSubmit 使用 WebSocket API**

修改 `handleSubmit` 函数：

```typescript
async function handleSubmit(prompt: string) {
  if (!$selectedProject) return;

  const projectId = $selectedProject.id;
  completionSignalAtByProject[projectId] = null;
  clearProgressTimeline(projectId);

  const currentMessages =
    messagesByProject[projectId] || getDemoMessages($selectedProject.name);
  const userMessage: Message = {
    id: createLocalMessageId(),
    role: "user",
    content: prompt,
    timestamp: Date.now(),
  };
  const updatedMessages = [...currentMessages, userMessage];
  messagesByProject[projectId] = updatedMessages;
  isSending = true;
  sendingProjectId = projectId;

  const assistantMessage: Message = {
    id: createLocalMessageId(),
    role: "assistant",
    content: "处理中...",
    timestamp: Date.now(),
  };
  messagesByProject[projectId] = [...updatedMessages, assistantMessage];

  try {
    // Use session key from conversation or create one
    const sessionKey = conversationByProject[projectId] || projectId;

    // Send via WebSocket
    const { runId } = await sendChatMessage(sessionKey, prompt);

    // Subscribe to events for this run
    const unsubscribe = subscribeToChatEvents((event: ChatEventPayload) => {
      if (event.runId !== runId) return;

      if (event.state === "delta" && event.message?.content) {
        updateLatestThinkingMessage(projectId, event.message.content);
      } else if (event.state === "final") {
        if (event.message?.content) {
          updateLatestThinkingMessage(projectId, event.message.content);
        }
        clearProgressTimeline(projectId);
        stopSending(projectId);
        unsubscribe();
      } else if (event.state === "error") {
        updateLatestThinkingMessage(
          projectId,
          `错误：${event.errorMessage || "未知错误"}`,
        );
        clearProgressTimeline(projectId);
        stopSending(projectId);
        unsubscribe();
      } else if (event.state === "aborted") {
        updateLatestThinkingMessage(projectId, "对话已中止");
        clearProgressTimeline(projectId);
        stopSending(projectId);
        unsubscribe();
      }
    });
  } catch (error) {
    stopSending(projectId);
    messagesByProject[projectId] = [
      ...updatedMessages,
      {
        ...assistantMessage,
        content: `错误：${error instanceof Error ? error.message : "发送消息失败"}`,
      },
    ];
  }
}
```

- [ ] **Step 4: 添加连接状态检查**

修改 `InputArea` 组件的 `disabled` 属性：

```svelte
<InputArea
  disabled={isSending || !$selectedProject || !$isConnected}
  placeholder={$selectedProject
    ? $isConnected
      ? "输入 / 使用命令"
      : "正在连接 AI 服务..."
    : "请先添加项目"}
  onSubmit={handleSubmit}
/>
```

- [ ] **Step 5: 添加连接状态提示（可选）**

在聊天区域顶部添加连接状态提示：

```svelte
{#if $connectionState === "error"}
  <div class="connection-error">
    连接失败：{$connectionError}
    <button onclick={() => initializeOpenClaw()}>重试</button>
  </div>
{/if}

{#if $connectionState === "connecting"}
  <div class="connection-status">正在连接 AI 服务...</div>
{/if}
```

添加样式：

```css
.connection-error {
  background-color: #fee2e2;
  color: #dc2626;
  padding: 0.5rem 1rem;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.connection-error button {
  background: none;
  border: 1px solid #dc2626;
  color: #dc2626;
  padding: 0.25rem 0.5rem;
  cursor: pointer;
}

.connection-status {
  background-color: #fef3c7;
  color: #d97706;
  padding: 0.5rem 1rem;
}
```

- [ ] **Step 6: 运行类型检查**

Run: `cd /Users/nasakim/projects/sman && npm run typecheck`

Expected: 通过，无错误

- [ ] **Step 7: Commit**

```bash
git add src/components/chat/ChatWindow.svelte
git commit -m "feat(chat): integrate OpenClaw WebSocket API"
```

---

## Chunk 7: Tauri CSP 配置

### Task 7.1: 更新 CSP 允许 WebSocket 连接

**Files:**
- Modify: `src-tauri/tauri.conf.json`

- [ ] **Step 1: 更新 CSP 配置**

修改 `src-tauri/tauri.conf.json` 中的 `security.csp`：

将：
```json
"csp": "default-src 'self'; connect-src 'self' https://api.openai.com https://api.anthropic.com; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'"
```

改为：
```json
"csp": "default-src 'self'; connect-src 'self' ws://127.0.0.1:18789 https://api.openai.com https://api.anthropic.com; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'"
```

- [ ] **Step 2: Commit**

```bash
git add src-tauri/tauri.conf.json
git commit -m "fix: allow WebSocket connections in CSP"
```

---

## Chunk 8: 测试和验证

### Task 8.1: 端到端测试

**Files:**
- No new files

- [ ] **Step 1: 启动 OpenClaw Gateway**

首先确保 OpenClaw Gateway 正在运行：

Run: `cd /Users/nasakim/projects/openclaw && npm run dev -- gateway`

Expected: Gateway 在 `ws://127.0.0.1:18789` 监听

- [ ] **Step 2: 启动 SMAN 应用**

Run: `cd /Users/nasakim/projects/sman && npm run tauri dev`

Expected: 应用正常启动

- [ ] **Step 3: 测试聊天功能**

1. 添加一个项目
2. 在输入框输入 "你好"
3. 验证：
   - 消息发送成功
   - 显示 "处理中..." 状态
   - 收到 AI 回复（流式更新）
   - 回复完成后状态正常

- [ ] **Step 4: 测试错误处理**

1. 关闭 OpenClaw Gateway
2. 尝试发送消息
3. 验证：
   - 显示连接错误提示
   - 重试按钮可用

- [ ] **Step 5: 最终 Commit**

```bash
git add .
git commit -m "feat: complete OpenClaw WebSocket integration"
```

---

## 验收清单

### 配置打包

- [ ] `~/.smanlocal/` 目录在首次启动时自动创建
- [ ] 默认 `openclaw.json` 配置正确生成
- [ ] 默认 Workspace 提示词文件正确创建
- [ ] Sidecar 启动时使用正确的环境变量

### WebSocket 连接

- [ ] WebSocket 连接正常建立
- [ ] `chat.send` 发送消息成功
- [ ] `chat.event` 流式响应正常接收
- [ ] UI 正确显示增量更新
- [ ] 错误处理正常工作
- [ ] 断线重连机制有效
- [ ] 连接状态 UI 提示正常

### Sidecar 打包

- [ ] OpenClaw 编译为独立可执行文件
- [ ] Sidecar 随 Tauri 应用打包
- [ ] 首次启动时自动初始化配置
- [ ] 业务人员无需额外安装即可使用

---

**Plan complete and saved to `docs/superpowers/plans/2026-03-11-sman-openclaw-ws-integration-plan.md`. Ready to execute?**
