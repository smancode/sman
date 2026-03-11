# SMAN WebSocket 稳定性增强设计

**日期**: 2026-03-11
**状态**: 设计完成，待实施
**目标**: 增强 WebSocket 连接稳定性，实现 WebSearch API Key 同步

---

## 1. 背景

### 1.1 当前问题

SMAN 已有基础的 WebSocket 客户端实现，但缺少：
- 生产级重连策略（当前是固定间隔重试）
- 健康检查机制
- WebSearch API Key 同步到 OpenClaw

### 1.2 目标

借鉴 ClawX 最佳实践，以**最简单的方式**实现：
1. 指数退避重连策略
2. 健康检查（ping/pong）
3. WebSearch API Key 环境变量同步

**不做**（保持简单）：
- 设备身份认证
- 配置热重载
- Sidecar 进程监控
- 复杂的连接 UI 指示器

---

## 2. 设计方案

### 2.1 连接状态机

```
disconnected → connecting → connected
     ↑                           ↓
     └────── reconnecting ←──────┘
```

| 状态 | 说明 |
|------|------|
| `disconnected` | 未连接，不自动重连 |
| `connecting` | 正在建立 WebSocket 连接 |
| `connected` | 已连接并通过认证 |
| `reconnecting` | 连接断开后自动重连中 |

### 2.2 指数退避重连

```typescript
interface ReconnectConfig {
  baseDelayMs: number;      // 1000ms - 初始延迟
  maxDelayMs: number;       // 30000ms - 最大延迟
  maxAttempts: number;      // 10 - 最大重试次数
  backoffFactor: number;    // 1.5 - 退避因子
}

// 延迟计算: min(maxDelayMs, baseDelayMs * backoffFactor ^ attempts)
// 示例: 1000 -> 1500 -> 2250 -> 3375 -> 5062 -> 7593 -> 11390 -> 17085 -> 25627 -> 30000
```

**策略**：
- 连接断开后自动触发重连
- 达到最大重试次数后停止，等待手动重连
- 成功连接后重置重试计数器

### 2.3 健康检查

**机制**：
- 使用 WebSocket 原生 ping/pong
- 每 30 秒发送一次 ping
- 60 秒无响应视为连接不健康，触发重连

```typescript
interface HealthCheckConfig {
  pingIntervalMs: number;   // 30000ms - ping 间隔
  pongTimeoutMs: number;    // 60000ms - pong 超时
}
```

### 2.4 WebSearch API Key 同步

**OpenClaw 支持的环境变量**：

| 环境变量 | SMAN 设置字段 | 说明 |
|---------|--------------|------|
| `BRAVE_API_KEY` | `webSearch.braveApiKey` | Brave Search |
| `TAVILY_API_KEY` | `webSearch.tavilyApiKey` | Tavily Search |
| `BING_API_KEY` | `webSearch.bingApiKey` | Bing Search |

**同步时机**：Sidecar 启动时传入环境变量

**实现**：
```rust
// sidecar.rs - 启动时
let mut env_vars = Vec::new();
if !settings.web_search.brave_api_key.is_empty() {
    env_vars.push(("BRAVE_API_KEY", settings.web_search.brave_api_key.clone()));
}
if !settings.web_search.tavily_api_key.is_empty() {
    env_vars.push(("TAVILY_API_KEY", settings.web_search.tavily_api_key.clone()));
}
if !settings.web_search.bing_api_key.is_empty() {
    env_vars.push(("BING_API_KEY", settings.web_search.bing_api_key.clone()));
}
```

---

## 3. 组件设计

### 3.1 类型定义更新

**文件**: `src/core/openclaw/types.ts`

```typescript
// 连接状态
export type WSClientState = "disconnected" | "connecting" | "connected" | "reconnecting";

// 重连配置
export interface ReconnectConfig {
  baseDelayMs: number;
  maxDelayMs: number;
  maxAttempts: number;
  backoffFactor: number;
}

// 健康检查配置
export interface HealthCheckConfig {
  enabled: boolean;
  pingIntervalMs: number;
  pongTimeoutMs: number;
}

// 客户端配置（扩展）
export interface WSClientConfig {
  url: string;
  reconnect: ReconnectConfig;
  healthCheck: HealthCheckConfig;
  requestTimeoutMs: number;
}
```

### 3.2 客户端增强

**文件**: `src/core/openclaw/client-ws.ts`

**新增方法**：

```typescript
export class OpenClawWSClient {
  // 健康检查
  private startHealthCheck(): void;
  private stopHealthCheck(): void;
  private handlePong(): void;

  // 重连策略
  private scheduleReconnect(): void;
  private getReconnectDelay(): number;
  private resetReconnectState(): void;

  // 手动重连（达到最大重试后）
  async manualReconnect(): Promise<void>;
}
```

**关键改动**：

1. 状态变更时通知回调
2. 连接断开时自动触发重连
3. 健康检查失败时触发重连
4. 支持手动重连（最大重试后）

### 3.3 Sidecar 启动更新

**文件**: `src-tauri/src/commands/sidecar.rs`

**改动**：
1. 读取 SMAN 设置中的 WebSearch API Key
2. 启动 Sidecar 时作为环境变量传入

---

## 4. 文件清单

### 4.1 修改文件

| 文件 | 改动 |
|------|------|
| `src/core/openclaw/types.ts` | 添加 ReconnectConfig, HealthCheckConfig, 更新 WSClientState |
| `src/core/openclaw/client-ws.ts` | 实现指数退避重连 + 健康检查 |
| `src-tauri/src/commands/sidecar.rs` | 传入 WebSearch 环境变量 |

### 4.2 不需要修改

- `src/core/openclaw/api.ts` - 无需改动，底层能力由 client-ws.ts 提供
- `src/lib/api/openclaw.ts` - Svelte Store 层无需改动
- 前端组件 - 暂不添加连接状态 UI

---

## 5. 验收标准

### 5.1 重连机制

- [ ] 连接断开后自动触发重连
- [ ] 重连延迟按指数退避增加
- [ ] 达到最大重试后停止，状态变为 `disconnected`
- [ ] 成功连接后重置重试计数

### 5.2 健康检查

- [ ] 连接成功后启动健康检查
- [ ] 定期发送 ping
- [ ] 收到 pong 后重置超时计时
- [ ] 超时后触发重连

### 5.3 WebSearch 同步

- [ ] Sidecar 启动时传入非空的 API Key
- [ ] OpenClaw 能够使用配置的 WebSearch 功能

---

## 6. 实施顺序

1. **类型定义** - 更新 `types.ts`
2. **重连策略** - 更新 `client-ws.ts` 的重连逻辑
3. **健康检查** - 在 `client-ws.ts` 添加 ping/pong 机制
4. **环境变量** - 更新 `sidecar.rs` 传入 WebSearch API Key
5. **测试验证** - 手动测试断线重连和 WebSearch 功能
