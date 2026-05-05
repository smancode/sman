---
name: knowledge-technical
description: "技术知识：API 细节、数据库 schema、三方集成、基础设施、算法。经代码验证，由 skill-auto-updater 聚合。"
_scanned:
  commitHash: "35f8e752359eff2474610cf31f0beaaa40ccbca9"
  scannedAt: "2026-05-05T00:00:00.000Z"
  branch: "master"
---

# Technical Knowledge

> 贡献者: nasakim | 验证时间: 2026-05-05

## 项目架构与目录结构
> by nasakim | 验证: 2026-05
✅ [已验证] CLAUDE.md:L60-120
- **后端（server/）**：Node.js，WebSocket + HTTP 双协议入口，SQLite 存储，Claude Agent SDK 会话管理
- **前端（src/）**：React + Zustand 状态管理，按功能模块划分
- **桌面端（electron/）**：Electron 封装
- **Stardom 服务器（stardom/）**：独立包边界

## 扩展能力体系
> by nasakim | 验证: 2026-05
✅ [已验证] CLAUDE.md:L210-220
- **MCP Servers**：Web 搜索、浏览器操作（CDP 协议）
- **Capabilities**：按需激活能力包，通过 Gateway MCP 注入每个会话
- **Plugins**：Superpowers、Gstack 等插件扩展

## Claude Agent SDK 集成
> by nasakim | 验证: 2026-05
✅ [已验证] server/claude-session.ts
- **SDK 版本**：@anthropic-ai/claude-agent-sdk v0.2
- **会话持久化**：SDK session_id 存储到 SQLite
- **消息排队**：await streamDone 排队机制
- **Idle 清理**：30 分钟无活动自动清理

## 数据库设计（SQLite）
> by nasakim | 验证: 2026-05
✅ [已验证] 各 store 文件
- **数据库文件**：~/.sman/sman.db
- **连接方式**：better-sqlite3（预编译二进制）
- **WAL 模式**：启用 Write-Ahead Logging

### 主要表结构
- sessions, messages, cron_tasks, batch_tasks, batch_runs
- chatbot_sessions, chatbot_messages
- stardom_tasks, stardom_learned_routes, stardom_pair_history
- knowledge_extraction_progress

## WebSocket 消息协议
> by nasakim | 验证: 2026-05
✅ [已验证] server/index.ts
- **端口**：5880
- **认证**：首条消息 auth.verify + Bearer token
- **消息格式**：JSON with type field
- **消息隔离**：会话特定消息不广播

## 会话初始化流程
> by nasakim | 验证: 2026-05
✅ [已验证] server/init/init-manager.ts
1. workspace-scanner → 扫描项目
2. skill-injector → 注入 Skills
3. capability-matcher → 匹配 Capabilities
4. claude-init-runner → 执行初始化对话

## 知识提取机制
> by nasakim | 验证: 2026-05
✅ [已验证] server/knowledge-extractor.ts
- **触发**：每 10 分钟空闲时
- **存储**：{workspace}/.sman/knowledge/{category}-{username}.md
- **共享**：git push
- **聚合**：skill-auto-updater 每 2 小时
- **去重**：hash 标记 + 增量提取

## 地球路径存储
> by nasakim | 验证: 2026-05
✅ [已验证] server/smart-path-store.ts
- **存储方式**：文件系统（非 SQLite）
- **路径定义**：{workspace}/.sman/paths/{pathId}/path.md
- **执行记录**：{pathId}/runs/{runId}.json
- **执行机制**：逐步骤执行，每步纯内存临时会话

## 星域三层架构
> by nasakim | 验证: 2026-05
✅ [已验证] server/stardom/, src/features/stardom/, stardom/src/
- **前端层**：React UI
- **桥接层**：连接管理、经验提取
- **星域服务器**：独立包

### Agent 进化机制
- 对话经验 → learned_routes.experience
- 磨合记录 → pair_history
- 搜索排序：老搭档 > 历史协作 > 有经验 > 远程
