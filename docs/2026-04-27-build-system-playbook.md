# 全栈应用构建体系 Playbook

> 基于 Sman 项目实战经验提炼的通用构建方法论
> 适用场景：Electron + Node.js 后端 + React 前端 的全栈桌面应用
> 目标：快速、自动、可靠地构建和部署

---

## 目录

- [一、道：构建哲学](#一道构建哲学)
- [二、体：体系结构](#二体体系结构)
- [三、术：实践指南](#三术实践指南)
  - [3.1 项目模板](#31-项目模板)
  - [3.2 构建脚本](#32-构建脚本)
  - [3.3 CI/CD 配置](#33-cicd-配置)
  - [3.4 部署方案](#34-部署方案)
  - [3.5 评估体系](#35-评估体系)
- [四、附录：踩坑速查](#四附录踩坑速查)

---

## 一、道：构建哲学

### 1.1 三大核心原则

#### 原则一：确定性构建（Deterministic Build）

**定义**：同样的代码、同样的环境、同样的输入，必须产生同样的输出。

**为什么重要**：
- 避免"我本地可以"的灾难
- 回滚时确保产物一致性
- 安全审计需要可复现的构建

**落地要求**：
- 锁定依赖版本（`package-lock.json` / `pnpm-lock.yaml`）
- 锁定 Node 版本（`.nvmrc` / `package.json#engines`）
- 锁定系统工具版本（Docker 镜像 tag 不用 `latest`）
- 构建环境标准化（Docker / CI 统一环境）

#### 原则二：失败快速暴露（Fail Fast）

**定义**：构建流程中任何问题都应在最早阶段暴露，而不是在最后打包时才发现。

**落地要求**：
- 类型检查在编译前执行
- 单元测试在集成测试前执行
- 静态分析在单元测试前执行
- 构建脚本遇到错误立即退出（`set -e` / `set -euo pipefail`）

**构建阶段顺序**：

```
lint → type-check → unit-test → build → integration-test → package → verify
```

#### 原则三：单一真相源（Single Source of Truth）

**定义**：版本号、配置、依赖关系等关键信息只应存在一份，其他位置通过引用获得。

**落地要求**：
- 版本号只在 `package.json` 中定义，其他位置通过脚本读取
- 环境配置通过 `.env` 文件管理，不硬编码在脚本中
- 构建产物目录结构由单一配置文件决定

### 1.2 构建策略决策树

```
是否需要桌面端？
├── 否 → 纯 Web 应用
│       ├── 是否需要 SSR？
│       │   ├── 是 → Next.js / Nuxt（服务端渲染 + 静态导出）
│       │   └── 否 → Vite + SPA（纯静态部署）
│       └── 部署方式：
│           ├── 有运维团队 → Docker + K8s
│           └── 无运维团队 → Vercel / Netlify / 云函数
│
└── 是 → 桌面应用
        ├── 是否需要本地后端服务？
        │   ├── 是 → Electron + Node.js 嵌入式服务（Sman 模式）
        │   │   └── 前端加载方式：
        │   │       ├── 追求简单 → loadFile() 本地文件
        │   │       └── 需要动态能力 → loadURL() HTTP 服务
        │   └── 否 → Electron + 纯前端（Tauri 也可考虑）
        └── 是否需要服务端部署？
            ├── 是 → 服务端独立打包 + Docker
            └── 否 → 仅桌面端打包
```

### 1.3 技术选型黄金法则

| 决策点 | 推荐选择 | 理由 |
|--------|---------|------|
| 包管理器 | pnpm | 磁盘效率高、monorepo 友好、严格依赖管理 |
| 前端构建 | Vite | 快速 HMR、原生 ESM、生态成熟 |
| 前端框架 | React + TypeScript | 类型安全、生态丰富 |
| 后端运行时 | Node.js 22 LTS | 长期支持、原生模块兼容性好 |
| 后端模块 | ESM (`"type": "module"`) | 现代标准、Tree-shaking 友好 |
| 桌面框架 | Electron | 成熟稳定、原生 API 丰富 |
| 数据库 | better-sqlite3 | 零配置、单文件、预编译二进制 |
| 样式方案 | TailwindCSS | 原子化、构建时 Purge、体积小 |
| 测试框架 | Vitest | 与 Vite 同生态、快速、ESM 原生支持 |

---

## 二、体：体系结构

### 2.1 构建流水线架构

```
┌─────────────────────────────────────────────────────────────────┐
│                        构建流水线                                │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  ┌──────────┐   ┌──────────┐   ┌──────────┐   ┌──────────┐    │
│  │  代码提交 │ → │  触发构建 │ → │  环境准备 │ → │  质量门禁 │    │
│  └──────────┘   └──────────┘   └──────────┘   └──────────┘    │
│                                                      │          │
│  ┌──────────┐   ┌──────────┐   ┌──────────┐         ▼          │
│  │  产物验证 │ ← │  打包分发 │ ← │  并行构建 │ ←  全部通过      │
│  └──────────┘   └──────────┘   └──────────┘                    │
│       │                                                        │
│       ▼                                                        │
│  ┌──────────┐                                                  │
│  │  发布上线 │                                                  │
│  └──────────┘                                                  │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### 2.2 质量门禁体系

```
Level 1: 静态检查（秒级）
├── ESLint / Prettier 代码风格
├── TypeScript 类型检查
└── 依赖安全扫描 (pnpm audit)

Level 2: 单元测试（分钟级）
├── 业务逻辑单元测试
├── 工具函数测试
└── 覆盖率阈值检查（≥ 80%）

Level 3: 集成测试（分钟级）
├── API 接口测试
├── 数据库操作测试
└── 端到端关键路径测试

Level 4: 构建验证（分钟级）
├── 前端构建产物检查
├── 后端编译产物检查
├── Electron 打包产物检查
└── 安装包可安装性验证

Level 5: 发布前检查（手动）
├── 版本号确认
├── CHANGELOG 确认
└── 回滚方案确认
```

### 2.3 产物管理策略

```
构建产物目录结构：

project-root/
├── dist/                    # 开发构建产物（.gitignore）
│   ├── assets/              # 前端静态资源
│   ├── server/              # 后端编译产物
│   │   ├── package.json     # ESM 标记 {"type": "module"}
│   │   └── server/          # 实际代码
│   └── electron/            # Electron 编译产物
│
├── release/                 # 发布产物（.gitignore）
│   ├── Sman-Setup-x.x.x.exe    # Windows 安装包
│   ├── Sman-x.x.x.dmg          # macOS 安装包
│   └── docker-image.tar        # Docker 镜像导出
│
└── artifacts/               # CI 产物（归档）
    ├── build-report.json    # 构建报告
    ├── coverage/            # 测试覆盖率
    └── logs/                # 构建日志
```

### 2.4 环境管理矩阵

| 环境 | 用途 | 触发方式 | 产物保留 |
|------|------|---------|---------|
| 本地开发 | 日常开发 | 手动 `pnpm dev` | 不保留 |
| CI 构建 | PR 验证 | 自动触发 | 保留 7 天 |
| 预发布 | 内部测试 | 手动触发 | 保留 30 天 |
| 生产 | 正式发布 | 手动触发 + 审批 | 永久保留 |

---

## 三、术：实践指南

### 3.1 项目模板

#### 3.1.1 目录结构模板

```
my-project/
├── package.json              # 根配置，"type": "module"
├── tsconfig.json             # 前端 TS 配置
├── vite.config.ts            # Vite 配置
├── .nvmrc                    # Node 版本锁定
├── pnpm-workspace.yaml       # 如需 monorepo
│
├── src/                      # React 前端源码
│   ├── app/
│   ├── features/
│   ├── components/
│   ├── stores/
│   └── lib/
│
├── server/                   # Node.js 后端源码
│   ├── index.ts              # 入口
│   ├── tsconfig.json         # 后端 TS 配置（module: ES2022）
│   └── ...
│
├── electron/                 # Electron 主进程
│   ├── main.ts               # 主进程入口
│   ├── preload.ts            # 预加载脚本
│   └── tsconfig.json         # Electron TS 配置（module: CommonJS）
│
├── shared/                   # 前后端共享类型
│   └── types.ts
│
├── scripts/                  # 构建脚本
│   ├── dev.sh
│   ├── build.sh
│   ├── build-win.sh
│   ├── build-mac.sh
│   └── deploy/
│       ├── docker-compose.yml
│       └── install.sh
│
├── tests/                    # 测试文件
│   ├── unit/
│   └── e2e/
│
├── .github/
│   └── workflows/
│       ├── ci.yml
│       ├── build-desktop.yml
│       └── release.yml
│
├── build/                    # 构建资源（图标等）
│   └── icon.ico
│
└── docs/
    └── build-playbook.md     # 本项目构建文档
```

#### 3.1.2 package.json 模板

```json
{
  "name": "my-project",
  "version": "0.1.0",
  "private": true,
  "type": "module",
  "main": "electron/dist/main/main.js",
  "engines": {
    "node": ">=22.0.0",
    "pnpm": ">=9.0.0"
  },
  "scripts": {
    "dev": "vite",
    "dev:server": "tsx server/index.ts",
    "build": "vite build && tsc -p server/tsconfig.json && node scripts/post-build.js",
    "build:electron": "electron-vite build",
    "electron:build": "pnpm build && pnpm build:electron && electron-builder",
    "test": "vitest run",
    "test:watch": "vitest",
    "lint": "eslint . --ext .ts,.tsx",
    "typecheck": "tsc --noEmit && tsc -p server/tsconfig.json --noEmit",
    "audit": "pnpm audit --audit-level moderate"
  },
  "dependencies": {
    "express": "^4.21.0",
    "react": "^19.0.0",
    "react-dom": "^19.0.0",
    "ws": "^8.18.0",
    "zod": "^4.3.6"
  },
  "devDependencies": {
    "@types/node": "^22.0.0",
    "@types/react": "^19.0.0",
    "@vitejs/plugin-react": "^4.3.0",
    "electron": "^33.0.0",
    "electron-builder": "^25.0.0",
    "electron-vite": "^5.0.0",
    "tsx": "^4.19.0",
    "typescript": "^5.7.0",
    "vite": "^6.0.0",
    "vitest": "^2.1.0"
  },
  "pnpm": {
    "onlyBuiltDependencies": ["better-sqlite3", "esbuild", "electron"]
  },
  "build": {
    "appId": "com.mycompany.myproject",
    "productName": "MyProject",
    "icon": "build/icon.ico",
    "files": [
      "dist/**/*",
      "electron/dist/**/*",
      "node_modules/**/*",
      "!node_modules/**/*.d.ts",
      "!node_modules/**/*.md",
      "!node_modules/**/test/**/*"
    ],
    "mac": {
      "target": ["dmg"],
      "category": "public.app-category.developer-tools"
    },
    "win": {
      "target": [{ "target": "nsis", "arch": ["x64"] }],
      "artifactName": "MyProject-Setup-${version}.${ext}"
    },
    "nsis": {
      "oneClick": false,
      "allowToChangeInstallationDirectory": true,
      "perMachine": false
    },
    "asar": true,
    "asarUnpack": ["node_modules/better-sqlite3/**/*"],
    "directories": {
      "output": "release"
    }
  }
}
```

#### 3.1.3 TypeScript 配置模板

**前端 tsconfig.json：**

```json
{
  "compilerOptions": {
    "target": "ES2022",
    "module": "ESNext",
    "moduleResolution": "bundler",
    "esModuleInterop": true,
    "strict": true,
    "skipLibCheck": true,
    "outDir": "dist",
    "rootDir": ".",
    "jsx": "react-jsx",
    "paths": {
      "@/*": ["./src/*"]
    }
  },
  "include": ["src", "server", "shared"],
  "exclude": ["node_modules", "dist", "electron"]
}
```

**后端 server/tsconfig.json：**

```json
{
  "compilerOptions": {
    "target": "ES2022",
    "module": "ES2022",
    "moduleResolution": "node",
    "esModuleInterop": true,
    "strict": true,
    "skipLibCheck": true,
    "outDir": "../dist/server",
    "rootDir": "..",
    "declaration": true
  },
  "include": ["./**/*.ts", "../shared/**/*.ts"],
  "exclude": ["node_modules", "../dist"]
}
```

**Electron electron/tsconfig.json：**

```json
{
  "compilerOptions": {
    "target": "ES2022",
    "module": "CommonJS",
    "moduleResolution": "node",
    "esModuleInterop": true,
    "strict": true,
    "skipLibCheck": true,
    "outDir": "../dist/electron",
    "rootDir": ".",
    "declaration": true
  },
  "include": ["./**/*.ts"],
  "exclude": ["node_modules", "../dist"]
}
```

> **关键决策**：三个 tsconfig 分别对应三种模块系统：
> - 前端：ESNext（Vite 处理）
> - 后端：ES2022（Node.js 原生 ESM）
> - Electron：CommonJS（Electron 主进程兼容）

#### 3.1.4 Vite 配置模板

```typescript
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import path from 'path'
import { readFileSync } from 'fs'

const pkg = JSON.parse(readFileSync(path.resolve(__dirname, 'package.json'), 'utf-8'))

export default defineConfig({
  define: {
    __APP_VERSION__: JSON.stringify(pkg.version),
  },
  plugins: [react()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  server: {
    port: 5881,
    proxy: {
      '/api': {
        target: 'http://localhost:5880',
        changeOrigin: true,
      },
      '/ws': {
        target: 'ws://localhost:5880',
        ws: true,
      },
    },
  },
  optimizeDeps: {
    exclude: ['electron'],
  },
  build: {
    outDir: 'dist',
    sourcemap: true,
    rollupOptions: {
      output: {
        manualChunks: {
          vendor: ['react', 'react-dom'],
        },
      },
    },
  },
})
```

### 3.2 构建脚本

#### 3.2.1 开发启动脚本 `scripts/dev.sh`

```bash
#!/usr/bin/env bash
set -euo pipefail

# ═══════════════════════════════════════════════════════════════
# 开发模式启动脚本
# 一键启动：后端 + 前端 + Electron
# ═══════════════════════════════════════════════════════════════

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

# 颜色定义
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'
info()  { echo -e "${CYAN}[INFO]${NC} $*"; }
ok()    { echo -e "${GREEN}[OK]${NC} $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC} $*"; }
err()   { echo -e "${RED}[ERROR]${NC} $*" >&2; }

# ── 1. 环境检查 ──
if ! command -v pnpm &> /dev/null; then
  err "pnpm not found. Install: npm install -g pnpm"
  exit 1
fi

if [ ! -f ".nvmrc" ]; then
  warn "No .nvmrc found, using current Node version"
else
  CURRENT_NODE=$(node -v | cut -d'v' -f2 | cut -d'.' -f1)
  REQUIRED_NODE=$(cat .nvmrc | cut -d'.' -f1)
  if [ "$CURRENT_NODE" != "$REQUIRED_NODE" ]; then
    err "Node version mismatch. Current: $(node -v), Required: $(cat .nvmrc)"
    err "Run: nvm use (or fnm use)"
    exit 1
  fi
fi

if [ ! -d "node_modules" ]; then
  info "Installing dependencies..."
  pnpm install
fi

# ── 2. 端口冲突清理 ──
kill_port() {
  local port=$1
  local pids=$(lsof -i :"$port" -sTCP:LISTEN -t 2>/dev/null | tr '\n' ' ')
  if [ -n "$pids" ]; then
    warn "Port $port in use, killing $pids..."
    echo "$pids" | xargs kill -15 2>/dev/null
    sleep 1
    local remaining=$(lsof -i :"$port" -sTCP:LISTEN -t 2>/dev/null | tr '\n' ' ')
    if [ -n "$remaining" ]; then
      echo "$remaining" | xargs kill -9 2>/dev/null
    fi
  fi
}

kill_port 5880
kill_port 5881
ok "Ports ready"

# ── 3. 构建 ──
info "Building backend..."
pnpm tsc -p server/tsconfig.json 2>&1 | tail -5
if [ ! -f "dist/server/package.json" ]; then
  echo '{"type":"module"}' > dist/server/package.json
fi
ok "Backend built"

info "Building Electron..."
pnpm build:electron 2>&1 | tail -5
ok "Electron built"

# ── 4. 启动服务 ──
cleanup() {
  echo -e "\n${YELLOW}Shutting down...${NC}"
  jobs -p | xargs kill 2>/dev/null || true
  wait 2>/dev/null || true
  ok "Done"
}
trap cleanup EXIT INT TERM

info "Starting backend on port 5880..."
pnpm dev:server &

info "Starting frontend on port 5881..."
pnpm dev &

# ── 5. 等待就绪 ──
wait_for_service() {
  local url=$1
  local name=$2
  local max_wait=${3:-30}
  for i in $(seq 1 $max_wait); do
    if curl -s "$url" > /dev/null 2>&1; then
      ok "$name ready"
      return 0
    fi
    if [ $i -eq $max_wait ]; then
      err "$name failed to start"
      exit 1
    fi
    sleep 0.5
  done
}

wait_for_service "http://localhost:5880/api/health" "Backend" 30
wait_for_service "http://localhost:5881" "Frontend" 30

# ── 6. 启动 Electron ──
info "Starting Electron..."
npx electron . &

ok "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
ok "  Development environment ready"
ok "  Frontend: http://localhost:5881"
ok "  Backend:  http://localhost:5880"
ok "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

wait
```

#### 3.2.2 生产构建脚本 `scripts/build.sh`

```bash
#!/usr/bin/env bash
set -euo pipefail

# ═══════════════════════════════════════════════════════════════
# 生产构建脚本
# 构建前端 + 后端 + Electron，生成可分发产物
# ═══════════════════════════════════════════════════════════════

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'
info()  { echo -e "${CYAN}[INFO]${NC} $*"; }
ok()    { echo -e "${GREEN}[OK]${NC} $*"; }
err()   { echo -e "${RED}[ERROR]${NC} $*" >&2; }

START_TIME=$(date +%s)

# ── 1. 质量门禁 ──
info "Running quality gates..."

info "  [1/4] Linting..."
pnpm lint || { err "Lint failed"; exit 1; }

info "  [2/4] Type checking..."
pnpm typecheck || { err "Type check failed"; exit 1; }

info "  [3/4] Security audit..."
pnpm audit --audit-level moderate || warn "Security audit found issues"

info "  [4/4] Unit tests..."
pnpm test || { err "Tests failed"; exit 1; }

ok "Quality gates passed"

# ── 2. 版本号设置 ──
if [ "${SET_VERSION:-}" = "date" ]; then
  DATE_VERSION=$(date '+%y.%-m.%-d')
  info "Setting version to $DATE_VERSION"
  node -e "const fs=require('fs');const p=JSON.parse(fs.readFileSync('package.json','utf8'));p.version='$DATE_VERSION';fs.writeFileSync('package.json',JSON.stringify(p,null,2)+'\n')"
fi

# ── 3. 清理旧产物 ──
info "Cleaning old artifacts..."
rm -rf dist release
ok "Cleaned"

# ── 4. 构建前端 ──
info "Building frontend (Vite)..."
pnpm vite build
ok "Frontend built"

# ── 5. 构建后端 ──
info "Building backend (TypeScript)..."
pnpm tsc -p server/tsconfig.json
# 生成 ESM 标记文件
echo '{"type":"module"}' > dist/server/package.json
# 复制模板文件（如果有）
if [ -d "server/init/templates" ]; then
  cp -r server/init/templates dist/server/server/init/templates
fi
ok "Backend built"

# ── 6. 构建 Electron ──
info "Building Electron..."
pnpm build:electron
ok "Electron built"

# ── 7. 构建报告 ──
END_TIME=$(date +%s)
BUILD_DURATION=$((END_TIME - START_TIME))

mkdir -p artifacts

cat > artifacts/build-report.json <<EOF
{
  "timestamp": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "version": "$(node -p "require('./package.json').version")",
  "duration_seconds": $BUILD_DURATION,
  "platform": "$(uname -s)",
  "node_version": "$(node -v)",
  "frontend_size": $(du -sb dist/assets 2>/dev/null | cut -f1 || echo 0),
  "backend_size": $(du -sb dist/server 2>/dev/null | cut -f1 || echo 0)
}
EOF

ok "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
ok "  Build completed in ${BUILD_DURATION}s"
ok "  Version: $(node -p "require('./package.json').version")"
ok "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
```

#### 3.2.3 Windows 打包脚本 `scripts/build-win.sh`

```bash
#!/bin/bash
set -euo pipefail

# ═══════════════════════════════════════════════════════════════
# Windows x64 打包脚本
# 必须在 Windows 环境（Git Bash / MSYS2 / WSL）中运行
# ═══════════════════════════════════════════════════════════════

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR/.."

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'
info()  { echo -e "${CYAN}[INFO]${NC} $*"; }
ok()    { echo -e "${GREEN}[OK]${NC} $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC} $*"; }
err()   { echo -e "${RED}[ERROR]${NC} $*" >&2; }

# ── 检查操作系统 ──
if [[ "$(uname -s)" != "MINGW"* && "$(uname -s)" != "MSYS"* && "$(uname -s)" != "CYGWIN"* && "$(uname -s)" != "Windows_NT" ]]; then
  err "This script must run on Windows"
  exit 1
fi

# ── 检查 Node 版本 ──
NODE_MAJOR=$(node -e "console.log(process.version.split('.')[0].replace('v',''))")
if [[ "$NODE_MAJOR" -lt 22 ]]; then
  err "Node.js >= 22 required, current: $(node -v)"
  exit 1
fi

# ── 主流程 ──
main() {
  local SKIP_DEPS=false
  [[ "${1:-}" == "--skip-deps" ]] && SKIP_DEPS=true

  info "MyProject Windows x64 Build"
  info "Version: $(node -p "require('./package.json').version")"
  info "Date: $(date '+%Y-%m-%d %H:%M:%S')"

  # 1. 依赖安装
  if [[ "$SKIP_DEPS" == "false" ]]; then
    info "Installing dependencies..."
    pnpm install
    ok "Dependencies installed"

    # 清理 pnpm 无效符号链接（electron-builder 会遍历这些目录）
    local rollup_dir="node_modules/.pnpm/node_modules/@rollup"
    if [[ -d "$rollup_dir" ]]; then
      for link in "$rollup_dir"/*; do
        if [[ -L "$link" && ! -e "$link" ]]; then
          warn "Removing broken symlink: $(basename "$link")"
          rm "$link"
        fi
      done
    fi
  fi

  # 2. 构建
  info "Building application..."
  pnpm build
  ok "Build complete"

  # 3. 构建 Electron
  info "Building Electron..."
  pnpm build:electron
  ok "Electron built"

  # 4. 验证原生模块
  info "Checking native modules..."
  local SQLITE_DIR
  SQLITE_DIR=$(find node_modules/.pnpm -path "*/better-sqlite3@*/better-sqlite3" -maxdepth 6 -type d 2>/dev/null | head -1)
  if [[ -n "$SQLITE_DIR" ]]; then
    local NODE_FILE="$SQLITE_DIR/build/Release/better_sqlite3.node"
    if [[ ! -f "$NODE_FILE" ]]; then
      err "better_sqlite3.node not found!"
      exit 1
    fi
    ok "Native module ready"
  fi

  # 5. 打包
  info "Packaging with electron-builder..."
  export CSC_IDENTITY_AUTO_DISCOVERY=false
  rm -f release/*.exe release/*.blockmap 2>/dev/null || true
  npx electron-builder --win nsis --x64
  ok "Packaging complete"

  # 6. 验证
  local installer=""
  for f in release/*-Setup-*.exe; do
    [[ -f "$f" ]] && installer="$f" && break
  done

  if [[ -z "$installer" ]]; then
    err "Installer not found!"
    ls -la release/ 2>/dev/null || true
    exit 1
  fi

  local exe_size=$(du -sh "$installer" | cut -f1)

  echo ""
  echo "============================================================"
  echo -e "  ${GREEN}Build Successful!${NC}"
  echo "============================================================"
  echo "  Installer: $installer ($exe_size)"
  echo "============================================================"
}

main "$@"
```

#### 3.2.4 macOS 打包脚本 `scripts/build-mac.sh`

```bash
#!/bin/bash
set -euo pipefail

# ═══════════════════════════════════════════════════════════════
# macOS DMG 打包脚本
# ═══════════════════════════════════════════════════════════════

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR/.."

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'
info()  { echo -e "${CYAN}[INFO]${NC} $*"; }
ok()    { echo -e "${GREEN}[OK]${NC} $*"; }
err()   { echo -e "${RED}[ERROR]${NC} $*" >&2; }

# ── 检查 ──
if [[ "$(uname -s)" != "Darwin" ]]; then
  err "This script must run on macOS"
  exit 1
fi

info "MyProject macOS Build"

# 构建流程
pnpm install
pnpm build
pnpm build:electron

# 打包
rm -f release/*.dmg release/*.blockmap 2>/dev/null || true
npx electron-builder --mac dmg

# 验证
local dmg=""
for f in release/*.dmg; do
  [[ -f "$f" ]] && dmg="$f" && break
done

if [[ -n "$dmg" ]]; then
  ok "DMG created: $dmg ($(du -sh "$dmg" | cut -f1))"
else
  err "DMG not found!"
  exit 1
fi
```

### 3.3 CI/CD 配置

#### 3.3.1 CI 流水线 `.github/workflows/ci.yml`

```yaml
name: CI

on:
  push:
    branches: [main, master]
  pull_request:
    branches: [main, master]

jobs:
  quality-gates:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Setup Node.js
        uses: actions/setup-node@v4
        with:
          node-version-file: '.nvmrc'
          cache: 'pnpm'

      - name: Setup pnpm
        uses: pnpm/action-setup@v2
        with:
          version: 9

      - name: Install dependencies
        run: pnpm install --frozen-lockfile

      - name: Lint
        run: pnpm lint

      - name: Type check
        run: pnpm typecheck

      - name: Security audit
        run: pnpm audit --audit-level moderate
        continue-on-error: true

      - name: Unit tests
        run: pnpm test

      - name: Build
        run: pnpm build

      - name: Upload build artifacts
        uses: actions/upload-artifact@v4
        with:
          name: build-output
          path: |
            dist/
            artifacts/
          retention-days: 7

  build-desktop:
    needs: quality-gates
    strategy:
      matrix:
        os: [windows-latest, macos-latest]
    runs-on: ${{ matrix.os }}
    steps:
      - uses: actions/checkout@v4

      - name: Setup Node.js
        uses: actions/setup-node@v4
        with:
          node-version-file: '.nvmrc'
          cache: 'pnpm'

      - name: Setup pnpm
        uses: pnpm/action-setup@v2
        with:
          version: 9

      - name: Install dependencies
        run: pnpm install --frozen-lockfile

      - name: Build application
        run: pnpm build

      - name: Build Electron
        run: pnpm build:electron

      - name: Package (Windows)
        if: matrix.os == 'windows-latest'
        run: npx electron-builder --win nsis --x64
        env:
          CSC_IDENTITY_AUTO_DISCOVERY: false

      - name: Package (macOS)
        if: matrix.os == 'macos-latest'
        run: npx electron-builder --mac dmg

      - name: Upload installer
        uses: actions/upload-artifact@v4
        with:
          name: installer-${{ matrix.os }}
          path: release/*
          retention-days: 30
```

#### 3.3.2 发布流水线 `.github/workflows/release.yml`

```yaml
name: Release

on:
  workflow_dispatch:
    inputs:
      version:
        description: 'Version bump type'
        required: true
        default: 'patch'
        type: choice
        options:
          - patch
          - minor
          - major

jobs:
  release:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0

      - name: Setup Node.js
        uses: actions/setup-node@v4
        with:
          node-version-file: '.nvmrc'
          cache: 'pnpm'

      - name: Setup pnpm
        uses: pnpm/action-setup@v2
        with:
          version: 9

      - name: Install dependencies
        run: pnpm install --frozen-lockfile

      - name: Bump version
        run: |
          pnpm version ${{ github.event.inputs.version }} --no-git-tag-version
          echo "VERSION=$(node -p "require('./package.json').version")" >> $GITHUB_ENV

      - name: Build all
        run: pnpm build

      - name: Run tests
        run: pnpm test

      - name: Generate changelog
        run: |
          echo "## Changes in v${VERSION}" > RELEASE_NOTES.md
          git log $(git describe --tags --abbrev=0)..HEAD --oneline >> RELEASE_NOTES.md

      - name: Commit version bump
        run: |
          git config user.name "github-actions"
          git config user.email "github-actions@github.com"
          git add package.json
          git commit -m "chore(release): v${VERSION}"
          git tag "v${VERSION}"
          git push origin main --tags

      - name: Create GitHub Release
        uses: softprops/action-gh-release@v1
        with:
          tag_name: v${{ env.VERSION }}
          name: Release v${{ env.VERSION }}
          body_path: RELEASE_NOTES.md
          draft: true
```

### 3.4 部署方案

#### 3.4.1 Docker 部署 `scripts/deploy/Dockerfile`

```dockerfile
# ═══════════════════════════════════════════════════════════════
# 服务端 Docker 镜像
# 适用于：独立部署后端服务场景
# ═══════════════════════════════════════════════════════════════

FROM node:22-alpine AS builder

WORKDIR /app
COPY package.json pnpm-lock.yaml ./
RUN npm install -g pnpm && pnpm install --frozen-lockfile

COPY . .
RUN pnpm build

# ── 生产镜像 ──
FROM node:22-alpine

WORKDIR /app

# 只复制生产依赖和构建产物
COPY --from=builder /app/dist ./dist
COPY --from=builder /app/node_modules ./node_modules
COPY --from=builder /app/package.json ./

# 创建数据目录
RUN mkdir -p /data

ENV NODE_ENV=production
ENV PORT=5880
ENV SMANBASE_HOME=/data

EXPOSE 5880

HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:5880/api/health || exit 1

CMD ["node", "dist/server/server/index.js"]
```

#### 3.4.2 Docker Compose `scripts/deploy/docker-compose.yml`

```yaml
version: '3.8'

services:
  myapp:
    build:
      context: ../..
      dockerfile: scripts/deploy/Dockerfile
    ports:
      - "5880:5880"
    volumes:
      - app-data:/data
    environment:
      - NODE_ENV=production
      - PORT=5880
    restart: unless-stopped
    healthcheck:
      test: ["CMD", "wget", "--no-verbose", "--tries=1", "--spider", "http://localhost:5880/api/health"]
      interval: 30s
      timeout: 3s
      retries: 3
      start_period: 10s

volumes:
  app-data:
```

#### 3.4.3 一键安装脚本 `scripts/deploy/install.sh`

```bash
#!/bin/bash
set -euo pipefail

# ═══════════════════════════════════════════════════════════════
# 服务端一键安装脚本
# 用法: curl -fsSL https://your-domain.com/install.sh | bash
# ═══════════════════════════════════════════════════════════════

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'
info()  { echo -e "${CYAN}[INFO]${NC} $*"; }
ok()    { echo -e "${GREEN}[OK]${NC} $*"; }
err()   { echo -e "${RED}[ERROR]${NC} $*" >&2; }

info "MyProject Server Installer"

# ── 检查依赖 ──
if ! command -v docker &> /dev/null; then
  err "Docker not found. Please install Docker first:"
  err "  https://docs.docker.com/get-docker/"
  exit 1
fi

if ! command -v docker-compose &> /dev/null; then
  err "Docker Compose not found. Please install it first."
  exit 1
fi

# ── 下载配置 ──
INSTALL_DIR="${INSTALL_DIR:-/opt/myproject}"
mkdir -p "$INSTALL_DIR"
cd "$INSTALL_DIR"

info "Downloading deployment files..."
curl -fsSL "https://your-domain.com/docker-compose.yml" -o docker-compose.yml
curl -fsSL "https://your-domain.com/Dockerfile" -o Dockerfile
ok "Files downloaded"

# ── 启动 ──
info "Starting services..."
docker-compose up -d

# ── 等待就绪 ──
info "Waiting for service to be ready..."
for i in $(seq 1 30); do
  if curl -s http://localhost:5880/api/health > /dev/null 2>&1; then
    ok "Service is ready!"
    break
  fi
  if [ $i -eq 30 ]; then
    err "Service failed to start"
    docker-compose logs
    exit 1
  fi
  sleep 1
done

# ── 完成 ──
echo ""
echo "============================================================"
echo -e "  ${GREEN}Installation Complete!${NC}"
echo "============================================================"
echo "  Access: http://localhost:5880"
echo "  Data dir: $INSTALL_DIR"
echo "  Logs: docker-compose logs -f"
echo "============================================================"
```

### 3.5 评估体系

#### 3.5.1 构建质量指标

| 指标 | 目标值 | 测量方式 | 告警阈值 |
|------|--------|---------|---------|
| 构建时间 | < 5 分钟 | CI 日志 | > 10 分钟 |
| 产物大小 | < 200MB | `du -sh release/` | > 300MB |
| 测试覆盖率 | ≥ 80% | Vitest coverage | < 70% |
| 安全漏洞 | 0 高危 | `pnpm audit` | > 0 高危 |
| 类型错误 | 0 | `tsc --noEmit` | > 0 |
| 构建成功率 | ≥ 95% | CI 统计 | < 90% |

#### 3.5.2 构建报告生成脚本 `scripts/build-report.js`

```javascript
#!/usr/bin/env node
/**
 * 构建报告生成器
 * 在构建完成后运行，生成 JSON 格式的构建报告
 */

import fs from 'fs'
import path from 'path'
import { execSync } from 'child_process'

const pkg = JSON.parse(fs.readFileSync('package.json', 'utf-8'))
const report = {
  timestamp: new Date().toISOString(),
  project: pkg.name,
  version: pkg.version,
  platform: process.platform,
  arch: process.arch,
  node_version: process.version,
  git: {
    branch: execSync('git rev-parse --abbrev-ref HEAD').toString().trim(),
    commit: execSync('git rev-parse --short HEAD').toString().trim(),
    dirty: execSync('git status --porcelain').toString().trim().length > 0,
  },
  sizes: {},
  duration: 0, // 由调用方传入 BUILD_START_TIME
}

// 测量产物大小
const measure = (dir) => {
  if (!fs.existsSync(dir)) return 0
  const stats = fs.statSync(dir)
  return stats.isDirectory() ? getFolderSize(dir) : stats.size
}

const getFolderSize = (dir) => {
  let size = 0
  const files = fs.readdirSync(dir)
  for (const file of files) {
    const filePath = path.join(dir, file)
    const stats = fs.statSync(filePath)
    size += stats.isDirectory() ? getFolderSize(filePath) : stats.size
  }
  return size
}

report.sizes.frontend = measure('dist/assets')
report.sizes.backend = measure('dist/server')
report.sizes.electron = measure('electron/dist')

// 检查是否有安装包
const releaseDir = 'release'
if (fs.existsSync(releaseDir)) {
  const installers = fs.readdirSync(releaseDir).filter(f =>
    f.endsWith('.exe') || f.endsWith('.dmg') || f.endsWith('.AppImage')
  )
  report.installers = installers.map(f => ({
    name: f,
    size: fs.statSync(path.join(releaseDir, f)).size,
  }))
}

// 保存报告
const artifactsDir = 'artifacts'
fs.mkdirSync(artifactsDir, { recursive: true })
fs.writeFileSync(
  path.join(artifactsDir, 'build-report.json'),
  JSON.stringify(report, null, 2)
)

console.log('Build report generated: artifacts/build-report.json')
console.log(JSON.stringify(report, null, 2))
```

#### 3.5.3 健康检查端点

```typescript
// server/index.ts 中添加
app.get('/api/health', (_req, res) => {
  res.json({
    status: 'ok',
    version: process.env.npm_package_version || 'unknown',
    uptime: process.uptime(),
    timestamp: new Date().toISOString(),
    memory: process.memoryUsage(),
  })
})

app.get('/api/health/detailed', (_req, res) => {
  // 检查数据库连接等关键依赖
  const checks = {
    database: checkDatabase(),
    disk: checkDiskSpace(),
    memory: checkMemory(),
  }
  const allHealthy = Object.values(checks).every(c => c.status === 'ok')
  res.status(allHealthy ? 200 : 503).json({
    status: allHealthy ? 'ok' : 'degraded',
    checks,
  })
})
```

---

## 四、附录：踩坑速查

### 4.1 CJS/ESM 兼容性

| 问题 | 现象 | 解决方案 |
|------|------|---------|
| `ERR_REQUIRE_ESM` | `require()` 无法加载 ESM 模块 | 改用 `import()` 动态导入 |
| `__dirname is not defined` | ESM 中没有 `__dirname` | `path.dirname(fileURLToPath(import.meta.url))` |
| `ERR_UNSUPPORTED_ESM_URL_SCHEME` | Windows 上 `import()` 失败 | 使用 `file:///` URL |
| 模块解析失败 | 混合 CJS/ESM 时路径错误 | 确保 `package.json` 有 `"type": "module"` |

### 4.2 原生模块处理

| 问题 | 现象 | 解决方案 |
|------|------|---------|
| MODULE_VERSION 不匹配 | 原生模块与 Node 版本不兼容 | 使用预编译二进制，不要手动 rebuild |
| ASAR 内找不到 bindings | 打包后原生模块加载失败 | 禁用 ASAR 或配置 `asarUnpack` |
| electron-rebuild 失败 | pnpm 结构下 rebuild 无效 | 直接用 pnpm install 的预编译二进制 |

### 4.3 Electron 特有问题

| 问题 | 现象 | 解决方案 |
|------|------|---------|
| 白屏 | Electron 窗口空白 | 禁用 GPU 加速：`app.disableHardwareAcceleration()` |
| Auth 拦截静态文件 | 加载页面返回 401 | Auth 中间件只对 `/api/` 生效 |
| 端口冲突 | 开发服务器启动失败 | 脚本中自动检测并清理占用端口的进程 |

### 4.4 构建环境

| 问题 | 现象 | 解决方案 |
|------|------|---------|
| pnpm 符号链接问题 | electron-builder 遍历报错 | 清理 `node_modules/.pnpm/node_modules/@rollup/` 无效链接 |
| Node 版本不一致 | 构建产物运行时崩溃 | 使用 `.nvmrc` 锁定版本，CI 中校验 |
| 依赖未锁定 | 不同环境构建结果不同 | 提交 `pnpm-lock.yaml`，CI 用 `--frozen-lockfile` |

---

## 五、Sman 项目参考映射

| 本 Playbook 章节 | Sman 对应实践 | 可借鉴度 |
|-----------------|--------------|---------|
| 3.1.2 package.json 模板 | `package.json` | 高 |
| 3.1.3 tsconfig 模板 | `tsconfig.json` + `server/tsconfig.json` + `electron/tsconfig.json` | 高 |
| 3.1.4 Vite 配置 | `vite.config.ts` | 高 |
| 3.2.1 dev.sh | `dev.sh` | 高 |
| 3.2.3 build-win.sh | `build-win.sh` | 高 |
| 3.3.1 CI 配置 | 无（Sman 未配置 CI） | 新增 |
| 3.4 Docker 部署 | 无（Sman 未配置） | 新增 |
| 4.1 CJS/ESM | `docs/windows-packaging.md` 第 1、3、4、6 条 | 高 |
| 4.2 原生模块 | `docs/windows-packaging.md` 第 5、11 条 | 高 |
| 4.3 Electron | `docs/windows-packaging.md` 第 7、8 条 | 高 |

---

*文档版本: 1.0*
*基于 Sman v26.4.21 项目经验提炼*
*最后更新: 2026-04-27*
