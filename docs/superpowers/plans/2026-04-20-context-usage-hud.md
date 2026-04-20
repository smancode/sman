# Context Usage HUD Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在聊天输入框上方添加一个实时上下文长度进度条 HUD，显示当前会话的 input tokens 占模型上下文窗口的百分比，带绿/黄/红颜色区分。

**Architecture:** 后端在 `chat.done` 事件中追加 `usage` 数据（input/output tokens），前端 store 接收并存储，新增 `ContextUsageBar` 组件从 settings 获取模型 maxInputTokens 并渲染进度条。颜色按占比分段：绿(<50%)、黄(50-75%)、红(>75%)。

**Tech Stack:** React + TypeScript + TailwindCSS + Zustand

---

## File Structure

| File | Action | Responsibility |
|------|--------|---------------|
| `server/model-capabilities.ts` | Modify | 为 `MODEL_CAPABILITIES_MAP` 补充 `maxInputTokens` |
| `server/claude-session.ts` | Modify | `chat.done` 追加完整 `usage` 字段 |
| `src/stores/chat.ts` | Modify | 新增 `contextUsage` 状态，接收 `chat.done` 的 usage 数据 |
| `src/features/chat/ContextUsageBar.tsx` | Create | 进度条 UI 组件 |
| `src/features/chat/ChatInput.tsx` | Modify | 引入 `ContextUsageBar` 组件 |
| `tests/server/model-capabilities.test.ts` | Modify | 补充 maxInputTokens 测试 |

---

## Chunk 1: Backend — Model Capabilities & Usage Data

### Task 1: 补充 MODEL_CAPABILITIES_MAP 的 maxInputTokens

**Files:**
- Modify: `server/model-capabilities.ts:32-62`
- Test: `tests/server/model-capabilities.test.ts`

**背景:** `DetectedCapabilities` 接口已有 `maxInputTokens?: number` 字段，但映射表里的模型都没有填值。需要补充常见模型的上下文窗口大小，作为 HUD 计算百分比的依据。

- [ ] **Step 1: 修改映射表，补充 maxInputTokens**

为每个模型添加 `maxInputTokens`:
- Claude 系列: 200000
- GPT-4o: 128000
- GPT-4o-mini: 128000
- GPT-4 Turbo: 128000
- GPT-4: 8192
- DeepSeek Chat: 64000
- DeepSeek Reasoner: 64000
- Qwen VL Plus: 32000
- Qwen VL Max: 32000
- LLaVA 系列: 4096
- BakLLaVA: 4096
- Qwen2-VL: 32000
- Llama 3.2 Vision: 128000
- MiniCPM-V: 4096

同时修改 `lookupByFuzzyName` 函数，为模糊匹配也返回 `maxInputTokens`:
- claude → 200000
- gpt-4 (不含 gpt-3) → 128000
- gpt-3 → 4096
- deepseek → 64000
- qwen-vl / qwen-vision → 32000
- glm + vision → 128000
- llava / bakllava / minicpm-v → 4096
- llama + vision → 128000

- [ ] **Step 2: 运行现有测试确保不破坏**

Run: `pnpm test tests/server/model-capabilities.test.ts`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add server/model-capabilities.ts
git commit -m "feat(model-capabilities): add maxInputTokens to all model entries"
```

### Task 2: chat.done 追加完整 usage 数据

**Files:**
- Modify: `server/claude-session.ts:1358-1368`

**背景:** 当前 `chat.done` 只发送 `cost`，`usage` 只在 `chat.context_warning` 中使用但没有随 `chat.done` 发送。需要把 `usage` 字段追加到 `chat.done` 消息中，让前端在每次对话完成后更新精确的 token 数。

- [ ] **Step 1: 修改 chat.done 消息，追加 usage**

在 `server/claude-session.ts` 的 `chat.done` 发送逻辑中（约第 1358-1368 行），将：
```typescript
wsSend(JSON.stringify({
  type: isError ? 'chat.error' : 'chat.done',
  sessionId,
  cost,
  usage: result.usage ? {
    inputTokens: result.usage.input_tokens,
    outputTokens: result.usage.output_tokens,
  } : undefined,
  ...(isError ? { ... } : {}),
}));
```

改为始终发送 `usage`（即使 undefined 也发送，前端可以据此清除旧数据）：
```typescript
wsSend(JSON.stringify({
  type: isError ? 'chat.error' : 'chat.done',
  sessionId,
  cost,
  usage: result.usage ? {
    inputTokens: result.usage.input_tokens,
    outputTokens: result.usage.output_tokens,
  } : null,
  ...(isError ? { ... } : {}),
}));
```

- [ ] **Step 2: Commit**

```bash
git add server/claude-session.ts
git commit -m "feat(chat): include usage data in chat.done message"
```

---

## Chunk 2: Frontend — Store & Component

### Task 3: chat store 新增 contextUsage 状态

**Files:**
- Modify: `src/stores/chat.ts`

**背景:** 当前 store 有 `contextWarning` 状态但只在警告时设置。需要新增一个持久的 `contextUsage` 状态，在每次 `chat.done` 时更新，供 HUD 组件读取。

- [ ] **Step 1: 在 ChatState 接口中添加 contextUsage**

在 `src/stores/chat.ts` 约第 111 行（contextWarning 下方）添加：
```typescript
/** Context usage stats for current session */
contextUsage: { inputTokens: number; outputTokens: number } | null;
```

- [ ] **Step 2: 在 store 初始状态中添加**

在 `useChatStore` 的初始对象中（约第 262 行）添加：
```typescript
contextUsage: null,
```

- [ ] **Step 3: 在 chat.done handler 中接收 usage**

在 `unsubDone` handler 中（约第 855 行），在 `set({...})` 之前添加 usage 处理：
```typescript
const usage = data.usage as { inputTokens: number; outputTokens: number } | null | undefined;
if (usage && get().currentSessionId === streamSessionId) {
  set({ contextUsage: usage });
}
```

- [ ] **Step 4: 在 switchSession 和 refresh 中清除/保留**

`switchSession` 中（约第 396 行）已有 `contextWarning: null`，旁边添加 `contextUsage: null`：
```typescript
set({
  currentSessionId: sessionId,
  messages: cached ?? [],
  streamingBlocks: targetBlocks,
  error: null,
  contextWarning: null,
  contextUsage: null,
  sending: isTargetSending,
  loading: !cached,
});
```

`refresh` 中（约第 1078 行）也添加 `contextUsage: null`：
```typescript
set({ messages: [], streamingBlocks: [], error: null, contextWarning: null, contextUsage: null, sending: false });
```

- [ ] **Step 5: Commit**

```bash
git add src/stores/chat.ts
git commit -m "feat(chat-store): add contextUsage state for HUD"
```

### Task 4: 创建 ContextUsageBar 组件

**Files:**
- Create: `src/features/chat/ContextUsageBar.tsx`

**背景:** 这是一个纯展示组件，从 settings store 读取模型 capabilities，从 chat store 读取 usage，计算百分比并渲染进度条。

- [ ] **Step 1: 创建组件文件**

```tsx
import { useMemo } from 'react';
import { useChatStore } from '@/stores/chat';
import { useSettingsStore } from '@/stores/settings';
import { cn } from '@/lib/utils';
import {
  Tooltip,
  TooltipContent,
  TooltipTrigger,
} from '@/components/ui/tooltip';

/** Fallback max input tokens when model capabilities are unknown */
const DEFAULT_MAX_INPUT_TOKENS = 200_000;

/** Known model max input tokens by fuzzy name matching */
function getMaxInputTokensByModel(model: string): number {
  const lower = model.toLowerCase();
  if (lower.includes('claude')) return 200_000;
  if (lower.includes('gpt-4') && !lower.includes('gpt-3')) return 128_000;
  if (lower.includes('gpt-3')) return 4_096;
  if (lower.includes('deepseek')) return 64_000;
  if (lower.includes('qwen-vl') || lower.includes('qwen-vision')) return 32_000;
  if (lower.includes('glm') && lower.includes('vision')) return 128_000;
  if (lower.includes('llava') || lower.includes('bakllava') || lower.includes('minicpm-v')) return 4_096;
  if (lower.includes('llama') && lower.includes('vision')) return 128_000;
  return DEFAULT_MAX_INPUT_TOKENS;
}

function getMaxInputTokens(model: string, capabilities?: { maxInputTokens?: number } | null): number {
  if (capabilities?.maxInputTokens) {
    return capabilities.maxInputTokens;
  }
  return getMaxInputTokensByModel(model);
}

function formatTokens(n: number): string {
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`;
  if (n >= 1_000) return `${(n / 1_000).toFixed(0)}K`;
  return String(n);
}

export function ContextUsageBar() {
  const contextUsage = useChatStore((s) => s.contextUsage);
  const settings = useSettingsStore((s) => s.settings);

  const { percentage, colorClass, inputTokens, maxTokens } = useMemo(() => {
    if (!contextUsage || contextUsage.inputTokens <= 0) {
      return { percentage: 0, colorClass: 'bg-green-500', inputTokens: 0, maxTokens: DEFAULT_MAX_INPUT_TOKENS };
    }

    const model = settings?.llm?.model || '';
    const capabilities = settings?.llm?.capabilities;
    const max = getMaxInputTokens(model, capabilities);
    const pct = Math.min(100, Math.round((contextUsage.inputTokens / max) * 100));

    let colorClass = 'bg-green-500';
    if (pct > 75) colorClass = 'bg-red-500';
    else if (pct >= 50) colorClass = 'bg-amber-500';

    return { percentage: pct, colorClass, inputTokens: contextUsage.inputTokens, maxTokens: max };
  }, [contextUsage, settings]);

  // Don't render if no session or no usage data
  if (!contextUsage || inputTokens <= 0) {
    return null;
  }

  const filledBlocks = Math.round(percentage / 10);
  const emptyBlocks = 10 - filledBlocks;

  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <div className="flex items-center gap-1.5 text-xs text-muted-foreground select-none cursor-default">
          <span className="text-[11px] font-medium">Context</span>
          <div className="flex gap-px">
            {Array.from({ length: filledBlocks }).map((_, i) => (
              <div key={`f-${i}`} className={cn('w-2 h-3.5 rounded-sm', colorClass)} />
            ))}
            {Array.from({ length: emptyBlocks }).map((_, i) => (
              <div key={`e-${i}`} className="w-2 h-3.5 rounded-sm bg-muted" />
            ))}
          </div>
          <span className="text-[11px] font-medium tabular-nums w-8 text-right">{percentage}%</span>
        </div>
      </TooltipTrigger>
      <TooltipContent side="top">
        <p className="text-xs">
          Input: {formatTokens(inputTokens)} / Max: {formatTokens(maxTokens)} ({percentage}%)
        </p>
      </TooltipContent>
    </Tooltip>
  );
}
```

- [ ] **Step 2: Commit**

```bash
git add src/features/chat/ContextUsageBar.tsx
git commit -m "feat(chat): add ContextUsageBar component"
```

### Task 5: ChatInput 引入 ContextUsageBar

**Files:**
- Modify: `src/features/chat/ChatInput.tsx`

**背景:** 在输入框的按钮行上方引入 `ContextUsageBar`，靠右对齐。

- [ ] **Step 1: 添加 import**

在 `src/features/chat/ChatInput.tsx` 的 import 区域添加：
```typescript
import { ContextUsageBar } from './ContextUsageBar';
```

- [ ] **Step 2: 在按钮行上方插入组件**

在 `ChatInput` 组件的 return 中，找到按钮行 `<div className="flex items-end gap-1.5">`（约第 314 行），在其上方添加：

```tsx
{/* Context Usage HUD */}
<div className="flex justify-end px-1 pb-1">
  <ContextUsageBar />
</div>
```

具体位置：在 `<div className="flex items-end gap-1.5">` 之前，即：
```tsx
        {/* Context Usage HUD */}
        <div className="flex justify-end px-1 pb-1">
          <ContextUsageBar />
        </div>

        <div className="flex items-end gap-1.5">
```

- [ ] **Step 3: Commit**

```bash
git add src/features/chat/ChatInput.tsx
git commit -m "feat(chat): integrate ContextUsageBar into ChatInput"
```

---

## Chunk 3: Testing & Verification

### Task 6: 编译检查

- [ ] **Step 1: 运行 TypeScript 编译**

Run: `pnpm tsc --noEmit`
Expected: No errors

- [ ] **Step 2: 如有错误则修复**

### Task 7: 运行相关测试

- [ ] **Step 1: 运行 model-capabilities 测试**

Run: `pnpm test tests/server/model-capabilities.test.ts`
Expected: PASS

- [ ] **Step 2: 运行 chat store 相关测试（如有）**

Run: `pnpm test tests/server/claude-session.test.ts`
Expected: PASS

### Task 8: 手动验证

- [ ] **Step 1: 启动开发服务器**

Run: `./dev.sh`

- [ ] **Step 2: 创建会话并发送消息**

- 打开桌面端
- 选择一个项目目录
- 发送一条消息
- 观察输入框上方是否出现 `Context ████░░░░░░ 41%` 样式的进度条
- 验证颜色随百分比变化

- [ ] **Step 3: 验证 tooltip**

- Hover 进度条
- 确认显示 `Input: 45K / Max: 200K (41%)` 格式的 tooltip

---

## Summary

| # | Task | Files |
|---|------|-------|
| 1 | 补充 maxInputTokens 到映射表 | `server/model-capabilities.ts` |
| 2 | chat.done 追加 usage | `server/claude-session.ts` |
| 3 | chat store 新增 contextUsage | `src/stores/chat.ts` |
| 4 | 创建 ContextUsageBar 组件 | `src/features/chat/ContextUsageBar.tsx` |
| 5 | ChatInput 引入组件 | `src/features/chat/ChatInput.tsx` |
| 6 | 编译检查 | - |
| 7 | 运行测试 | - |
| 8 | 手动验证 | - |
