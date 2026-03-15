# 多业务系统支持实现计划

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans (if harness does NOT have subagents) to implement this plan.

**Goal:** 用户可以在 UI 上选择不同的业务系统发起会话，每个会话绑定一个业务系统，Claude Code 在对应目录下工作，UI 上显示业务系统列表（通过左侧栏树形分组导航），会话信息持久化到本地存储。

**Architecture:**
- 前端新增 `BusinessSystemsStore` 管理业务系统和会话
- 左侧会话栏按业务系统分组（树形结构）
- 点击会话项时根据 systemId 切换会话
- 用户发送第一条消息时，根据消息内容自动命名会话（取前6字）

**Tech Stack:**
- **Frontend**: React 19 + TypeScript + Vite + Tailwind CSS + shadcn/ui
- **Backend**: Node.js + Express
- **Gateway**: OpenClaw (已内置)
- **Execution**: Claude Code (按需启动)
- **LSP**: jdtls + pyright

---

## Chunk 1: SmanWeb 打包脚本

### Task 1.1: 创建打包脚本

**Files:**
- Create: `scripts/bundle-business-systems.mjs`
- Create: `resources/business-systems.yaml`
- Modify: `package.json`

**Steps:**

- [ ] **Step 1: 创建配置文件**

Create: `resources/business-systems.yaml`

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

- [ ] **Step 2: 创建打包脚本**

Create: `scripts/bundle-business-systems.mjs`

```javascript
#!/usr/bin/env zx

/**
 * bundle-business-systems.mjs
 *
 * Copies configured business systems to bundled/business-systems/
 */

import 'zx/globals';
import {
  existsSync,
  mkdirSync,
  rmSync,
  readFileSync,
  writeFileSync,
  cpSync,
  statSync,
} from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import yaml from 'yaml';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(__dirname, '..');
const OUTPUT = path.join(ROOT, 'bundled', 'business-systems');
const CONFIG_FILE = path.join(ROOT, 'resources', 'business-systems.yaml');

echo`📦 Bundling business systems...`;

// Read configuration
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

// Clean output directory
if (existsSync(OUTPUT)) {
  rmSync(OUTPUT, { recursive: true });
}
mkdirSync(OUTPUT, { recursive: true });

// Helper: get directory size
function getDirSize(dirPath) {
  let size = 0;
  const files = require('fs').readdirSync(dirPath);
  for (const file of files) {
    const filePath = path.join(dirPath, file);
    const stats = statSync(filePath);
    if (stats.isDirectory()) {
      size += getDirSize(filePath);
    } else {
      size += stats.size;
    }
  }
  return size;
}

// Helper: format size
function formatSize(bytes) {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

// Copy each business system
let copiedCount = 0;
for (const system of config.systems) {
  const sourcePath = path.resolve(ROOT, system.path);
  const targetPath = path.join(OUTPUT, system.id);

  echo`   Copying ${system.id} from ${system.path}...`;

  if (!existsSync(sourcePath)) {
    echo`⚠️  Business system source not found: id=${system.id}, path=${system.path}`;
    continue;
  }

  cpSync(sourcePath, targetPath, { recursive: true });
  copiedCount++;
  echo`   ✓ ${system.name} (${system.id})`;
}

// Update config paths (打包后使用 bundled 路径)
const bundledConfig = {
  version: config.version,
  systems: config.systems.map((s) => ({
    id: s.id,
    name: s.name,
    description: s.description,
    techStack: s.techStack,
    path: `bundled/business-systems/${s.id}/`,
  })),
};

// Write bundled config to OpenClaw directory
const openclawConfigDir = path.join(ROOT, 'bundled', 'openclaw');
mkdirSync(openclawConfigDir, { recursive: true });
const openclawConfigFile = path.join(openclawConfigDir, 'business-systems.yaml');
writeFileSync(openclawConfigFile, yaml.stringify(bundledConfig));

echo``;
echo`✅ Business systems bundled: ${OUTPUT}`;
echo`   Systems: ${copiedCount}`;
if (copiedCount > 0) {
  echo`   Total size: ${formatSize(getDirSize(OUTPUT))}`;
}
for (const system of config.systems) {
  echo`     - ${system.name} (${system.id})`;
}
```

- [ ] **Step 3: 修改 package.json**

Modify: `package.json`

在 `scripts` 部分添加新命令：

```json
{
  "scripts": {
    "build": "vite build && zx scripts/bundle-openclaw.mjs && zx scripts/bundle-skills.mjs && zx scripts/bundle-claude-code.mjs && zx scripts/bundle-lsp.mjs && zx scripts/bundle-business-systems.mjs && tsc -p server/tsconfig.json",
    "bundle:business-systems": "zx scripts/bundle-business-systems.mjs"
  }
}
```

- [ ] **Step 4: 安装 yaml 依赖**

Run: `pnpm add yaml`

Expected: yaml 包安装成功

- [ ] **Step 5: 测试打包脚本**

Run: `pnpm bundle:business-systems`

Expected:
- 如果没有配置业务系统，显示 "No business systems configured, skipping."
- 如果有配置且路径存在，复制成功

- [ ] **Step 6: Commit**

```bash
git add scripts/bundle-business-systems.mjs resources/business-systems.yaml package.json pnpm-lock.yaml
git commit -m "feat(bundle): add business-systems bundling script"
```

---

## Chunk 2: 前端类型定义和 Store

### Task 2.1: 创建类型定义

**Files:**
- Create: `src/types/business-system.ts`

**Steps:**

- [ ] **Step 1: 创建类型文件**

Create: `src/types/business-system.ts`

```typescript
/**
 * 业务系统相关类型定义
 */

export interface BusinessSystem {
  id: string;
  name: string;
  description?: string;
  path: string;
  techStack?: string[];
}

export interface SystemSession {
  /** 会话唯一标识 */
  id: string;
  /** 关联的业务系统 ID */
  systemId: string;
  /** 会话名称 ("新会话" 或用户消息前6字) */
  label: string;
  /** 创建时间戳 */
  createdAt: number;
  /** 更新时间戳 */
  updatedAt?: number;
}

export interface SystemsListResponse {
  systems: BusinessSystem[];
}
```

- [ ] **Step 2: Commit**

```bash
git add src/types/business-system.ts
git commit -m "feat(types): add business-system type definitions"
```

### Task 2.2: 创建业务系统 Store

**Files:**
- Create: `src/stores/business-systems.ts`

**Steps:**

- [ ] **Step 1: 查看现有 gateway client 实现**

Read: `src/stores/gateway.ts` 了解如何获取 gateway client

- [ ] **Step 2: 创建 Store 文件**

Create: `src/stores/business-systems.ts`

```typescript
import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import type { BusinessSystem, SystemSession, SystemsListResponse } from '@/types/business-system';
import { getGatewayClient } from './gateway';

interface BusinessSystemsState {
  // 业务系统列表 (从 Gateway 获取)
  systems: BusinessSystem[];
  systemsLoading: boolean;
  systemsError: string | null;
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
  getSystemById: (systemId: string) => BusinessSystem | undefined;
}

export const useBusinessSystemsStore = create<BusinessSystemsState>()(
  persist(
    (set, get) => ({
      // 初始状态
      systems: [],
      systemsLoading: false,
      systemsError: null,
      sessions: [],
      currentSessionId: null,

      // 加载业务系统列表
      loadSystems: async () => {
        set({ systemsLoading: true, systemsError: null });
        try {
          const client = getGatewayClient();
          if (!client) {
            set({ systemsError: 'Gateway 未连接', systemsLoading: false });
            return;
          }

          const result = await client.rpc<SystemsListResponse>('systems.list', {});
          if (result?.systems) {
            set({ systems: result.systems, systemsLoading: false });
          } else {
            set({ systemsLoading: false });
          }
        } catch (error) {
          const message = error instanceof Error ? error.message : '加载业务系统失败';
          set({ systemsError: message, systemsLoading: false });
        }
      },

      // 创建新会话
      createSession: (systemId: string) => {
        const sessionId = `session-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
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

      // 根据 ID 获取业务系统
      getSystemById: (systemId: string) => {
        return get().systems.find((s) => s.id === systemId);
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

- [ ] **Step 3: Commit**

```bash
git add src/stores/business-systems.ts
git commit -m "feat(store): add business-systems store with session management"
```

---

## Chunk 3: UI 组件 - 业务系统选择弹窗

### Task 3.1: 创建 SystemSelector 组件

**Files:**
- Create: `src/components/SystemSelector.tsx`

**Steps:**

- [ ] **Step 1: 查看现有 shadcn/ui 组件**

Run: `ls src/components/ui/` 查看可用的 UI 组件

- [ ] **Step 2: 创建 SystemSelector 组件**

Create: `src/components/SystemSelector.tsx`

```tsx
import { useEffect } from 'react';
import { FolderOpen, Loader2, Tag } from 'lucide-react';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group';
import { Label } from '@/components/ui/label';
import { useBusinessSystemsStore } from '@/stores/business-systems';
import type { BusinessSystem } from '@/types/business-system';

interface SystemSelectorProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onSelect: (systemId: string) => void;
}

function SystemCard({
  system,
  selected,
  onSelect,
}: {
  system: BusinessSystem;
  selected: boolean;
  onSelect: () => void;
}) {
  return (
    <div
      className={`flex items-start gap-3 rounded-lg border p-4 cursor-pointer transition-colors ${
        selected
          ? 'border-primary bg-primary/5'
          : 'border-border hover:border-primary/50 hover:bg-muted/50'
      }`}
      onClick={onSelect}
    >
      <RadioGroupItem value={system.id} id={system.id} className="mt-1" />
      <div className="flex-1 min-w-0">
        <Label htmlFor={system.id} className="text-base font-medium cursor-pointer">
          {system.name}
        </Label>
        {system.description && (
          <p className="text-sm text-muted-foreground mt-1">{system.description}</p>
        )}
        <div className="flex flex-wrap items-center gap-2 mt-2 text-xs text-muted-foreground">
          <span className="flex items-center gap-1">
            <FolderOpen className="h-3 w-3" />
            {system.path}
          </span>
        </div>
        {system.techStack && system.techStack.length > 0 && (
          <div className="flex flex-wrap gap-1 mt-2">
            {system.techStack.map((tech) => (
              <span
                key={tech}
                className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full bg-muted text-xs"
              >
                <Tag className="h-2.5 w-2.5" />
                {tech}
              </span>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}

export function SystemSelector({ open, onOpenChange, onSelect }: SystemSelectorProps) {
  const {
    systems,
    systemsLoading,
    systemsError,
    loadSystems,
  } = useBusinessSystemsStore();

  const [selectedId, setSelectedId] = useState<string | null>(null);

  // 加载业务系统列表
  useEffect(() => {
    if (open && systems.length === 0) {
      loadSystems();
    }
  }, [open, systems.length, loadSystems]);

  const handleConfirm = () => {
    if (selectedId) {
      onSelect(selectedId);
      onOpenChange(false);
      setSelectedId(null);
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-[500px]">
        <DialogHeader>
          <DialogTitle>选择业务系统</DialogTitle>
          <DialogDescription>
            选择要操作的业务系统，开始新会话
          </DialogDescription>
        </DialogHeader>

        <div className="py-4">
          {systemsLoading ? (
            <div className="flex items-center justify-center py-8">
              <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
            </div>
          ) : systemsError ? (
            <div className="text-center py-8 text-destructive">
              <p>{systemsError}</p>
              <Button variant="outline" size="sm" className="mt-2" onClick={loadSystems}>
                重试
              </Button>
            </div>
          ) : systems.length === 0 ? (
            <div className="text-center py-8 text-muted-foreground">
              <p>没有可用的业务系统</p>
              <p className="text-sm mt-1">请联系管理员配置业务系统</p>
            </div>
          ) : (
            <RadioGroup value={selectedId ?? ''} onValueChange={setSelectedId}>
              <div className="space-y-3 max-h-[400px] overflow-y-auto">
                {systems.map((system) => (
                  <SystemCard
                    key={system.id}
                    system={system}
                    selected={selectedId === system.id}
                    onSelect={() => setSelectedId(system.id)}
                  />
                ))}
              </div>
            </RadioGroup>
          )}
        </div>

        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            取消
          </Button>
          <Button onClick={handleConfirm} disabled={!selectedId}>
            开始会话
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
```

- [ ] **Step 3: 检查是否有 RadioGroup 组件**

Run: `ls src/components/ui/radio-group.tsx`

如果没有，安装它：

Run: `npx shadcn@latest add radio-group`

- [ ] **Step 4: 添加缺失的 import**

上面的代码需要添加 `useState` import。

Modify: `src/components/SystemSelector.tsx`

在第一行添加:

```tsx
import { useEffect, useState } from 'react';
```

- [ ] **Step 5: Commit**

```bash
git add src/components/SystemSelector.tsx
git commit -m "feat(ui): add SystemSelector dialog component"
```

---

## Chunk 4: UI 组件 - 左侧会话树

### Task 4.1: 创建 SessionTree 组件

**Files:**
- Create: `src/components/SessionTree.tsx`

**Steps:**

- [ ] **Step 1: 创建 SessionTree 组件**

Create: `src/components/SessionTree.tsx`

```tsx
import { useState } from 'react';
import {
  ChevronDown,
  ChevronRight,
  FolderOpen,
  MessageSquare,
  Plus,
  Trash2,
} from 'lucide-react';
import { Button } from '@/components/ui/button';
import { ScrollArea } from '@/components/ui/scroll-area';
import {
  ContextMenu,
  ContextMenuContent,
  ContextMenuItem,
  ContextMenuTrigger,
} from '@/components/ui/context-menu';
import { useBusinessSystemsStore } from '@/stores/business-systems';
import type { BusinessSystem, SystemSession } from '@/types/business-system';
import { cn } from '@/lib/utils';

interface SessionTreeProps {
  onNewSession: () => void;
}

function SessionItem({
  session,
  isActive,
  onSelect,
  onDelete,
}: {
  session: SystemSession;
  isActive: boolean;
  onSelect: () => void;
  onDelete: () => void;
}) {
  return (
    <ContextMenu>
      <ContextMenuTrigger>
        <div
          className={cn(
            'flex items-center gap-2 px-3 py-1.5 rounded-md cursor-pointer text-sm',
            'hover:bg-muted/50 transition-colors',
            isActive && 'bg-primary/10 text-primary font-medium'
          )}
          onClick={onSelect}
        >
          <MessageSquare className="h-4 w-4 shrink-0 text-muted-foreground" />
          <span className="truncate">{session.label}</span>
        </div>
      </ContextMenuTrigger>
      <ContextMenuContent>
        <ContextMenuItem
          className="text-destructive focus:text-destructive"
          onClick={onDelete}
        >
          <Trash2 className="h-4 w-4 mr-2" />
          删除会话
        </ContextMenuItem>
      </ContextMenuContent>
    </ContextMenu>
  );
}

function SystemGroup({
  system,
  sessions,
  currentSessionId,
  expanded,
  onToggle,
  onSessionSelect,
  onSessionDelete,
}: {
  system: BusinessSystem;
  sessions: SystemSession[];
  currentSessionId: string | null;
  expanded: boolean;
  onToggle: () => void;
  onSessionSelect: (sessionId: string) => void;
  onSessionDelete: (sessionId: string) => void;
}) {
  const sessionCount = sessions.length;

  return (
    <div className="mb-1">
      {/* 系统标题行 */}
      <div
        className="flex items-center gap-1 px-2 py-1.5 rounded-md cursor-pointer hover:bg-muted/50 transition-colors"
        onClick={onToggle}
      >
        {expanded ? (
          <ChevronDown className="h-4 w-4 shrink-0 text-muted-foreground" />
        ) : (
          <ChevronRight className="h-4 w-4 shrink-0 text-muted-foreground" />
        )}
        <FolderOpen className="h-4 w-4 shrink-0 text-muted-foreground" />
        <span className="text-sm font-medium truncate flex-1">{system.name}</span>
        <span className="text-xs text-muted-foreground">({sessionCount})</span>
      </div>

      {/* 会话列表 */}
      {expanded && sessionCount > 0 && (
        <div className="ml-4 mt-1 space-y-0.5">
          {sessions.map((session) => (
            <SessionItem
              key={session.id}
              session={session}
              isActive={session.id === currentSessionId}
              onSelect={() => onSessionSelect(session.id)}
              onDelete={() => onSessionDelete(session.id)}
            />
          ))}
        </div>
      )}
    </div>
  );
}

export function SessionTree({ onNewSession }: SessionTreeProps) {
  const {
    systems,
    sessions,
    currentSessionId,
    getSessionsBySystem,
    switchSession,
    deleteSession,
  } = useBusinessSystemsStore();

  // 记录展开状态的系统 ID
  const [expandedSystems, setExpandedSystems] = useState<Set<string>>(new Set());

  const toggleSystem = (systemId: string) => {
    setExpandedSystems((prev) => {
      const next = new Set(prev);
      if (next.has(systemId)) {
        next.delete(systemId);
      } else {
        next.add(systemId);
      }
      return next;
    });
  };

  return (
    <div className="flex flex-col h-full">
      {/* 新建会话按钮 */}
      <div className="p-2 border-b">
        <Button
          variant="outline"
          size="sm"
          className="w-full"
          onClick={onNewSession}
        >
          <Plus className="h-4 w-4 mr-2" />
          新建会话
        </Button>
      </div>

      {/* 会话树 */}
      <ScrollArea className="flex-1">
        <div className="p-2">
          {systems.length === 0 ? (
            <div className="text-center py-8 text-sm text-muted-foreground">
              <p>没有可用的业务系统</p>
            </div>
          ) : (
            systems.map((system) => (
              <SystemGroup
                key={system.id}
                system={system}
                sessions={getSessionsBySystem(system.id)}
                currentSessionId={currentSessionId}
                expanded={expandedSystems.has(system.id)}
                onToggle={() => toggleSystem(system.id)}
                onSessionSelect={switchSession}
                onSessionDelete={deleteSession}
              />
            ))
          )}
        </div>
      </ScrollArea>
    </div>
  );
}
```

- [ ] **Step 2: 检查需要的 UI 组件**

Run: `ls src/components/ui/ | grep -E "(scroll-area|context-menu)"`

如果没有，安装它们：

Run: `npx shadcn@latest add scroll-area context-menu`

- [ ] **Step 3: Commit**

```bash
git add src/components/SessionTree.tsx
git commit -m "feat(ui): add SessionTree component with context menu"
```

---

## Chunk 5: 集成到主布局

### Task 5.1: 修改 App 布局集成新组件

**Files:**
- Modify: `src/App.tsx`
- Modify: `src/components/ChatView.tsx` 或类似组件

**Steps:**

- [ ] **Step 1: 查看现有 App 结构**

Read: `src/App.tsx`

- [ ] **Step 2: 查看现有聊天组件**

Glob: `src/components/*Chat*.tsx`

- [ ] **Step 3: 根据现有结构集成新组件**

这步需要根据实际代码结构来执行。一般需要：
1. 在主布局左侧添加 `SessionTree`
2. 添加 `SystemSelector` 弹窗状态管理
3. 连接 `SystemSelector` → 创建会话 → `sessions.ensure` RPC

以下是示例修改（需要根据实际代码调整）：

```tsx
// 在 App.tsx 或主布局组件中添加

import { useState } from 'react';
import { SessionTree } from '@/components/SessionTree';
import { SystemSelector } from '@/components/SystemSelector';
import { useBusinessSystemsStore } from '@/stores/business-systems';
import { getGatewayClient } from '@/stores/gateway';

function App() {
  const [selectorOpen, setSelectorOpen] = useState(false);
  const { createSession, getCurrentSession, getCurrentSystem } = useBusinessSystemsStore();

  const currentSession = getCurrentSession();
  const currentSystem = getCurrentSystem();

  const handleSystemSelect = async (systemId: string) => {
    // 创建本地会话
    const sessionId = createSession(systemId);

    // 通知 Gateway 确保会话
    const client = getGatewayClient();
    if (client) {
      try {
        await client.rpc('sessions.ensure', {
          sessionKey: sessionId,
          systemId: systemId,
        });
      } catch (error) {
        console.error('Failed to ensure session:', error);
      }
    }
  };

  return (
    <div className="flex h-screen">
      {/* 左侧会话树 */}
      <aside className="w-64 border-r">
        <SessionTree onNewSession={() => setSelectorOpen(true)} />
      </aside>

      {/* 主聊天区域 */}
      <main className="flex-1">
        {/* 顶部显示当前业务系统 */}
        {currentSession && currentSystem && (
          <header className="border-b px-4 py-2 flex items-center gap-2 text-sm">
            <FolderOpen className="h-4 w-4 text-muted-foreground" />
            <span className="font-medium">{currentSystem.name}</span>
            <span className="text-muted-foreground">›</span>
            <span>{currentSession.label}</span>
          </header>
        )}

        {/* 聊天内容 */}
        {/* ... 现有聊天组件 ... */}
      </main>

      {/* 业务系统选择弹窗 */}
      <SystemSelector
        open={selectorOpen}
        onOpenChange={setSelectorOpen}
        onSelect={handleSystemSelect}
      />
    </div>
  );
}
```

- [ ] **Step 4: 测试 UI 流程**

Run: `pnpm dev`

测试步骤：
1. 打开浏览器访问 http://localhost:5173
2. 点击"新建会话"按钮
3. 验证弹窗显示业务系统列表
4. 选择一个系统，验证会话创建成功
5. 验证左侧树显示新会话
6. 点击会话，验证切换功能

- [ ] **Step 5: Commit**

```bash
git add src/App.tsx
git commit -m "feat(ui): integrate SessionTree and SystemSelector into main layout"
```

---

## Chunk 6: 会话自动命名

### Task 6.1: 实现发送消息时自动命名会话

**Files:**
- Modify: `src/stores/chat.ts` 或消息发送相关组件

**Steps:**

- [ ] **Step 1: 查看现有消息发送逻辑**

Read: `src/stores/chat.ts` 查找消息发送函数

- [ ] **Step 2: 添加自动命名逻辑**

在用户发送第一条消息时，调用 `updateSessionLabel` 自动命名。

```typescript
// 在消息发送函数中添加

import { useBusinessSystemsStore } from './business-systems';

// 在发送消息时
const sendMessage = async (content: string) => {
  const { getCurrentSession, updateSessionLabel } = useBusinessSystemsStore.getState();
  const currentSession = getCurrentSession();

  // 如果是"新会话"，自动命名
  if (currentSession && currentSession.label === '新会话') {
    const newLabel = content.slice(0, 6) + (content.length > 6 ? '...' : '');
    updateSessionLabel(currentSession.id, newLabel);
  }

  // ... 继续发送消息
};
```

- [ ] **Step 3: 测试自动命名**

Run: `pnpm dev`

测试步骤：
1. 创建新会话
2. 发送一条消息
3. 验证会话名称自动更新为消息前6字

- [ ] **Step 4: Commit**

```bash
git add src/stores/chat.ts
git commit -m "feat(chat): auto-name session from first message"
```

---

## Chunk 7: OpenClaw Gateway API 扩展（依赖 OpenClaw 项目）

> **注意**: 此部分需要在 OpenClaw 项目中实现，SmanWeb 通过 RPC 调用。

### Task 7.1: 在 OpenClaw 中添加 systems.list API

**Files:**
- 需要在 OpenClaw 项目中修改

**说明:**
此任务需要在 OpenClaw Gateway 中实现 `systems.list` RPC 方法，返回配置的业务系统列表。

如果 OpenClaw 项目暂未支持，SmanWeb 可以使用 mock 数据进行前端开发。

**Mock 方案（临时）:**

如果 OpenClaw 暂不支持 `systems.list`，可以在前端添加 fallback：

```typescript
// 在 loadSystems 中添加 fallback
loadSystems: async () => {
  set({ systemsLoading: true, systemsError: null });
  try {
    const client = getGatewayClient();
    if (!client) {
      // Fallback: 使用本地配置
      const mockSystems: BusinessSystem[] = [
        {
          id: 'demo',
          name: '演示系统',
          description: '演示用业务系统',
          path: './demo/',
          techStack: ['typescript'],
        },
      ];
      set({ systems: mockSystems, systemsLoading: false });
      return;
    }

    // ... 原有逻辑
  }
}
```

---

## Chunk 8: 集成测试

### Task 8.1: E2E 测试 - 新建会话流程

**Files:**
- Create: `test-chat-e2e.cjs`

**Steps:**

- [ ] **Step 1: 查看现有 E2E 测试**

Read: `test-chat-e2e.cjs`

- [ ] **Step 2: 添加多业务系统测试用例**

根据现有测试格式，添加测试：
1. 创建会话并选择业务系统
2. 发送消息并验证自动命名
3. 切换会话
4. 删除会话

- [ ] **Step 3: 运行测试**

Run: `node test-chat-e2e.cjs`

Expected: 所有测试通过

- [ ] **Step 4: Commit**

```bash
git add test-chat-e2e.cjs
git commit -m "test: add multi-business-system e2e tests"
```

---

## 执行顺序总结

```
Chunk 1: 打包脚本 (Task 1.1)
    ↓
Chunk 2: 类型定义和 Store (Task 2.1 → 2.2)
    ↓
Chunk 3: SystemSelector 组件 (Task 3.1)
    ↓
Chunk 4: SessionTree 组件 (Task 4.1)
    ↓
Chunk 5: 集成到主布局 (Task 5.1)
    ↓
Chunk 6: 会话自动命名 (Task 6.1)
    ↓
Chunk 7: OpenClaw API 扩展 (Task 7.1) - 可选/依赖外部项目
    ↓
Chunk 8: 集成测试 (Task 8.1)
```

---

## 文件清单

| 文件 | 状态 | 说明 |
|------|------|------|
| `resources/business-systems.yaml` | 新增 | 业务系统配置文件 |
| `scripts/bundle-business-systems.mjs` | 新增 | 打包脚本 |
| `src/types/business-system.ts` | 新增 | 类型定义 |
| `src/stores/business-systems.ts` | 新增 | 业务系统 Store |
| `src/components/SystemSelector.tsx` | 新增 | 业务系统选择弹窗 |
| `src/components/SessionTree.tsx` | 新增 | 左侧会话树 |
| `src/App.tsx` | 修改 | 集成新组件 |
| `src/stores/chat.ts` | 修改 | 添加自动命名逻辑 |
| `package.json` | 修改 | 添加打包脚本命令 |
