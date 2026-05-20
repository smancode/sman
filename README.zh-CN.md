[English](README.md) | [简体中文](README.zh-CN.md)

<div align="center">

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="./public/images/sman-logo-full-white.png" />
  <source media="(prefers-color-scheme: light)" srcset="./public/images/sman-logo-full-dark.png" />
  <img alt="Sman" width="240" />
</picture>

**基于业务的 AI 原生 Agent 协作平台**

🌐 [smancode.com](https://www.smancode.com)

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](./LICENSE)
[![GitHub Stars](https://img.shields.io/github/stars/smancode/sman?style=social)](https://github.com/smancode/sman/stargazers)
[![GitHub Issues](https://img.shields.io/github/issues/smancode/sman)](https://github.com/smancode/sman/issues)

[快速开始](#快速开始) · [功能特性](#功能特性) · [架构](#架构) · [开发](#开发) · [贡献指南](./CONTRIBUTING.md) · [官网](https://www.smancode.com)

</div>

---

## 为什么选择 Sman？

大多数 AI 编程助手只是套在编辑器上的聊天机器人。Sman 不同 — 它是一个 **全栈 AI 工作站**，在你已经熟悉的工作场景中为你服务。

| | Sman | 传统 AI 助手 |
|---|---|---|
| 配置 | 选择文件夹即可开始 | 需要配置 API Key、插件、提示词 |
| 上下文 | 自动读取整个项目 | 手动粘贴代码 |
| 接入 | 桌面端 / 企业微信 / 飞书 / 微信 | 仅支持单一平台 |
| 浏览器 | AI 直接控制 Chrome | 不支持 |
| 协作 | AI Agent 跨项目组队协作 | 仅支持单一 Agent |
| 记忆 | 自动学习项目规范 | 每次都从零开始 |

---

## 功能特性

### 零配置启动

无需折腾 API Key、提示词模板或 MCP 配置。选择你的项目目录，直接开始对话 — Sman 会自动分析代码库、加载对应技能、为 AI 提供上下文。

### AI 记忆

每次对话，Sman 自动提取业务规则、编码规范和技术细节到共享知识库。推送到 Git，整个团队的 AI 都会变得更聪明。

- 每次会话自动记录笔记
- 通过 Git 共享团队知识
- 用户画像自学习，适配个人编码风格

### 四端合一，同一个 AI

桌面端、企业微信、飞书、微信无缝衔接同一对话。不是四个独立的机器人 — 而是一个 AI 助手的四个入口。

支持文本、图片、语音和文件。

### 浏览器自动化

Sman 通过 DevTools 协议直接控制 Chrome — 你的 AI 可以：

- 自动继承你的 SSO 登录态
- 填写表单、抓取数据、操作页面流程
- 记住你常用的系统 URL

### 批量 & 定时任务

用 Markdown 描述你想做的事，AI 自动生成脚本、测试并执行。

- **批量引擎**：并发控制、暂停/恢复/取消、失败重试
- **定时任务**：定时巡检、自动报告、知识刷新
- **地球路径**：自然语言描述的多步骤工作流，支持串行或并行

### 协作星图

你的 Claude 是一颗"星"，你队友的 Claude 也是一颗"星"。当你的 AI 遇到不熟悉的领域，它会自动搜索星域网络，实时与最匹配的 Agent 协作。

<div align="center">

```
你的 AI（支付系统） ←→ 队友的 AI（库存系统）
         "这个退款会影响库存锁定吗？"
                    ↕
         即时跨项目解答
```

</div>

### 内置开发工具

| 工具 | 功能 |
|------|------|
| 代码查看器 | 文件树、语法高亮、符号搜索（CodeMirror 6） |
| Git 面板 | 状态、Diff、提交、推送 — AI 自动生成提交信息 |
| 地球路径 | 自然语言描述的多步骤工作流自动化 |
| 成就系统 | 加权积分、成就徽章、多维度排行榜 |

---

## 架构

```
用户（桌面端 / 企业微信 / 飞书 / 微信）
         │
         ▼
   Sman 后端（Express + WebSocket）
         │
         ▼
   Claude Agent SDK（V2 Session）
         │
         ▼
   项目目录 + MCP Servers + Plugins + Capabilities
         │
         ↕
   星域（多 Agent 协作网络）
```

---

## 快速开始

### 环境要求

- [Node.js 22 LTS](https://nodejs.org/)
- [pnpm](https://pnpm.io/)

### 安装 & 运行

```bash
git clone https://github.com/smancode/sman.git
cd sman
pnpm install
./dev.sh
```

然后：

1. 点击 **新建会话** → 选择你的项目目录
2. 开始对话 — AI 自动分析你的代码
3. 随时从企业微信 / 飞书 / 微信访问

### IM 中切换项目

```
//cd my-project     # 切换项目目录
//pwd               # 我在哪个目录？
//status            # 连接状态
//help              # 帮助
```

---

## 开发

### 开发模式

```bash
./dev.sh              # 一键启动（前端 + 后端 + Electron）

# 或分别启动：
pnpm dev              # 前端（端口 5881）
pnpm dev:server       # 后端（端口 5880）
```

### 生产构建

```bash
pnpm build            # 构建前端 + 后端
pnpm build:electron   # 编译 Electron 主进程
pnpm electron:build   # 完整构建 + 打包
```

### 平台打包

```bash
# macOS
bash build-mac.sh              # → release/Sman-<version>-arm64.dmg

# Windows
bash build-win.sh              # → release/Sman-Setup-<version>.exe（NSIS）
bash build-win.sh --skip-deps  # 跳过依赖安装
```

### 运行测试

```bash
pnpm test          # 所有测试
pnpm test:watch    # 监视模式
```

---

## 技术栈

| 层级 | 技术 |
|------|------|
| 前端 | React 19, TypeScript, TailwindCSS, Radix UI, Zustand, CodeMirror 6 |
| 后端 | Node.js, Express, WebSocket (ws), SQLite (better-sqlite3) |
| 桌面端 | Electron, electron-vite |
| AI | Claude Agent SDK |
| 渲染 | Shiki, Streamdown |
| 校验 | Zod |

---

## 能力总览

| 能力 | 说明 |
|------|------|
| 代码分析 | 自动扫描项目结构，加载上下文感知的技能 |
| 浏览器控制 | Chrome DevTools 协议 — 导航、填写、抓取 |
| Git 集成 | 状态、Diff、提交、推送，AI 自动生成提交信息 |
| 知识库 | 自动提取编码规范，通过 Git 在团队间共享 |
| 批量任务 | Markdown 驱动，支持并发、可恢复 |
| 定时调度 | 基于时间的自动化巡检和报告 |
| 地球路径 | 自然语言描述的多步骤工作流 |
| 协作星图 | 带信誉系统的多 Agent 网络 |
| 成就系统 | 加权积分、徽章解锁、多维度排行榜 |
| 多平台 | 桌面端 + 企业微信 + 飞书 + 微信 |
| 国际化 | 中文 & 英文，自动检测 |
| 私有部署 | 内网模型 + 本地数据，零数据外泄 |

---

## 项目结构

```
sman/
├── electron/           # Electron 主进程
├── server/             # Express + WebSocket 后端
│   ├── init/           # 会话初始化管道
│   └── services/       # 业务逻辑服务
├── src/                # React 前端
│   ├── components/     # UI 组件
│   ├── locales/        # 国际化（zh-CN.json, en-US.json）
│   ├── stores/         # Zustand 状态管理
│   └── ...
├── tests/              # 测试文件
├── docs/               # 文档
└── scripts/            # 构建 & 工具脚本
```

---

## 端口

| 端口 | 用途 |
|------|------|
| 5880 | HTTP + WebSocket（生产模式） |
| 5881 | Vite 开发服务器（仅开发模式） |

---

## 环境变量

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `PORT` | `5880` | HTTP 服务端口 |
| `SMANBASE_HOME` | `~/.sman` | 用户数据目录 |

---

## 贡献指南

欢迎贡献！请查看 [CONTRIBUTING.md](./CONTRIBUTING.md) 了解：

- 开发环境搭建
- 编码规范
- PR 提交流程
- 项目结构说明

## 安全

发现安全漏洞？请查看 [SECURITY.md](./SECURITY.md) 了解负责任的披露流程。

## 许可证

Sman 基于 [MIT License](./LICENSE) 发布。

---

<div align="center">

### Star 历史

[![Star History Chart](https://api.star-history.com/svg?repos=smancode/sman&type=Date)](https://star-history.com/#smancode/sman&Date)

**[⭐ 在 GitHub 上给我们加星](https://github.com/smancode/sman/stargazers)** — 这是对我们最大的鼓励！

</div>
