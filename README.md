# SmanBase

**零配置的 Claude Code 业务助手。** 选个项目目录，就能和 AI 聊你的代码。

支持桌面端（Electron）、企业微信 Bot、飞书 Bot 三端同时接入。

## 解决的问题

Claude Code 很强，但配置门槛高：
- 需要理解 API Key、Base URL、Model 选择
- 需要为每个项目手动配置上下文
- 在团队中推广时，配置同步是噩梦

SmanBase 的思路：**让用户只做一件事——选目录。** AI 自动理解你的项目，主动调用 Skills，把知识留在本地。

## 核心能力

| 能力 | 说明 |
|------|------|
| **零配置启动** | 选择目录 → 开始对话，不需要预设任何业务系统 |
| **三端接入** | 桌面端 + 企业微信 + 飞书，同一个会话随时切换 |
| **Skills 驱动** | 自动加载通用技能 + 根据项目代码生成专用技能 |
| **定时任务** | Cron 定时触发 AI 任务，自动记录执行结果 |
| **批量任务** | 并发控制、暂停/恢复，批量处理多个目标 |
| **浏览器自动化** | AI 直接操控 Chrome，可用于自动填表、数据抓取 |
| **私有化部署** | 配置内网模型 URL + API Key，纯本地运行 |

## 架构

```
用户 (桌面端 / 企业微信 / 飞书)
         ↓
    SmanBase 后端 (Express + WebSocket)
         ↓
    Claude Agent SDK (V2 Session 持久化)
         ↓
    项目目录 + MCP Servers + Skills
```

极简两层：后端 + Claude Agent SDK，去掉中间层。

## 快速开始

```bash
# 安装依赖
pnpm install

# 一键启动（后端 + 前端 + Electron）
./dev.sh
```

启动后：
1. 点击「新建会话」→ 选择项目目录
2. 开始对话，AI 自动分析代码结构
3. 通过桌面端、企业微信或飞书随时访问

## 三端入口

| 入口 | 连接方式 |
|------|---------|
| 桌面端 | 打开 Electron 应用（自动连接 `ws://localhost:5880/ws`）|
| 企业微信 | 配置 WebSocket 机器人，推送消息到 `wss://openws.work.weixin.qq.com` |
| 飞书 | 配置飞书事件订阅，接收消息并回复 |

## 技术栈

- **前端**: React 19 + TailwindCSS + Radix UI + Zustand
- **后端**: Node.js + Express + WebSocket (ws) + SQLite
- **桌面**: Electron + electron-vite
- **AI**: Claude Agent SDK (`@anthropic-ai/claude-agent-sdk` + `@anthropic-ai/claude-code`)
- **代码高亮**: Shiki

## 项目结构

```
server/           # Node.js 后端
  ├── chatbot/    # 企业微信 + 飞书 Bot 集成
  ├── web-access/ # 浏览器自动化 (CDP 协议)
  └── ...
src/              # React 前端
electron/         # Electron 桌面应用
plugins/          # Claude Code 插件 (web-access, superpowers)
```

## 使用技巧

**在企业微信/飞书中切换项目目录：**

```
//cd my-project
//pwd
```

**查看当前状态：**

```
//status
```

**使用定时任务：**

在设置页面配置 Cron 表达式，AI 会在指定时间自动执行任务。

## 设计理念

> **越简单越好，不要让用户看不懂。**

不追求功能堆砌，追求每个功能都能被普通用户理解和使用。
