---
name: knowledge-business
description: "业务知识：产品需求、用户流程、业务规则、领域术语。经代码验证，由 skill-auto-updater 聚合。"
_scanned:
  commitHash: "35f8e752359eff2474610cf31f0beaaa40ccbca9"
  scannedAt: "2026-05-05T00:00:00.000Z"
  branch: "master"
---

# Business Knowledge

> 贡献者: nasakim | 验证时间: 2026-05-05

## 核心产品定位
> by nasakim | 验证: 2026-05
✅ [已验证] CLAUDE.md:L1-10
- **Sman**：智能业务系统助手，核心交互方式为多端对话
- 四端支持：桌面端（Electron）、企业微信 Bot、飞书 Bot、微信 Bot
- 选择项目目录即可开始对话，零预配置

## 功能模块概览
> by nasakim | 验证: 2026-05
✅ [已验证] CLAUDE.md:L45-50
- **协作星图**：多 Agent 协作网络（仪表盘 + 像素世界），含声望系统、任务路由、远程协作
- **地球路径（工作流）**：多步骤自动化，逐步骤执行，上一步结果作为下一步上下文，支持定时调度与资源复用
- **定时任务（Cron）**：Cron 表达式驱动的自动化任务，支持手动触发与队列管理
- **知识管理**：自动提取业务/规范/技术知识，通过 git push 实现团队知识共享

## 四端入口
> by nasakim | 验证: 2026-05
✅ [已验证] CLAUDE.md:L18-27
| 入口 | 连接方式 | 文件 |
|------|---------|------|
| 桌面端 | WebSocket (`ws://localhost:5880/ws`) | `electron/main.ts` |
| 企业微信 | WebSocket 长连接 (`wss://openws.work.weixin.qq.com`) | `server/chatbot/wecom-bot-connection.ts` |
| 飞书 | 飞书 SDK 事件监听 | `server/chatbot/feishu-bot-connection.ts` |
| 微信 | 微信 Bot 连接 | `server/chatbot/weixin-bot-connection.ts` |

## 侧边栏核心功能
> by nasakim | 验证: 2026-05
✅ [已验证] CLAUDE.md:L30-36
1. **新建会话** → 选择项目目录 → 开始对话
2. **协作星图** → 多 Agent 协作网络
3. **定时任务** → Cron 表达式驱动的自动化任务
4. **地球路径** → 多步骤自动化工作流
5. **设置** → LLM、Web 搜索、Chatbot、用户画像等配置

## Chatbot 命令
> by nasakim | 验证: 2026-05
✅ [已验证] CLAUDE.md:L180-195
| 命令 | 说明 |
|------|------|
| `//cd <项目名或路径>` | 切换工作目录（支持 `~` 路径和数字序号） |
| `//pwd` | 显示当前工作目录 |
| `//workspaces` / `//wss` | 列出桌面端已打开的项目 |
| `//status` / `//sts` | 显示连接状态 |
| `//help` | 显示帮助信息 |
