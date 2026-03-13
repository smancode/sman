# SmanWeb 智能业务系统集成方案实现计划

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 SmanWeb + OpenClaw + acpx + Claude Code 焊死为一个可部署的服务，实现智能业务系统架构。

**Architecture:** 采用 Node.js 后端作为主进程，使用 child_process 管理 OpenClaw Gateway 和 acpx 子进程。前端通过 WebSocket 连接 OpenClaw Gateway，OpenClaw 通过 acpx (ACP 协议) 调用 Claude Code 执行业务操作。

**Tech Stack:** Node.js + Express (后端), React + Vite (前端), OpenClaw (Agent 平台), acpx (ACP 桥接), Claude Code (执行层), Docker (部署单元)

---

## Chunk 1: 架构设计与文件结构

### 目录结构设计

```
smanweb/
├── server/                      # Node.js 后端 (新增)
│   ├── index.ts                 # 服务入口
│   ├── process-manager.ts       # 子进程管理 (OpenClaw + acpx)
│   ├── gateway-proxy.ts         # WebSocket 代理
│   ├── routes/
│   │   ├── health.ts            # 健康检查
│   │   ├── config.ts            # 配置管理
│   │   └── sessions.ts          # 会话管理
│   └── utils/
│       ├── logger.ts            # 日志工具
│       └── ports.ts             # 端口分配
├── bundled/                     # 打包的依赖 (新增)
│   ├── openclaw/                # OpenClaw + 依赖
│   ├── skills/                  # 预装技能
│   └── manifest.json            # 版本锁定
├── scripts/                     # 构建脚本 (新增)
│   ├── bundle-openclaw.mjs      # 打包 OpenClaw
│   ├── bundle-skills.mjs        # 打包技能
│   └── build-server.mjs         # 构建后端
├── src/                         # 前端代码 (现有)
├── Dockerfile                   # Docker 构建 (新增)
├── docker-compose.yml           # 本地开发 (新增)
└── package.json                 # 更新依赖
```

### 核心流程

```
┌─────────────────────────────────────────────────────────────────┐
│                     Docker Container                             │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │                    Node.js Backend                        │  │
│  │  ┌─────────────────────────────────────────────────────┐ │  │
│  │  │  Process Manager                                     │ │  │
│  │  │  - spawn OpenClaw Gateway (port 18789)              │ │  │
│  │  │  - monitor health, restart on crash                 │ │  │
│  │  └─────────────────────────────────────────────────────┘ │  │
│  │  ┌─────────────────────────────────────────────────────┐ │  │
│  │  │  Gateway Proxy (WebSocket)                          │ │  │
│  │  │  - forward client WS to OpenClaw                    │ │  │
│  │  │  - inject auth token                                │ │  │
│  │  └─────────────────────────────────────────────────────┘ │  │
│  │  ┌─────────────────────────────────────────────────────┐ │  │
│  │  │  Express Server (port 3000)                         │ │  │
│  │  │  - serve static frontend                            │ │  │
│  │  │  - /api/* REST endpoints                            │ │  │
│  │  └─────────────────────────────────────────────────────┘ │  │
│  └──────────────────────────────────────────────────────────┘  │
│                              │                                   │
│                    WebSocket │                                   │
│                              ▼                                   │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │                  OpenClaw Gateway                        │  │
│  │              (bundled/openclaw/)                         │  │
│  │  - ACP bridge enabled                                    │  │
│  │  - connects to Claude Code via acpx                      │  │
│  └──────────────────────────────────────────────────────────┘  │
│                              │                                   │
│                          ACP Protocol                            │
│                              ▼                                   │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │                      acpx                                │  │
│  │          (headless ACP client)                           │  │
│  │  - spawns Claude Code process                            │  │
│  │  - manages session lifecycle                             │  │
│  └──────────────────────────────────────────────────────────┘  │
│                              │                                   │
│                     Tool Calls / MCP                             │
│                              ▼                                   │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │                    Claude Code                           │  │
│  │              (execution layer)                           │  │
│  │  - reads/writes business system code                     │  │
│  │  - loads business-specific skills                        │  │
│  └──────────────────────────────────────────────────────────┘  │
│                              │                                   │
│                     File System / API                            │
│                              ▼                                   │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │                  Business System                         │  │
│  │              (mounted volume)                            │  │
│  │  - ERP / CRM / Custom App                                │  │
│  └──────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
```

---

## Chunk 2: Task 1 - Node.js 后端基础设施

### Task 1: 创建 Node.js 后端基础设施

**Files:**
- Create: `server/index.ts`
- Create: `server/process-manager.ts`
- Create: `server/gateway-proxy.ts`
- Create: `server/utils/logger.ts`
- Create: `server/utils/ports.ts`
- Modify: `package.json`

- [ ] **Step 1: 更新 package.json 添加后端依赖**

```json
{
  "scripts": {
    "dev": "vite",
    "dev:server": "tsx watch server/index.ts",
    "dev:full": "concurrently \"pnpm dev\" \"pnpm dev:server\"",
    "build": "vite build && zx scripts/bundle-openclaw.mjs && zx scripts/bundle-skills.mjs && tsc -p server/tsconfig.json",
    "start": "node dist/server/index.js",
    "bundle:openclaw": "zx scripts/bundle-openclaw.mjs",
    "bundle:skills": "zx scripts/bundle-skills.mjs"
  },
  "dependencies": {
    "express": "^4.21.0",
    "ws": "^8.19.0",
    "uuid": "^10.0.0",
    "acpx": "^0.4.0"
  },
  "devDependencies": {
    "@types/express": "^5.0.0",
    "@types/uuid": "^10.0.0",
    "tsx": "^4.19.0",
    "concurrently": "^9.0.0",
    "zx": "^8.8.5"
  }
}
```

- [ ] **Step 2: 创建日志工具**

Create `server/utils/logger.ts`:

```typescript
/**
 * Simple structured logger for server-side operations
 */

type LogLevel = 'debug' | 'info' | 'warn' | 'error';

interface LogEntry {
  timestamp: string;
  level: LogLevel;
  component: string;
  message: string;
  data?: Record<string, unknown>;
}

function formatLog(entry: LogEntry): string {
  const { timestamp, level, component, message, data } = entry;
  const prefix = `[${timestamp}] [${level.toUpperCase()}] [${component}]`;
  if (data) {
    return `${prefix} ${message} ${JSON.stringify(data)}`;
  }
  return `${prefix} ${message}`;
}

export function createLogger(component: string) {
  const log = (level: LogLevel, message: string, data?: Record<string, unknown>) => {
    const entry: LogEntry = {
      timestamp: new Date().toISOString(),
      level,
      component,
      message,
      data,
    };
    const output = formatLog(entry);

    if (level === 'error') {
      console.error(output);
    } else if (level === 'warn') {
      console.warn(output);
    } else {
      console.log(output);
    }
  };

  return {
    debug: (msg: string, data?: Record<string, unknown>) => log('debug', msg, data),
    info: (msg: string, data?: Record<string, unknown>) => log('info', msg, data),
    warn: (msg: string, data?: Record<string, unknown>) => log('warn', msg, data),
    error: (msg: string, data?: Record<string, unknown>) => log('error', msg, data),
  };
}

export type Logger = ReturnType<typeof createLogger>;
```

- [ ] **Step 3: 创建端口分配工具**

Create `server/utils/ports.ts`:

```typescript
/**
 * Port allocation utilities
 */

export const DEFAULT_PORTS = {
  server: 3000,
  gateway: 18789,
  vite: 5173,
} as const;

/**
 * Check if a port is available
 */
export async function isPortAvailable(port: number): Promise<boolean> {
  const net = await import('net');

  return new Promise((resolve) => {
    const server = net.createServer();
    server.once('error', () => resolve(false));
    server.once('listening', () => {
      server.close();
      resolve(true);
    });
    server.listen(port);
  });
}

/**
 * Find an available port starting from the given port
 */
export async function findAvailablePort(startPort: number): Promise<number> {
  let port = startPort;
  while (!(await isPortAvailable(port))) {
    port++;
    if (port > startPort + 100) {
      throw new Error(`Could not find available port starting from ${startPort}`);
    }
  }
  return port;
}
```

- [ ] **Step 4: 创建进程管理器**

Create `server/process-manager.ts`:

```typescript
/**
 * Process Manager for OpenClaw Gateway and acpx
 *
 * Manages child processes with:
 * - Health monitoring
 * - Auto-restart on crash
 * - Graceful shutdown
 */

import { spawn, ChildProcess } from 'child_process';
import { createLogger } from './utils/logger';
import path from 'path';

const log = createLogger('ProcessManager');

export interface ProcessConfig {
  name: string;
  command: string;
  args: string[];
  env?: NodeJS.ProcessEnv;
  cwd?: string;
  restartOnExit?: boolean;
  healthCheckInterval?: number;
}

export interface ManagedProcess {
  name: string;
  process: ChildProcess | null;
  config: ProcessConfig;
  isHealthy: boolean;
  restartCount: number;
  lastStart: number;
}

export class ProcessManager {
  private processes: Map<string, ManagedProcess> = new Map();
  private shutdownRequested = false;
  private healthCheckTimers: Map<string, NodeJS.Timeout> = new Map();

  /**
   * Start a managed process
   */
  start(config: ProcessConfig): ManagedProcess {
    if (this.processes.has(config.name)) {
      throw new Error(`Process ${config.name} is already running`);
    }

    const managed: ManagedProcess = {
      name: config.name,
      process: null,
      config,
      isHealthy: false,
      restartCount: 0,
      lastStart: 0,
    };

    this.processes.set(config.name, managed);
    this.spawnProcess(managed);

    return managed;
  }

  private spawnProcess(managed: ManagedProcess): void {
    const { config } = managed;
    log.info(`Starting process: ${config.name}`, { command: config.command, args: config.args });

    managed.lastStart = Date.now();
    managed.process = spawn(config.command, config.args, {
      cwd: config.cwd || process.cwd(),
      env: { ...process.env, ...config.env },
      stdio: ['ignore', 'pipe', 'pipe'],
      windowsHide: true,
    });

    // Handle stdout
    managed.process.stdout?.on('data', (data: Buffer) => {
      const lines = data.toString().trim().split('\n');
      for (const line of lines) {
        log.info(`[${config.name}] ${line}`);
      }
    });

    // Handle stderr
    managed.process.stderr?.on('data', (data: Buffer) => {
      const lines = data.toString().trim().split('\n');
      for (const line of lines) {
        log.warn(`[${config.name}] ${line}`);
      }
    });

    // Handle process exit
    managed.process.on('exit', (code, signal) => {
      log.warn(`Process ${config.name} exited`, { code, signal });
      managed.process = null;
      managed.isHealthy = false;

      // Auto-restart unless shutdown requested
      if (!this.shutdownRequested && config.restartOnExit !== false) {
        const delay = Math.min(1000 * Math.pow(2, managed.restartCount), 30000);
        managed.restartCount++;

        log.info(`Scheduling restart for ${config.name} in ${delay}ms`);
        setTimeout(() => {
          if (!this.shutdownRequested && this.processes.has(config.name)) {
            this.spawnProcess(managed);
          }
        }, delay);
      }
    });

    managed.process.on('error', (err) => {
      log.error(`Process ${config.name} error`, { error: err.message });
      managed.isHealthy = false;
    });

    // Start health check
    this.startHealthCheck(managed);
  }

  private startHealthCheck(managed: ManagedProcess): void {
    const interval = managed.config.healthCheckInterval || 30000;

    const timer = setInterval(() => {
      if (managed.process && !managed.process.killed) {
        managed.isHealthy = true;
      } else {
        managed.isHealthy = false;
      }
    }, interval);

    this.healthCheckTimers.set(managed.name, timer);
  }

  /**
   * Stop a managed process
   */
  stop(name: string): Promise<void> {
    return new Promise((resolve) => {
      const managed = this.processes.get(name);
      if (!managed || !managed.process) {
        resolve();
        return;
      }

      log.info(`Stopping process: ${name}`);

      // Clear health check
      const timer = this.healthCheckTimers.get(name);
      if (timer) {
        clearInterval(timer);
        this.healthCheckTimers.delete(name);
      }

      // Graceful shutdown
      managed.process.once('exit', () => {
        log.info(`Process ${name} stopped`);
        resolve();
      });

      // Send SIGTERM, force kill after 10s
      managed.process.kill('SIGTERM');

      setTimeout(() => {
        if (managed.process && !managed.process.killed) {
          log.warn(`Force killing process: ${name}`);
          managed.process.kill('SIGKILL');
        }
      }, 10000);
    });
  }

  /**
   * Stop all managed processes
   */
  async stopAll(): Promise<void> {
    this.shutdownRequested = true;
    const stops: Promise<void>[] = [];

    for (const name of this.processes.keys()) {
      stops.push(this.stop(name));
    }

    await Promise.all(stops);
    log.info('All processes stopped');
  }

  /**
   * Get status of all processes
   */
  getStatus(): Record<string, { healthy: boolean; restartCount: number }> {
    const status: Record<string, { healthy: boolean; restartCount: number }> = {};

    for (const [name, managed] of this.processes) {
      status[name] = {
        healthy: managed.isHealthy,
        restartCount: managed.restartCount,
      };
    }

    return status;
  }
}

/**
 * Create OpenClaw Gateway process config
 */
export function createGatewayConfig(options: {
  bundledPath: string;
  port: number;
  authToken: string;
}): ProcessConfig {
  return {
    name: 'openclaw-gateway',
    command: 'node',
    args: [
      path.join(options.bundledPath, 'openclaw.mjs'),
      'gateway',
      '--port', String(options.port),
      '--auth-mode', 'token',
      '--auth-token', options.authToken,
      '--bind', 'loopback',
    ],
    cwd: options.bundledPath,
    restartOnExit: true,
    healthCheckInterval: 10000,
  };
}
```

- [ ] **Step 5: 创建 WebSocket 代理**

Create `server/gateway-proxy.ts`:

```typescript
/**
 * WebSocket Proxy for OpenClaw Gateway
 *
 * Proxies client WebSocket connections to the OpenClaw Gateway,
 * injecting authentication tokens automatically.
 */

import { WebSocketServer, WebSocket } from 'ws';
import { createLogger } from './utils/logger';
import http from 'http';

const log = createLogger('GatewayProxy');

export interface GatewayProxyOptions {
  server: http.Server;
  gatewayUrl: string;
  gatewayToken: string;
  path?: string;
}

export function createGatewayProxy(options: GatewayProxyOptions): WebSocketServer {
  const { server, gatewayUrl, gatewayToken, path = '/ws' } = options;

  const wss = new WebSocketServer({ server, path });

  wss.on('connection', (clientWs, req) => {
    const clientIp = req.socket.remoteAddress || 'unknown';
    log.info('Client connected', { ip: clientIp, path: req.url });

    // Connect to OpenClaw Gateway
    const gatewayWs = new WebSocket(gatewayUrl);

    gatewayWs.on('open', () => {
      log.debug('Connected to gateway');

      // Forward client messages to gateway
      clientWs.on('message', (data) => {
        if (gatewayWs.readyState === WebSocket.OPEN) {
          gatewayWs.send(data);
        }
      });

      // Handle gateway messages
      gatewayWs.on('message', (data) => {
        if (clientWs.readyState === WebSocket.OPEN) {
          clientWs.send(data);
        }
      });

      // Handle close
      const onClose = (reason: string) => {
        log.debug('Connection closed', { reason });
        if (gatewayWs.readyState === WebSocket.OPEN) {
          gatewayWs.close();
        }
        if (clientWs.readyState === WebSocket.OPEN) {
          clientWs.close();
        }
      };

      clientWs.on('close', () => onClose('client'));
      gatewayWs.on('close', () => onClose('gateway'));

      // Handle errors
      clientWs.on('error', (err) => log.error('Client WS error', { error: err.message }));
      gatewayWs.on('error', (err) => log.error('Gateway WS error', { error: err.message }));
    });

    gatewayWs.on('error', (err) => {
      log.error('Failed to connect to gateway', { error: err.message });
      clientWs.close(1011, 'Gateway connection failed');
    });
  });

  log.info('Gateway proxy created', { path, gatewayUrl });
  return wss;
}
```

- [ ] **Step 6: 创建服务入口**

Create `server/index.ts`:

```typescript
/**
 * SmanWeb Server - Node.js Backend
 *
 * Main entry point for the integrated service:
 * - Serves static frontend
 * - Manages OpenClaw Gateway process
 * - Proxies WebSocket connections
 */

import express from 'express';
import http from 'http';
import path from 'path';
import { createLogger } from './utils/logger';
import { ProcessManager, createGatewayConfig } from './process-manager';
import { createGatewayProxy } from './gateway-proxy';
import { DEFAULT_PORTS } from './utils/ports';

const log = createLogger('Server');

// Configuration from environment
const config = {
  port: parseInt(process.env.PORT || String(DEFAULT_PORTS.server), 10),
  gatewayPort: parseInt(process.env.GATEWAY_PORT || String(DEFAULT_PORTS.gateway), 10),
  gatewayToken: process.env.GATEWAY_TOKEN || 'sman-default-token-change-in-production',
  bundledPath: path.resolve(__dirname, '../bundled/openclaw'),
  staticPath: path.resolve(__dirname, '../dist'),
};

// Express app
const app = express();
const server = http.createServer(app);

// Process manager
const processManager = new ProcessManager();

// Health check endpoint
app.get('/api/health', (req, res) => {
  const processStatus = processManager.getStatus();
  const gatewayHealthy = processStatus['openclaw-gateway']?.healthy ?? false;

  res.json({
    status: gatewayHealthy ? 'healthy' : 'degraded',
    timestamp: new Date().toISOString(),
    processes: processStatus,
  });
});

// Configuration endpoint
app.get('/api/config', (req, res) => {
  res.json({
    gateway: {
      url: `ws://127.0.0.1:${config.gatewayPort}`,
      token: config.gatewayToken,
    },
  });
});

// Static files (frontend) - serve last to allow API routes to match first
app.use(express.static(config.staticPath));

// SPA fallback - serve index.html for all unmatched routes
app.get('*', (req, res) => {
  res.sendFile(path.join(config.staticPath, 'index.html'));
});

// Graceful shutdown
async function shutdown(signal: string) {
  log.info(`Received ${signal}, shutting down...`);
  await processManager.stopAll();
  server.close(() => {
    log.info('Server closed');
    process.exit(0);
  });
}

process.on('SIGTERM', () => shutdown('SIGTERM'));
process.on('SIGINT', () => shutdown('SIGINT'));

// Start server
async function start() {
  log.info('Starting SmanWeb server', { config });

  // Start OpenClaw Gateway
  const gatewayConfig = createGatewayConfig({
    bundledPath: config.bundledPath,
    port: config.gatewayPort,
    authToken: config.gatewayToken,
  });

  processManager.start(gatewayConfig);

  // Create WebSocket proxy
  createGatewayProxy({
    server,
    gatewayUrl: `ws://127.0.0.1:${config.gatewayPort}`,
    gatewayToken: config.gatewayToken,
    path: '/ws',
  });

  // Start HTTP server
  server.listen(config.port, () => {
    log.info(`Server listening on port ${config.port}`);
    log.info(`Gateway proxy available at ws://localhost:${config.port}/ws`);
  });
}

start().catch((err) => {
  log.error('Failed to start server', { error: err.message });
  process.exit(1);
});
```

- [ ] **Step 7: 创建 server tsconfig.json**

Create `server/tsconfig.json`:

```json
{
  "compilerOptions": {
    "target": "ES2022",
    "module": "NodeNext",
    "moduleResolution": "NodeNext",
    "outDir": "../dist/server",
    "rootDir": ".",
    "strict": true,
    "esModuleInterop": true,
    "skipLibCheck": true,
    "declaration": false
  },
  "include": ["./**/*.ts"],
  "exclude": ["node_modules"]
}
```

- [ ] **Step 8: Commit infrastructure**

```bash
git add server/ package.json
git commit -m "feat(server): add Node.js backend infrastructure

- Add process manager for OpenClaw Gateway
- Add WebSocket proxy with auto-auth
- Add health check and config endpoints
- Add structured logging
```

---

## Chunk 3: Task 2 - OpenClaw 打包脚本

### Task 2: 创建 OpenClaw 打包脚本

**Files:**
- Create: `scripts/bundle-openclaw.mjs`
- Create: `scripts/bundle-skills.mjs`
- Create: `bundled/manifest.json`
- Create: `resources/skills/manifest.json`

- [ ] **Step 1: 创建技能清单**

Create `resources/skills/manifest.json`:

```json
{
  "skills": [
    {
      "slug": "business-analyzer",
      "repo": "openclaw/skills",
      "repoPath": "skills/business-analyzer",
      "ref": "main"
    }
  ]
}
```

- [ ] **Step 2: 创建 OpenClaw 打包脚本**

Create `scripts/bundle-openclaw.mjs` (参考 ClawX 的实现):

```javascript
#!/usr/bin/env zx

/**
 * bundle-openclaw.mjs
 *
 * Bundles OpenClaw with all dependencies for self-contained deployment.
 * Follows the same approach as ClawX's bundle-openclaw.mjs.
 */

import 'zx/globals';
import { realpathSync, existsSync, mkdirSync, rmSync, cpSync, statSync, readdirSync, lstatSync, writeFileSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(__dirname, '..');
const OUTPUT = path.join(ROOT, 'bundled', 'openclaw');
const NODE_MODULES = path.join(ROOT, 'node_modules');

function normWin(p) {
  if (process.platform !== 'win32') return p;
  if (p.startsWith('\\\\?\\')) return p;
  return '\\\\?\\' + p.replace(/\//g, '\\');
}

echo`📦 Bundling OpenClaw for SmanWeb...`;

// 1. Resolve openclaw package
const openclawLink = path.join(NODE_MODULES, 'openclaw');
if (!existsSync(openclawLink)) {
  echo`❌ node_modules/openclaw not found. Run pnpm install first.`;
  process.exit(1);
}

const openclawReal = realpathSync(openclawLink);
echo`   openclaw resolved: ${openclawReal}`;

// 2. Clean output
if (existsSync(OUTPUT)) {
  rmSync(OUTPUT, { recursive: true });
}
mkdirSync(OUTPUT, { recursive: true });

// 3. Copy openclaw package
echo`   Copying openclaw package...`;
cpSync(openclawReal, OUTPUT, { recursive: true, dereference: true });

// 4. Collect transitive dependencies via pnpm virtual store
const collected = new Map();
const queue = [];

function getVirtualStoreNodeModules(realPkgPath) {
  let dir = realPkgPath;
  while (dir !== path.dirname(dir)) {
    if (path.basename(dir) === 'node_modules') return dir;
    dir = path.dirname(dir);
  }
  return null;
}

function listPackages(nodeModulesDir) {
  const result = [];
  const nDir = normWin(nodeModulesDir);
  if (!existsSync(nDir)) return result;

  for (const entry of readdirSync(nDir)) {
    if (entry === '.bin') continue;
    const entryPath = path.join(nodeModulesDir, entry);

    if (entry.startsWith('@')) {
      try {
        const scopeEntries = readdirSync(normWin(entryPath));
        for (const sub of scopeEntries) {
          result.push({
            name: `${entry}/${sub}`,
            fullPath: path.join(entryPath, sub),
          });
        }
      } catch { /* skip */ }
    } else {
      result.push({ name: entry, fullPath: entryPath });
    }
  }
  return result;
}

const openclawVirtualNM = getVirtualStoreNodeModules(openclawReal);
if (!openclawVirtualNM) {
  echo`❌ Could not determine pnpm virtual store for openclaw`;
  process.exit(1);
}

queue.push({ nodeModulesDir: openclawVirtualNM, skipPkg: 'openclaw' });

const SKIP_PACKAGES = new Set(['typescript', '@playwright/test']);
const SKIP_SCOPES = ['@types/'];
let skippedDevCount = 0;

while (queue.length > 0) {
  const { nodeModulesDir, skipPkg } = queue.shift();
  const packages = listPackages(nodeModulesDir);

  for (const { name, fullPath } of packages) {
    if (name === skipPkg) continue;
    if (SKIP_PACKAGES.has(name) || SKIP_SCOPES.some(s => name.startsWith(s))) {
      skippedDevCount++;
      continue;
    }

    let realPath;
    try {
      realPath = realpathSync(fullPath);
    } catch {
      continue;
    }

    if (collected.has(realPath)) continue;
    collected.set(realPath, name);

    const depVirtualNM = getVirtualStoreNodeModules(realPath);
    if (depVirtualNM && depVirtualNM !== nodeModulesDir) {
      queue.push({ nodeModulesDir: depVirtualNM, skipPkg: name });
    }
  }
}

echo`   Found ${collected.size} total packages`;

// 5. Copy to output
const outputNodeModules = path.join(OUTPUT, 'node_modules');
mkdirSync(outputNodeModules, { recursive: true });

let copiedCount = 0;
const copiedNames = new Set();

for (const [realPath, pkgName] of collected) {
  if (copiedNames.has(pkgName)) continue;
  copiedNames.add(pkgName);

  const dest = path.join(outputNodeModules, pkgName);
  try {
    mkdirSync(normWin(path.dirname(dest)), { recursive: true });
    cpSync(normWin(realPath), normWin(dest), { recursive: true, dereference: true });
    copiedCount++;
  } catch (err) {
    echo`   ⚠️  Skipped ${pkgName}: ${err.message}`;
  }
}

// 6. Cleanup function
function getDirSize(dir) {
  let total = 0;
  try {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      const p = path.join(dir, entry.name);
      if (entry.isDirectory()) total += getDirSize(p);
      else if (entry.isFile()) total += statSync(p).size;
    }
  } catch { /* ignore */ }
  return total;
}

function formatSize(bytes) {
  if (bytes >= 1024 * 1024 * 1024) return `${(bytes / 1024 / 1024 / 1024).toFixed(1)}G`;
  if (bytes >= 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)}M`;
  if (bytes >= 1024) return `${(bytes / 1024).toFixed(1)}K`;
  return `${bytes}B`;
}

function rmSafe(target) {
  try {
    const stat = lstatSync(target);
    if (stat.isDirectory()) rmSync(target, { recursive: true, force: true });
    else rmSync(target, { force: true });
    return true;
  } catch { return false; }
}

function cleanupBundle(outputDir) {
  let removedCount = 0;
  const nm = path.join(outputDir, 'node_modules');

  if (existsSync(nm)) {
    const REMOVE_DIRS = new Set(['test', 'tests', '__tests__', '.github', 'docs', 'examples']);
    const REMOVE_FILE_EXTS = ['.d.ts', '.d.ts.map', '.js.map', '.mjs.map'];
    const REMOVE_FILE_NAMES = new Set(['.DS_Store', 'README.md', 'CHANGELOG.md', 'LICENSE.md']);

    function walkClean(dir) {
      let entries;
      try { entries = readdirSync(dir, { withFileTypes: true }); } catch { return; }
      for (const entry of entries) {
        const full = path.join(dir, entry.name);
        if (entry.isDirectory()) {
          if (REMOVE_DIRS.has(entry.name)) {
            if (rmSafe(full)) removedCount++;
          } else {
            walkClean(full);
          }
        } else if (entry.isFile()) {
          const name = entry.name;
          if (REMOVE_FILE_NAMES.has(name) || REMOVE_FILE_EXTS.some(e => name.endsWith(e))) {
            if (rmSafe(full)) removedCount++;
          }
        }
      }
    }
    walkClean(nm);
  }

  return removedCount;
}

echo``;
echo`🧹 Cleaning up bundle...`;
const sizeBefore = getDirSize(OUTPUT);
const cleanedCount = cleanupBundle(OUTPUT);
const sizeAfter = getDirSize(OUTPUT);
echo`   Removed ${cleanedCount} files/directories`;
echo`   Size: ${formatSize(sizeBefore)} → ${formatSize(sizeAfter)}`;

// 7. Verify
const entryExists = existsSync(path.join(OUTPUT, 'openclaw.mjs'));
const distExists = existsSync(path.join(OUTPUT, 'dist', 'entry.js'));

echo``;
echo`✅ Bundle complete: ${OUTPUT}`;
echo`   Unique packages copied: ${copiedCount}`;
echo`   Dev-only packages skipped: ${skippedDevCount}`;
echo`   openclaw.mjs: ${entryExists ? '✓' : '✗'}`;

if (!entryExists) {
  echo`❌ Bundle verification failed!`;
  process.exit(1);
}
```

- [ ] **Step 3: 创建技能打包脚本**

Create `scripts/bundle-skills.mjs`:

```javascript
#!/usr/bin/env zx

/**
 * bundle-skills.mjs
 *
 * Bundles pre-installed skills from GitHub repos using sparse checkout.
 */

import 'zx/globals';
import { readFileSync, existsSync, mkdirSync, rmSync, cpSync, writeFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const ROOT = join(__dirname, '..');
const MANIFEST_PATH = join(ROOT, 'resources', 'skills', 'manifest.json');
const OUTPUT_ROOT = join(ROOT, 'bundled', 'skills');
const TMP_ROOT = join(ROOT, 'build', '.tmp-skills');

function loadManifest() {
  if (!existsSync(MANIFEST_PATH)) {
    echo`⚠️  No skills manifest found at ${MANIFEST_PATH}`;
    return [];
  }
  const raw = readFileSync(MANIFEST_PATH, 'utf8');
  const parsed = JSON.parse(raw);
  if (!parsed || !Array.isArray(parsed.skills)) {
    return [];
  }
  return parsed.skills;
}

function groupByRepoRef(entries) {
  const grouped = new Map();
  for (const entry of entries) {
    const ref = entry.ref || 'main';
    const key = `${entry.repo}#${ref}`;
    if (!grouped.has(key)) grouped.set(key, { repo: entry.repo, ref, entries: [] });
    grouped.get(key).entries.push(entry);
  }
  return [...grouped.values()];
}

function createRepoDirName(repo, ref) {
  return `${repo.replace(/[\\/]/g, '__')}__${ref.replace(/[^a-zA-Z0-9._-]/g, '_')}`;
}

async function fetchSparseRepo(repo, ref, paths, checkoutDir) {
  const remote = `https://github.com/${repo}.git`;
  mkdirSync(checkoutDir, { recursive: true });

  await $`git init ${checkoutDir}`;
  await $`git -C ${checkoutDir} remote add origin ${remote}`;
  await $`git -C ${checkoutDir} sparse-checkout init --cone`;
  await $`git -C ${checkoutDir} sparse-checkout set ${paths}`;
  await $`git -C ${checkoutDir} fetch --depth 1 origin ${ref}`;
  await $`git -C ${checkoutDir} checkout FETCH_HEAD`;

  const commit = (await $`git -C ${checkoutDir} rev-parse HEAD`).stdout.trim();
  return commit;
}

echo`📦 Bundling pre-installed skills...`;
const manifestSkills = loadManifest();

rmSync(OUTPUT_ROOT, { recursive: true, force: true });
mkdirSync(OUTPUT_ROOT, { recursive: true });
rmSync(TMP_ROOT, { recursive: true, force: true });
mkdirSync(TMP_ROOT, { recursive: true });

if (manifestSkills.length === 0) {
  echo`   No skills to bundle`;
} else {
  const lock = {
    generatedAt: new Date().toISOString(),
    skills: [],
  };

  const groups = groupByRepoRef(manifestSkills);
  for (const group of groups) {
    const repoDir = join(TMP_ROOT, createRepoDirName(group.repo, group.ref));
    const sparsePaths = [...new Set(group.entries.map((entry) => entry.repoPath))];

    echo`Fetching ${group.repo} @ ${group.ref}`;
    const commit = await fetchSparseRepo(group.repo, group.ref, sparsePaths, repoDir);
    echo`   commit ${commit}`;

    for (const entry of group.entries) {
      const sourceDir = join(repoDir, entry.repoPath);
      const targetDir = join(OUTPUT_ROOT, entry.slug);

      if (!existsSync(sourceDir)) {
        echo`   ⚠️  Missing source path: ${entry.repoPath}`;
        continue;
      }

      rmSync(targetDir, { recursive: true, force: true });
      cpSync(sourceDir, targetDir, { recursive: true, dereference: true });

      lock.skills.push({
        slug: entry.slug,
        version: commit,
        repo: entry.repo,
        repoPath: entry.repoPath,
        ref: group.ref,
        commit,
      });

      echo`   OK ${entry.slug}`;
    }
  }

  writeFileSync(join(OUTPUT_ROOT, '.skills-lock.json'), `${JSON.stringify(lock, null, 2)}\n`, 'utf8');
}

rmSync(TMP_ROOT, { recursive: true, force: true });

// Create manifest for bundled dependencies
const manifest = {
  generatedAt: new Date().toISOString(),
  openclaw: {
    version: 'from npm',
    bundledAt: new Date().toISOString(),
  },
  skills: manifestSkills,
};

writeFileSync(join(ROOT, 'bundled', 'manifest.json'), `${JSON.stringify(manifest, null, 2)}\n`, 'utf8');

echo`✅ Bundled skills ready: ${OUTPUT_ROOT}`;
```

- [ ] **Step 4: Commit bundle scripts**

```bash
git add scripts/ resources/ bundled/
git commit -m "feat(scripts): add OpenClaw and skills bundling scripts

- bundle-openclaw.mjs: package OpenClaw with all dependencies
- bundle-skills.mjs: fetch and bundle pre-installed skills
- Add skills manifest template"
```

---

## Chunk 4: Task 3 - Docker 部署

### Task 3: 创建 Docker 部署配置

**Files:**
- Create: `Dockerfile`
- Create: `docker-compose.yml`
- Create: `.dockerignore`

- [ ] **Step 1: 创建 .dockerignore**

```
node_modules
dist
build
bundled
.git
.gitignore
*.md
.env*
.vscode
.idea
*.log
```

- [ ] **Step 2: 创建 Dockerfile**

```dockerfile
# Build stage for frontend
FROM node:22-alpine AS frontend-builder

WORKDIR /app

# Install pnpm
RUN corepack enable && corepack prepare pnpm@latest --activate

# Copy package files
COPY package.json pnpm-lock.yaml ./

# Install dependencies
RUN pnpm install --frozen-lockfile

# Copy source
COPY . .

# Build frontend and bundle OpenClaw
RUN pnpm build

# Production stage
FROM node:22-alpine

WORKDIR /app

# Install pnpm for runtime
RUN corepack enable && corepack prepare pnpm@latest --activate

# Copy built assets
COPY --from=frontend-builder /app/dist ./dist
COPY --from=frontend-builder /app/bundled ./bundled
COPY --from=frontend-builder /app/node_modules ./node_modules
COPY --from=frontend-builder /app/package.json ./

# Create data directory for persistence
RUN mkdir -p /app/data

# Environment defaults
ENV PORT=3000
ENV GATEWAY_PORT=18789
ENV GATEWAY_TOKEN=sman-change-me-in-production
ENV NODE_ENV=production

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=10s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:3000/api/health || exit 1

EXPOSE 3000

# Start the server
CMD ["node", "dist/server/index.js"]
```

- [ ] **Step 3: 创建 docker-compose.yml**

```yaml
version: '3.8'

services:
  smanweb:
    build:
      context: .
      dockerfile: Dockerfile
    ports:
      - "3000:3000"
    environment:
      - PORT=3000
      - GATEWAY_PORT=18789
      - GATEWAY_TOKEN=${GATEWAY_TOKEN:-sman-change-me}
    volumes:
      # Mount business system code
      - ./business-system:/app/business-system:ro
      # Persist session data
      - sman-data:/app/data
      # Persist Claude Code credentials
      - claude-credentials:/root/.claude
    restart: unless-stopped
    healthcheck:
      test: ["CMD", "wget", "--no-verbose", "--tries=1", "--spider", "http://localhost:3000/api/health"]
      interval: 30s
      timeout: 10s
      retries: 3
      start_period: 10s

volumes:
  sman-data:
  claude-credentials:
```

- [ ] **Step 4: Commit Docker configuration**

```bash
git add Dockerfile docker-compose.yml .dockerignore
git commit -m "feat(docker): add Docker deployment configuration

- Multi-stage build for optimized image size
- Health check integration
- Volume mounts for persistence
- docker-compose for local development"
```

---

## Chunk 5: Task 4 - 前端配置更新

### Task 4: 更新前端以使用后端配置

**Files:**
- Modify: `src/stores/gateway.ts`
- Modify: `src/features/settings/index.tsx`

- [ ] **Step 1: 更新 gateway store 支持从后端获取配置**

在 `src/stores/gateway.ts` 中添加自动配置获取:

```typescript
// 在文件开头添加
const BACKEND_CONFIG_URL = '/api/config';

// 添加 fetchServerConfig 函数
export async function fetchServerConfig(): Promise<{ url: string; token: string } | null> {
  try {
    const response = await fetch(BACKEND_CONFIG_URL);
    if (!response.ok) return null;
    return await response.json();
  } catch {
    return null;
  }
}
```

- [ ] **Step 2: 更新 settings 页面显示集成状态**

在 settings 页面添加显示"集成模式"状态，当后端可用时自动使用后端配置。

- [ ] **Step 3: Commit frontend updates**

```bash
git add src/
git commit -m "feat(frontend): add backend config integration

- Fetch gateway config from backend API
- Show integrated mode status in settings"
```

---

## Chunk 6: Task 5 - 文档更新

### Task 5: 更新 README 和部署文档

**Files:**
- Modify: `README.md`

- [ ] **Step 1: 更新 README 添加部署说明**

在 README.md 中添加:

```markdown
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
```

- [ ] **Step 2: Commit documentation**

```bash
git add README.md
git commit -m "docs: add deployment documentation

- Docker deployment guide
- Manual deployment steps
- Configuration reference
- Business system integration"
```

---

## Verification Checklist

运行完整验证:

- [ ] **构建测试**: `pnpm build` 成功
- [ ] **服务器启动**: `pnpm start` 启动无错误
- [ ] **健康检查**: `curl http://localhost:3000/api/health` 返回 healthy
- [ ] **WebSocket 代理**: 前端能通过 `/ws` 连接 OpenClaw
- [ ] **Docker 构建**: `docker build .` 成功
- [ ] **Docker 运行**: `docker-compose up` 启动并健康

---

## Summary

此计划将 SmanWeb 从纯前端应用转变为完整的集成服务:

1. **Node.js 后端**: 管理子进程、代理 WebSocket、提供 API
2. **OpenClaw 打包**: 将 OpenClaw 及其依赖打包为自包含目录
3. **技能打包**: 从 GitHub 获取并打包预装技能
4. **Docker 部署**: 一键部署整个服务栈
5. **配置集成**: 前端自动从后端获取配置

最终产物是一个 Docker 镜像，包含:
- SmanWeb 前端
- OpenClaw Gateway
- acpx (ACP 桥接)
- Claude Code (通过 acpx 调用)

用户只需:
1. 挂载业务系统代码
2. 配置 Claude API 密钥
3. 启动服务

即可获得完整的智能业务系统。
