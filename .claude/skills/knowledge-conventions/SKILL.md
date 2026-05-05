---
name: knowledge-conventions
description: "开发规范：编码约定、命名规则、架构决策、项目特定规则。经代码验证，由 skill-auto-updater 聚合。"
_scanned:
  commitHash: "35f8e752359eff2474610cf31f0beaaa40ccbca9"
  scannedAt: "2026-05-05T00:00:00.000Z"
  branch: "master"
---

# Development Conventions

> 贡献者: nasakim | 验证时间: 2026-05-05

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
