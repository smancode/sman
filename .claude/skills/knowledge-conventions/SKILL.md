---
name: knowledge-conventions
description: "开发规范：编码约定、命名规则、架构决策、项目特定规则。经代码验证，由 skill-auto-updater 聚合。"
_scanned:
  commitHash: "4db35f24f89dda0c11aa6aad83ba7bb7f8df368a"
  scannedAt: "2026-05-06T00:00:00.000Z"
  branch: "master"
---

# Development Conventions

> 贡献者: nasakim | 验证时间: 2026-05-06

**注意**：当前 conventions-nasakim.md 文件为空，暂无用户提交的规范。

## 已发现的编码规范（从代码中提取）

### TypeScript/JavaScript 规范
- **ESM 模块**：服务端使用 ESM (`"type": "module"`)
- **__dirname 替代**：服务端用 `path.dirname(fileURLToPath(import.meta.url))` 替代 `__dirname`
- **类型安全**：使用 TypeScript strict mode
- **Zod 验证**：所有外部输入使用 Zod schema 验证

### 架构规范
- **消息隔离**：所有 server 端的 Map 以 sessionId 为 key，防止跨会话串扰
- **消息排队**：SDK 不支持打断，后端通过 `await streamDone` 排队消息
- **Auth 边界**：只有 `/api/` 路径需要 Bearer auth，静态文件直接放行

### 文件命名
- **测试文件**：`tests/server/{module-name}.test.ts`
- **类型文件**：`types.ts` 或 `{module}-types.ts`
- **技能文件**：`.claude/skills/{skill-name}/SKILL.md`

### Git 规范
- **Commit 消息**：使用 Co-Authored-By 标记 Claude 贡献
- **分支策略**：master 主分支 + 功能分支

### 日志规范
- **使用 utils/logger.ts**：统一日志接口
- **日志级别**：debug, info, warn, error

## 待补充的规范
- 参数校验规范（CODING_RULES.md）
- 错误处理规范
- SQL 编写规范
- WebSocket 消息格式规范

## 消息推送必须基于会话订阅精确路由
> by nasakim | 验证: 2026-05
✅ [已验证] server/index.ts:L146-214
- 禁止遍历所有已认证客户端广播消息，必须通过 client↔session 双向映射精确定位接收者
- 每条 WebSocket 消息只能发给订阅了对应会话的客户端，防止消息错发到其他标签页
- 核心函数：`subscribeClientToSession`, `unsubscribeClientFromSession`, `getSessionClients`, `sendToSessionClients`
- 客户端断开时必须双向清理映射，防止内存泄漏和幽灵订阅

## CLAUDE.md 精简原则：只保留框架，详情指向 skill
> by nasakim | 验证: 2026-05
✅ [已验证] CLAUDE.md (已从 622 行精简到约 200 行)
- CLAUDE.md 定位：基础信息、构建/测试方法、不知道就无法继续的关键信息、特别声明
- 具体项目信息（详细目录结构、API 列表、注意事项详解等）不内联，通过 skill 按需加载，CLAUDE.md 中仅指向对应 skill
- 目标行数控制在 200 行左右，避免膨胀（曾有 622 行的教训）

## 设计哲学：大模型越强，产品自动变强
> by nasakim | 验证: 2026-05
✅ [已验证] server/claude-session.ts:L312,324,359
- 不搞强制的计数、阈值、降级机制等硬编码兜底，只通过提示词（user prompt）说清楚期望的执行逻辑
- 信任 LLM 的判断能力，AUTO 模式下如果用户搞不定会自己关掉
- 用户 prompt 修改行为指令放 user prompt（`[Sman 行为要求]`区块），不动 system prompt
- AUTO 模式规则覆盖所有 skill 中的"等用户确认"要求
