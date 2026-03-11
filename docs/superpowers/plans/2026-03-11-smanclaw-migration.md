# SMAN 全量迁移实施计划

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 `smanclaw-desktop` 的前端、桌面壳层和核心编排完整迁移到 `sman` 仓库，建立以 TypeScript 为核心的架构，通过 ACPX 调用 ClaudeCode。

**Architecture:**
- `src/core` - 编排核心与执行器（已存在基础骨架）
- `src/ui` - SvelteKit 前端（从源仓迁移）
- `src/bridge` - Tauri 与前端的 TS 桥接（新建）
- `src-tauri` - Tauri 桌面壳（从源仓迁移）

**Tech Stack:** TypeScript, SvelteKit, Tauri 2.x, Vite, Vitest, TailwindCSS

---

## 文件结构总览

### 需要创建的目录结构

```
sman/
├── src/
│   ├── core/                    # 已存在，保持不变
│   │   ├── orchestrator.ts
│   │   ├── registry.ts
│   │   ├── types.ts
│   │   ├── acpx.ts
│   │   └── claudecode-engine.ts
│   ├── ui/                      # 新建
│   │   ├── routes/
│   │   ├── components/
│   │   │   ├── layout/
│   │   │   ├── chat/
│   │   │   ├── project/
│   │   │   └── task/
│   │   ├── lib/
│   │   │   ├── api/
│   │   │   ├── chat/
│   │   │   ├── stores/
│   │   │   └── types/
│   │   ├── tests/
│   │   ├── app.css
│   │   └── app.html
│   └── bridge/                  # 新建
│       ├── tauri-events.ts
│       ├── tauri-commands.ts
│       └── runtime-gateway.ts
├── src-tauri/                   # 从源仓迁移
│   ├── src/
│   │   ├── commands/
│   │   ├── orchestration/
│   │   └── *.rs
│   └── tauri.conf.json
└── docs/
    └── migration/
```

---

## Chunk 1: 工程骨架搭建 (S1)

### Task 1.1: 安装前端依赖

**Files:**
- Modify: `package.json`
- Modify: `package-lock.json` (自动生成)

- [ ] **Step 1: 更新 package.json 添加 SvelteKit + Tauri 依赖**

```json
{
  "name": "sman",
  "version": "0.1.0",
  "private": true,
  "type": "module",
  "scripts": {
    "dev": "vite dev",
    "build": "vite build",
    "preview": "vite preview",
    "check": "svelte-kit sync && svelte-check --tsconfig ./tsconfig.json",
    "check:watch": "svelte-kit sync && svelte-check --tsconfig ./tsconfig.json --watch",
    "test": "vitest run",
    "test:watch": "vitest",
    "lint": "prettier --plugin prettier-plugin-svelte --check \"src/**/*.{svelte,ts,js,css}\" \"*.{json,js,ts}\"",
    "format": "prettier --plugin prettier-plugin-svelte --write \"src/**/*.{svelte,ts,js,css}\" \"*.{json,js,ts}\"",
    "typecheck": "svelte-kit sync && svelte-check --tsconfig ./tsconfig.json",
    "tauri": "tauri",
    "tauri:dev": "tauri dev",
    "tauri:build": "tauri build"
  },
  "devDependencies": {
    "@sveltejs/adapter-static": "^3.0.8",
    "@sveltejs/kit": "^2.16.0",
    "@sveltejs/vite-plugin-svelte": "^5.1.1",
    "@tailwindcss/vite": "^4.0.0",
    "@tauri-apps/api": "^2.2.0",
    "@tauri-apps/cli": "^2.2.0",
    "@types/node": "^24.0.0",
    "prettier": "^3.4.2",
    "prettier-plugin-svelte": "^3.3.3",
    "svelte": "^5.19.0",
    "svelte-check": "^4.1.4",
    "tailwindcss": "^4.0.0",
    "typescript": "^5.7.3",
    "vite": "^6.0.11",
    "vitest": "^4.0.7"
  },
  "dependencies": {
    "@tauri-apps/plugin-dialog": "^2.6.0",
    "highlight.js": "^11.11.1",
    "markdown-it": "^14.1.0",
    "mermaid": "^11.12.3"
  }
}
```

- [ ] **Step 2: 安装依赖**

Run: `npm install`
Expected: 依赖安装成功，无错误

- [ ] **Step 3: 验证依赖安装**

Run: `npm list --depth=0`
Expected: 显示所有已安装的依赖包

---

### Task 1.2: 创建 SvelteKit 配置文件

**Files:**
- Create: `svelte.config.js`
- Create: `vite.config.ts`
- Modify: `tsconfig.json`

- [ ] **Step 1: 创建 svelte.config.js**

```javascript
import adapter from "@sveltejs/adapter-static";
import { vitePreprocess } from "@sveltejs/vite-plugin-svelte";

/** @type {import('@sveltejs/kit').Config} */
const config = {
  preprocess: vitePreprocess(),
  kit: {
    adapter: adapter({
      pages: "build",
      assets: "build",
      fallback: "index.html",
      precompress: false,
      strict: true,
    }),
    files: {
      routes: "src/ui/routes",
      lib: "src/ui/lib",
      assets: "src/ui/static",
      template: "src/ui/app.html",
    },
  },
};

export default config;
```

- [ ] **Step 2: 创建 vite.config.ts**

```typescript
import { sveltekit } from "@sveltejs/kit/vite";
import tailwindcss from "@tailwindcss/vite";
import { defineConfig } from "vite";

export default defineConfig({
  plugins: [tailwindcss(), sveltekit()],
  test: {
    include: ["src/**/*.{test,spec}.{js,ts}"],
    globals: true,
    environment: "jsdom",
  },
  resolve: {
    alias: {
      $lib: "/src/ui/lib",
      $components: "/src/ui/components",
    },
  },
});
```

- [ ] **Step 3: 更新 tsconfig.json**

```json
{
  "extends": "./.svelte-kit/tsconfig.json",
  "compilerOptions": {
    "allowJs": true,
    "checkJs": true,
    "esModuleInterop": true,
    "forceConsistentCasingInFileNames": true,
    "resolveJsonModule": true,
    "skipLibCheck": true,
    "sourceMap": true,
    "strict": true,
    "moduleResolution": "bundler",
    "module": "ESNext",
    "target": "ESNext",
    "verbatimModuleSyntax": true,
    "isolatedModules": true,
    "lib": ["ESNext", "DOM", "DOM.Iterable"],
    "baseUrl": ".",
    "paths": {
      "$lib": ["./src/ui/lib"],
      "$lib/*": ["./src/ui/lib/*"],
      "$components": ["./src/ui/components"],
      "$components/*": ["./src/ui/components/*"]
    }
  },
  "include": [
    "src/**/*.ts",
    "src/**/*.svelte",
    "src/**/*.js",
    ".svelte-kit/**/*.svelte",
    ".svelte-kit/**/*.js"
  ],
  "exclude": ["node_modules", "build", "src-tauri"]
}
```

- [ ] **Step 4: 验证配置**

Run: `npm run check`
Expected: SvelteKit 同步成功（可能会有文件缺失警告，正常）

---

### Task 1.3: 创建目录结构

**Files:**
- Create directories

- [ ] **Step 1: 创建 UI 目录结构**

Run:
```bash
mkdir -p src/ui/routes/settings && \
mkdir -p src/ui/components/{layout,chat,project,task} && \
mkdir -p src/ui/lib/{api,chat,stores,types} && \
mkdir -p src/ui/tests && \
mkdir -p src/ui/static
```
Expected: 目录创建成功

- [ ] **Step 2: 创建 bridge 目录**

Run: `mkdir -p src/bridge`
Expected: 目录创建成功

- [ ] **Step 3: 创建 migration 文档目录**

Run: `mkdir -p docs/migration`
Expected: 目录创建成功

- [ ] **Step 4: 验证目录结构**

Run: `find src -type d | head -20`
Expected: 显示创建的目录结构

---

## Chunk 2: 前端静态迁移 (S2)

### Task 2.1: 迁移基础模板文件

**Files:**
- Create: `src/ui/app.html`
- Create: `src/ui/app.css`

- [ ] **Step 1: 复制 app.html**

Run:
```bash
cp /Users/nasakim/projects/smanclaw/smanclaw-desktop/crates/smanclaw-desktop/src/app.html /Users/nasakim/projects/sman/src/ui/app.html
```
Expected: 文件复制成功

- [ ] **Step 2: 复制 app.css**

Run:
```bash
cp /Users/nasakim/projects/smanclaw/smanclaw-desktop/crates/smanclaw-desktop/src/app.css /Users/nasakim/projects/sman/src/ui/app.css
```
Expected: 文件复制成功

- [ ] **Step 3: 更新 app.html 中的路径引用**

在 `src/ui/app.html` 中，确保引用的路径正确：
```html
%sveltekit.head%
%sveltekit.body%
```

- [ ] **Step 4: 验证文件存在**

Run: `ls -la src/ui/app.*`
Expected: 显示 app.html 和 app.css

---

### Task 2.2: 迁移路由页面

**Files:**
- Create: `src/ui/routes/+layout.svelte`
- Create: `src/ui/routes/+page.svelte`
- Create: `src/ui/routes/settings/+page.svelte`

- [ ] **Step 1: 复制 +layout.svelte**

Run:
```bash
cp /Users/nasakim/projects/smanclaw/smanclaw-desktop/crates/smanclaw-desktop/src/routes/+layout.svelte /Users/nasakim/projects/sman/src/ui/routes/+layout.svelte
```
Expected: 文件复制成功

- [ ] **Step 2: 复制 +page.svelte**

Run:
```bash
cp /Users/nasakim/projects/smanclaw/smanclaw-desktop/crates/smanclaw-desktop/src/routes/+page.svelte /Users/nasakim/projects/sman/src/ui/routes/+page.svelte
```
Expected: 文件复制成功

- [ ] **Step 3: 复制 settings/+page.svelte**

Run:
```bash
cp /Users/nasakim/projects/smanclaw/smanclaw-desktop/crates/smanclaw-desktop/src/routes/settings/+page.svelte /Users/nasakim/projects/sman/src/ui/routes/settings/+page.svelte
```
Expected: 文件复制成功

- [ ] **Step 4: 验证路由文件**

Run: `find src/ui/routes -name "*.svelte"`
Expected: 显示 3 个 svelte 文件

---

### Task 2.3: 迁移 layout 组件

**Files:**
- Create: `src/ui/components/layout/Header.svelte`
- Create: `src/ui/components/layout/MainLayout.svelte`
- Create: `src/ui/components/layout/Sidebar.svelte`

- [ ] **Step 1: 复制 layout 组件**

Run:
```bash
cp /Users/nasakim/projects/smanclaw/smanclaw-desktop/crates/smanclaw-desktop/src/components/layout/*.svelte /Users/nasakim/projects/sman/src/ui/components/layout/
```
Expected: 文件复制成功

- [ ] **Step 2: 验证 layout 组件**

Run: `ls src/ui/components/layout/`
Expected: 显示 Header.svelte, MainLayout.svelte, Sidebar.svelte

---

### Task 2.4: 迁移 chat 组件

**Files:**
- Create: `src/ui/components/chat/ChatWindow.svelte`
- Create: `src/ui/components/chat/CodeBlock.svelte`
- Create: `src/ui/components/chat/InputArea.svelte`
- Create: `src/ui/components/chat/MermaidRenderer.svelte`
- Create: `src/ui/components/chat/MessageBubble.svelte`
- Create: `src/ui/components/chat/SkillPicker.svelte`

- [ ] **Step 1: 复制 chat 组件**

Run:
```bash
cp /Users/nasakim/projects/smanclaw/smanclaw-desktop/crates/smanclaw-desktop/src/components/chat/*.svelte /Users/nasakim/projects/sman/src/ui/components/chat/
```
Expected: 文件复制成功

- [ ] **Step 2: 验证 chat 组件**

Run: `ls src/ui/components/chat/`
Expected: 显示 6 个 svelte 文件

---

### Task 2.5: 迁移 project 组件

**Files:**
- Create: `src/ui/components/project/ProjectCard.svelte`
- Create: `src/ui/components/project/ProjectList.svelte`

- [ ] **Step 1: 复制 project 组件**

Run:
```bash
cp /Users/nasakim/projects/smanclaw/smanclaw-desktop/crates/smanclaw-desktop/src/components/project/*.svelte /Users/nasakim/projects/sman/src/ui/components/project/
```
Expected: 文件复制成功

- [ ] **Step 2: 验证 project 组件**

Run: `ls src/ui/components/project/`
Expected: 显示 2 个 svelte 文件

---

### Task 2.6: 迁移 task 组件

**Files:**
- Create: `src/ui/components/task/FileTree.svelte`
- Create: `src/ui/components/task/SubTaskProgress.svelte`
- Create: `src/ui/components/task/TaskProgress.svelte`

- [ ] **Step 1: 复制 task 组件**

Run:
```bash
cp /Users/nasakim/projects/smanclaw/smanclaw-desktop/crates/smanclaw-desktop/src/components/task/*.svelte /Users/nasakim/projects/sman/src/ui/components/task/
```
Expected: 文件复制成功

- [ ] **Step 2: 验证 task 组件**

Run: `ls src/ui/components/task/`
Expected: 显示 3 个 svelte 文件

---

### Task 2.7: 修复组件 import 路径

**Files:**
- Modify: `src/ui/components/**/*.svelte`
- Modify: `src/ui/routes/**/*.svelte`

- [ ] **Step 1: 识别需要修复的 import 路径**

Run:
```bash
grep -r "from '\.\./\.\./lib" src/ui/ || echo "No matches found"
grep -r "from '\$lib" src/ui/ || echo "No matches found"
```
Expected: 显示所有使用旧路径的文件

- [ ] **Step 2: 批量替换 import 路径（../lib -> $lib）**

对每个需要修复的文件，将：
- `from '../../lib/xxx'` 替换为 `from '$lib/xxx'`
- `from '../lib/xxx'` 替换为 `from '$lib/xxx'`

- [ ] **Step 3: 修复组件间引用路径**

检查组件间的相对路径引用，确保路径正确：
- layout 组件之间的引用
- chat 组件之间的引用
- 跨目录的组件引用

- [ ] **Step 4: 验证页面可启动**

Run: `npm run dev`
Expected: 开发服务器启动，无编译错误（可能有运行时错误，正常）

---

## Chunk 3: 状态与类型迁移 (S3)

### Task 3.1: 迁移类型定义

**Files:**
- Create: `src/ui/lib/types/index.ts`

- [ ] **Step 1: 复制类型定义文件**

Run:
```bash
cp /Users/nasakim/projects/smanclaw/smanclaw-desktop/crates/smanclaw-desktop/src/lib/types/index.ts /Users/nasakim/projects/sman/src/ui/lib/types/index.ts
```
Expected: 文件复制成功

- [ ] **Step 2: 对齐 core/types 与 ui/types**

检查 `src/core/types.ts` 和 `src/ui/lib/types/index.ts`，确保：
- TaskStatus 定义一致
- ExecutionResult 定义一致
- 避免重复定义

- [ ] **Step 3: 验证类型文件**

Run: `npx tsc --noEmit src/ui/lib/types/index.ts`
Expected: 类型检查通过

---

### Task 3.2: 迁移 stores

**Files:**
- Create: `src/ui/lib/stores/projects.ts`
- Create: `src/ui/lib/stores/settings.ts`
- Create: `src/ui/lib/stores/tasks.ts`

- [ ] **Step 1: 复制 stores 文件**

Run:
```bash
cp /Users/nasakim/projects/smanclaw/smanclaw-desktop/crates/smanclaw-desktop/src/lib/stores/*.ts /Users/nasakim/projects/sman/src/ui/lib/stores/
```
Expected: 文件复制成功

- [ ] **Step 2: 验证 stores 文件**

Run: `ls src/ui/lib/stores/`
Expected: 显示 3 个 ts 文件

---

### Task 3.3: 迁移 chat 工具函数

**Files:**
- Create: `src/ui/lib/chat/assistantContent.ts`

- [ ] **Step 1: 复制 assistantContent.ts**

Run:
```bash
cp /Users/nasakim/projects/smanclaw/smanclaw-desktop/crates/smanclaw-desktop/src/lib/chat/assistantContent.ts /Users/nasakim/projects/sman/src/ui/lib/chat/assistantContent.ts
```
Expected: 文件复制成功

- [ ] **Step 2: 验证文件**

Run: `ls src/ui/lib/chat/`
Expected: 显示 assistantContent.ts

---

### Task 3.4: 迁移 Tauri API 封装

**Files:**
- Create: `src/ui/lib/api/tauri.ts`

- [ ] **Step 1: 复制 tauri.ts**

Run:
```bash
cp /Users/nasakim/projects/smanclaw/smanclaw-desktop/crates/smanclaw-desktop/src/lib/api/tauri.ts /Users/nasakim/projects/sman/src/ui/lib/api/tauri.ts
```
Expected: 文件复制成功

- [ ] **Step 2: 验证文件**

Run: `ls src/ui/lib/api/`
Expected: 显示 tauri.ts

---

### Task 3.5: 类型检查验证

**Files:**
- All TypeScript files in src/ui/

- [ ] **Step 1: 运行类型检查**

Run: `npm run typecheck`
Expected: 类型检查通过或显示需要修复的错误

- [ ] **Step 2: 修复类型错误**

根据类型检查输出，修复所有类型错误：
- 缺失的类型导入
- 类型不匹配
- 缺失的模块声明

- [ ] **Step 3: 再次验证类型检查**

Run: `npm run typecheck`
Expected: 类型检查通过

---

## Chunk 4: 测试迁移 (S5)

### Task 4.1: 迁移前端测试

**Files:**
- Create: `src/ui/tests/assistantContent.test.ts`
- Create: `src/ui/tests/messageUpdate.test.ts`
- Create: `src/ui/tests/tasksStoreEvents.test.ts`

- [ ] **Step 1: 复制测试文件**

Run:
```bash
cp /Users/nasakim/projects/smanclaw/smanclaw-desktop/crates/smanclaw-desktop/src/lib/__tests__/assistantContent.test.ts /Users/nasakim/projects/sman/src/ui/tests/
cp /Users/nasakim/projects/smanclaw/smanclaw-desktop/crates/smanclaw-desktop/src/lib/__tests__/messageUpdate.test.ts /Users/nasakim/projects/sman/src/ui/tests/
cp /Users/nasakim/projects/smanclaw/smanclaw-desktop/crates/smanclaw-desktop/src/lib/__tests__/tasksStoreEvents.test.ts /Users/nasakim/projects/sman/src/ui/tests/
```
Expected: 文件复制成功

- [ ] **Step 2: 修复测试文件中的 import 路径**

更新测试文件中的 import 路径以匹配新目录结构：
- `from '../chat/assistantContent'` -> `from '$lib/chat/assistantContent'`
- `from '../stores/tasks'` -> `from '$lib/stores/tasks'`

- [ ] **Step 3: 运行测试**

Run: `npm run test`
Expected: 测试运行（可能有失败，需要修复）

- [ ] **Step 4: 修复测试失败**

根据测试输出修复所有失败的测试

- [ ] **Step 5: 再次运行测试验证**

Run: `npm run test`
Expected: 所有测试通过

---

### Task 4.2: 更新 vitest 配置

**Files:**
- Modify: `vitest.config.ts`

- [ ] **Step 1: 更新 vitest.config.ts**

```typescript
import { defineConfig } from "vitest/config";
import { sveltekit } from "@sveltejs/kit/vite";
import path from "path";

export default defineConfig({
  plugins: [sveltekit()],
  test: {
    include: ["src/**/*.{test,spec}.{js,ts}"],
    globals: true,
    environment: "jsdom",
    setupFiles: ["./src/ui/tests/setup.ts"],
  },
  resolve: {
    alias: {
      $lib: path.resolve("./src/ui/lib"),
      $components: path.resolve("./src/ui/components"),
    },
  },
});
```

- [ ] **Step 2: 创建测试 setup 文件**

Create: `src/ui/tests/setup.ts`

```typescript
// Test setup file
// Add any global test configuration here
```

- [ ] **Step 3: 验证测试配置**

Run: `npm run test`
Expected: 测试运行成功

---

## Chunk 5: Bridge 层实现 (S4)

### Task 5.1: 创建 Tauri 事件桥接

**Files:**
- Create: `src/bridge/tauri-events.ts`

- [ ] **Step 1: 创建 tauri-events.ts**

```typescript
import { listen, type UnlistenFn } from "@tauri-apps/api/event";

export interface TaskProgressEvent {
  taskId: string;
  status: "pending" | "running" | "succeeded" | "failed";
  progress?: number;
  message?: string;
}

export interface ChatStreamEvent {
  conversationId: string;
  content: string;
  isComplete: boolean;
}

export type SmanEvent =
  | { type: "task:progress"; payload: TaskProgressEvent }
  | { type: "chat:stream"; payload: ChatStreamEvent };

export function subscribeToSmanEvents(
  handler: (event: SmanEvent) => void
): Promise<UnlistenFn> {
  return listen<SmanEvent["payload"]>("sman-event", (event) => {
    const payload = event.payload as SmanEvent;
    handler(payload);
  });
}
```

- [ ] **Step 2: 验证文件创建**

Run: `ls src/bridge/tauri-events.ts`
Expected: 文件存在

---

### Task 5.2: 创建 Tauri 命令桥接

**Files:**
- Create: `src/bridge/tauri-commands.ts`

- [ ] **Step 1: 创建 tauri-commands.ts**

```typescript
import { invoke } from "@tauri-apps/api/core";
import type { TaskStatus } from "../core/types.js";

export interface CreateTaskRequest {
  projectId: string;
  prompt: string;
}

export interface TaskResponse {
  id: string;
  status: TaskStatus;
  createdAt: string;
}

export interface ProjectResponse {
  id: string;
  name: string;
  path: string;
  createdAt: string;
}

// Project commands
export async function listProjects(): Promise<ProjectResponse[]> {
  return invoke<ProjectResponse[]>("list_projects");
}

export async function createProject(
  name: string,
  path: string
): Promise<ProjectResponse> {
  return invoke<ProjectResponse>("create_project", { name, path });
}

// Task commands
export async function createTask(
  request: CreateTaskRequest
): Promise<TaskResponse> {
  return invoke<TaskResponse>("create_task", request);
}

export async function executeTask(taskId: string): Promise<void> {
  return invoke("execute_task", { taskId });
}

export async function getTaskStatus(taskId: string): Promise<TaskStatus> {
  return invoke<TaskStatus>("get_task_status", { taskId });
}

// Settings commands
export async function getSettings(): Promise<Record<string, unknown>> {
  return invoke("get_settings");
}

export async function updateSettings(
  settings: Record<string, unknown>
): Promise<void> {
  return invoke("update_settings", { settings });
}
```

- [ ] **Step 2: 验证文件创建**

Run: `ls src/bridge/tauri-commands.ts`
Expected: 文件存在

---

### Task 5.3: 创建运行时网关

**Files:**
- Create: `src/bridge/runtime-gateway.ts`

- [ ] **Step 1: 创建 runtime-gateway.ts**

```typescript
import { UnifiedOrchestrator } from "../core/orchestrator.js";
import { EngineRegistry } from "../core/registry.js";
import {
  ClaudeCodeEngine,
  createAcpxClaudeCodeRunner,
} from "../core/claudecode-engine.js";
import type { AcpxClient } from "../core/acpx.js";
import type { TaskRecord, ExecutionResult } from "../core/types.js";

export interface RuntimeGateway {
  execute(prompt: string, taskId: string): Promise<ExecutionResult>;
  getStatus(taskId: string): "pending" | "running" | "succeeded" | "failed" | undefined;
}

export function createRuntimeGateway(
  acpxClient?: AcpxClient
): RuntimeGateway {
  const registry = new EngineRegistry();

  const runner = acpxClient
    ? createAcpxClaudeCodeRunner(acpxClient)
    : undefined;

  const engine = new ClaudeCodeEngine(runner);
  registry.register(engine);

  const orchestrator = new UnifiedOrchestrator(registry);

  return {
    async execute(prompt: string, taskId: string): Promise<ExecutionResult> {
      const task: TaskRecord = {
        id: taskId,
        payload: prompt,
        status: "pending",
      };
      orchestrator.insertTask(task);
      return orchestrator.executeTask(taskId, "claudecode");
    },

    getStatus(taskId: string) {
      return orchestrator.status(taskId);
    },
  };
}
```

- [ ] **Step 2: 验证文件创建**

Run: `ls src/bridge/runtime-gateway.ts`
Expected: 文件存在

---

### Task 5.4: 导出 bridge 模块

**Files:**
- Create: `src/bridge/index.ts`

- [ ] **Step 1: 创建 index.ts**

```typescript
export * from "./tauri-events.js";
export * from "./tauri-commands.js";
export * from "./runtime-gateway.js";
```

- [ ] **Step 2: 验证模块导出**

Run: `npx tsc --noEmit src/bridge/index.ts`
Expected: 类型检查通过

---

## Chunk 6: Tauri 壳迁移 (S6)

### Task 6.1: 复制 src-tauri 目录

**Files:**
- Create: `src-tauri/` 整个目录

- [ ] **Step 1: 复制 src-tauri 核心文件**

Run:
```bash
cp -r /Users/nasakim/projects/smanclaw/smanclaw-desktop/crates/smanclaw-desktop/src-tauri /Users/nasakim/projects/sman/
```
Expected: 目录复制成功

- [ ] **Step 2: 清理不需要的文件**

Run:
```bash
rm -rf /Users/nasakim/projects/sman/src-tauri/.smanclaw-desktop && \
rm -rf /Users/nasakim/projects/sman/src-tauri/web && \
rm -rf /Users/nasakim/projects/sman/src-tauri/gen
```
Expected: 清理完成

- [ ] **Step 3: 验证 src-tauri 结构**

Run: `ls -la src-tauri/`
Expected: 显示 Cargo.toml, src/, tauri.conf.json, icons/ 等

---

### Task 6.2: 更新 tauri.conf.json

**Files:**
- Modify: `src-tauri/tauri.conf.json`

- [ ] **Step 1: 更新配置文件**

修改 `src-tauri/tauri.conf.json`，将：
- `productName` 改为 `sman`
- `beforeBuildCommand` 改为 `npm run build`
- `beforeDevCommand` 改为 `npm run dev`
- `devUrl` 保持或更新
- `frontendDist` 改为 `../build`

- [ ] **Step 2: 验证配置**

Run: `cat src-tauri/tauri.conf.json`
Expected: 显示更新后的配置

---

### Task 6.3: 更新 Cargo.toml

**Files:**
- Modify: `src-tauri/Cargo.toml`

- [ ] **Step 1: 更新包名**

修改 `src-tauri/Cargo.toml`：
```toml
[package]
name = "sman"
version = "0.1.0"
```

- [ ] **Step 2: 验证配置**

Run: `cat src-tauri/Cargo.toml`
Expected: 显示更新后的配置

---

### Task 6.4: 验证 Tauri 构建

**Files:**
- None (验证任务)

- [ ] **Step 1: 检查 Rust 环境**

Run: `rustc --version && cargo --version`
Expected: 显示 Rust 版本信息

- [ ] **Step 2: 尝试 Tauri 开发模式**

Run: `npm run tauri:dev`
Expected: Tauri 应用启动（可能有错误需要修复）

- [ ] **Step 3: 记录并修复问题**

记录启动过程中的任何错误并修复

---

## Chunk 7: 门禁验证与文档 (S7-S8)

### Task 7.1: 运行完整门禁检查

**Files:**
- None (验证任务)

- [ ] **Step 1: 运行测试**

Run: `npm run test`
Expected: 所有测试通过

- [ ] **Step 2: 运行类型检查**

Run: `npm run typecheck`
Expected: 类型检查通过

- [ ] **Step 3: 运行 lint**

Run: `npm run lint`
Expected: Lint 检查通过

- [ ] **Step 4: 运行构建**

Run: `npm run build`
Expected: 构建成功

- [ ] **Step 5: 运行 Tauri 构建**

Run: `npm run tauri:build`
Expected: Tauri 构建成功

---

### Task 7.2: 创建迁移文档

**Files:**
- Create: `docs/migration/file-mapping.csv`
- Create: `docs/migration/cutover-checklist.md`
- Create: `docs/migration/rollback-guide.md`

- [ ] **Step 1: 创建 file-mapping.csv**

```csv
源文件,目标文件,状态
src/routes/+layout.svelte,src/ui/routes/+layout.svelte,已迁移
src/routes/+page.svelte,src/ui/routes/+page.svelte,已迁移
src/routes/settings/+page.svelte,src/ui/routes/settings/+page.svelte,已迁移
src/components/layout/Header.svelte,src/ui/components/layout/Header.svelte,已迁移
src/components/layout/MainLayout.svelte,src/ui/components/layout/MainLayout.svelte,已迁移
src/components/layout/Sidebar.svelte,src/ui/components/layout/Sidebar.svelte,已迁移
src/components/chat/ChatWindow.svelte,src/ui/components/chat/ChatWindow.svelte,已迁移
src/components/chat/CodeBlock.svelte,src/ui/components/chat/CodeBlock.svelte,已迁移
src/components/chat/InputArea.svelte,src/ui/components/chat/InputArea.svelte,已迁移
src/components/chat/MermaidRenderer.svelte,src/ui/components/chat/MermaidRenderer.svelte,已迁移
src/components/chat/MessageBubble.svelte,src/ui/components/chat/MessageBubble.svelte,已迁移
src/components/chat/SkillPicker.svelte,src/ui/components/chat/SkillPicker.svelte,已迁移
src/components/project/ProjectCard.svelte,src/ui/components/project/ProjectCard.svelte,已迁移
src/components/project/ProjectList.svelte,src/ui/components/project/ProjectList.svelte,已迁移
src/components/task/FileTree.svelte,src/ui/components/task/FileTree.svelte,已迁移
src/components/task/SubTaskProgress.svelte,src/ui/components/task/SubTaskProgress.svelte,已迁移
src/components/task/TaskProgress.svelte,src/ui/components/task/TaskProgress.svelte,已迁移
src/lib/api/tauri.ts,src/ui/lib/api/tauri.ts,已迁移
src/lib/chat/assistantContent.ts,src/ui/lib/chat/assistantContent.ts,已迁移
src/lib/stores/projects.ts,src/ui/lib/stores/projects.ts,已迁移
src/lib/stores/settings.ts,src/ui/lib/stores/settings.ts,已迁移
src/lib/stores/tasks.ts,src/ui/lib/stores/tasks.ts,已迁移
src/lib/types/index.ts,src/ui/lib/types/index.ts,已迁移
src/lib/__tests__/assistantContent.test.ts,src/ui/tests/assistantContent.test.ts,已迁移
src/lib/__tests__/messageUpdate.test.ts,src/ui/tests/messageUpdate.test.ts,已迁移
src/lib/__tests__/tasksStoreEvents.test.ts,src/ui/tests/tasksStoreEvents.test.ts,已迁移
src/app.css,src/ui/app.css,已迁移
src/app.html,src/ui/app.html,已迁移
src-tauri/,src-tauri/,已迁移
```

- [ ] **Step 2: 创建 cutover-checklist.md**

```markdown
# 切换检查清单

## 切换前检查

- [ ] 所有测试通过
- [ ] 类型检查通过
- [ ] Lint 检查通过
- [ ] 构建成功
- [ ] Tauri 构建成功
- [ ] 核心流程验证通过（创建任务 -> 执行 -> 状态更新）

## 切换步骤

1. 冻结源仓 `smanclaw-desktop` 的前端功能变更
2. 锁定基线 commit
3. 更新所有开发环境的仓库指向
4. 通知团队成员

## 切换后验证

- [ ] 创建项目功能正常
- [ ] 执行任务功能正常
- [ ] 聊天功能正常
- [ ] 设置功能正常
- [ ] 任务进度显示正常
```

- [ ] **Step 3: 创建 rollback-guide.md**

```markdown
# 回滚指南

## 回滚场景

如果迁移后发现严重问题，可以按以下步骤回滚：

## 回滚步骤

1. 停止所有使用 `sman` 的开发活动
2. 切换回 `smanclaw-desktop` 仓库
3. 恢复之前保存的数据文件：
   - `.smanclaw-desktop/` 目录
   - 数据库文件
4. 验证 `smanclaw-desktop` 功能正常

## 数据迁移

如果在新系统中有新数据产生：
1. 导出新系统数据
2. 转换为旧系统格式
3. 导入旧系统

## 回滚验证

- [ ] 旧系统启动正常
- [ ] 核心功能正常
- [ ] 数据完整性验证
```

- [ ] **Step 4: 验证文档**

Run: `ls docs/migration/`
Expected: 显示 3 个文档文件

---

## 执行摘要

| 阶段 | 任务数 | 关键产出 |
|-----|-------|---------|
| Chunk 1: 工程骨架 | 3 | SvelteKit + Tauri 配置完成 |
| Chunk 2: 前端迁移 | 7 | 所有 Svelte 组件迁移完成 |
| Chunk 3: 状态类型 | 5 | Stores + Types 迁移完成 |
| Chunk 4: 测试迁移 | 2 | 测试通过 |
| Chunk 5: Bridge 层 | 4 | Tauri 桥接完成 |
| Chunk 6: Tauri 壳 | 4 | 桌面应用可构建 |
| Chunk 7: 门禁文档 | 2 | 所有检查通过 + 文档完成 |

**总任务数:** 27
**预计完成时间:** 按顺序执行，每任务 2-5 分钟
