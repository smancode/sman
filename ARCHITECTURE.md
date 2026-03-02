# SmanCode 架构文档

## 系统架构

```
┌─────────────────────────────────────────────────────────────────┐
│                      客户端层                                     │
├─────────────────────────────────────────────────────────────────┤
│  ┌──────────────┐    ┌──────────────┐    ┌──────────────┐       │
│  │  Web 前端    │    │  IDE 插件    │    │  CLI 客户端   │       │
│  │  (SolidJS)   │    │  (Kotlin)    │    │   (可选)      │       │
│  └──────┬───────┘    └──────┬───────┘    └──────┬───────┘       │
│         │                   │                   │                │
│         └───────────────────┼───────────────────┘                │
│                             │ HTTP / WebSocket                    │
└─────────────────────────────┼───────────────────────────────────┘
                              │
┌─────────────────────────────┼───────────────────────────────────┐
│                     API 服务层                                   │
├─────────────────────────────┼───────────────────────────────────┤
│  ┌──────────────────────────────────────────────────────────┐   │
│  │                    Hono HTTP Server                       │   │
│  │  • REST API (/api/*)                                      │   │
│  │  • WebSocket (/ws)                                        │   │
│  │  • CORS 支持                                              │   │
│  └──────────────────────────────────────────────────────────┘   │
└─────────────────────────────┬───────────────────────────────────┘
                              │
┌─────────────────────────────┼───────────────────────────────────┐
│                     核心服务层                                   │
├─────────────────────────────┼───────────────────────────────────┤
│  ┌──────────────┐    ┌──────────────┐    ┌──────────────┐       │
│  │ SessionMgr   │    │ AgentRegistry│    │ ToolRegistry │       │
│  └──────────────┘    └──────────────┘    └──────────────┘       │
│                                                                  │
│  ┌──────────────┐    ┌──────────────┐    ┌──────────────┐       │
│  │ ProviderMgr  │    │ MemoryMgr    │    │ PermissionMgr│       │
│  └──────────────┘    └──────────────┘    └──────────────┘       │
└─────────────────────────────┬───────────────────────────────────┘
                              │
┌─────────────────────────────┼───────────────────────────────────┐
│                     基础设施层                                   │
├─────────────────────────────┼───────────────────────────────────┤
│  ┌──────────────┐    ┌──────────────┐    ┌──────────────┐       │
│  │ EventBus     │    │ VectorStore  │    │ FileStore    │       │
│  └──────────────┘    └──────────────┘    └──────────────┘       │
│                                                                  │
│  ┌──────────────┐    ┌──────────────┐                           │
│  │ Embedding    │    │ LLM Client   │                           │
│  └──────────────┘    └──────────────┘                           │
└─────────────────────────────────────────────────────────────────┘
```

## 核心模块

### 1. Agent 系统

**职责**：管理 Agent 生命周期和执行

```typescript
interface AgentExecutor {
  info: AgentInfo
  init(): Promise<void>
  execute(input: AgentInput, ctx: AgentContext): AsyncGenerator<StreamEvent, AgentOutput>
  destroy(): Promise<void>
}
```

**关键特性**：
- 多 Agent 协作
- 动态 Agent 生成
- 子任务支持

### 2. Session 系统

**职责**：管理会话和消息历史

```typescript
interface SessionManager {
  create(projectId: string, parentId?: string): Promise<Session>
  get(sessionId: string): Promise<Session | undefined>
  appendMessage(sessionId: string, message: MessageInput): Promise<Message>
  getHistory(sessionId: string): Promise<Message[]>
  compact(sessionId: string): Promise<CompactionResult>
}
```

**关键特性**：
- 会话持久化
- 上下文压缩
- 事件订阅

### 3. Tool 系统

**职责**：工具定义和执行

```typescript
interface ToolDefinition {
  id: string
  description: string
  parameters: ZodSchema
  execute(args: unknown, ctx: ToolContext): Promise<ToolResult>
}
```

**内置工具**：
- `read_file` - 读取文件
- `write_file` - 写入文件
- `edit_file` - 编辑文件
- `glob` - 文件搜索
- `grep` - 内容搜索
- `bash` - 命令执行
- `semantic_search` - 语义搜索
- `web_search` - Web 搜索

### 4. Memory 系统

**职责**：长期记忆和语义检索

```typescript
interface MemoryManager {
  store(entry: MemoryEntry): Promise<MemoryEntry>
  search(options: MemoryQueryOptions): Promise<MemoryEntry[]>
  inject(source: KnowledgeSource): Promise<MemoryEntry[]>
}
```

**关键特性**：
- 向量存储
- 混合搜索（BM25 + 向量）
- 知识注入

### 5. Permission 系统

**职责**：权限控制和用户确认

```typescript
interface PermissionManager {
  check(request: PermissionRequest): Promise<PermissionCheckResult>
  askUser(request: PermissionRequest): Promise<boolean>
  grantTemporary(grant: TemporaryGrant): Promise<void>
}
```

**权限动作**：
- `allow` - 允许执行
- `deny` - 拒绝执行
- `ask` - 请求用户确认

## 数据流

### 消息处理流程

```
用户输入
    │
    ▼
┌─────────────┐
│ WebSocket   │
│ receive     │
└─────┬───────┘
      │
      ▼
┌─────────────┐
│ SessionMgr  │
│ appendMsg   │
└─────┬───────┘
      │
      ▼
┌─────────────┐
│ Agent       │
│ execute     │
├─────────────┤
│ • LLM 调用  │
│ • 工具执行  │
│ • 流式输出  │
└─────┬───────┘
      │
      ▼
┌─────────────┐
│ WebSocket   │
│ broadcast   │
└─────────────┘
```

### 上下文压缩流程

```
检查 Token 使用
      │
      ▼
超过阈值？
      │
  ┌───┴───┐
  │ Yes   │ No
  ▼       ▼
压缩    继续
  │
  ▼
┌─────────────┐
│ Prune       │
│ 裁剪旧消息  │
└─────┬───────┘
      │
      ▼
仍超阈值？
      │
  ┌───┴───┐
  │ Yes   │ No
  ▼       ▼
Summarize  完成
  │
  ▼
生成摘要
替换旧消息
```

## 扩展点

### 添加新 Agent

```typescript
registry.register({
  name: "custom-agent",
  mode: "subagent",
  systemPrompt: "You are a custom agent...",
  tools: ["read_file", "grep"],
})
```

### 添加新工具

```typescript
registry.register({
  id: "my_tool",
  description: "My custom tool",
  parameters: z.object({
    input: z.string(),
  }),
  execute: async (args, ctx) => {
    return {
      title: "Tool executed",
      output: "Result: " + args.input,
    }
  },
})
```

### 添加新 Provider

```typescript
providerRegistry.register({
  id: "my-provider",
  name: "My LLM Provider",
  async request(options) {
    // 实现请求逻辑
  },
  async *stream(options) {
    // 实现流式响应
  },
})
```

## 配置

### 环境变量

```bash
# LLM 配置
LLM_API_KEY=your-api-key
LLM_BASE_URL=https://api.example.com
LLM_MODEL=gpt-4

# 向量化配置
BGE_ENDPOINT=http://localhost:8000
BGE_DIMENSION=1024

# 服务配置
PORT=4000
HOST=localhost
```

### 运行时配置

```typescript
const config = {
  server: {
    port: 4000,
    cors: {
      origins: ["http://localhost:3000"],
    },
  },
  agent: {
    defaultModel: "gpt-4",
    maxSteps: 10,
  },
  memory: {
    vectorDimension: 1024,
    maxResults: 10,
  },
  permission: {
    defaultAction: "ask",
  },
}
```
