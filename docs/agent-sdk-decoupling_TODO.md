# Agent SDK 解耦计划

> 当前 Claude Agent SDK 耦合度过高，未来需切换为自研 Agent 框架或其他 SDK。
> 本文档记录耦合现状和解耦方案，作为后续重构的参考。

## 耦合现状

SDK 直接散布在 8 个源文件中，**没有抽象层**。

### 涉及文件

| 文件 | SDK 用途 | 切换难度 |
|------|---------|---------|
| `server/claude-session.ts` | Session 生命周期（创建、发送、流式迭代、中断） | **高**（核心，~1580 行） |
| `server/stardom/stardom-mcp.ts` | In-process MCP Server 创建 | 低 |
| `server/capabilities/gateway-mcp-server.ts` | In-process MCP Server + 动态注入 | 中 |
| `server/capabilities/office-skills-runner.ts` | `tool()` 定义 | 低 |
| `server/web-access/mcp-server.ts` | In-process MCP Server 创建 | 低 |
| `server/mcp-config.ts` | `McpServerConfig` 类型 | 低 |
| `server/batch-engine.ts` | `query()` 一次性查询 | 低 |
| `server/init/capability-matcher.ts` | `unstable_v2_prompt` 一次性查询 | 低 |
| `scripts/patch-sdk.mjs` | 直接修改 SDK 内部源码 | **高**（依赖内部实现） |

### 6 个耦合点

1. **Session 生命周期**：`unstable_v2_createSession` + `SDKSession.send()` / `.stream()`
2. **动态 MCP 注入**：`SDKSession.setMcpServers()` — 运行时向会话注入新工具
3. **In-process MCP Server**：`createSdkMcpServer()` + `tool()` — 4 个模块使用
4. **一次性查询**：`query()` / `unstable_v2_prompt()` — 批量引擎和能力匹配
5. **外部 MCP 配置类型**：`McpServerConfig` stdio 类型
6. **Patch 脚本**：直接改 SDK 源码，添加 `interrupt`、`setModel`、`setPermissionMode` 等方法，修改 `ProcessTransport` 传参

### 依赖 SDK 内部行为的细节

- `process.chdir()` workaround：SDK 的 `unstable_v2_createSession` 不传 `cwd` 给 `ProcessTransport`，只能临时切工作目录
- `(session as any).pid`：通过未文档化的属性做进程存活检查
- `(session as any).interrupt?.()`：patch 脚本添加的方法，用于中止查询
- Stream event 格式（`assistant`、`stream_event`、`tool_progress`、`result`、`system`）：前端 WebSocket 协议围绕这些类型构建

## 解耦方案

### 目标

定义一个 Agent Adapter 接口，让业务代码只依赖接口，不依赖具体 SDK 实现。

### 核心接口设计

```typescript
/**
 * Agent 会话句柄
 */
interface SessionHandle {
  sessionId: string;
  send(content: string | UserMessage): AsyncIterable<AgentEvent>;
  abort(): void;
  close(): void;
  setMcpServers(servers: McpServerDefinition[]): void;
}

/**
 * Agent 事件（SDK 无关的统一事件类型）
 */
type AgentEvent =
  | { type: 'text_delta'; text: string }
  | { type: 'thinking_delta'; text: string }
  | { type: 'tool_start'; toolName: string; toolUseId: string }
  | { type: 'tool_delta'; toolUseId: string; delta: string }
  | { type: 'tool_result'; toolUseId: string; result: string }
  | { type: 'done'; cost?: CostInfo; usage?: UsageInfo }
  | { type: 'error'; error: string };

/**
 * Agent Adapter — 所有 Agent SDK 的统一抽象
 */
interface AgentAdapter {
  createSession(workspace: string, options: SessionOptions): Promise<SessionHandle>;
  resumeSession(sessionId: string, workspace: string): Promise<SessionHandle>;
  closeSession(sessionId: string): void;
  query(prompt: string, options: QueryOptions): Promise<string>;
}

/**
 * MCP 工具定义（不依赖 SDK 专有 API）
 */
interface McpToolDefinition {
  name: string;
  description: string;
  inputSchema: Record<string, unknown>;
  handler: (params: unknown) => Promise<unknown>;
}

interface McpServerDefinition {
  name: string;
  version: string;
  tools: McpToolDefinition[];
}
```

### 重构步骤

#### Phase 1：定义接口 + 实现 Claude Adapter（最小改动）

1. 创建 `server/agent/agent-adapter.ts` — 定义上述接口
2. 创建 `server/agent/claude-adapter.ts` — 实现 `AgentAdapter`，封装当前所有 SDK 调用
3. 创建 `server/agent/mcp-server.ts` — 封装 MCP Server 创建逻辑（不再直接调用 `createSdkMcpServer`）
4. `claude-session.ts` 改为依赖 `AgentAdapter` 接口而非 SDK
5. 验证：功能不变，所有测试通过

**改动量**：~300-500 行重构，不动功能

#### Phase 2：迁移 MCP 模块

1. `web-access/mcp-server.ts` → 使用 `agent/mcp-server.ts` 的封装
2. `stardom/stardom-mcp.ts` → 同上
3. `capabilities/gateway-mcp-server.ts` → 同上
4. `capabilities/office-skills-runner.ts` → 同上
5. 移除 `scripts/patch-sdk.mjs` 中的 hack，将必要的功能在 adapter 层解决

**改动量**：~200 行

#### Phase 3：迁移一次性查询

1. `batch-engine.ts` 的 `query()` → `agentAdapter.query()`
2. `init/capability-matcher.ts` 的 `unstable_v2_prompt` → `agentAdapter.query()`

**改动量**：~50 行

#### Phase 4：切换到新 Agent 框架

有了 adapter 层后，切换只需：
1. 新建 `server/agent/custom-adapter.ts` 实现 `AgentAdapter`
2. 在 `server/index.ts` 中替换 adapter 实现
3. 删除 `claude-adapter.ts` 和 `@anthropic-ai/claude-agent-sdk` 依赖

### 前端无需改动

Adapter 层将 SDK 的 stream event 统一翻译为 `AgentEvent`，`claude-session.ts` 将 `AgentEvent` 翻译为 WebSocket 协议（`chat.delta`、`chat.tool_start` 等）。前端只依赖 WebSocket 协议，不依赖 SDK 类型，因此**前端零改动**。

## 前置条件

- [ ] Phase 1 开始前：确定自研 Agent 框架的基本能力要求（流式输出、MCP 工具、会话持久化）
- [ ] Phase 2 开始前：MCP 协议标准化（确认新框架支持标准 MCP 还是自定义工具协议）
- [ ] Patch 脚本的每个 hack 需要找到替代方案或在 adapter 内部解决

## 备注

- 此重构不影响任何用户可见功能
- 优先级低于业务功能开发，作为架构改善任务排期
- 建议在下次动 `claude-session.ts` 时顺手启动 Phase 1
