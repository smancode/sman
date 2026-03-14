# SmanWeb - 智能业务系统一体化部署包

SmanWeb 是**开箱即用的智能业务系统部署包**，将四层架构焊死为一个服务，用户无需安装任何依赖即可使用。

## 核心价值

**传统部署 vs SmanWeb 部署：**

```
传统：用户需安装 Node.js + OpenClaw + Claude Code + LSP + ...
SmanWeb：一键启动，全部内置
```

## 架构说明

```
┌─────────────────────────────────────────────────────────────────┐
│                     SmanWeb 部署包 (约 650MB)                    │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │                    Node.js Backend                        │  │
│  │  - Express 服务器 (port 3000)                            │  │
│  │  - 进程管理 (启动 OpenClaw Gateway)                       │  │
│  │  - WebSocket 代理 (/ws → OpenClaw)                       │  │
│  │  - 静态文件服务 (React 前端)                               │  │
│  └──────────────────────────────────────────────────────────┘  │
│                              │ WebSocket                        │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │              OpenClaw Gateway (bundled/openclaw)         │  │
│  │  - 智能体平台底座                                          │  │
│  │  - 会话管理、技能调度                                      │  │
│  │  - 多渠道支持                                              │  │
│  └──────────────────────────────────────────────────────────┘  │
│                              │ acpx (ACP 协议)                   │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │              Claude Code (bundled/claude-code)            │  │
│  │  - 代码生成 / 重构 / 调试                                  │  │
│  │  - 业务系统理解和开发                                       │  │
│  │  - LSP 代码智能                                            │  │
│  └──────────────────────────────────────────────────────────┘  │
│                              │ File System                      │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │              LSP Servers (bundled/lsp)                    │  │
│  │  - jdtls (Java)                                          │  │
│  │  - pyright (Python)                                       │  │
│  └──────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                  Business System (挂载卷)                       │
│              用户的业务系统代码                                   │
│         ERP / CRM / Custom Apps / Any Domain                   │
└─────────────────────────────────────────────────────────────────┘
```

## 打包内容

| 组件 | 位置 | 大小 | 说明 |
|------|------|------|------|
| **OpenClaw Gateway** | `bundled/openclaw/` | ~489MB | Agent 平台 + 所有依赖 |
| **Claude Code** | `bundled/claude-code/` | ~56MB | 执行层 CLI |
| **LSP Servers** | `bundled/lsp/` | ~70MB | jdtls + pyright |
| **自定义技能** | `bundled/skills/` | 可变 | 业务特定技能 |
| **React 前端** | `dist/` | ~600KB | Web UI |
| **Node.js 后端** | `dist/server/` | ~20KB | Express 服务器 |

## Quick Start

### 开发模式

```bash
# 安装依赖
pnpm install

# 启动开发服务器 (前端)
pnpm dev

# 启动后端 (另一个终端)
pnpm dev:server

# 或同时启动
pnpm dev:full
```

### 生产部署

```bash
# 完整构建 (打包所有组件)
pnpm build

# 启动服务
PORT=3000 GATEWAY_TOKEN=your-secure-token pnpm start

# 健康检查
curl http://localhost:3000/api/health
```

### Docker 部署

```bash
# 构建并启动
docker-compose up -d

# 查看日志
docker-compose logs -f

# 停止
docker-compose down
```

## 配置项

| 环境变量 | 默认值 | 说明 |
|---------|--------|------|
| `PORT` | 3000 | HTTP 服务端口 |
| `GATEWAY_PORT` | 18789 | OpenClaw Gateway 内部端口 |
| `GATEWAY_TOKEN` | *必须设置* | Gateway 认证令牌 |

## 业务系统集成

将业务系统代码挂载到容器中：

```bash
docker run -v /path/to/business-system:/app/business-system:ro smanweb
```

Claude Code 将能够读取和修改业务系统代码。

## 添加自定义技能

1. 编辑 `resources/skills/manifest.json`：

```json
{
  "skills": [
    {
      "slug": "my-business-skill",
      "repo": "your-org/skills-repo",
      "repoPath": "skills/my-skill",
      "ref": "main"
    }
  ]
}
```

2. 运行构建：

```bash
pnpm build
```

技能会自动从 GitHub 拉取并打包到 `bundled/skills/`。

## 项目结构

```
smanweb/
├── server/                 # Node.js 后端
│   ├── index.ts           # 服务入口
│   ├── process-manager.ts # 进程管理
│   └── gateway-proxy.ts   # WebSocket 代理
├── src/                   # React 前端
├── scripts/               # 构建脚本
│   ├── bundle-openclaw.mjs    # 打包 OpenClaw
│   ├── bundle-skills.mjs      # 打包技能
│   ├── bundle-claude-code.mjs # 打包 Claude Code
│   └── bundle-lsp.mjs         # 打包 LSP
├── bundled/               # 打包产物
│   ├── openclaw/         # OpenClaw Gateway
│   ├── claude-code/      # Claude Code CLI
│   ├── lsp/              # LSP 服务器
│   └── skills/           # 自定义技能
├── resources/             # 资源文件
│   └── skills/
│       └── manifest.json # 技能清单
├── Dockerfile
└── docker-compose.yml
```

## API 端点

| 端点 | 说明 |
|------|------|
| `GET /api/health` | 健康检查 (返回 OpenClaw 状态) |
| `GET /api/config` | 获取 Gateway 配置 |
| `WS /ws` | WebSocket 代理到 OpenClaw |

## Tech Stack

- **Frontend**: React 19 + TypeScript + Vite + Tailwind CSS + shadcn/ui
- **Backend**: Node.js + Express + WebSocket
- **Agent Platform**: OpenClaw Gateway
- **Execution Layer**: Claude Code
- **LSP**: jdtls (Java) + pyright (Python)

## License

MIT
