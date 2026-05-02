# Shiki Web Worker 语法高亮插件 Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 创建一个兼容 `CodeHighlighterPlugin` 接口的 Web Worker 版 Shiki 语法高亮插件，替换 `@streamdown/code`，彻底消除主线程阻塞。

**Architecture:** Worker 线程负责 Shiki 初始化和 `codeToTokens` 调用，主线程通过 `postMessage` 发送代码、接收高亮结果。主线程插件实现 `CodeHighlighterPlugin` 接口，对 Streamdown 完全透明。

**Tech Stack:** Vite (内置 Web Worker 支持), Shiki, TypeScript, Streamdown

---

## File Structure

| 文件 | 职责 |
|------|------|
| `src/lib/shiki-worker.ts` | Web Worker 入口 — Shiki 初始化、高亮请求处理、缓存管理 |
| `src/lib/shiki-worker-client.ts` | 主线程客户端 — 封装 Worker 通信、提供 Promise 化 API |
| `src/lib/streamdown-plugins.ts` | 修改 — 用 Worker 版插件替换 `@streamdown/code` |
| `tests/client/shiki-worker.test.ts` | 测试 — Worker 客户端的单元测试 |

---

## Chunk 1: Web Worker 实现

### Task 1: Worker 线程 (`src/lib/shiki-worker.ts`)

**Files:**
- Create: `src/lib/shiki-worker.ts`

**Context:**
- Worker 线程需要导入 Shiki 的 `createHighlighter` 和 `createJavaScriptRegexEngine`
- 需要维护语言高亮器缓存（`Map<themeKey, Highlighter>`）
- 需要维护结果缓存（`Map<cacheKey, TokensResult>`）
- 通过 `self.onmessage` 接收请求，通过 `self.postMessage` 返回结果

**接口设计：**

请求消息格式：
```typescript
interface HighlightRequest {
  id: string;           // 唯一请求 ID，用于回调匹配
  code: string;
  language: string;
  themes: [string, string];
}
```

响应消息格式：
```typescript
interface HighlightResponse {
  id: string;
  result: TokensResult | null;
  error?: string;
}
```

- [ ] **Step 1: 创建 Worker 文件骨架**

```typescript
import { createHighlighter, type TokensResult, type BundledLanguage } from 'shiki';
import { createJavaScriptRegexEngine } from 'shiki/engine/javascript';

const engine = createJavaScriptRegexEngine({ forgiving: true });

// 缓存: themeKey -> Highlighter promise
const highlighterCache = new Map<string, ReturnType<typeof createHighlighter>>();
// 缓存: cacheKey -> TokensResult
const resultCache = new Map<string, TokensResult>();

function getCacheKey(code: string, language: string, themes: [string, string]): string {
  const prefix = code.slice(0, 100);
  const suffix = code.length > 100 ? code.slice(-100) : '';
  return `${language}:${themes[0]}:${themes[1]}:${code.length}:${prefix}:${suffix}`;
}

function getThemeKey(themes: [string, string]): string {
  return `${themes[0]}-${themes[1]}`;
}

async function ensureHighlighter(themes: [string, string]) {
  const key = getThemeKey(themes);
  if (!highlighterCache.has(key)) {
    highlighterCache.set(key, createHighlighter({ themes, langs: [], engine }));
  }
  return highlighterCache.get(key)!;
}

self.onmessage = async (event) => {
  const { id, code, language, themes } = event.data;
  
  try {
    const cacheKey = getCacheKey(code, language, themes);
    
    // 检查缓存
    if (resultCache.has(cacheKey)) {
      self.postMessage({ id, result: resultCache.get(cacheKey) });
      return;
    }
    
    const highlighter = await ensureHighlighter(themes);
    
    // 按需加载语言
    const lang = language as BundledLanguage;
    if (!highlighter.getLoadedLanguages().includes(lang)) {
      await highlighter.loadLanguage(lang);
    }
    
    const result = highlighter.codeToTokens(code, {
      lang,
      themes: { light: themes[0], dark: themes[1] },
    });
    
    resultCache.set(cacheKey, result);
    self.postMessage({ id, result });
  } catch (err) {
    self.postMessage({ 
      id, 
      result: null, 
      error: err instanceof Error ? err.message : String(err) 
    });
  }
};
```

- [ ] **Step 2: Commit**

```bash
git add src/lib/shiki-worker.ts
git commit -m "feat(worker): create Shiki syntax highlighting Web Worker"
```

---

## Chunk 2: Worker 客户端

### Task 2: 主线程客户端 (`src/lib/shiki-worker-client.ts`)

**Files:**
- Create: `src/lib/shiki-worker-client.ts`

**Context:**
- 封装 Worker 的创建和通信
- 提供 Promise 化的 `highlight()` 方法
- 管理请求 ID 和回调映射
- 单例模式，避免重复创建 Worker

- [ ] **Step 1: 创建客户端文件**

```typescript
import type { TokensResult } from 'shiki';

interface PendingRequest {
  resolve: (result: TokensResult) => void;
  reject: (error: Error) => void;
}

let worker: Worker | null = null;
let requestId = 0;
const pendingRequests = new Map<string, PendingRequest>();

function getWorker(): Worker {
  if (!worker) {
    worker = new Worker(new URL('./shiki-worker.ts', import.meta.url), {
      type: 'module',
    });
    worker.onmessage = (event) => {
      const { id, result, error } = event.data;
      const pending = pendingRequests.get(id);
      if (!pending) return;
      pendingRequests.delete(id);
      if (error) {
        pending.reject(new Error(error));
      } else {
        pending.resolve(result);
      }
    };
    worker.onerror = (err) => {
      console.error('[ShikiWorker] Worker error:', err);
    };
  }
  return worker;
}

export function highlightCode(
  code: string,
  language: string,
  themes: [string, string],
): Promise<TokensResult> {
  return new Promise((resolve, reject) => {
    const id = `${++requestId}`;
    pendingRequests.set(id, { resolve, reject });
    getWorker().postMessage({ id, code, language, themes });
  });
}

export function terminateWorker(): void {
  if (worker) {
    worker.terminate();
    worker = null;
    pendingRequests.clear();
  }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/lib/shiki-worker-client.ts
git commit -m "feat(worker): create Shiki Worker client for main thread"
```

---

## Chunk 3: Streamdown 插件适配

### Task 3: 替换 `streamdown-plugins.ts`

**Files:**
- Modify: `src/lib/streamdown-plugins.ts`

**Context:**
- 当前实现直接导入 `@streamdown/code`，在主线程初始化 Shiki
- 新实现使用 Worker 客户端，但保持 `CodeHighlighterPlugin` 接口兼容
- `highlight()` 方法需要支持同步返回（缓存命中）和异步回调

**关键设计决策：**
- Worker 高亮是异步的，但 `CodeHighlighterPlugin.highlight()` 的回调模式天然支持异步
- 首次高亮返回 `null`（尚未就绪），Worker 完成后通过回调返回结果
- 需要维护一个内存缓存，避免重复请求 Worker

- [ ] **Step 1: 重写 `streamdown-plugins.ts`**

```typescript
/**
 * Streamdown plugin configuration with Web Worker-based Shiki highlighting.
 *
 * Replaces @streamdown/code with a custom Worker implementation that offloads
 * Shiki initialization and codeToTokens() to a background thread, eliminating
 * main-thread blocking during syntax highlighting.
 */

import { useState, useEffect, useRef } from 'react';
import type { CodeHighlighterPlugin } from 'streamdown';
import type { TokensResult } from 'shiki';
import { highlightCode } from './shiki-worker-client';

const themes: [string, string] = ['one-light', 'one-dark-pro'];

// In-memory cache for highlighted results (main thread)
const resultCache = new Map<string, TokensResult>();
const pendingCallbacks = new Map<string, Set<(result: TokensResult) => void>>();

function getCacheKey(code: string, language: string): string {
  const prefix = code.slice(0, 100);
  const suffix = code.length > 100 ? code.slice(-100) : '';
  return `${language}:${themes[0]}:${themes[1]}:${code.length}:${prefix}:${suffix}`;
}

// Supported languages cache (populated on first use)
let supportedLanguages: Set<string> | null = null;

// Create the plugin instance
function createWorkerPlugin(): CodeHighlighterPlugin {
  return {
    name: 'shiki-worker',
    type: 'code-highlighter',

    supportsLanguage(language: string): boolean {
      // Shiki supports most languages, be permissive
      return true;
    },

    getSupportedLanguages(): string[] {
      return [];
    },

    getThemes() {
      return themes;
    },

    highlight({ code, language }, callback) {
      const cacheKey = getCacheKey(code, language);

      // Cache hit — return immediately
      if (resultCache.has(cacheKey)) {
        return resultCache.get(cacheKey)!;
      }

      // Already pending — add callback to queue
      if (pendingCallbacks.has(cacheKey)) {
        if (callback) {
          pendingCallbacks.get(cacheKey)!.add(callback);
        }
        return null;
      }

      // New request — dispatch to worker
      const callbacks = new Set<(result: TokensResult) => void>();
      if (callback) {
        callbacks.add(callback);
      }
      pendingCallbacks.set(cacheKey, callbacks);

      highlightCode(code, language, themes)
        .then((result) => {
          resultCache.set(cacheKey, result);
          const cbs = pendingCallbacks.get(cacheKey);
          pendingCallbacks.delete(cacheKey);
          if (cbs) {
            for (const cb of cbs) {
              try { cb(result); } catch { /* ignore */ }
            }
          }
        })
        .catch((err) => {
          console.error('[ShikiWorker] Highlight failed:', err);
          pendingCallbacks.delete(cacheKey);
        });

      return null;
    },
  };
}

let cachedPlugin: CodeHighlighterPlugin | null = null;
let loadPromise: Promise<CodeHighlighterPlugin> | null = null;

function loadCodePlugin(): Promise<CodeHighlighterPlugin> {
  if (!loadPromise) {
    loadPromise = Promise.resolve(createWorkerPlugin());
    cachedPlugin = await loadPromise;
  }
  return loadPromise;
}

/**
 * Hook that returns the Shiki code highlighter plugin.
 * Returns undefined until the plugin is loaded, then the cached instance.
 */
export function useCodePlugin(): CodeHighlighterPlugin | undefined {
  const [plugin, setPlugin] = useState<CodeHighlighterPlugin | undefined>(cachedPlugin ?? undefined);

  useEffect(() => {
    if (cachedPlugin) {
      setPlugin(cachedPlugin);
      return;
    }
    loadCodePlugin().then(setPlugin);
  }, []);

  return plugin;
}
```

- [ ] **Step 2: Commit**

```bash
git add src/lib/streamdown-plugins.ts
git commit -m "feat(worker): replace @streamdown/code with Web Worker Shiki plugin"
```

---

## Chunk 4: 测试

### Task 4: Worker 客户端测试

**Files:**
- Create: `tests/client/shiki-worker.test.ts`

- [ ] **Step 1: 编写测试**

```typescript
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

// Mock Worker globally
class MockWorker {
  onmessage: ((event: MessageEvent) => void) | null = null;
  onerror: ((err: ErrorEvent) => void) | null = null;
  postedMessages: any[] = [];

  postMessage(data: any) {
    this.postedMessages.push(data);
  }

  terminate() {}

  // Simulate worker response
  simulateResponse(data: any) {
    if (this.onmessage) {
      this.onmessage(new MessageEvent('message', { data }));
    }
  }
}

describe('Shiki Worker Client', () => {
  let mockWorker: MockWorker;

  beforeEach(() => {
    mockWorker = new MockWorker();
    global.Worker = vi.fn(() => mockWorker) as any;
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('should send highlight request to worker', async () => {
    const { highlightCode } = await import('../../src/lib/shiki-worker-client');
    
    const promise = highlightCode('const x = 1;', 'typescript', ['one-light', 'one-dark-pro']);
    
    expect(mockWorker.postedMessages).toHaveLength(1);
    expect(mockWorker.postedMessages[0]).toMatchObject({
      code: 'const x = 1;',
      language: 'typescript',
      themes: ['one-light', 'one-dark-pro'],
    });
    expect(mockWorker.postedMessages[0].id).toBeDefined();
  });

  it('should resolve with worker response', async () => {
    const { highlightCode } = await import('../../src/lib/shiki-worker-client');
    
    const promise = highlightCode('const x = 1;', 'typescript', ['one-light', 'one-dark-pro']);
    
    const requestId = mockWorker.postedMessages[0].id;
    mockWorker.simulateResponse({
      id: requestId,
      result: { tokens: [] },
    });
    
    const result = await promise;
    expect(result).toEqual({ tokens: [] });
  });

  it('should reject on worker error', async () => {
    const { highlightCode } = await import('../../src/lib/shiki-worker-client');
    
    const promise = highlightCode('const x = 1;', 'typescript', ['one-light', 'one-dark-pro']);
    
    const requestId = mockWorker.postedMessages[0].id;
    mockWorker.simulateResponse({
      id: requestId,
      result: null,
      error: 'Language not found',
    });
    
    await expect(promise).rejects.toThrow('Language not found');
  });
});
```

- [ ] **Step 2: 运行测试**

```bash
pnpm test tests/client/shiki-worker.test.ts
```

Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add tests/client/shiki-worker.test.ts
git commit -m "test(worker): add Shiki Worker client unit tests"
```

---

## Chunk 5: 集成验证

### Task 5: 端到端验证

- [ ] **Step 1: 类型检查**

```bash
pnpm exec tsc --noEmit
```

Expected: 零错误

- [ ] **Step 2: 运行全部测试**

```bash
pnpm test
```

Expected: 全部通过

- [ ] **Step 3: 开发模式验证**

```bash
pnpm dev
```

打开浏览器，发送包含代码块的消息，验证：
1. 代码块正常高亮
2. 流式输出时 UI 不卡顿
3. 大代码块（>100 行）渲染流畅

- [ ] **Step 4: Commit**

```bash
git commit -m "feat(worker): complete Shiki Web Worker integration"
```

---

## 风险与回滚

| 风险 | 缓解措施 |
|------|----------|
| Worker 初始化失败 | 保留 `@streamdown/code` 作为 fallback |
| 语言加载延迟 | Worker 内按需加载，首次稍慢但后续缓存 |
| 缓存膨胀 | 限制缓存大小（可后续添加 LRU） |

**回滚方案：** 恢复 `src/lib/streamdown-plugins.ts` 到使用 `@streamdown/code` 的版本。
