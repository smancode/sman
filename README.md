# SmanWeb - 智能业务系统交互界面

SmanWeb 是**智能业务系统**的用户交互层，提供简单可靠的 Web/App 界面。

## 核心设计理念

### 智能业务系统四层架构

```
┌─────────────────────────────────────────────────────────────┐
│                    SmanWeb (UI Layer)                       │
│              简单可靠的交互界面                               │
│                    Web / App                                │
└─────────────────────────────────────────────────────────────┘
                              │
                              │ WebSocket
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                  OpenClaw (Agent Platform)                   │
│              秘书 · 能力基座 · 主动服务                        │
│           Gateway + Skills + Multi-channel                  │
└─────────────────────────────────────────────────────────────┘
                              │
                              │ Tool Calls / MCP
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                 Claude Code (Execution Layer)                │
│              精细深入 · 理解和开发业务系统                      │
│          Code Generation / Refactoring / Debugging          │
└─────────────────────────────────────────────────────────────┘
                              │
                              │ File System / API / Database
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                  Business System (Target)                    │
│                   传统业务系统                                │
│         ERP / CRM / Custom Apps / Any Domain                │
└─────────────────────────────────────────────────────────────┘
```

### 各层职责

| 层级 | 角色 | 职责 |
|------|------|------|
| **SmanWeb** | 交互界面 | 提供简单可靠的 Web/App UI，用户与系统交互的入口 |
| **OpenClaw** | 秘书/基座 | 智能体平台底座，管理会话、技能、多渠道，主动提供服务 |
| **Claude Code** | 执行者 | 精细深入的工作：代码生成、重构、调试、业务理解 |
| **Business System** | 目标系统 | 被智能化改造的业务系统 |

### 与传统架构的区别

**传统部署：**
```
用户 → 业务系统（无智能）
```

**智能业务系统部署：**
```
用户 → SmanWeb → OpenClaw → Claude Code → 业务系统（智能增强）
```

## 关键问题：如何将四层焊死为一个服务？

这是当前需要解决的核心架构问题。

### 初步思路

1. **进程管理**：使用 supervisor / systemd / Docker Compose 管理多进程
2. **服务发现**：各组件之间如何发现和连接
3. **配置统一**：统一的配置管理和环境变量
4. **日志聚合**：统一的日志收集和查看
5. **健康检查**：整体服务的健康状态监控
6. **一键部署**：单一部署单元，简化运维

### 待分析方向

- [ ] Docker Compose 方案
- [ ] Kubernetes Operator 方案
- [ ] 单一进程嵌入方案
- [ ] Supervisor/systemd 方案
- [ ] 混合方案

---

## Quick Start

### Prerequisites

- Node.js 20+
- pnpm (recommended) or npm
- OpenClaw Gateway (running on port 18789/18790)

### Development

```bash
# Install dependencies
pnpm install

# Start development server
pnpm dev

# Build for production
pnpm build

# Preview production build
pnpm preview
```

## Local Testing with OpenClaw

⚠️ **重要**: 不要修改本地默认的 OpenClaw 端口 18789！

### 启动测试用 OpenClaw Gateway

```bash
cd ~/projects/openclaw

# 启动测试用 Gateway (端口 18790)
OPENCLAW_GATEWAY_PORT=18790 \
OPENCLAW_GATEWAY_AUTH_MODE=token \
OPENCLAW_GATEWAY_AUTH_TOKEN=sman-31244d65207dcced \
OPENCLAW_GATEWAY_BIND=loopback \
pnpm gateway
```

### SmanWeb 连接配置

| 配置项 | 值 |
|--------|---|
| **Gateway URL** | `ws://127.0.0.1:18790` |
| **Token** | `sman-31244d65207dcced` |

### 端口说明

| 端口 | 用途 | 说明 |
|------|------|------|
| 18789 | 默认 OpenClaw | **不要修改** - 本地日常使用 |
| 18790 | 测试用 OpenClaw | SmanWeb 测试专用 |
| 5173 | SmanWeb Dev | Vite 开发服务器 |
| 3000 | SmanWeb Prod | Docker/生产端口 |

## Tech Stack

- React 19 + TypeScript
- Vite
- Tailwind CSS
- shadcn/ui
- Zustand (state management)
- React Router

## Deployment

### Docker Deployment (Recommended)

```bash
# Build and run
docker-compose up -d

# View logs
docker-compose logs -f

# Stop
docker-compose down
```

### Manual Deployment

```bash
# Install dependencies
pnpm install

# Build frontend and bundle dependencies
pnpm build

# Start server
PORT=3000 GATEWAY_TOKEN=your-token pnpm start
```

### Configuration

| Environment Variable | Default | Description |
|---------------------|---------|-------------|
| `PORT` | 3000 | Server HTTP port |
| `GATEWAY_PORT` | 18789 | Internal OpenClaw Gateway port |
| `GATEWAY_TOKEN` | *required* | Authentication token for gateway |

### Business System Integration

Mount your business system code at `/app/business-system`:

```bash
docker run -v /path/to/your/code:/app/business-system:ro smanweb
```

## License

MIT
