# SmanBase Design Spec

> 智能业务底座：去掉 OpenClaw，用 Claude Agent SDK 直连 Claude Code，Skills 驱动的业务赋能平台。

## 1. 背景与动机

### 1.1 问题

SmanWeb 当前的四层架构（UI → SmanWeb 后端 → OpenClaw Gateway → Claude Code）存在以下问题：

- **OpenClaw 过度工程化**：600+ 文件打包、复杂配置、版本升级负担重
- **OpenClaw 承诺但未交付**：Memory DB 空转（0 条记录）、Compaction 未启用（count=0）、跨会话记忆为零
- **双重 LLM 调用**：OpenClaw 调一次模型做规划，TaskMonitor 再调 Claude Code 执行，成本翻倍
- **Skills 维护分裂**：部分在 OpenClaw 的 skills/ 目录，部分已移植到 Claude Code skills，配置分散
- **客户价值错位**：真正有价值的是预配置的业务 Skills，不是 OpenClaw 框架本身

### 1.2 核心认知

> 通用能力靠大厂（Claude Code 会越来越强），我们做的是业务层（预配置 + 编排 + 交付）。

大模型升级、大厂工具升级我们自然跟着变强。我们专注于服务好业务本身。

## 2. 核心定位

**SmanBase 是一个 Skills 驱动的智能业务底座**，核心交付物：

| 交付物 | 说明 |
|--------|------|
| 预配置的 Claude Code Skills | 面向特定业务场景的技能包 |
| 动态 Skills 管理体系 | Registry + Profile + 运行时注入 |
| 业务系统模板 | CLAUDE.md 模板 + 目录结构初始化 |
| TaskMonitor 编排引擎 | Plan 驱动的多步骤任务执行 |
| 开箱即用的桌面应用 | Electron 打包，含 bundled Claude Code |

## 3. 架构设计

### 3.1 整体架构（两层，替代原来的四层）

```
用户 → SmanBase UI (React + Electron)
         ↓ WebSocket
      SmanBase 后端 (Node.js)
         ├─ ClaudeSessionManager → Claude Agent SDK → Claude Code
         ├─ SkillsRegistry → Profile 驱动 Skills 注入
         └─ TaskMonitor → Claude Code 执行 Plan MD
              ↓
         业务系统 A / B / C (各自的 workspace + CLAUDE.md + memory)
```

### 3.2 与 SmanWeb 的关键区别

| 维度 | SmanWeb (旧) | SmanBase (新) |
|------|-------------|---------------|
| 中间层 | OpenClaw Gateway | 无（SmanBase 后端直连） |
| LLM 调用 | 双重（OpenClaw + Claude Code） | 单次（Claude Code only） |
| 会话管理 | OpenClaw transcript (JSONL) | SmanBase SQLite |
| Skills 管理 | OpenClaw skills/ + Claude Code skills/ | 统一 Registry + 运行时注入 |
| Memory | OpenClaw Memory DB（空转） | Claude Code 原生 memory (.claude/memory/) |
| 上下文压缩 | 无 | Claude Code auto compression |
| 流式输出 | Gateway 透传 | SDK AsyncGenerator → WebSocket |
| Web Search | 无 | Claude 内置 + Brave/Tavily MCP |

## 4. Skills 体系

### 4.1 Skills Registry（注册表）

```json
// ~/.smanbase/registry.json
{
  "version": "1.0",
  "skills": {
    "java-scanner": {
      "name": "Java 项目扫描",
      "description": "扫描 Java 项目结构、API、实体、枚举等",
      "version": "1.2.0",
      "path": "skills/java-scanner",
      "triggers": ["auto-on-init", "manual"],
      "tags": ["java", "analysis", "architecture"]
    }
  }
}
```

### 4.2 业务系统 Profile

```json
// ~/.smanbase/profiles/{systemId}/profile.json
{
  "systemId": "projectA",
  "name": "项目A",
  "workspace": "/data/projects/projectA",
  "description": "Spring Boot 微服务",
  "skills": ["java-scanner", "sql-audit"],
  "autoTriggers": {
    "onInit": ["java-scanner"],
    "onConversationStart": []
  },
  "claudeMdTemplate": "CLAUDE.md.tpl"
}
```

### 4.3 Skills 动态注入

SmanBase 后端调 Claude Code 时，根据 profile 动态注入 Skills：

```typescript
const profile = loadProfile(systemId);
const skillDirs = profile.skills.map(s =>
  path.join(SMANBASE_HOME, 'skills', s)
);

const q = query({
  prompt: userMessage,
  options: {
    cwd: profile.workspace,
    systemPrompt: buildSystemPrompt(profile, skillDirs),
  }
});
```

### 4.4 自动触发机制

- **接入时自动触发**（autoTriggers.onInit）：新业务系统接入后，SmanBase 自动用 Claude Code 执行配置的 Skills，生成 CLAUDE.md 和 memory
- **日常对话触发**：Claude Code 根据 Skills 的 description 自动判断是否调用，用户也可显式触发

### 4.5 Skills 更新

```bash
smanbase skills update          # git pull skills 仓库
smanbase skills list            # 列出所有可用 Skills
smanbase skills add <name>      # 添加自定义 Skill
```

或通过 UI 操作。

## 5. 后端模块

### 5.1 目录结构

```
smanbase/
├── server/
│   ├── index.ts                 ← 启动入口，HTTP + WebSocket
│   ├── claude-session.ts        ← Claude 会话管理（核心，替代 OpenClaw Gateway）
│   ├── task-monitor.ts          ← TaskMonitor（从 smanweb 复用 + 改造）
│   ├── skills-registry.ts       ← Skills 注册表读写
│   ├── profile-manager.ts       ← 业务系统 profile 管理
│   ├── session-store.ts         ← 会话持久化（SQLite）
│   ├── websearch-config.ts      ← Web Search 配置管理
│   └── utils/
│       └── logger.ts
├── src/                         ← React 前端（从 smanweb 迁移 + 简化）
├── electron/                    ← Electron 桌面应用壳（从 smanweb 复用）
├── scripts/
│   └── bundle-claude-code.mjs   ← Claude Code 打包脚本（复用）
├── resources/
│   └── templates/
│       └── default-profile/     ← 默认业务系统模板
└── package.json
```

### 5.2 ClaudeSessionManager

核心模块，替代 OpenClaw Gateway 的全部功能：

```typescript
class ClaudeSessionManager {
  // 创建会话
  async create(systemId: string): Promise<string>;
  // 发送消息（流式）
  async sendMessage(sessionId: string, message: string, ws: WebSocket): Promise<void>;
  // 中止当前执行
  abort(sessionId: string): void;
  // 恢复历史会话
  async resume(sessionId: string): Promise<string>;
  // 列出会话
  listSessions(systemId?: string): SmanSession[];
  // 获取历史消息
  getHistory(sessionId: string, limit?: number): Message[];
}
```

### 5.3 WebSocket 协议

```
// 客户端 → 服务端
{ "type": "session.create", "systemId": "projectA" }
{ "type": "session.resume", "sessionId": "xxx" }
{ "type": "session.list", "systemId": "projectA" }
{ "type": "chat.send", "sessionId": "xxx", "content": "帮我扫描代码" }
{ "type": "chat.abort", "sessionId": "xxx" }

// 服务端 → 客户端
{ "type": "session.created", "sessionId": "xxx", "systemId": "projectA" }
{ "type": "chat.delta", "sessionId": "xxx", "content": "正在分析..." }
{ "type": "chat.event", "sessionId": "xxx", "event": "tool_use", "tool": "Read" }
{ "type": "chat.done", "sessionId": "xxx", "cost": 0.05 }
{ "type": "chat.error", "sessionId": "xxx", "error": "..." }
```

### 5.4 会话持久化（SQLite）

```sql
CREATE TABLE sessions (
  id TEXT PRIMARY KEY,
  system_id TEXT NOT NULL,
  workspace TEXT NOT NULL,
  profile_json TEXT,
  created_at DATETIME,
  last_active_at DATETIME
);

CREATE TABLE messages (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  session_id TEXT NOT NULL,
  role TEXT NOT NULL,
  content TEXT,
  created_at DATETIME,
  FOREIGN KEY (session_id) REFERENCES sessions(id)
);
```

消息历史存在 SmanBase 的 SQLite 里，不依赖 Claude Code 的 transcript。

### 5.5 TaskMonitor

从 smanweb 复用，关键改造：
- 完成通知从 `GatewayRpc.chat.send` → 直接推 WebSocket 给前端
- 执行方式从 `spawn CLI` → 可选 SDK `query()`（也可继续用 CLI）
- 锁机制保持不变（同时只跑一个任务）

## 6. UI 简化

去掉 OpenClaw 相关的所有设置，只保留：

```
Settings
├── LLM 配置
│   ├── API Key
│   ├── Model（Claude Sonnet/Opus/Haiku）
│   └── Base URL（可选，支持兼容端点）
├── Web Search
│   ├── Provider（Anthropic 内置 / Brave / Tavily）
│   ├── API Key（非内置时需要）
│   └── 免费额度显示
└── 关于
    └── 版本信息
```

## 7. Web Search

### 7.1 双模式支持

| 模式 | 价格 | 特点 |
|------|------|------|
| Claude 内置 | $10/千次 + token | 零配置，开箱即用 |
| Brave Search MCP | $5/千次，有免费额度 | 便宜，可控 |
| Tavily MCP | ~$8/千次，1000次/月免费 | AI 优化，免费额度多 |

### 7.2 配置

```json
// ~/.smanbase/config.json
{
  "webSearch": {
    "provider": "builtin",
    "braveApiKey": "",
    "tavilyApiKey": "",
    "maxUsesPerSession": 50
  }
}
```

配置 API Key 后自动启用对应 MCP Server，用户无需了解 MCP 概念。

## 8. 业务系统接入流程

```
Step 1: 客户在 UI 添加业务系统（指定 workspace + 选择 Skills）
Step 2: SmanBase 创建 profile + 初始化目录结构
Step 3: 自动执行 onInit Skills（如项目扫描）
Step 4: 生成 CLAUDE.md（模板 + 扫描结果）+ memory
Step 5: 就绪，客户开始对话
```

## 9. 打包交付

```
SmanBase 安装包/
├── smanbase              ← 主程序（Electron 应用）
│   └── app.asar          ← 后端 + 前端
├── claude-code/          ← bundled Claude Code CLI
└── smanbase-data/        ← 初始数据（首次启动解压到 ~/.smanbase/）
    ├── registry.json
    ├── skills/
    └── templates/
```

## 10. 运行时目录

```
~/.smanbase/
├── config.json           ← SmanBase 配置
├── registry.json         ← Skills 注册表
├── skills/               ← Skills 仓库 (git)
├── profiles/             ← 业务系统配置
│   └── {systemId}/
│       ├── profile.json
│       └── CLAUDE.md.tpl
├── smanbase.db           ← SQLite
└── logs/
```

## 11. 技术选型

| 维度 | 选型 |
|------|------|
| 后端 | Node.js + TypeScript |
| 前端 | React + Vite |
| 桌面 | Electron |
| LLM | Claude Agent SDK (@anthropic-ai/claude-agent-sdk) |
| 会话存储 | SQLite (better-sqlite3) |
| Web Search | Claude 内置 + Brave/Tavily MCP |
| Skills 管理 | Registry + Profile + 运行时注入 |
