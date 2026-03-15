# SmanWeb 多业务系统支持设计文档

> **版本**: 1.0
> **日期**: 2026-03-15
> **作者**: Claude

## 1. 概述

### 1.1 背景

SmanWeb 作为智能业务系统一体化部署包，当前架构为 `UI → OpenClaw → Claude Code → 业务系统`。用户需要能够在同一套系统中为多个业务系统（如 ERP、CRM、小程序）提供 AI 辅助开发服务。

### 1.2 目标

- 用户可以在 UI 上选择不同的业务系统发起会话
- 每个会话绑定一个业务系统，Claude Code 在对应目录下工作
- 会话之间完全隔离，互不干扰
- 业务系统源码打包到部署包中，支持直接修改生产代码

### 1.3 核心理念

**AI 时代的"生产即开发"**：业务系统源码打包进去，AI 可以直接修改生产环境的代码，修改后重启服务即可生效。

---

## 2. 整体架构

### 2.1 架构图

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           SmanWeb 部署包                                 │
│                                                                         │
│  ┌───────────────────────────────────────────────────────────────────┐ │
│  │                      React 前端 (dist/)                            │ │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────────┐   │ │
│  │  │ 左侧会话栏  │  │ 聊天区域    │  │ 业务系统选择弹窗        │   │ │
│  │  │ - 树形分组  │  │ - 消息列表  │  │ - 系统列表              │   │ │
│  │  │ - 新建会话  │  │ - 输入框    │  │ - 名称/路径展示         │   │ │
│  │  └─────────────┘  └─────────────┘  └─────────────────────────┘   │ │
│  └───────────────────────────────────────────────────────────────────┘ │
│                              │ RPC (WebSocket)                         │
│  ┌───────────────────────────────────────────────────────────────────┐ │
│  │                   Node.js 后端 (dist/server/)                      │ │
│  │  - WebSocket 代理到 OpenClaw Gateway                               │ │
│  │  - 静态文件服务                                                    │ │
│  └───────────────────────────────────────────────────────────────────┘ │
│                              │ WebSocket                               │
│  ┌───────────────────────────────────────────────────────────────────┐ │
│  │                 OpenClaw Gateway (bundled/openclaw/)               │ │
│  │  ┌─────────────────────────────────────────────────────────────┐  │ │
│  │  │ business-systems.yaml                                        │  │ │
│  │  │ systems:                                                     │  │ │
│  │  │   - id: erp                                                  │  │ │
│  │  │     name: ERP系统                                            │  │ │
│  │  │     path: business-systems/erp/                              │  │ │
│  │  │   - id: crm                                                  │  │ │
│  │  │     name: CRM系统                                            │  │ │
│  │  │     path: business-systems/crm/                              │  │ │
│  │  └─────────────────────────────────────────────────────────────┘  │ │
│  │                                                                     │ │
│  │  新增 API:                                                          │ │
│  │  - systems.list → 返回业务系统列表                                  │ │
│  │  - sessions.ensure 支持 systemId 参数                               │ │
│  └───────────────────────────────────────────────────────────────────┘ │
│                              │ 按需启动                                 │
│  ┌───────────────────────────────────────────────────────────────────┐ │
│  │                   Claude Code (bundled/claude-code/)               │ │
│  │  - 每个会话独立进程                                                 │ │
│  │  - 工作目录 = 选中的业务系统路径                                    │ │
│  └───────────────────────────────────────────────────────────────────┘ │
│                              │ 读写                                     │
│  ┌───────────────────────────────────────────────────────────────────┐ │
│  │                  业务系统 (bundled/business-systems/)              │ │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────────┐   │ │
│  │  │ erp/        │  │ crm/        │  │ miniapp/                │   │ │
│  │  │ src/        │  │ src/        │  │ src/                    │   │ │
│  │  │ pom.xml     │  │ manage.py   │  │ package.json            │   │ │
│  │  └─────────────┘  └─────────────┘  └─────────────────────────┘   │ │
│  └───────────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────────┘
```

### 2.2 数据流

```
用户操作                     前端                    后端/Gateway              Claude Code
   │                         │                         │                         │
   │  1. 点击"新建会话"      │                         │                         │
   │ ──────────────────────> │                         │                         │
   │                         │                         │                         │
   │  2. 弹窗展示业务系统    │                         │                         │
   │ <────────────────────── │                         │                         │
   │                         │                         │                         │
   │  3. 选择 "ERP系统"      │                         │                         │
   │ ──────────────────────> │                         │                         │
   │                         │                         │                         │
   │                         │  4. systems.list        │                         │
   │                         │ ───────────────────────>│                         │
   │                         │                         │                         │
   │                         │  5. 返回系统列表        │                         │
   │                         │ <───────────────────────│                         │
   │                         │                         │                         │
   │                         │  6. sessions.ensure     │                         │
   │                         │     {systemId: "erp"}   │                         │
   │                         │ ───────────────────────>│                         │
   │                         │                         │                         │
   │                         │                         │  7. 查找 erp → cwd      │
   │                         │                         │ ───────────────────────┐│
   │                         │                         │                         ││
   │                         │                         │  8. acpx.ensureSession  │
   │                         │                         │     {cwd: "/app/.../erp/"}
   │                         │                         │ ───────────────────────>│
   │                         │                         │                         │
   │                         │  9. 返回 session handle │                         │
   │                         │ <───────────────────────│                         │
   │                         │                         │                         │
   │  10. 进入聊天界面       │                         │                         │
   │ <────────────────────── │                         │                         │
   │                         │                         │                         │
   │  11. 发送消息           │                         │                         │
   │ ──────────────────────> │                         │                         │
   │                         │                         │                         │
   │                         │  12. chat.send          │                         │
   │                         │ ───────────────────────>│                         │
   │                         │                         │                         │
   │                         │                         │  13. 启动 Claude Code   │
   │                         │                         │      cwd = /app/erp/   │
   │                         │                         │ ───────────────────────>│
   │                         │                         │                         │
   │                         │  14. 流式返回响应       │                         │
   │                         │ <─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ │ <─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ │
```

---

## 3. 数据结构设计

### 3.1 业务系统配置 (OpenClaw 侧)

**文件位置**: `bundled/openclaw/business-systems.yaml`

```yaml
# 业务系统配置
version: "1.0"

systems:
  - id: "erp"
    name: "ERP系统"
    description: "企业资源管理系统"
    path: "business-systems/erp/"
    techStack:
      - "java"
      - "spring"
      - "mysql"

  - id: "crm"
    name: "CRM系统"
    description: "客户关系管理系统"
    path: "business-systems/crm/"
    techStack:
      - "python"
      - "django"
      - "postgresql"

  - id: "miniapp"
    name: "小程序"
    description: "微信小程序"
    path: "business-systems/miniapp/"
    techStack:
      - "typescript"
      - "react"
      - "nodejs"
```

**配置说明**:
- `id`: 业务系统唯一标识，用于 API 调用
- `name`: 显示名称，用于 UI 展示
- `description`: 描述信息
- `path`: 相对于 SmanWeb 根目录的路径
- `techStack`: 技术栈标签，可用于 UI 展示或后续智能提示

### 3.2 Gateway RPC 接口扩展

#### 3.2.1 新增 `systems.list` 方法

**请求**:
```json
{
  "type": "req",
  "id": "req-123",
  "method": "systems.list",
  "params": {}
}
```

**响应**:
```json
{
  "type": "res",
  "id": "req-123",
  "ok": true,
  "payload": {
    "systems": [
      {
        "id": "erp",
        "name": "ERP系统",
        "description": "企业资源管理系统",
        "path": "business-systems/erp/",
        "techStack": ["java", "spring", "mysql"]
      },
      {
        "id": "crm",
        "name": "CRM系统",
        "description": "客户关系管理系统",
        "path": "business-systems/crm/",
        "techStack": ["python", "django", "postgresql"]
      }
    ]
  }
}
```

#### 3.2.2 扩展 `sessions.ensure` 方法

**新增 `systemId` 参数**:

```json
{
  "type": "req",
  "id": "req-456",
  "method": "sessions.ensure",
  "params": {
    "sessionKey": "agent:main:session-123",
    "systemId": "erp"
  }
}
```

**处理逻辑**:
1. 根据 `systemId` 查找 `business-systems.yaml` 中的配置
2. 获取对应的 `path`
3. 解析为绝对路径: `{smanwebRoot}/{path}`
4. 调用 `acpx.ensureSession({ cwd: absolutePath, ... })`

### 3.3 前端数据结构

#### 3.3.1 业务系统类型

```typescript
// src/types/business-system.ts

export interface BusinessSystem {
  id: string;
  name: string;
  description?: string;
  path: string;
  techStack?: string[];
}

export interface SystemSession {
  id: string;           // session key
  systemId: string;     // 关联的业务系统 ID
  label: string;        // 会话名称 ("新会话" 或用户消息前6字)
  createdAt: number;
  updatedAt?: number;
}
```

#### 3.3.2 Store 扩展

```typescript
// src/stores/business-systems.ts

import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import type { BusinessSystem, SystemSession } from '@/types/business-system';

interface BusinessSystemsState {
  // 业务系统列表 (从 Gateway 获取)
  systems: BusinessSystem[];
  systemsLoading: boolean;
  loadSystems: () => Promise<void>;

  // 会话列表 (本地存储)
  sessions: SystemSession[];
  currentSessionId: string | null;

  // 会话操作
  createSession: (systemId: string) => string;
  updateSessionLabel: (sessionId: string, label: string) => void;
  deleteSession: (sessionId: string) => void;
  switchSession: (sessionId: string) => void;

  // 辅助方法
  getSessionsBySystem: (systemId: string) => SystemSession[];
  getCurrentSession: () => SystemSession | null;
  getCurrentSystem: () => BusinessSystem | null;
}

export const useBusinessSystemsStore = create<BusinessSystemsState>()(
  persist(
    (set, get) => ({
      // 初始状态
      systems: [],
      systemsLoading: false,
      sessions: [],
      currentSessionId: null,

      // 加载业务系统列表
      loadSystems: async () => {
        set({ systemsLoading: true });
        try {
          const client = getGatewayClient();
          if (!client) return;

          const result = await client.rpc<{ systems: BusinessSystem[] }>('systems.list', {});
          if (result?.systems) {
            set({ systems: result.systems });
          }
        } finally {
          set({ systemsLoading: false });
        }
      },

      // 创建新会话
      createSession: (systemId: string) => {
        const sessionId = `agent:main:session-${Date.now()}`;
        const newSession: SystemSession = {
          id: sessionId,
          systemId,
          label: '新会话',
          createdAt: Date.now(),
        };

        set((state) => ({
          sessions: [...state.sessions, newSession],
          currentSessionId: sessionId,
        }));

        return sessionId;
      },

      // 更新会话名称
      updateSessionLabel: (sessionId: string, label: string) => {
        set((state) => ({
          sessions: state.sessions.map((s) =>
            s.id === sessionId ? { ...s, label, updatedAt: Date.now() } : s
          ),
        }));
      },

      // 删除会话
      deleteSession: (sessionId: string) => {
        set((state) => {
          const remaining = state.sessions.filter((s) => s.id !== sessionId);
          const newCurrentId =
            state.currentSessionId === sessionId
              ? remaining[0]?.id ?? null
              : state.currentSessionId;

          return {
            sessions: remaining,
            currentSessionId: newCurrentId,
          };
        });
      },

      // 切换会话
      switchSession: (sessionId: string) => {
        set({ currentSessionId: sessionId });
      },

      // 按系统分组获取会话
      getSessionsBySystem: (systemId: string) => {
        return get().sessions.filter((s) => s.systemId === systemId);
      },

      // 获取当前会话
      getCurrentSession: () => {
        const { sessions, currentSessionId } = get();
        return sessions.find((s) => s.id === currentSessionId) ?? null;
      },

      // 获取当前业务系统
      getCurrentSystem: () => {
        const { systems, sessions, currentSessionId } = get();
        const currentSession = sessions.find((s) => s.id === currentSessionId);
        if (!currentSession) return null;
        return systems.find((s) => s.id === currentSession.systemId) ?? null;
      },
    }),
    {
      name: 'smanweb-sessions',
      partialize: (state) => ({
        sessions: state.sessions,
        currentSessionId: state.currentSessionId,
      }),
    }
  )
);
```

---

## 4. UI 设计

### 4.1 左侧会话栏（树形结构）

```
┌────────────────────────────┐
│  [+ 新建会话]              │
├────────────────────────────┤
│                            │
│  ▼ 📁 ERP系统 (2)          │
│      ├─ 新会话             │
│      └─ 修复订单库存...    │
│                            │
│  ▼ 📁 CRM系统 (1)          │
│      └─ 帮我加个导出...    │
│                            │
│  ▶ 📁 小程序 (0)           │
│                            │
└────────────────────────────┘
```

**交互逻辑**:
1. 点击 `[+ 新建会话]` → 弹出业务系统选择弹窗
2. 点击业务系统分组标题（如 `📁 ERP系统`）→ 展开/收起
3. 点击会话项 → 切换到该会话
4. 右键会话项 → 删除/重命名菜单

### 4.2 业务系统选择弹窗

```
┌────────────────────────────────────────────┐
│           选择业务系统                      │
├────────────────────────────────────────────┤
│                                            │
│  选择要操作的业务系统，开始新会话           │
│                                            │
│  ┌──────────────────────────────────────┐ │
│  │  ○ ERP系统                           │ │
│  │    企业资源管理系统                    │ │
│  │    📁 business-systems/erp/          │ │
│  │    🏷️ java, spring, mysql            │ │
│  └──────────────────────────────────────┘ │
│                                            │
│  ┌──────────────────────────────────────┐ │
│  │  ○ CRM系统                           │ │
│  │    客户关系管理系统                    │ │
│  │    📁 business-systems/crm/          │ │
│  │    🏷️ python, django, postgresql     │ │
│  └──────────────────────────────────────┘ │
│                                            │
│  ┌──────────────────────────────────────┐ │
│  │  ○ 小程序                            │ │
│  │    微信小程序                          │ │
│  │    📁 business-systems/miniapp/      │ │
│  │    🏷️ typescript, react, nodejs      │ │
│  └──────────────────────────────────────┘ │
│                                            │
│              [取消]    [开始会话]           │
└────────────────────────────────────────────┘
```

### 4.3 聊天区域

与现有设计保持一致，顶部显示当前业务系统信息：

```
┌────────────────────────────────────────────┐
│  📁 ERP系统  >  修复订单库存问题            │
├────────────────────────────────────────────┤
│                                            │
│  [消息列表区域]                             │
│                                            │
├────────────────────────────────────────────┤
│  [附件] [输入框...........................] [发送] │
└────────────────────────────────────────────┘
```

---

## 5. 打包流程改造

### 5.1 新增打包脚本

**文件**: `scripts/bundle-business-systems.mjs`

```javascript
#!/usr/bin/env zx

/**
 * bundle-business-systems.mjs
 *
 * 打包业务系统源码到 bundled/business-systems/
 */

import 'zx/globals';
import { existsSync, mkdirSync, cpSync, rmSync, readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import yaml from 'yaml';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(__dirname, '..');
const OUTPUT = path.join(ROOT, 'bundled', 'business-systems');
const CONFIG_FILE = path.join(ROOT, 'resources', 'business-systems.yaml');

echo`📦 Bundling business systems...`;

// 读取配置
if (!existsSync(CONFIG_FILE)) {
  echo`⚠️  No business-systems.yaml found, skipping.`;
  process.exit(0);
}

const configContent = readFileSync(CONFIG_FILE, 'utf8');
const config = yaml.parse(configContent);

if (!config.systems || config.systems.length === 0) {
  echo`⚠️  No business systems configured, skipping.`;
  process.exit(0);
}

// 清理输出目录
if (existsSync(OUTPUT)) {
  rmSync(OUTPUT, { recursive: true });
}
mkdirSync(OUTPUT, { recursive: true });

// 复制每个业务系统
for (const system of config.systems) {
  const sourcePath = path.resolve(ROOT, system.path);
  const targetPath = path.join(OUTPUT, system.id);

  if (!existsSync(sourcePath)) {
    echo`⚠️  Business system source not found: ${system.path}`;
    continue;
  }

  echo`   Copying ${system.id} from ${system.path}...`;
  cpSync(sourcePath, targetPath, { recursive: true });
  echo`   ✓ ${system.name} (${system.id})`;
}

// 更新配置中的路径（改为打包后的路径）
const bundledConfig = {
  version: config.version,
  systems: config.systems.map((s) => ({
    ...s,
    path: `bundled/business-systems/${s.id}/`,
  })),
};

// 写入打包后的配置到 OpenClaw 目录
const openclawConfigDir = path.join(ROOT, 'bundled', 'openclaw');
mkdirSync(openclawConfigDir, { recursive: true });
writeFileSync(
  path.join(openclawConfigDir, 'business-systems.yaml'),
  yaml.stringify(bundledConfig)
);

echo``;
echo`✅ Business systems bundled: ${OUTPUT}`;
echo`   Systems: ${config.systems.length}`;
```

### 5.2 修改 package.json 构建脚本

```json
{
  "scripts": {
    "build": "vite build && pnpm bundle:all",
    "bundle:all": "pnpm bundle:openclaw && pnpm bundle:claude-code && pnpm bundle:lsp && pnpm bundle:skills && pnpm bundle:business-systems",
    "bundle:business-systems": "zx scripts/bundle-business-systems.mjs"
  }
}
```

### 5.3 配置文件示例

**文件**: `resources/business-systems.yaml`

```yaml
# 业务系统配置
# 打包时会复制到 bundled/business-systems/

version: "1.0"

systems:
  # 示例：取消注释并配置你的业务系统
  # - id: "erp"
  #   name: "ERP系统"
  #   description: "企业资源管理系统"
  #   path: "../my-erp-project/"  # 相对于 smanweb 根目录
  #   techStack:
  #     - "java"
  #     - "spring"
```

---

## 6. OpenClaw 改造

### 6.1 配置加载

OpenClaw Gateway 启动时加载 `business-systems.yaml`：

```typescript
// 新增: src/config/business-systems.ts

export type BusinessSystem = {
  id: string;
  name: string;
  description?: string;
  path: string;
  techStack?: string[];
};

export type BusinessSystemsConfig = {
  version: string;
  systems: BusinessSystem[];
};

export async function loadBusinessSystemsConfig(
  configPath: string
): Promise<BusinessSystemsConfig | null> {
  // 读取并解析 YAML 文件
  // 如果文件不存在，返回 null
}
```

### 6.2 Gateway API 扩展

#### 6.2.1 新增 `systems.list` 处理器

```typescript
// src/gateway/handlers/systems.ts

export function registerSystemsHandlers(gateway: GatewayServer, config: BusinessSystemsConfig) {
  gateway.registerMethod('systems.list', async () => {
    return {
      systems: config.systems.map((s) => ({
        id: s.id,
        name: s.name,
        description: s.description,
        path: s.path,
        techStack: s.techStack,
      })),
    };
  });
}
```

#### 6.2.2 修改 `sessions.ensure` 处理器

```typescript
// 修改现有的 sessions.ensure 处理器

gateway.registerMethod('sessions.ensure', async (params) => {
  const { sessionKey, systemId, ...otherParams } = params;

  let cwd = params.cwd;

  // 如果指定了 systemId，从配置中获取对应的 cwd
  if (systemId && businessSystemsConfig) {
    const system = businessSystemsConfig.systems.find((s) => s.id === systemId);
    if (system) {
      cwd = path.resolve(smanwebRoot, system.path);
    } else {
      throw new GatewayError('SYSTEM_NOT_FOUND', `Business system not found: ${systemId}`);
    }
  }

  // 调用 ACP runtime
  return await acpRuntime.ensureSession({
    sessionKey,
    cwd,
    ...otherParams,
  });
});
```

---

## 7. 实现任务分解

### Phase 1: OpenClaw 改造（优先级：高）

| 任务 | 说明 | 依赖 |
|------|------|------|
| 1.1 | 新增 `business-systems.yaml` 配置加载模块 | - |
| 1.2 | 实现 `systems.list` Gateway API | 1.1 |
| 1.3 | 修改 `sessions.ensure` 支持 `systemId` 参数 | 1.1 |
| 1.4 | 单元测试 | 1.2, 1.3 |

### Phase 2: SmanWeb 打包改造（优先级：高）

| 任务 | 说明 | 依赖 |
|------|------|------|
| 2.1 | 创建 `resources/business-systems.yaml` 配置文件 | - |
| 2.2 | 实现 `scripts/bundle-business-systems.mjs` | 2.1 |
| 2.3 | 修改 `package.json` 构建脚本 | 2.2 |
| 2.4 | 测试完整打包流程 | 2.3 |

### Phase 3: 前端 UI 改造（优先级：高）

| 任务 | 说明 | 依赖 |
|------|------|------|
| 3.1 | 新增 `src/types/business-system.ts` 类型定义 | - |
| 3.2 | 新增 `src/stores/business-systems.ts` Store | 3.1 |
| 3.3 | 实现左侧会话栏树形结构组件 | 3.2 |
| 3.4 | 实现业务系统选择弹窗组件 | 3.2 |
| 3.5 | 修改聊天区域，显示当前业务系统 | 3.3, 3.4 |
| 3.6 | 实现会话自动命名（用户消息前6字） | 3.3 |

### Phase 4: 集成测试（优先级：中）

| 任务 | 说明 | 依赖 |
|------|------|------|
| 4.1 | E2E 测试：新建会话流程 | Phase 1-3 |
| 4.2 | E2E 测试：多业务系统切换 | Phase 1-3 |
| 4.3 | E2E 测试：会话持久化 | Phase 1-3 |

---

## 8. 错误处理

### 8.1 业务系统不存在

```typescript
// 当 systemId 对应的业务系统不存在时
{
  "ok": false,
  "error": {
    "code": "SYSTEM_NOT_FOUND",
    "message": "Business system not found: invalid-id"
  }
}
```

### 8.2 业务系统路径无效

```typescript
// 当业务系统路径不存在或无法访问时
{
  "ok": false,
  "error": {
    "code": "SYSTEM_PATH_INVALID",
    "message": "Business system path does not exist: /app/business-systems/erp/"
  }
}
```

### 8.3 前端处理

```typescript
// 显示友好的错误提示
if (error.code === 'SYSTEM_NOT_FOUND') {
  showToast('所选业务系统不存在，请刷新页面重试');
}
if (error.code === 'SYSTEM_PATH_INVALID') {
  showToast('业务系统路径异常，请联系管理员');
}
```

---

## 9. 后续扩展

### 9.1 业务系统上下文记忆

未来可以让 OpenClaw 记住每个业务系统的：
- 最近修改的文件
- 常用的代码模式
- 项目特定的约定

### 9.2 业务系统模板

支持从模板创建新业务系统，自动生成初始代码结构。

### 9.3 多用户隔离

如果需要支持多用户，可以在会话中增加 `userId` 维度。

---

## 10. 附录

### 10.1 文件清单

| 文件 | 说明 | 状态 |
|------|------|------|
| `resources/business-systems.yaml` | 业务系统配置文件 | 新增 |
| `scripts/bundle-business-systems.mjs` | 打包脚本 | 新增 |
| `src/types/business-system.ts` | 类型定义 | 新增 |
| `src/stores/business-systems.ts` | 前端 Store | 新增 |
| `src/components/SystemSelector.tsx` | 业务系统选择弹窗 | 新增 |
| `src/components/SessionTree.tsx` | 左侧会话树 | 新增 |
| `bundled/openclaw/business-systems.yaml` | 打包后的配置 | 构建生成 |
| `bundled/business-systems/` | 打包后的业务系统 | 构建生成 |

### 10.2 依赖关系

```
OpenClaw 改造 (1.1-1.4)
       ↓
SmanWeb 打包 (2.1-2.4)
       ↓
前端 UI (3.1-3.6)
       ↓
集成测试 (4.1-4.3)
```
