# SmanWeb - 智能底座 Web 前端实现计划

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 构建一个 Web 前端，连接远程 OpenClaw Gateway，为用户提供 AI 智能体交互界面，支持咨询、设计、编码、测试等插拔式能力。

**Architecture:** 采用前后端分离架构。前端为纯 React SPA，通过 WebSocket 连接远程 OpenClaw Gateway。后端仅需一个轻量级 BFF 层处理认证和代理。复用 ClawX 的 UI 组件和状态管理逻辑。

**Tech Stack:**
- 前端: React 19 + TypeScript + Vite + Tailwind CSS + shadcn/ui + Zustand
- 后端: Node.js (Express/Fastify) 或直接连接 Gateway
- 通信: WebSocket (OpenClaw Gateway 协议) + HTTP API
- 认证: JWT + Gateway Token

---

## 项目结构

```
~/projects/smanweb/
├── src/
│   ├── app/                    # 应用入口和路由
│   │   ├── App.tsx
│   │   ├── main.tsx
│   │   └── routes.tsx
│   ├── components/             # UI 组件 (复用 ClawX)
│   │   ├── ui/                 # shadcn/ui 基础组件
│   │   ├── common/             # 通用组件
│   │   └── layout/             # 布局组件
│   ├── features/               # 功能模块
│   │   ├── chat/               # 聊天功能
│   │   ├── skills/             # 技能管理
│   │   ├── agents/             # Agent 管理
│   │   ├── cron/               # 定时任务
│   │   └── settings/           # 设置页面
│   ├── lib/                    # 核心库
│   │   ├── gateway-client.ts   # WebSocket 客户端 (改造自 ClawX)
│   │   ├── api-client.ts       # HTTP API 客户端
│   │   └── auth.ts             # 认证逻辑
│   ├── stores/                 # Zustand 状态管理
│   │   ├── chat.ts
│   │   ├── gateway.ts
│   │   ├── skills.ts
│   │   └── settings.ts
│   ├── types/                  # TypeScript 类型定义
│   └── i18n/                   # 国际化
├── server/                     # 可选 BFF 层
│   └── index.ts                # 认证代理服务器
├── public/
├── docs/
└── package.json
```

---

## Chunk 1: 项目初始化与基础架构

### Task 1: 创建项目骨架

**Files:**
- Create: `package.json`
- Create: `tsconfig.json`
- Create: `vite.config.ts`
- Create: `tailwind.config.js`
- Create: `postcss.config.js`
- Create: `index.html`
- Create: `src/app/main.tsx`
- Create: `src/app/App.tsx`
- Create: `src/index.css`

- [ ] **Step 1: 初始化 Vite React TypeScript 项目**

```bash
cd ~/projects/smanweb
pnpm create vite . --template react-ts
```

- [ ] **Step 2: 安装核心依赖**

```bash
cd ~/projects/smanweb
pnpm add react-router-dom zustand i18next react-i18next lucide-react clsx tailwind-merge class-variance-authority
pnpm add -D tailwindcss postcss autoprefixer @types/node
```

- [ ] **Step 3: 初始化 Tailwind CSS**

```bash
cd ~/projects/smanweb
pnpm dlx tailwindcss init -p
```

- [ ] **Step 4: 配置 tailwind.config.js**

```javascript
/** @type {import('tailwindcss').Config} */
export default {
  darkMode: ["class"],
  content: [
    "./index.html",
    "./src/**/*.{js,ts,jsx,tsx}",
  ],
  theme: {
    extend: {
      colors: {
        border: "hsl(var(--border))",
        input: "hsl(var(--input))",
        ring: "hsl(var(--ring))",
        background: "hsl(var(--background))",
        foreground: "hsl(var(--foreground))",
        primary: {
          DEFAULT: "hsl(var(--primary))",
          foreground: "hsl(var(--primary-foreground))",
        },
        secondary: {
          DEFAULT: "hsl(var(--secondary))",
          foreground: "hsl(var(--secondary-foreground))",
        },
        muted: {
          DEFAULT: "hsl(var(--muted))",
          foreground: "hsl(var(--muted-foreground))",
        },
        accent: {
          DEFAULT: "hsl(var(--accent))",
          foreground: "hsl(var(--accent-foreground))",
        },
        destructive: {
          DEFAULT: "hsl(var(--destructive))",
          foreground: "hsl(var(--destructive-foreground))",
        },
      },
      borderRadius: {
        lg: "var(--radius)",
        md: "calc(var(--radius) - 2px)",
        sm: "calc(var(--radius) - 4px)",
      },
    },
  },
  plugins: [require("tailwindcss-animate")],
}
```

- [ ] **Step 5: 安装 tailwindcss-animate**

```bash
pnpm add -D tailwindcss-animate
```

- [ ] **Step 6: 创建 src/index.css**

```css
@tailwind base;
@tailwind components;
@tailwind utilities;

@layer base {
  :root {
    --background: 0 0% 100%;
    --foreground: 222.2 84% 4.9%;
    --card: 0 0% 100%;
    --card-foreground: 222.2 84% 4.9%;
    --popover: 0 0% 100%;
    --popover-foreground: 222.2 84% 4.9%;
    --primary: 222.2 47.4% 11.2%;
    --primary-foreground: 210 40% 98%;
    --secondary: 210 40% 96.1%;
    --secondary-foreground: 222.2 47.4% 11.2%;
    --muted: 210 40% 96.1%;
    --muted-foreground: 215.4 16.3% 46.9%;
    --accent: 210 40% 96.1%;
    --accent-foreground: 222.2 47.4% 11.2%;
    --destructive: 0 84.2% 60.2%;
    --destructive-foreground: 210 40% 98%;
    --border: 214.3 31.8% 91.4%;
    --input: 214.3 31.8% 91.4%;
    --ring: 222.2 84% 4.9%;
    --radius: 0.5rem;
  }

  .dark {
    --background: 222.2 84% 4.9%;
    --foreground: 210 40% 98%;
    --card: 222.2 84% 4.9%;
    --card-foreground: 210 40% 98%;
    --popover: 222.2 84% 4.9%;
    --popover-foreground: 210 40% 98%;
    --primary: 210 40% 98%;
    --primary-foreground: 222.2 47.4% 11.2%;
    --secondary: 217.2 32.6% 17.5%;
    --secondary-foreground: 210 40% 98%;
    --muted: 217.2 32.6% 17.5%;
    --muted-foreground: 215 20.2% 65.1%;
    --accent: 217.2 32.6% 17.5%;
    --accent-foreground: 210 40% 98%;
    --destructive: 0 62.8% 30.6%;
    --destructive-foreground: 210 40% 98%;
    --border: 217.2 32.6% 17.5%;
    --input: 217.2 32.6% 17.5%;
    --ring: 212.7 26.8% 83.9%;
  }
}

@layer base {
  * {
    @apply border-border;
  }
  body {
    @apply bg-background text-foreground;
  }
}
```

- [ ] **Step 7: 创建基础 App.tsx**

```typescript
// src/app/App.tsx
import { BrowserRouter, Routes, Route } from 'react-router-dom'

export function App() {
  return (
    <BrowserRouter>
      <div className="min-h-screen bg-background">
        <Routes>
          <Route path="/" element={<div className="p-4">SmanWeb - Loading...</div>} />
        </Routes>
      </div>
    </BrowserRouter>
  )
}
```

- [ ] **Step 8: 创建 main.tsx**

```typescript
// src/app/main.tsx
import React from 'react'
import ReactDOM from 'react-dom/client'
import { App } from './App'
import '../index.css'

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
)
```

- [ ] **Step 9: 更新 index.html**

```html
<!DOCTYPE html>
<html lang="en">
  <head>
    <meta charset="UTF-8" />
    <link rel="icon" type="image/svg+xml" href="/vite.svg" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>SmanWeb - AI Agent Platform</title>
  </head>
  <body>
    <div id="root"></div>
    <script type="module" src="/src/app/main.tsx"></script>
  </body>
</html>
```

- [ ] **Step 10: 验证项目启动**

```bash
cd ~/projects/smanweb
pnpm dev
```

Expected: 浏览器打开 http://localhost:5173 显示 "SmanWeb - Loading..."

- [ ] **Step 11: 提交初始化代码**

```bash
cd ~/projects/smanweb
git init
git add .
git commit -m "chore: initialize smanweb project with Vite + React + TypeScript + Tailwind"
```

---

### Task 2: 复用 ClawX UI 基础组件

**Files:**
- Create: `src/lib/utils.ts`
- Create: `src/components/ui/button.tsx`
- Create: `src/components/ui/input.tsx`
- Create: `src/components/ui/card.tsx`
- Create: `src/components/ui/badge.tsx`
- Create: `src/components/ui/separator.tsx`
- Create: `src/components/ui/switch.tsx`
- Create: `src/components/ui/textarea.tsx`
- Create: `src/components/ui/tooltip.tsx`

- [ ] **Step 1: 复制工具函数**

```bash
cp ~/projects/ClawX/src/lib/utils.ts ~/projects/smanweb/src/lib/utils.ts
```

- [ ] **Step 2: 安装 shadcn/ui 所需的 Radix 依赖**

```bash
cd ~/projects/smanweb
pnpm add @radix-ui/react-slot @radix-ui/react-tooltip @radix-ui/react-switch @radix-ui/react-separator
```

- [ ] **Step 3: 复制 UI 组件**

```bash
cp ~/projects/ClawX/src/components/ui/button.tsx ~/projects/smanweb/src/components/ui/
cp ~/projects/ClawX/src/components/ui/input.tsx ~/projects/smanweb/src/components/ui/
cp ~/projects/ClawX/src/components/ui/card.tsx ~/projects/smanweb/src/components/ui/
cp ~/projects/ClawX/src/components/ui/badge.tsx ~/projects/smanweb/src/components/ui/
cp ~/projects/ClawX/src/components/ui/separator.tsx ~/projects/smanweb/src/components/ui/
cp ~/projects/ClawX/src/components/ui/switch.tsx ~/projects/smanweb/src/components/ui/
cp ~/projects/ClawX/src/components/ui/textarea.tsx ~/projects/smanweb/src/components/ui/
cp ~/projects/ClawX/src/components/ui/tooltip.tsx ~/projects/smanweb/src/components/ui/
```

- [ ] **Step 4: 修复组件中的导入路径**

需要修改各组件文件，将 `@/lib/utils` 改为相对路径或配置 tsconfig paths。

- [ ] **Step 5: 配置 tsconfig 路径别名**

更新 `tsconfig.json`:

```json
{
  "compilerOptions": {
    "target": "ES2020",
    "useDefineForClassFields": true,
    "lib": ["ES2020", "DOM", "DOM.Iterable"],
    "module": "ESNext",
    "skipLibCheck": true,
    "moduleResolution": "bundler",
    "allowImportingTsExtensions": true,
    "resolveJsonModule": true,
    "isolatedModules": true,
    "noEmit": true,
    "jsx": "react-jsx",
    "strict": true,
    "noUnusedLocals": true,
    "noUnusedParameters": true,
    "noFallthroughCasesInSwitch": true,
    "baseUrl": ".",
    "paths": {
      "@/*": ["./src/*"]
    }
  },
  "include": ["src"],
  "references": [{ "path": "./tsconfig.node.json" }]
}
```

- [ ] **Step 6: 更新 vite.config.ts 支持路径别名**

```typescript
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import path from 'path'

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
})
```

- [ ] **Step 7: 验证组件编译**

```bash
cd ~/projects/smanweb
pnpm build
```

Expected: 构建成功无错误

- [ ] **Step 8: 提交 UI 组件**

```bash
cd ~/projects/smanweb
git add .
git commit -m "feat: add shadcn/ui base components from ClawX"
```

---

### Task 3: 复用布局组件

**Files:**
- Create: `src/components/layout/MainLayout.tsx`
- Create: `src/components/layout/Sidebar.tsx`
- Create: `src/components/layout/TitleBar.tsx`

- [ ] **Step 1: 复制布局组件**

```bash
cp ~/projects/ClawX/src/components/layout/MainLayout.tsx ~/projects/smanweb/src/components/layout/
cp ~/projects/ClawX/src/components/layout/Sidebar.tsx ~/projects/smanweb/src/components/layout/
```

注意：TitleBar.tsx 是 Electron 特有的，Web 版本不需要。

- [ ] **Step 2: 修改 MainLayout.tsx 适配 Web**

移除 Electron 相关代码，简化为纯 Web 布局：

```typescript
// src/components/layout/MainLayout.tsx
import { Outlet } from 'react-router-dom'
import { Sidebar } from './Sidebar'
import { cn } from '@/lib/utils'

export function MainLayout() {
  return (
    <div className="flex h-screen overflow-hidden bg-background">
      <Sidebar />
      <main className="flex-1 overflow-auto">
        <Outlet />
      </main>
    </div>
  )
}
```

- [ ] **Step 3: 修改 Sidebar.tsx 适配 Web**

移除 Electron IPC 调用，改用 React Router 导航。

- [ ] **Step 4: 提交布局组件**

```bash
cd ~/projects/smanweb
git add .
git commit -m "feat: add layout components adapted for web"
```

---

## Chunk 2: Gateway 客户端与认证

### Task 4: 创建 Gateway 客户端

**Files:**
- Create: `src/lib/gateway-client.ts`
- Create: `src/types/gateway.ts`
- Create: `src/stores/gateway.ts`

- [ ] **Step 1: 创建 Gateway 类型定义**

```typescript
// src/types/gateway.ts

export interface GatewayConfig {
  url: string
  token?: string
  autoReconnect?: boolean
  reconnectInterval?: number
}

export interface GatewayHello {
  version: string
  features?: {
    methods?: string[]
  }
}

export interface GatewayStatus {
  state: 'connecting' | 'connected' | 'disconnected' | 'error'
  error?: string
  lastConnected?: number
}

export type GatewayEventHandler = (payload: unknown) => void

export interface GatewayRequest {
  type: 'req'
  id: string
  method: string
  params?: unknown
}

export interface GatewayResponse {
  type: 'res'
  id: string
  ok: boolean
  payload?: unknown
  error?: { code: string; message: string }
}

export interface GatewayEvent {
  type: 'event'
  event: string
  payload: unknown
  seq: number
}
```

- [ ] **Step 2: 创建 Gateway 客户端 (改造自 ClawX)**

```typescript
// src/lib/gateway-client.ts
import type { GatewayConfig, GatewayHello, GatewayEventHandler, GatewayStatus } from '@/types/gateway'

type PendingRequest = {
  resolve: (value: unknown) => void
  reject: (error: Error) => void
  timeout: ReturnType<typeof setTimeout>
}

class GatewayBrowserClient {
  private ws: WebSocket | null = null
  private config: GatewayConfig
  private connectPromise: Promise<void> | null = null
  private pendingRequests = new Map<string, PendingRequest>()
  private eventHandlers = new Map<string, Set<GatewayEventHandler>>()
  private status: GatewayStatus = { state: 'disconnected' }
  private statusListeners: Set<(status: GatewayStatus) => void> = new Set()
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null

  constructor(config: GatewayConfig) {
    this.config = {
      autoReconnect: true,
      reconnectInterval: 3000,
      ...config,
    }
  }

  getStatus(): GatewayStatus {
    return this.status
  }

  onStatusChange(listener: (status: GatewayStatus) => void): () => void {
    this.statusListeners.add(listener)
    return () => this.statusListeners.delete(listener)
  }

  private setStatus(status: GatewayStatus): void {
    this.status = status
    this.statusListeners.forEach((l) => l(status))
  }

  async connect(): Promise<void> {
    if (this.ws?.readyState === WebSocket.OPEN) {
      return
    }
    if (this.connectPromise) {
      await this.connectPromise
      return
    }

    this.connectPromise = this.openSocket()
    try {
      await this.connectPromise
    } finally {
      this.connectPromise = null
    }
  }

  disconnect(): void {
    this.clearReconnectTimer()
    if (this.ws) {
      this.ws.close()
      this.ws = null
    }
    for (const [, request] of this.pendingRequests) {
      clearTimeout(request.timeout)
      request.reject(new Error('Gateway connection closed'))
    }
    this.pendingRequests.clear()
    this.setStatus({ state: 'disconnected' })
  }

  async rpc<T>(method: string, params?: unknown, timeoutMs = 60000): Promise<T> {
    await this.connect()
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) {
      throw new Error('Gateway socket is not connected')
    }

    const id = `${Date.now()}-${Math.random().toString(16).slice(2)}`
    const request = {
      type: 'req' as const,
      id,
      method,
      params,
    }

    return await new Promise<T>((resolve, reject) => {
      const timeout = setTimeout(() => {
        this.pendingRequests.delete(id)
        reject(new Error(`Gateway RPC timeout: ${method}`))
      }, timeoutMs)

      this.pendingRequests.set(id, {
        resolve: resolve as (value: unknown) => void,
        reject,
        timeout,
      })
      this.ws!.send(JSON.stringify(request))
    })
  }

  on(eventName: string, handler: GatewayEventHandler): () => void {
    const handlers = this.eventHandlers.get(eventName) || new Set<GatewayEventHandler>()
    handlers.add(handler)
    this.eventHandlers.set(eventName, handlers)

    return () => {
      const current = this.eventHandlers.get(eventName)
      current?.delete(handler)
      if (current && current.size === 0) {
        this.eventHandlers.delete(eventName)
      }
    }
  }

  private clearReconnectTimer(): void {
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer)
      this.reconnectTimer = null
    }
  }

  private scheduleReconnect(): void {
    if (!this.config.autoReconnect) return
    this.clearReconnectTimer()
    this.reconnectTimer = setTimeout(() => {
      this.connect().catch(() => {
        // Reconnect failed, will retry again
      })
    }, this.config.reconnectInterval)
  }

  private async openSocket(): Promise<void> {
    this.setStatus({ state: 'connecting' })

    await new Promise<void>((resolve, reject) => {
      const ws = new WebSocket(this.config.url)
      let resolved = false
      let challengeTimer: ReturnType<typeof setTimeout> | null = null

      const cleanup = () => {
        if (challengeTimer) {
          clearTimeout(challengeTimer)
          challengeTimer = null
        }
      }

      const resolveOnce = () => {
        if (!resolved) {
          resolved = true
          cleanup()
          resolve()
        }
      }

      const rejectOnce = (error: Error) => {
        if (!resolved) {
          resolved = true
          cleanup()
          this.setStatus({ state: 'error', error: error.message })
          reject(error)
        }
      }

      ws.onopen = () => {
        challengeTimer = setTimeout(() => {
          rejectOnce(new Error('Gateway connect challenge timeout'))
          ws.close()
        }, 10000)
      }

      ws.onmessage = (event) => {
        try {
          const message = JSON.parse(String(event.data)) as Record<string, unknown>

          // Handle connect.challenge
          if (message.type === 'event' && message.event === 'connect.challenge') {
            const nonce = (message.payload as { nonce?: string } | undefined)?.nonce
            if (!nonce) {
              rejectOnce(new Error('Gateway connect.challenge missing nonce'))
              return
            }

            const connectFrame = {
              type: 'req',
              id: `connect-${Date.now()}`,
              method: 'connect',
              params: {
                minProtocol: 3,
                maxProtocol: 3,
                client: {
                  id: 'smanweb-client',
                  displayName: 'SmanWeb',
                  version: '0.1.0',
                  platform: navigator.platform,
                  mode: 'ui',
                },
                auth: this.config.token ? { token: this.config.token } : {},
                caps: [],
                role: 'operator',
                scopes: ['operator.admin'],
              },
            }
            ws.send(JSON.stringify(connectFrame))
            return
          }

          // Handle connect response
          if (message.type === 'res' && typeof message.id === 'string') {
            if (String(message.id).startsWith('connect-')) {
              if (message.ok === false) {
                rejectOnce(new Error(String((message.error as { message?: string })?.message || 'Connect failed')))
                return
              }
              this.ws = ws
              this.setStatus({ state: 'connected', lastConnected: Date.now() })
              resolveOnce()
              return
            }

            // Handle RPC responses
            const pending = this.pendingRequests.get(message.id)
            if (pending) {
              clearTimeout(pending.timeout)
              this.pendingRequests.delete(message.id)
              if (message.ok === false || message.error) {
                const errorMessage =
                  typeof message.error === 'object' && message.error !== null
                    ? String((message.error as { message?: string }).message || JSON.stringify(message.error))
                    : String(message.error || 'Gateway request failed')
                pending.reject(new Error(errorMessage))
              } else {
                pending.resolve(message.payload)
              }
            }
            return
          }

          // Handle events
          if (message.type === 'event' && typeof message.event === 'string') {
            this.emitEvent(message.event, message.payload)
            return
          }

          // Handle server-initiated requests
          if (typeof message.method === 'string') {
            this.emitEvent(message.method, message.params)
          }
        } catch (error) {
          if (!resolved) {
            rejectOnce(error instanceof Error ? error : new Error(String(error)))
          }
        }
      }

      ws.onerror = () => {
        if (!resolved) {
          rejectOnce(new Error('Gateway WebSocket error'))
        }
      }

      ws.onclose = (event) => {
        this.ws = null
        if (!resolved) {
          rejectOnce(new Error(`Gateway WebSocket closed: ${event.code} ${event.reason}`))
          return
        }
        // Clean up pending requests
        for (const [, request] of this.pendingRequests) {
          clearTimeout(request.timeout)
          request.reject(new Error('Gateway connection closed'))
        }
        this.pendingRequests.clear()
        this.emitEvent('__close__', { code: event.code, reason: event.reason })
        this.setStatus({ state: 'disconnected' })
        this.scheduleReconnect()
      }
    })
  }

  private emitEvent(eventName: string, payload: unknown): void {
    const handlers = this.eventHandlers.get(eventName)
    if (!handlers) return
    for (const handler of handlers) {
      try {
        handler(payload)
      } catch {
        // ignore handler failures
      }
    }
  }
}

// Singleton instance
let gatewayClientInstance: GatewayBrowserClient | null = null

export function createGatewayClient(config: GatewayConfig): GatewayBrowserClient {
  gatewayClientInstance = new GatewayBrowserClient(config)
  return gatewayClientInstance
}

export function getGatewayClient(): GatewayBrowserClient {
  if (!gatewayClientInstance) {
    throw new Error('Gateway client not initialized. Call createGatewayClient first.')
  }
  return gatewayClientInstance
}

export { GatewayBrowserClient }
```

- [ ] **Step 3: 创建 Gateway Store**

```typescript
// src/stores/gateway.ts
import { create } from 'zustand'
import { persist } from 'zustand/middleware'
import type { GatewayStatus } from '@/types/gateway'

interface GatewayState {
  // Config
  url: string
  token: string
  setConfig: (url: string, token: string) => void

  // Status
  status: GatewayStatus
  setStatus: (status: GatewayStatus) => void

  // Connection
  connected: boolean
  setConnected: (connected: boolean) => void
}

export const useGatewayStore = create<GatewayState>()(
  persist(
    (set) => ({
      url: '',
      token: '',
      setConfig: (url, token) => set({ url, token }),

      status: { state: 'disconnected' },
      setStatus: (status) => set({ status }),

      connected: false,
      setConnected: (connected) => set({ connected }),
    }),
    {
      name: 'smanweb-gateway',
      partialize: (state) => ({ url: state.url, token: state.token }),
    }
  )
)
```

- [ ] **Step 4: 验证 Gateway 客户端编译**

```bash
cd ~/projects/smanweb
pnpm build
```

Expected: 构建成功

- [ ] **Step 5: 提交 Gateway 客户端**

```bash
cd ~/projects/smanweb
git add .
git commit -m "feat: add gateway client with WebSocket protocol support"
```

---

### Task 5: 创建设置页面和连接管理

**Files:**
- Create: `src/features/settings/ConnectionSettings.tsx`
- Create: `src/features/settings/index.tsx`
- Create: `src/hooks/use-gateway-connection.ts`

- [ ] **Step 1: 创建连接 Hook**

```typescript
// src/hooks/use-gateway-connection.ts
import { useEffect, useCallback, useRef } from 'react'
import { useGatewayStore } from '@/stores/gateway'
import { createGatewayClient, getGatewayClient } from '@/lib/gateway-client'

export function useGatewayConnection() {
  const { url, token, status, setStatus, setConnected } = useGatewayStore()
  const clientRef = useRef<ReturnType<typeof createGatewayClient> | null>(null)

  const connect = useCallback(async () => {
    if (!url) {
      setStatus({ state: 'error', error: 'Gateway URL is required' })
      return
    }

    try {
      if (!clientRef.current) {
        clientRef.current = createGatewayClient({ url, token })
      }
      await clientRef.current.connect()
      setConnected(true)
    } catch (error) {
      setStatus({ state: 'error', error: error instanceof Error ? error.message : 'Connection failed' })
      setConnected(false)
    }
  }, [url, token, setStatus, setConnected])

  const disconnect = useCallback(() => {
    clientRef.current?.disconnect()
    clientRef.current = null
    setConnected(false)
  }, [setConnected])

  // Subscribe to status changes
  useEffect(() => {
    if (!clientRef.current) return

    const unsubscribe = clientRef.current.onStatusChange((newStatus) => {
      setStatus(newStatus)
      setConnected(newStatus.state === 'connected')
    })

    return unsubscribe
  }, [setStatus, setConnected])

  return {
    status,
    connect,
    disconnect,
    client: clientRef.current,
  }
}
```

- [ ] **Step 2: 创建连接设置组件**

```typescript
// src/features/settings/ConnectionSettings.tsx
import { useState } from 'react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { useGatewayStore } from '@/stores/gateway'
import { useGatewayConnection } from '@/hooks/use-gateway-connection'
import { StatusBadge } from '@/components/common/StatusBadge'
import { Save, Plug, PlugZap } from 'lucide-react'

export function ConnectionSettings() {
  const { url: savedUrl, token: savedToken, setConfig } = useGatewayStore()
  const { status, connect, disconnect } = useGatewayConnection()

  const [url, setUrl] = useState(savedUrl)
  const [token, setToken] = useState(savedToken)

  const handleSave = () => {
    setConfig(url, token)
  }

  const handleConnect = async () => {
    handleSave()
    await connect()
  }

  const handleDisconnect = () => {
    disconnect()
  }

  const isConnected = status.state === 'connected'
  const isConnecting = status.state === 'connecting'

  return (
    <div className="space-y-6">
      <div className="space-y-4">
        <div className="space-y-2">
          <Label htmlFor="gateway-url">Gateway URL</Label>
          <Input
            id="gateway-url"
            type="text"
            placeholder="ws://your-server:18789 or wss://your-server/gateway"
            value={url}
            onChange={(e) => setUrl(e.target.value)}
            disabled={isConnected}
          />
        </div>

        <div className="space-y-2">
          <Label htmlFor="gateway-token">Token (Optional)</Label>
          <Input
            id="gateway-token"
            type="password"
            placeholder="Gateway authentication token"
            value={token}
            onChange={(e) => setToken(e.target.value)}
            disabled={isConnected}
          />
        </div>
      </div>

      <div className="flex items-center gap-4">
        <Button onClick={handleSave} variant="outline" disabled={!url || isConnected}>
          <Save className="mr-2 h-4 w-4" />
          Save
        </Button>

        {isConnected ? (
          <Button onClick={handleDisconnect} variant="destructive">
            <PlugZap className="mr-2 h-4 w-4" />
            Disconnect
          </Button>
        ) : (
          <Button onClick={handleConnect} disabled={!url || isConnecting}>
            <Plug className="mr-2 h-4 w-4" />
            {isConnecting ? 'Connecting...' : 'Connect'}
          </Button>
        )}

        <StatusBadge status={status.state} />
      </div>

      {status.error && (
        <div className="rounded-md bg-destructive/10 p-4 text-sm text-destructive">
          {status.error}
        </div>
      )}
    </div>
  )
}
```

- [ ] **Step 3: 创建设置页面**

```typescript
// src/features/settings/index.tsx
import { ConnectionSettings } from './ConnectionSettings'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Separator } from '@/components/ui/separator'

export function SettingsPage() {
  return (
    <div className="container mx-auto py-6 space-y-6">
      <h1 className="text-3xl font-bold">Settings</h1>

      <Card>
        <CardHeader>
          <CardTitle>Gateway Connection</CardTitle>
          <CardDescription>
            Configure the connection to your OpenClaw Gateway server
          </CardDescription>
        </CardHeader>
        <CardContent>
          <ConnectionSettings />
        </CardContent>
      </Card>

      <Separator />

      {/* More settings sections will be added here */}
    </div>
  )
}
```

- [ ] **Step 4: 创建 StatusBadge 组件**

```bash
cp ~/projects/ClawX/src/components/common/StatusBadge.tsx ~/projects/smanweb/src/components/common/
cp ~/projects/ClawX/src/components/common/LoadingSpinner.tsx ~/projects/smanweb/src/components/common/
```

- [ ] **Step 5: 验证设置页面**

```bash
cd ~/projects/smanweb
pnpm dev
```

- [ ] **Step 6: 提交设置页面**

```bash
cd ~/projects/smanweb
git add .
git commit -m "feat: add connection settings page with gateway configuration"
```

---

## Chunk 3: 聊天功能核心

### Task 6: 创建聊天状态管理

**Files:**
- Create: `src/types/chat.ts`
- Create: `src/stores/chat.ts`
- Create: `src/stores/chat/session-actions.ts`
- Create: `src/stores/chat/message-actions.ts`

- [ ] **Step 1: 创建聊天类型定义**

从 ClawX 复制并简化：

```bash
cp ~/projects/ClawX/src/stores/chat/types.ts ~/projects/smanweb/src/types/chat.ts
```

- [ ] **Step 2: 创建简化版聊天 Store**

```typescript
// src/stores/chat.ts
import { create } from 'zustand'
import type { RawMessage, ChatSession, ToolStatus } from '@/types/chat'
import { getGatewayClient } from '@/lib/gateway-client'

interface ChatState {
  // Messages
  messages: RawMessage[]
  loading: boolean
  error: string | null

  // Streaming
  sending: boolean
  activeRunId: string | null
  streamingMessage: unknown | null
  streamingTools: ToolStatus[]

  // Sessions
  sessions: ChatSession[]
  currentSessionKey: string
  currentAgentId: string

  // Thinking
  showThinking: boolean

  // Actions
  setCurrentSession: (key: string) => void
  sendMessage: (content: string, images?: string[]) => Promise<void>
  abortRun: () => Promise<void>
  fetchSessions: () => Promise<void>
  fetchHistory: (sessionKey: string) => Promise<void>
  clearError: () => void
  toggleThinking: () => void
}

export const useChatStore = create<ChatState>((set, get) => ({
  // Initial state
  messages: [],
  loading: false,
  error: null,
  sending: false,
  activeRunId: null,
  streamingMessage: null,
  streamingTools: [],
  sessions: [],
  currentSessionKey: 'main',
  currentAgentId: 'default',
  showThinking: false,

  // Actions
  setCurrentSession: (key) => {
    set({ currentSessionKey: key, messages: [] })
    get().fetchHistory(key)
  },

  sendMessage: async (content, images = []) => {
    const client = getGatewayClient()
    const { currentSessionKey, currentAgentId } = get()

    set({ sending: true, error: null })

    try {
      // Subscribe to events
      const unsubEvent = client.on('chat.event', (payload) => {
        const event = payload as { runId?: string; delta?: unknown; tool?: ToolStatus }
        if (event.delta) {
          set((state) => ({
            streamingMessage: event.delta,
            activeRunId: event.runId || state.activeRunId,
          }))
        }
        if (event.tool) {
          set((state) => ({
            streamingTools: [...state.streamingTools.filter(t => t.id !== event.tool?.id), event.tool!],
          }))
        }
      })

      const unsubDone = client.on('chat.done', (payload) => {
        const result = payload as { runId?: string; message?: RawMessage }
        if (result.message) {
          set((state) => ({
            messages: [...state.messages, result.message!],
            streamingMessage: null,
            streamingTools: [],
            sending: false,
            activeRunId: null,
          }))
        }
        unsubEvent()
        unsubDone()
      })

      await client.rpc('chat.send', {
        message: { role: 'user', content },
        images,
        sessionKey: currentSessionKey,
        agentId: currentAgentId,
      })
    } catch (error) {
      set({
        sending: false,
        error: error instanceof Error ? error.message : 'Failed to send message',
      })
    }
  },

  abortRun: async () => {
    const client = getGatewayClient()
    const { activeRunId } = get()
    if (activeRunId) {
      await client.rpc('chat.abort', { runId: activeRunId })
    }
    set({ sending: false, activeRunId: null, streamingMessage: null })
  },

  fetchSessions: async () => {
    const client = getGatewayClient()
    try {
      const result = await client.rpc<{ sessions: ChatSession[] }>('sessions.list')
      set({ sessions: result.sessions || [] })
    } catch (error) {
      console.error('Failed to fetch sessions:', error)
    }
  },

  fetchHistory: async (sessionKey) => {
    const client = getGatewayClient()
    set({ loading: true })
    try {
      const result = await client.rpc<{ messages: RawMessage[] }>('chat.history', { sessionKey })
      set({ messages: result.messages || [], loading: false })
    } catch (error) {
      set({
        loading: false,
        error: error instanceof Error ? error.message : 'Failed to load history',
      })
    }
  },

  clearError: () => set({ error: null }),

  toggleThinking: () => set((state) => ({ showThinking: !state.showThinking })),
}))
```

- [ ] **Step 3: 验证聊天 Store 编译**

```bash
cd ~/projects/smanweb
pnpm build
```

- [ ] **Step 4: 提交聊天 Store**

```bash
cd ~/projects/smanweb
git add .
git commit -m "feat: add chat store with session and message management"
```

---

### Task 7: 创建聊天 UI 组件

**Files:**
- Create: `src/features/chat/ChatMessage.tsx`
- Create: `src/features/chat/ChatInput.tsx`
- Create: `src/features/chat/ChatToolbar.tsx`
- Create: `src/features/chat/message-utils.ts`
- Create: `src/features/chat/index.tsx`

- [ ] **Step 1: 复制消息工具函数**

```bash
cp ~/projects/ClawX/src/pages/Chat/message-utils.ts ~/projects/smanweb/src/features/chat/
```

- [ ] **Step 2: 安装 Markdown 渲染依赖**

```bash
cd ~/projects/smanweb
pnpm add react-markdown remark-gfm
```

- [ ] **Step 3: 创建 ChatMessage 组件 (简化版)**

```typescript
// src/features/chat/ChatMessage.tsx
import { cn } from '@/lib/utils'
import { User, Bot } from 'lucide-react'
import type { RawMessage } from '@/types/chat'
import { extractText, extractThinking, extractToolUse } from './message-utils'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'

interface ChatMessageProps {
  message: RawMessage
  showThinking?: boolean
}

export function ChatMessage({ message, showThinking = false }: ChatMessageProps) {
  const text = extractText(message)
  const thinking = extractThinking(message)
  const tools = extractToolUse(message)
  const isUser = message.role === 'user'

  return (
    <div
      className={cn(
        'flex gap-4 px-4 py-3',
        isUser ? 'bg-muted/50' : 'bg-background'
      )}
    >
      <div className={cn(
        'flex h-8 w-8 shrink-0 items-center justify-center rounded-full',
        isUser ? 'bg-primary text-primary-foreground' : 'bg-secondary'
      )}>
        {isUser ? <User className="h-4 w-4" /> : <Bot className="h-4 w-4" />}
      </div>

      <div className="flex-1 space-y-2 overflow-hidden">
        {showThinking && thinking && (
          <div className="rounded-md bg-muted p-3 text-sm text-muted-foreground italic">
            {thinking}
          </div>
        )}

        {tools.length > 0 && (
          <div className="space-y-1">
            {tools.map((tool, i) => (
              <div key={i} className="text-xs text-muted-foreground">
                🔧 {tool.name || `tool-${i}`}
              </div>
            ))}
          </div>
        )}

        <div className="prose prose-sm dark:prose-invert max-w-none">
          <ReactMarkdown remarkPlugins={[remarkGfm]}>
            {text}
          </ReactMarkdown>
        </div>
      </div>
    </div>
  )
}
```

- [ ] **Step 4: 创建 ChatInput 组件**

```typescript
// src/features/chat/ChatInput.tsx
import { useState, useRef, KeyboardEvent } from 'react'
import { Button } from '@/components/ui/button'
import { Textarea } from '@/components/ui/textarea'
import { Send, Square, Paperclip, Image as ImageIcon } from 'lucide-react'
import { useChatStore } from '@/stores/chat'

export function ChatInput() {
  const [input, setInput] = useState('')
  const textareaRef = useRef<HTMLTextAreaElement>(null)
  const { sending, sendMessage, abortRun } = useChatStore()

  const handleSubmit = async () => {
    const trimmed = input.trim()
    if (!trimmed || sending) return

    setInput('')
    await sendMessage(trimmed)
  }

  const handleKeyDown = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      handleSubmit()
    }
  }

  return (
    <div className="flex items-end gap-2 border-t bg-background p-4">
      <div className="flex gap-1">
        <Button variant="ghost" size="icon" disabled={sending}>
          <Paperclip className="h-4 w-4" />
        </Button>
        <Button variant="ghost" size="icon" disabled={sending}>
          <ImageIcon className="h-4 w-4" />
        </Button>
      </div>

      <Textarea
        ref={textareaRef}
        value={input}
        onChange={(e) => setInput(e.target.value)}
        onKeyDown={handleKeyDown}
        placeholder="Type a message..."
        disabled={sending}
        className="min-h-[60px] flex-1 resize-none"
        rows={1}
      />

      {sending ? (
        <Button variant="destructive" size="icon" onClick={abortRun}>
          <Square className="h-4 w-4" />
        </Button>
      ) : (
        <Button size="icon" onClick={handleSubmit} disabled={!input.trim()}>
          <Send className="h-4 w-4" />
        </Button>
      )}
    </div>
  )
}
```

- [ ] **Step 5: 创建 ChatToolbar 组件**

```typescript
// src/features/chat/ChatToolbar.tsx
import { Button } from '@/components/ui/button'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { useChatStore } from '@/stores/chat'
import { useGatewayStore } from '@/stores/gateway'
import { Brain, RefreshCw, Plus } from 'lucide-react'

export function ChatToolbar() {
  const { sessions, currentSessionKey, setCurrentSession, fetchSessions, showThinking, toggleThinking } = useChatStore()
  const { connected } = useGatewayStore()

  const handleNewSession = () => {
    const newKey = `session-${Date.now()}`
    setCurrentSession(newKey)
  }

  const handleRefresh = () => {
    if (connected) {
      fetchSessions()
    }
  }

  return (
    <div className="flex items-center gap-2">
      <Button variant="outline" size="sm" onClick={handleNewSession}>
        <Plus className="mr-1 h-4 w-4" />
        New Chat
      </Button>

      <Select value={currentSessionKey} onValueChange={setCurrentSession}>
        <SelectTrigger className="w-[200px]">
          <SelectValue placeholder="Select session" />
        </SelectTrigger>
        <SelectContent>
          {sessions.map((session) => (
            <SelectItem key={session.key} value={session.key}>
              {session.label || session.displayName || session.key}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>

      <Button variant="ghost" size="icon" onClick={handleRefresh} disabled={!connected}>
        <RefreshCw className="h-4 w-4" />
      </Button>

      <Button
        variant={showThinking ? 'secondary' : 'ghost'}
        size="icon"
        onClick={toggleThinking}
        title="Toggle thinking display"
      >
        <Brain className="h-4 w-4" />
      </Button>
    </div>
  )
}
```

- [ ] **Step 6: 创建 Chat 页面**

```typescript
// src/features/chat/index.tsx
import { useEffect, useRef } from 'react'
import { useChatStore } from '@/stores/chat'
import { useGatewayStore } from '@/stores/gateway'
import { ChatMessage } from './ChatMessage'
import { ChatInput } from './ChatInput'
import { ChatToolbar } from './ChatToolbar'
import { LoadingSpinner } from '@/components/common/LoadingSpinner'
import { AlertCircle, MessageSquare } from 'lucide-react'

export function ChatPage() {
  const { connected } = useGatewayStore()
  const {
    messages,
    loading,
    error,
    sending,
    streamingMessage,
    showThinking,
    currentSessionKey,
    fetchHistory,
    clearError,
  } = useChatStore()

  const scrollRef = useRef<HTMLDivElement>(null)

  // Load history when connected or session changes
  useEffect(() => {
    if (connected && currentSessionKey) {
      fetchHistory(currentSessionKey)
    }
  }, [connected, currentSessionKey, fetchHistory])

  // Auto-scroll to bottom
  useEffect(() => {
    if (scrollRef.current) {
      scrollRef.current.scrollTop = scrollRef.current.scrollHeight
    }
  }, [messages, streamingMessage])

  if (!connected) {
    return (
      <div className="flex h-full items-center justify-center">
        <div className="text-center text-muted-foreground">
          <MessageSquare className="mx-auto h-12 w-12 mb-4 opacity-50" />
          <p>Please connect to a Gateway to start chatting</p>
        </div>
      </div>
    )
  }

  return (
    <div className="flex h-full flex-col">
      {/* Toolbar */}
      <div className="flex shrink-0 items-center justify-end px-4 py-2">
        <ChatToolbar />
      </div>

      {/* Messages */}
      <div ref={scrollRef} className="flex-1 overflow-y-auto">
        {loading && messages.length === 0 ? (
          <div className="flex h-full items-center justify-center">
            <LoadingSpinner />
          </div>
        ) : messages.length === 0 ? (
          <div className="flex h-full items-center justify-center text-muted-foreground">
            <p>Start a new conversation</p>
          </div>
        ) : (
          <>
            {messages.map((msg, i) => (
              <ChatMessage key={msg.id || i} message={msg} showThinking={showThinking} />
            ))}
            {sending && streamingMessage && (
              <ChatMessage
                message={streamingMessage as any}
                showThinking={showThinking}
              />
            )}
          </>
        )}
      </div>

      {/* Error */}
      {error && (
        <div className="mx-4 mb-2 flex items-center gap-2 rounded-md bg-destructive/10 p-3 text-sm text-destructive">
          <AlertCircle className="h-4 w-4" />
          {error}
          <button onClick={clearError} className="ml-auto opacity-70 hover:opacity-100">
            ✕
          </button>
        </div>
      )}

      {/* Input */}
      <ChatInput />
    </div>
  )
}
```

- [ ] **Step 7: 验证聊天页面**

```bash
cd ~/projects/smanweb
pnpm dev
```

- [ ] **Step 8: 提交聊天功能**

```bash
cd ~/projects/smanweb
git add .
git commit -m "feat: add chat page with message display and input"
```

---

## Chunk 4: 路由与导航

### Task 8: 配置路由和主布局

**Files:**
- Create: `src/app/routes.tsx`
- Update: `src/app/App.tsx`
- Update: `src/components/layout/Sidebar.tsx`

- [ ] **Step 1: 创建路由配置**

```typescript
// src/app/routes.tsx
import { createBrowserRouter } from 'react-router-dom'
import { MainLayout } from '@/components/layout/MainLayout'
import { ChatPage } from '@/features/chat'
import { SettingsPage } from '@/features/settings'

export const router = createBrowserRouter([
  {
    path: '/',
    element: <MainLayout />,
    children: [
      {
        index: true,
        element: <ChatPage />,
      },
      {
        path: 'settings',
        element: <SettingsPage />,
      },
    ],
  },
])
```

- [ ] **Step 2: 更新 App.tsx**

```typescript
// src/app/App.tsx
import { RouterProvider } from 'react-router-dom'
import { router } from './routes'

export function App() {
  return <RouterProvider router={router} />
}
```

- [ ] **Step 3: 更新 Sidebar 导航**

```typescript
// src/components/layout/Sidebar.tsx
import { NavLink } from 'react-router-dom'
import { cn } from '@/lib/utils'
import { MessageSquare, Settings, Bot, Clock, Puzzle } from 'lucide-react'

const navItems = [
  { to: '/', icon: MessageSquare, label: 'Chat' },
  { to: '/agents', icon: Bot, label: 'Agents' },
  { to: '/skills', icon: Puzzle, label: 'Skills' },
  { to: '/cron', icon: Clock, label: 'Cron' },
  { to: '/settings', icon: Settings, label: 'Settings' },
]

export function Sidebar() {
  return (
    <aside className="flex h-full w-16 flex-col border-r bg-muted/50 lg:w-64">
      <div className="flex h-14 items-center justify-center border-b px-4 lg:justify-start">
        <span className="text-lg font-bold">SmanWeb</span>
      </div>

      <nav className="flex-1 space-y-1 p-2">
        {navItems.map((item) => (
          <NavLink
            key={item.to}
            to={item.to}
            className={({ isActive }) =>
              cn(
                'flex items-center gap-3 rounded-md px-3 py-2 text-sm font-medium transition-colors',
                isActive
                  ? 'bg-primary text-primary-foreground'
                  : 'text-muted-foreground hover:bg-muted hover:text-foreground'
              )
            }
          >
            <item.icon className="h-5 w-5" />
            <span className="hidden lg:inline">{item.label}</span>
          </NavLink>
        ))}
      </nav>
    </aside>
  )
}
```

- [ ] **Step 4: 验证路由和导航**

```bash
cd ~/projects/smanweb
pnpm dev
```

Expected: 左侧显示导航栏，点击可以切换页面

- [ ] **Step 5: 提交路由配置**

```bash
cd ~/projects/smanweb
git add .
git commit -m "feat: add routing and navigation sidebar"
```

---

## Chunk 5: 部署配置

### Task 9: 创建 Docker 部署配置

**Files:**
- Create: `Dockerfile`
- Create: `docker-compose.yml`
- Create: `nginx.conf`
- Create: `.dockerignore`

- [ ] **Step 1: 创建 Dockerfile**

```dockerfile
# Build stage
FROM node:20-alpine AS builder
WORKDIR /app
RUN npm install -g pnpm
COPY package.json pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile
COPY . .
RUN pnpm build

# Production stage
FROM nginx:alpine
COPY --from=builder /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
```

- [ ] **Step 2: 创建 nginx.conf**

```nginx
server {
    listen 80;
    server_name localhost;
    root /usr/share/nginx/html;
    index index.html;

    # Gzip compression
    gzip on;
    gzip_types text/plain text/css application/json application/javascript text/xml application/xml application/xml+rss text/javascript;

    # Handle SPA routing
    location / {
        try_files $uri $uri/ /index.html;
    }

    # Cache static assets
    location ~* \.(js|css|png|jpg|jpeg|gif|ico|svg|woff|woff2)$ {
        expires 1y;
        add_header Cache-Control "public, immutable";
    }
}
```

- [ ] **Step 3: 创建 docker-compose.yml**

```yaml
version: '3.8'

services:
  smanweb:
    build: .
    ports:
      - "3000:80"
    environment:
      - NODE_ENV=production
    restart: unless-stopped

  # Optional: OpenClaw Gateway for local development
  openclaw:
    image: openclaw/openclaw:latest
    ports:
      - "18789:18789"
    volumes:
      - openclaw-data:/root/.openclaw
    environment:
      - OPENCLAW_GATEWAY_AUTH_MODE=token
      - OPENCLAW_GATEWAY_AUTH_TOKEN=your-dev-token
    restart: unless-stopped
    profiles:
      - dev

volumes:
  openclaw-data:
```

- [ ] **Step 4: 创建 .dockerignore**

```
node_modules
dist
.git
*.md
.env*
.vscode
```

- [ ] **Step 5: 构建测试**

```bash
cd ~/projects/smanweb
docker build -t smanweb:test .
```

Expected: 构建成功

- [ ] **Step 6: 提交部署配置**

```bash
cd ~/projects/smanweb
git add .
git commit -m "feat: add Docker deployment configuration"
```

---

### Task 10: 创建环境配置和文档

**Files:**
- Create: `.env.example`
- Create: `README.md`
- Create: `docs/deployment.md`

- [ ] **Step 1: 创建 .env.example**

```env
# Gateway Configuration
VITE_GATEWAY_URL=ws://localhost:18789
VITE_GATEWAY_TOKEN=

# Optional: API endpoint for BFF layer
VITE_API_URL=
```

- [ ] **Step 2: 创建 README.md**

```markdown
# SmanWeb

智能底座 Web 前端，连接远程 OpenClaw Gateway。

## 功能

- 🔌 连接远程 OpenClaw Gateway
- 💬 聊天交互界面
- 🤖 Agent 管理
- 🧩 技能管理
- ⏰ 定时任务

## 快速开始

```bash
# 安装依赖
pnpm install

# 开发模式
pnpm dev

# 构建
pnpm build

# Docker 部署
docker-compose up -d
```

## 配置

1. 复制 `.env.example` 为 `.env`
2. 配置 Gateway URL 和 Token
3. 启动应用

## 架构

```
Browser (React SPA)
    │
    │ WebSocket
    ▼
OpenClaw Gateway (Linux Server)
    │
    ├── Skills (Claude Code, etc.)
    └── Business System Integration
```

## License

MIT
```

- [ ] **Step 3: 提交文档**

```bash
cd ~/projects/smanweb
git add .
git commit -m "docs: add README and environment configuration"
```

---

## 执行检查清单

完成所有任务后，验证以下功能：

- [ ] 项目可以正常启动 (`pnpm dev`)
- [ ] 可以配置 Gateway URL 和 Token
- [ ] 可以连接到远程 OpenClaw Gateway
- [ ] 可以发送和接收消息
- [ ] 可以切换会话
- [ ] Docker 构建成功
- [ ] 页面在移动端适配正常

---

## 后续扩展

以下功能可在后续迭代中添加：

1. **Skills 管理页面** - 浏览、安装、配置技能
2. **Agents 管理页面** - 创建和配置多个 Agent
3. **Cron 任务管理** - 定时任务配置
4. **用户认证系统** - 多用户支持
5. **主题定制** - 更多主题选项
6. **国际化** - 多语言支持
7. **文件上传** - 支持图片和文件附件
8. **会话导出** - 导出聊天记录
