# Sman (原 SmanBase) - 智能业务系统平台

> 此文件给 Claude Code 提供项目上下文

## 项目定位

Sman 是一个简化的智能业务平台，用户只需选择项目目录即可开始对话，无需预先配置业务系统。

## 核心架构

```
用户 → Sman (Electron 桌面应用)
         ↓
      Node.js 后端 (WebSocket)
         ↓
      Claude Agent SDK
         ↓
      项目目录 (用户选择)
```

## 使用流程

1. **新建会话** → 点击"新建会话"按钮 → 选择项目目录 → 开始对话
2. **会话管理** → 按目录分组显示 → 点击切换会话

**设计理念**：越简单越好，不要让用户看不懂。

## 关键目录

| 目录 | 说明 |
|------|------|
| `server/` | Node.js 后端，WebSocket 通信 |
| `src/` | React 前端 |
| `electron/` | Electron 桌面应用代码 |
| `~/.sman/` | 用户数据目录 |

## 用户数据目录结构 (`~/.sman/`)

```
~/.sman/
├── config.json          # LLM 配置 (API Key, Model, BaseURL)
├── registry.json        # Skills 注册表
├── sman.db              # SQLite 数据库 (会话和消息)
├── skills/              # 全局 Skills (预制通用技能)
└── logs/                # 日志文件
```

## Skills 机制

### Skills 加载顺序

1. **全局 Skills**: `~/.sman/skills/` - 预制的通用技能，所有项目可用
2. **项目 Skills**: `{workspace}/.claude/skills/` - 项目特定的技能

### Skills 工作流程

```
通用 Skills (项目分析)
         ↓
分析业务系统代码
         ↓
生成业务专用 Skills
         ↓
存放到 {workspace}/.claude/skills/
```

**核心价值**：通用 Skills 会对业务系统做项目分析，自动生成业务分析专用的 Skill。

### SkillsRegistry API

```typescript
// 获取所有可用的 Skill 目录
skillsRegistry.getAllSkillDirs(workspace: string): string[]
// 返回: ['~/.sman/skills/', '{workspace}/.claude/skills/']
```

## 关键文件

| 文件 | 说明 |
|------|------|
| `electron/main.ts` | Electron 主进程，窗口管理，IPC |
| `electron/preload.ts` | 预加载脚本，暴露 `selectDirectory` API |
| `server/index.ts` | 后端入口，WebSocket 处理 |
| `server/claude-session.ts` | Claude 会话管理，调用 Agent SDK |
| `server/skills-registry.ts` | Skills 注册和加载 |
| `server/session-store.ts` | SQLite 会话存储 |
| `src/components/SessionTree.tsx` | 会话树，内置目录选择器 |
| `src/components/DirectorySelector.tsx` | 目录选择组件 |
| `src/stores/chat.ts` | 聊天状态管理 |
| `src/stores/settings.ts` | 设置状态管理 |

## WebSocket API

### 会话管理

| 类型 | 说明 |
|------|------|
| `session.create` | 创建会话，参数: `{ workspace: string }` |
| `session.list` | 列出所有会话 |
| `session.delete` | 删除会话，参数: `{ sessionId: string }` |
| `session.history` | 获取会话历史 |

### 聊天

| 类型 | 说明 |
|------|------|
| `chat.send` | 发送消息，参数: `{ sessionId, content }` |
| `chat.abort` | 中止当前查询 |
| `chat.delta` | 流式响应 (服务端推送) |
| `chat.done` | 响应完成 (服务端推送) |
| `chat.error` | 错误 (服务端推送) |

### 设置

| 类型 | 说明 |
|------|------|
| `settings.get` | 获取配置 |
| `settings.update` | 更新配置 |
| `skills.list` | 列出所有 Skills |

## 构建和运行

### 开发模式

```bash
# 一键启动 (后端 + 前端 + Electron)
./dev.sh

# 或分别启动
pnpm dev           # 前端 (5881)
pnpm dev:server    # 后端 (5880)
```

### 生产构建

```bash
pnpm build         # 构建前端 + 后端
pnpm electron:build # 打包 Electron 应用
```

## 端口使用

| 端口 | 用途 |
|------|------|
| 5880 | HTTP 服务 + WebSocket (生产模式固定) |
| 5881 | Vite 开发服务器 (仅开发模式) |

## 环境变量

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `PORT` | 5880 | HTTP 服务端口 |
| `SMANBASE_HOME` | `~/.sman` | 用户数据目录 |

## 技术栈

- **前端**: React + TypeScript + TailwindCSS + Radix UI
- **后端**: Node.js + TypeScript + WebSocket (ws)
- **桌面**: Electron + electron-vite
- **数据库**: SQLite (better-sqlite3)
- **AI**: Claude Agent SDK (`@anthropic-ai/claude-agent-sdk`)

## 注意事项

1. **目录选择**: Electron 使用原生对话框，Web 模式使用 API 浏览
2. **Skills 加载**: 同时加载全局和项目特定 Skills
3. **会话分组**: 按目录名分组显示，目录名即为显示名称
4. **无预配置**: 用户无需预先配置业务系统，直接选择目录即可
