# SmanBase

智能业务底座：Skills 驱动的 Claude Code 业务赋能平台。

## 核心理念

> 通用能力靠大厂，我们做业务层（预配置 + 编排 + 交付）。

## 端口

| 端口 | 用途 |
|------|------|
| 5880 | SmanBase HTTP + WebSocket 服务 |
| 18789 | 本地日常 OpenClaw（不要动） |

## 架构

```
用户 → SmanBase UI → SmanBase 后端 → Claude Agent SDK → Claude Code → 业务系统
```

两层架构，去掉 OpenClaw 中间层。

## 开发

```bash
# 安装依赖
pnpm install

# 前端开发
pnpm dev

# 后端开发
pnpm dev:server
```

## 文档

- [设计文档](docs/superpowers/specs/2026-03-24-smanbase-design.md)
