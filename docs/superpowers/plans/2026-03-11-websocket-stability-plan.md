# WebSocket 稳定性增强实施计划

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 增强 WebSocket 连接稳定性，实现指数退避重连、健康检查和 WebSearch API Key 同步

**Architecture:** 扩展现有 `OpenClawWSClient` 类，添加重连策略和健康检查机制；修改 `sidecar.rs` 在启动时传入 WebSearch 环境变量

**Tech Stack:** TypeScript (SvelteKit), Rust (Tauri), WebSocket

---

## File Structure

| 文件 | 职责 | 操作 |
|------|------|------|
| `src/core/openclaw/types.ts` | 类型定义 | 修改 - 添加 ReconnectConfig, HealthCheckConfig |
| `src/core/openclaw/client-ws.ts` | WebSocket 客户端 | 修改 - 实现重连策略和健康检查 |
| `src-tauri/src/commands/sidecar.rs` | Sidecar 管理 | 修改 - 传入 WebSearch 环境变量 |

---

## Chunk 1: 类型定义更新

### Task 1: 更新 types.ts 添加新类型

**Files:**
- Modify: `src/core/openclaw/types.ts`

- [ ] **Step 1: 添加 ReconnectConfig 和 HealthCheckConfig 类型**

在 `types.ts` 文件末尾，替换现有的 `WSClientConfig` 和 `WSClientState` 定义：

```typescript
// ============================================
// Reconnection & Health Check Types
// ============================================

/** 重连配置 */
export interface ReconnectConfig {
  /** 初始延迟（毫秒） */
  baseDelayMs: number;
  /** 最大延迟（毫秒） */
  maxDelayMs: number;
  /** 最大重试次数 */
  maxAttempts: number;
  /** 退避因子 */
  backoffFactor: number;
}

/** 健康检查配置 */
export interface HealthCheckConfig {
  /** 是否启用 */
  enabled: boolean;
  /** ping 间隔（毫秒） */
  pingIntervalMs: number;
  /** pong 超时（毫秒） */
  pongTimeoutMs: number;
}

/** WebSocket 客户端配置（更新版） */
export interface WSClientConfig {
  url: string;
  /** @deprecated 使用 reconnect 替代 */
  reconnectIntervalMs?: number;
  /** @deprecated 使用 reconnect.maxAttempts 替代 */
  maxReconnectAttempts?: number;
  requestTimeoutMs: number;
  /** 重连配置 */
  reconnect: ReconnectConfig;
  /** 健康检查配置 */
  healthCheck: HealthCheckConfig;
}

/** 客户端状态（更新版） */
export type WSClientState =
  | "disconnected"
  | "connecting"
  | "connected"
  | "reconnecting";
```

- [ ] **Step 2: 运行类型检查**

Run: `pnpm typecheck`
Expected: PASS（类型兼容，向后兼容旧字段）

- [ ] **Step 3: Commit**

```bash
git add src/core/openclaw/types.ts
git commit -m "feat(openclaw): add ReconnectConfig and HealthCheckConfig types

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>"
```

---

## Chunk 2: 客户端重连策略实现

### Task 2: 实现指数退避重连

**Files:**
- Modify: `src/core/openclaw/client-ws.ts`

- [ ] **Step 1: 更新默认配置**

替换 `DEFAULT_CONFIG`:

```typescript
const DEFAULT_CONFIG: WSClientConfig = {
  url: "ws://127.0.0.1:18789",
  requestTimeoutMs: 120000,
  reconnect: {
    baseDelayMs: 1000,
    maxDelayMs: 30000,
    maxAttempts: 10,
    backoffFactor: 1.5,
  },
  healthCheck: {
    enabled: true,
    pingIntervalMs: 30000,
    pongTimeoutMs: 60000,
  },
};
```

- [ ] **Step 2: 添加新的私有字段**

在 `OpenClawWSClient` 类中，添加新字段（在 `requestId` 之后）:

```typescript
  // Health check
  private pingInterval: ReturnType<typeof setInterval> | null = null;
  private pongTimeout: ReturnType<typeof setTimeout> | null = null;
  private lastPongTime = 0;

  // Reconnect
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private shouldAutoReconnect = true;
```

- [ ] **Step 3: 实现重连延迟计算方法**

添加新方法（在 `setState` 方法之后）:

```typescript
  /** Calculate reconnect delay with exponential backoff */
  private getReconnectDelay(): number {
    const { baseDelayMs, maxDelayMs, backoffFactor } = this.config.reconnect;
    const delay = baseDelayMs * Math.pow(backoffFactor, this.reconnectAttempts);
    return Math.min(delay, maxDelayMs);
  }

  /** Reset reconnect state after successful connection */
  private resetReconnectState(): void {
    this.reconnectAttempts = 0;
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
  }
```

- [ ] **Step 4: 实现健康检查方法**

添加新方法（在 `resetReconnectState` 之后）:

```typescript
  /** Start health check (ping/pong) */
  private startHealthCheck(): void {
    if (!this.config.healthCheck.enabled || !this.ws) return;

    this.stopHealthCheck();
    this.lastPongTime = Date.now();

    // Set up pong listener
    this.ws.on("pong" as unknown as WebSocketEventMap["message"], () => {
      this.lastPongTime = Date.now();
      if (this.pongTimeout) {
        clearTimeout(this.pongTimeout);
        this.pongTimeout = null;
      }
    });

    // Start ping interval
    this.pingInterval = setInterval(() => {
      if (this.ws?.readyState === WebSocket.OPEN) {
        this.ws.ping();
        this.startPongTimeout();
      }
    }, this.config.healthCheck.pingIntervalMs);
  }

  /** Start pong timeout */
  private startPongTimeout(): void {
    if (this.pongTimeout) {
      clearTimeout(this.pongTimeout);
    }
    this.pongTimeout = setTimeout(() => {
      const elapsed = Date.now() - this.lastPongTime;
      if (elapsed > this.config.healthCheck.pongTimeoutMs) {
        console.warn("[OpenClawWS] Health check failed, triggering reconnect");
        this.triggerReconnect("health_check_timeout");
      }
    }, this.config.healthCheck.pongTimeoutMs);
  }

  /** Stop health check */
  private stopHealthCheck(): void {
    if (this.pingInterval) {
      clearInterval(this.pingInterval);
      this.pingInterval = null;
    }
    if (this.pongTimeout) {
      clearTimeout(this.pongTimeout);
      this.pongTimeout = null;
    }
  }
```

- [ ] **Step 5: 添加触发重连方法**

添加新方法（在 `stopHealthCheck` 之后）:

```typescript
  /** Trigger reconnection */
  private triggerReconnect(reason: string): void {
    console.log(`[OpenClawWS] Triggering reconnect: ${reason}`);
    this.stopHealthCheck();

    if (this.ws) {
      this.ws.close();
      this.ws = null;
    }

    this.handleDisconnect();
  }
```

- [ ] **Step 6: 更新 connect 方法**

替换现有的 `connect` 方法，在 `onopen` 中启动健康检查：

```typescript
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
        this.setState("disconnected");
        reject(new Error(`Failed to create WebSocket: ${err}`));
        return;
      }

      this.ws.onopen = () => {
        this.setState("connected");
        this.resetReconnectState();
        this.startHealthCheck();
        resolve();
      };

      this.ws.onerror = (err) => {
        console.error("[OpenClawWS] WebSocket error:", err);
        if (this._state === "connecting" || this._state === "reconnecting") {
          this.setState("disconnected");
          reject(new Error("WebSocket connection failed"));
        }
      };

      this.ws.onclose = () => {
        this.stopHealthCheck();
        this.handleDisconnect();
      };

      this.ws.onmessage = (event) => {
        this.handleMessage(event.data);
      };
    });
  }
```

- [ ] **Step 7: 更新 handleDisconnect 方法**

替换现有的 `handleDisconnect` 方法：

```typescript
  /** Handle disconnection */
  private handleDisconnect(): void {
    // Reject all pending requests
    for (const [, pending] of this.pendingRequests) {
      clearTimeout(pending.timeout);
      pending.reject(new Error("Connection closed"));
    }
    this.pendingRequests.clear();

    // Clear health check
    this.stopHealthCheck();

    // Check if we should auto-reconnect
    const { maxAttempts } = this.config.reconnect;
    if (!this.shouldAutoReconnect || this.reconnectAttempts >= maxAttempts) {
      console.log(`[OpenClawWS] Stopping reconnect (attempts: ${this.reconnectAttempts}/${maxAttempts})`);
      this.setState("disconnected");
      return;
    }

    // Schedule reconnect with exponential backoff
    this.reconnectAttempts++;
    const delay = this.getReconnectDelay();
    console.log(`[OpenClawWS] Scheduling reconnect ${this.reconnectAttempts}/${maxAttempts} in ${delay}ms`);

    this.setState("reconnecting");
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      this.connect().catch((err) => {
        console.error("[OpenClawWS] Reconnect failed:", err);
        // handleDisconnect will be called again by onclose
      });
    }, delay);
  }
```

- [ ] **Step 8: 更新 disconnect 方法**

替换现有的 `disconnect` 方法：

```typescript
  /** Disconnect from Gateway */
  disconnect(): void {
    this.shouldAutoReconnect = false;
    this.stopHealthCheck();
    this.resetReconnectState();

    if (this.ws) {
      this.ws.close();
      this.ws = null;
    }
    this.setState("disconnected");
  }

  /** Manual reconnect after max attempts reached */
  async manualReconnect(): Promise<void> {
    this.reconnectAttempts = 0;
    this.shouldAutoReconnect = true;
    return this.connect();
  }
```

- [ ] **Step 9: 运行类型检查**

Run: `pnpm typecheck`
Expected: PASS

- [ ] **Step 10: Commit**

```bash
git add src/core/openclaw/client-ws.ts
git commit -m "feat(openclaw): implement exponential backoff reconnect and health check

- Add exponential backoff for reconnection attempts
- Add ping/pong health check mechanism
- Support manual reconnect after max attempts
- Update state machine with 'reconnecting' state

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>"
```

---

## Chunk 3: WebSearch 环境变量同步

### Task 3: 修改 sidecar.rs 传入 WebSearch API Key

**Files:**
- Modify: `src-tauri/src/commands/sidecar.rs`
- Modify: `src-tauri/src/commands/settings.rs`

- [ ] **Step 1: 在 settings.rs 添加 WebSearch 设置结构体**

在 `WebSearchSettings` 结构体之后添加获取方法（如果还没有的话）：

```rust
#[tauri::command]
pub fn get_web_search_env_vars() -> Vec<(String, String)> {
    let settings = get_app_settings().unwrap_or_default();
    let mut vars = Vec::new();

    if !settings.webSearch.braveApiKey.is_empty() {
        vars.push(("BRAVE_API_KEY".to_string(), settings.webSearch.braveApiKey));
    }
    if !settings.webSearch.tavilyApiKey.is_empty() {
        vars.push(("TAVILY_API_KEY".to_string(), settings.webSearch.tavilyApiKey));
    }
    if !settings.webSearch.bingApiKey.is_empty() {
        vars.push(("BING_API_KEY".to_string(), settings.webSearch.bingApiKey));
    }

    vars
}
```

- [ ] **Step 2: 更新 sidecar.rs 传入环境变量**

替换 `start_openclaw_server` 函数：

```rust
#[tauri::command]
pub async fn start_openclaw_server(app: tauri::AppHandle) -> Result<String, String> {
    if SERVER_RUNNING.load(Ordering::SeqCst) {
        return Ok("OpenClaw server already running".to_string());
    }

    // Get isolated config directory
    let sman_dir = get_sman_local_dir();
    let config_path = sman_dir.join("openclaw.json");

    // Ensure config directory exists
    if !sman_dir.exists() {
        std::fs::create_dir_all(&sman_dir)
            .map_err(|e| format!("Failed to create config dir: {}", e))?;
    }

    let sidecar = app
        .shell()
        .sidecar("openclaw-server")
        .map_err(|e| format!("Failed to create sidecar: {}", e))?;

    // Get WebSearch API keys from settings
    let web_search_vars = crate::commands::settings::get_web_search_env_vars();

    // Set environment variables for isolated configuration
    let mut sidecar_env = sidecar
        .env("OPENCLAW_CONFIG_PATH", config_path.to_string_lossy().to_string())
        .env("OPENCLAW_STATE_DIR", sman_dir.to_string_lossy().to_string());

    // Add WebSearch API keys
    for (key, value) in web_search_vars {
        sidecar_env = sidecar_env.env(&key, value);
    }

    let (mut _rx, _child) = sidecar_env
        .spawn()
        .map_err(|e| format!("Failed to spawn sidecar: {}", e))?;

    SERVER_RUNNING.store(true, Ordering::SeqCst);
    Ok(format!(
        "OpenClaw server started on port {} with config at {:?}",
        OPENCLAW_PORT, config_path
    ))
}
```

- [ ] **Step 3: 确保 settings 模块导出 get_web_search_env_vars**

在 `src-tauri/src/commands/mod.rs` 中确认导出：

```rust
pub mod settings;
// 确保在 pub use 或 use 语句中导出 get_web_search_env_vars
```

- [ ] **Step 4: 运行 Rust 检查**

Run: `cd src-tauri && cargo check`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src-tauri/src/commands/sidecar.rs src-tauri/src/commands/settings.rs src-tauri/src/commands/mod.rs
git commit -m "feat(sidecar): pass WebSearch API keys as environment variables

- Add get_web_search_env_vars to settings module
- Pass BRAVE_API_KEY, TAVILY_API_KEY, BING_API_KEY to sidecar

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>"
```

---

## Chunk 4: 测试验证

### Task 4: 手动测试

- [ ] **Step 1: 编译前端**

Run: `pnpm build:vite`
Expected: BUILD SUCCESS

- [ ] **Step 2: 编译 Tauri**

Run: `pnpm tauri build` 或 `pnpm tauri dev`
Expected: APP STARTS

- [ ] **Step 3: 测试重连机制**

手动测试步骤：
1. 启动 SMAN 应用
2. 确认 WebSocket 连接成功
3. 手动关闭 OpenClaw Sidecar 进程
4. 观察控制台日志，确认重连尝试和指数退避
5. 重启 Sidecar，确认自动重连成功

- [ ] **Step 4: 测试健康检查**

手动测试步骤：
1. 启动 SMAN 应用
2. 确认 WebSocket 连接成功
3. 观察是否有定期 ping 日志
4. 模拟网络延迟，确认超时后触发重连

- [ ] **Step 5: 测试 WebSearch API Key**

手动测试步骤：
1. 在设置中配置 Brave API Key
2. 重启应用
3. 检查 Sidecar 进程的环境变量
4. 测试 web_search 功能是否可用

- [ ] **Step 6: Final Commit**

```bash
git add -A
git commit -m "test: verify WebSocket stability and WebSearch integration

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>"
```

---

## 验收清单

- [ ] 连接断开后自动触发重连
- [ ] 重连延迟按指数退避增加（1s → 1.5s → 2.25s → ...）
- [ ] 达到最大重试（10次）后停止，状态变为 `disconnected`
- [ ] 成功连接后重置重试计数
- [ ] 连接成功后启动健康检查
- [ ] 定期发送 ping（30s 间隔）
- [ ] 超时后（60s 无响应）触发重连
- [ ] Sidecar 启动时传入非空的 WebSearch API Key
- [ ] TypeScript 类型检查通过
- [ ] Rust 编译检查通过
