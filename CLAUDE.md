# SmanWeb CLAUDE.md

> 此文件给 Claude Code 提供项目上下文

## 项目定位

SmanWeb 是**智能业务系统一体化部署包**，将四层架构（UI + Agent平台 + 执行层 + 业务系统）焊死为一个开箱即用的服务。

## 核心架构

```
用户 → SmanWeb (Node.js 后端 + React 前端)
         ↓
      OpenClaw Gateway (bundled/)
         ↓
      Claude Code (bundled/)
         ↓
      Business System (挂载卷)
```

## 关键目录

| 目录 | 说明 |
|------|------|
| `server/` | Node.js 后端，管理 OpenClaw 进程，WebSocket 代理 |
| `src/` | React 前端 |
| `scripts/` | 打包脚本 (bundle-*.mjs) |
| `bundled/` | 打包产物 (OpenClaw, Claude Code, LSP, Skills) |
| `resources/skills/` | 技能清单配置 |

## 构建流程

```bash
pnpm build
# 执行顺序：
# 1. vite build → dist/
# 2. zx scripts/bundle-openclaw.mjs → bundled/openclaw/ (~489MB)
# 3. zx scripts/bundle-skills.mjs → bundled/skills/
# 4. zx scripts/bundle-claude-code.mjs → bundled/claude-code/ (~56MB)
# 5. zx scripts/bundle-lsp.mjs → bundled/lsp/
# 6. tsc -p server/tsconfig.json → dist/server/
```

## 运行模式

### 开发模式
```bash
pnpm dev          # 前端 (5173)
pnpm dev:server   # 后端 (3000)，需要本地有 OpenClaw
```

### 生产模式
```bash
pnpm start        # 使用 bundled/openclaw/ 启动完整服务
```

## 添加自定义技能

1. 编辑 `resources/skills/manifest.json`
2. 运行 `pnpm build` 或 `pnpm bundle:skills`

## 关键文件

- `server/index.ts` - 后端入口，启动 OpenClaw Gateway
- `server/process-manager.ts` - 进程管理器
- `server/gateway-proxy.ts` - WebSocket 代理
- `src/stores/gateway.ts` - 前端 Gateway 连接状态
- `src/stores/chat.ts` - 聊天状态管理

## 注意事项

- OpenClaw Gateway 使用 `--token` 参数认证（不是 `--auth-mode` + `--auth-token`）
- bundled/ 目录不提交到 git（太大）
- 健康检查端点 `/api/health` 会报告 OpenClaw 进程状态
