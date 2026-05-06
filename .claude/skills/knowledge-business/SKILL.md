---
name: knowledge-business
description: "业务知识：产品需求、用户流程、业务规则、领域术语。经代码验证，由 skill-auto-updater 聚合。"
_scanned:
  commitHash: "4db35f24f89dda0c11aa6aad83ba7bb7f8df368a"
  scannedAt: "2026-05-06T00:00:00.000Z"
  branch: "master"
---

# Business Knowledge

> 贡献者: nasakim | 验证时间: 2026-05-06

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

## 会话串扰是零容忍的核心体验问题
> by nasakim | 验证: 2026-05
✅ [已验证] server/index.ts:L146-214
- 用户反馈会话卡顿和串会话（不同用户的会话内容互相渗透）属于最高优先级问题
- 多标签页同时打开同一会话是必须支持的合法使用场景，不能简单禁用
- 实现机制：通过 `clientToSessions` 和 `sessionToClients` 双向 Map 精确路由消息

## 对话自省与需求澄清机制
> by nasakim | 验证: 2026-05
✅ [已验证] server/init/init-manager.ts:L239, server/claude-session.ts, server/init/templates/clarify-requirements/SKILL.md
- 核心痛点：AI 缺乏"自省"，用户反复校正说明理解偏差累积，需在中途发现走偏并纠正
- 触发条件：同一需求用户纠正 ≥ 2 次（含明确否定、纠正性指令、重复表达需求变体等）
- 落地形式：`clarify-requirements` 轻量 skill，触发后停止当前任务 → 3-5 个问题快速对齐 → 需求确认表 → 等待确认
- AUTO 模式下：理解清晰则自问自答，完全无法确认才停下来问用户
- 新建会话时通过 `META_SKILLS` 数组自动注入到 `{workspace}/.claude/skills/`

## 任务前提机制（事前预防优于事后纠正）
> by nasakim | 验证: 2026-05
✅ [已验证] server/claude-session.ts:L346
- 在 user prompt 中新增"任务前提"章节，位于"交付要求"和"需求澄清"之间
- 四个前提条件：深入理解需求（理解目标而非表面）、获取足够上下文（读文件/了解现有实现）、确认可执行性（评估依赖/权限/环境）、不确定就问
- 核心理念：一开始就把事情做对，避免因模糊导致的反复拉扯，不要基于假设做决定

## 项目功能模块全景
> by nasakim | 验证: 2026-05
✅ [已验证] server/web-search/, src/features/code-viewer/, src/features/git/
- Web 搜索：支持 baidu / brave / tavily 三个 MCP 提供商（server/web-search/baidu-mcp-server.ts, brave-mcp-server.ts, tavily-mcp-server.ts）
- 代码查看器：基于 CodeMirror 6 的内嵌编辑器（src/features/code-viewer/CodePanel.tsx:L4,8，WebSocket API `code.*` 含 5 个方法）
- Git 面板：完整的 Git 操作 UI（src/features/git/GitPanel.tsx，WebSocket API `git.*` 含 13 个方法覆盖 status/diff/commit/push/log/checkout/fetch 等）
