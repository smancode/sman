# SmanWeb - OpenClaw Web Interface

A modern web interface for OpenClaw built with React, TypeScript, and Tailwind CSS.

## Features

- Modern UI with Tailwind CSS and shadcn/ui components
- Real-time chat interface for OpenClaw
- Connection management and settings
- Multi-language support (i18n)
- Responsive design
- Docker-ready deployment

## Quick Start

### Prerequisites

- Node.js 20+
- pnpm (recommended) or npm

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

### Docker

```bash
# Build image
docker build -t smanweb .

# Run container
docker run -p 3000:80 smanweb

# Or use docker-compose
docker-compose up -d
```

## Local Testing with OpenClaw

⚠️ **重要**: 不要修改本地默认的 OpenClaw 端口 18789！

### 启动测试用 OpenClaw Gateway

为了测试 SmanWeb，需要从 `~/projects/openclaw` 启动一个独立的 Gateway 实例：

```bash
cd ~/projects/openclaw

# 启动测试用 Gateway (端口 18790)
OPENCLAW_GATEWAY_PORT=18789 \
OPENCLAW_GATEWAY_AUTH_MODE=token \
OPENCLAW_GATEWAY_AUTH_TOKEN=sman-31244d65207dcced \
OPENCLAW_GATEWAY_BIND=loopback \
pnpm gateway
```

### SmanWeb 连接配置

在 SmanWeb 设置页面配置：

| 配置项 | 值 |
|--------|---|
| **Gateway URL** | `ws://127.0.0.1:18789` |
| **Token** | `sman-31244d65207dcced` |

### 端口说明

| 端口 | 用途 | 说明 |
|------|------|------|
| 18789 | 默认 OpenClaw | **不要修改** - 本地日常使用 |
| 5173 | SmanWeb Dev | Vite 开发服务器 |
| 3000 | SmanWeb Prod | Docker/生产端口 |

## Project Structure

```
smanweb/
├── src/
│   ├── app/             # 应用入口和路由
│   ├── components/      # React 组件
│   │   ├── ui/          # shadcn/ui 基础组件
│   │   ├── common/      # 通用组件
│   │   └── layout/      # 布局组件
│   ├── features/        # 功能模块
│   │   ├── chat/        # 聊天功能
│   │   └── settings/    # 设置页面
│   ├── hooks/           # 自定义 Hooks
│   ├── lib/             # 核心库
│   │   └── gateway-client.ts  # WebSocket 客户端
│   ├── stores/          # Zustand 状态管理
│   └── types/           # TypeScript 类型
├── public/              # 静态资源
├── docs/                # 文档
└── dist/                # 构建输出
```

## Configuration

Copy `.env.example` to `.env` and configure:

```bash
cp .env.example .env
```

| Variable | Description | Default |
|----------|-------------|---------|
| VITE_API_URL | OpenClaw Gateway URL | ws://127.0.0.1:18789 |
| VITE_DEBUG | Enable debug mode | false |
| VITE_APP_TITLE | Application title | SmanWeb |

## Tech Stack

- React 19 + TypeScript
- Vite
- Tailwind CSS
- shadcn/ui
- Zustand (state management)
- React Router
- i18next

## Architecture

```
Browser (SmanWeb React SPA)
         │
         │ WebSocket (wss://)
         ▼
OpenClaw Gateway (Remote Server)
         │
    ┌────┼────┐
    ▼    ▼    ▼
Skills  Agents  Business System
```

## License

MIT
