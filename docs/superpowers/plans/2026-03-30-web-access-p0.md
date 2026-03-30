# Web Access P0 — CdpEngine + MCP Server 实施计划

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现 CdpEngine（直连用户 Chrome 的 CDP 引擎）并通过进程内 MCP Server 注册给 Claude Agent，让用户通过聊天自然语言操控企业网站。

**Architecture:** CdpEngine 通过 CDP WebSocket 直连用户已运行的 Chrome，天然复用 Cookie/Session。通过 SDK 的 `createSdkMcpServer` + `tool` API 注册进程内 MCP Server，注入到 Claude Agent SDK 的 `mcpServers` 选项。所有操作默认 30 秒超时，tab 按会话隔离。

**Tech Stack:** TypeScript, Chrome DevTools Protocol (CDP), `@anthropic-ai/claude-agent-sdk` (createSdkMcpServer/tool), Node.js 22+ 原生 WebSocket, vitest

---

## File Structure

```
server/
  web-access/
    index.ts                  ← 桶文件，导出 WebAccessService + 类型
    browser-engine.ts         ← BrowserEngine 接口 + TabContext 类型
    cdp-engine.ts             ← CdpEngine 实现
    web-access-service.ts     ← 服务层（单例，引擎选择，会话管理）
    mcp-server.ts             ← createSdkMcpServer + tool 定义
  claude-session.ts           ← 修改：注入 web-access MCP Server
  mcp-config.ts               ← 修改：buildMcpServers 返回类型扩展

tests/server/web-access/
  cdp-engine.test.ts          ← CdpEngine 单元测试
  web-access-service.test.ts  ← WebAccessService 单元测试
  mcp-server.test.ts          ← MCP Server 工具测试
```

---

## Chunk 1: BrowserEngine Interface + CdpEngine Core

### Task 1: Define BrowserEngine interface

**Files:**
- Create: `server/web-access/browser-engine.ts`

- [ ] **Step 1: Write the interface file**

```typescript
// server/web-access/browser-engine.ts

/** 操作超时错误 */
export class BrowserTimeoutError extends Error {
  constructor(method: string, timeoutMs: number) {
    super(`Browser operation timed out: ${method} (${timeoutMs}ms)`);
    this.name = 'BrowserTimeoutError';
  }
}

/** 连接断开错误 */
export class BrowserConnectionError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'BrowserConnectionError';
  }
}

/** 需要登录错误 */
export class LoginRequiredError extends Error {
  constructor(
    message: string,
    public readonly loginUrl: string,
  ) {
    super(message);
    this.name = 'LoginRequiredError';
  }
}

/** 等待条件 */
export type WaitCondition =
  | { type: 'selector'; selector: string }
  | { type: 'navigation'; timeoutMs?: number }
  | { type: 'idle'; idleMs?: number };

/** 操作结果中的页面快照 */
export interface PageSnapshot {
  title: string;
  url: string;
  /** 可访问性树的文本内容 */
  accessibilityTree: string;
  /** 是否检测到登录页 */
  isLoginPage: boolean;
  /** 如果是登录页，登录 URL */
  loginUrl?: string;
}

/** Tab 上下文 */
export interface TabContext {
  tabId: string;
  url: string;
  title: string;
  createdAt: number;
  lastUsedAt: number;
}

/** 浏览器引擎统一接口 */
export interface BrowserEngine {
  /** 检测引擎是否可用 */
  isAvailable(): Promise<boolean>;

  /** 创建新标签页并导航到指定 URL */
  newTab(url: string, sessionId?: string): Promise<TabContext>;

  /** 在标签页中导航到新 URL */
  navigate(tabId: string, url: string): Promise<PageSnapshot>;

  /** 获取当前页面的可访问性快照 */
  snapshot(tabId: string): Promise<PageSnapshot>;

  /** 截图 */
  screenshot(tabId: string): Promise<Buffer>;

  /** 点击元素 */
  click(tabId: string, selector: string): Promise<PageSnapshot>;

  /** 填写表单字段 */
  fill(tabId: string, selector: string, value: string): Promise<PageSnapshot>;

  /** 按键 */
  pressKey(tabId: string, key: string): Promise<PageSnapshot>;

  /** 执行 JavaScript */
  evaluate(tabId: string, expression: string): Promise<{ success: boolean; result?: unknown; error?: string }>;

  /** 等待条件满足 */
  waitFor(tabId: string, condition: WaitCondition, timeoutMs?: number): Promise<void>;

  /** 列出所有标签页 */
  listTabs(): Promise<TabContext[]>;

  /** 关闭标签页 */
  closeTab(tabId: string): Promise<void>;

  /** 关闭所有标签页并清理资源 */
  dispose(): Promise<void>;
}
```

- [ ] **Step 2: Commit**

```bash
git add server/web-access/browser-engine.ts
git commit -m "feat(web-access): define BrowserEngine interface and error types"
```

---

### Task 2: Write CdpEngine unit tests

**Files:**
- Create: `tests/server/web-access/cdp-engine.test.ts`

- [ ] **Step 1: Write failing tests for CdpEngine core**

Tests will mock the WebSocket and CDP protocol layer. Key test cases:

```typescript
// tests/server/web-access/cdp-engine.test.ts
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { CdpEngine } from '../../../server/web-access/cdp-engine.js';
import { BrowserTimeoutError, BrowserConnectionError } from '../../../server/web-access/browser-engine.js';

// Mock WebSocket
function createMockWebSocket() {
  const listeners: Record<string, Function[]> = {};
  const sent: any[] = [];
  return {
    readyState: 1, // OPEN
    send: vi.fn((data: string) => sent.push(JSON.parse(data))),
    close: vi.fn(),
    addEventListener: vi.fn((event: string, handler: Function) => {
      listeners[event] = listeners[event] || [];
      listeners[event].push(handler);
    }),
    removeEventListener: vi.fn(),
    on: vi.fn((event: string, handler: Function) => {
      listeners[event] = listeners[event] || [];
      listeners[event].push(handler);
    }),
    // Test helpers
    __listeners: listeners,
    __sent: sent,
    __trigger(event: string, data: any) {
      for (const handler of (listeners[event] || [])) handler({ data: JSON.stringify(data) });
    },
    __setReadyState(state: number) { this.readyState = state; },
  };
}

describe('CdpEngine', () => {
  describe('isAvailable', () => {
    it('should return false when no Chrome debug port found', async () => {
      const engine = new CdpEngine();
      // Mock discoverChromePort to return null
      vi.spyOn(engine as any, 'discoverChromePort').mockResolvedValue(null);
      expect(await engine.isAvailable()).toBe(false);
    });

    it('should return true when Chrome debug port found', async () => {
      const engine = new CdpEngine();
      vi.spyOn(engine as any, 'discoverChromePort').mockResolvedValue({ port: 9222, wsPath: null });
      vi.spyOn(engine as any, 'connectToChrome').mockResolvedValue(undefined);
      expect(await engine.isAvailable()).toBe(true);
    });
  });

  describe('newTab', () => {
    it('should create a background tab and return TabContext', async () => {
      const engine = new CdpEngine();
      const mockWs = createMockWebSocket();
      vi.spyOn(engine as any, 'ensureConnected').mockResolvedValue(mockWs);

      // Simulate CDP response for Target.createTarget
      const promise = engine.newTab('https://jira.example.com');
      // Find the pending CDP command and respond
      const cdpMsg = mockWs.__sent.find((m: any) => m.method === 'Target.createTarget');
      mockWs.__trigger('message', { id: cdpMsg.id, result: { targetId: 'tab-123' } });

      const tab = await promise;
      expect(tab.tabId).toBe('tab-123');
      expect(tab.url).toBe('https://jira.example.com');
    });

    it('should throw BrowserTimeoutError on timeout', async () => {
      const engine = new CdpEngine({ defaultTimeoutMs: 100 });
      const mockWs = createMockWebSocket();
      vi.spyOn(engine as any, 'ensureConnected').mockResolvedValue(mockWs);

      await expect(engine.newTab('https://example.com')).rejects.toThrow(BrowserTimeoutError);
    });
  });

  describe('navigate', () => {
    it('should navigate and return page snapshot', async () => {
      const engine = new CdpEngine();
      const mockWs = createMockWebSocket();
      vi.spyOn(engine as any, 'ensureConnected').mockResolvedValue(mockWs);
      vi.spyOn(engine as any, 'ensureSession').mockResolvedValue('session-1');

      // Setup: engine needs to know about the tab
      (engine as any).tabs.set('tab-123', { tabId: 'tab-123', url: '', title: '', createdAt: Date.now(), lastUsedAt: Date.now() });

      const promise = engine.navigate('tab-123', 'https://jira.example.com/browse/PROJ-123');

      // Respond to Page.navigate
      const navMsg = mockWs.__sent.find((m: any) => m.method === 'Page.navigate');
      if (navMsg) mockWs.__trigger('message', { id: navMsg.id, result: { frameId: 'frame-1' } });

      // Respond to Runtime.evaluate (readyState check)
      const evalMsg = mockWs.__sent.find((m: any) => m.method === 'Runtime.evaluate' && m.params?.expression?.includes('readyState'));
      if (evalMsg) mockWs.__trigger('message', { id: evalMsg.id, result: { result: { value: 'complete' } } });

      // Respond to snapshot evaluate
      const snapMsg = mockWs.__sent.find((m: any) => m.method === 'Runtime.evaluate' && m.params?.expression?.includes('document.title'));
      if (snapMsg) mockWs.__trigger('message', { id: snapMsg.id, result: { result: { value: 'PROJ-123 - Jira' } } });

      const snapshot = await promise;
      expect(snapshot.url).toContain('jira.example.com');
    });
  });

  describe('click', () => {
    it('should click element using JS click first', async () => {
      const engine = new CdpEngine();
      const mockWs = createMockWebSocket();
      vi.spyOn(engine as any, 'ensureConnected').mockResolvedValue(mockWs);
      vi.spyOn(engine as any, 'ensureSession').mockResolvedValue('session-1');
      (engine as any).tabs.set('tab-123', { tabId: 'tab-123', url: '', title: '', createdAt: Date.now(), lastUsedAt: Date.now() });

      const promise = engine.click('tab-123', '#create-issue');

      // Respond to evaluate (click JS)
      const clickMsg = mockWs.__sent.find((m: any) => m.method === 'Runtime.evaluate' && m.params?.expression?.includes('click'));
      if (clickMsg) mockWs.__trigger('message', { id: clickMsg.id, result: { result: { value: true } } });

      // Respond to snapshot evaluate
      const snapMsg = mockWs.__sent.find((m: any) => m.method === 'Runtime.evaluate' && m.params?.expression?.includes('document.title'));
      if (snapMsg) mockWs.__trigger('message', { id: snapMsg.id, result: { result: { value: 'Create Issue' } } });

      const snapshot = await promise;
      expect(snapshot.title).toBe('Create Issue');
    });
  });

  describe('fill', () => {
    it('should set input value and dispatch events', async () => {
      const engine = new CdpEngine();
      const mockWs = createMockWebSocket();
      vi.spyOn(engine as any, 'ensureConnected').mockResolvedValue(mockWs);
      vi.spyOn(engine as any, 'ensureSession').mockResolvedValue('session-1');
      (engine as any).tabs.set('tab-123', { tabId: 'tab-123', url: '', title: '', createdAt: Date.now(), lastUsedAt: Date.now() });

      const promise = engine.fill('tab-123', '#summary', 'Fix login bug');

      const fillMsg = mockWs.__sent.find((m: any) => m.method === 'Runtime.evaluate' && m.params?.expression?.includes('#summary'));
      if (fillMsg) mockWs.__trigger('message', { id: fillMsg.id, result: { result: { value: true } } });

      const snapMsg = mockWs.__sent.find((m: any) => m.method === 'Runtime.evaluate' && m.params?.expression?.includes('document.title'));
      if (snapMsg) mockWs.__trigger('message', { id: snapMsg.id, result: { result: { value: 'Create Issue' } } });

      const snapshot = await promise;
      expect(snapshot).toBeDefined();
    });
  });

  describe('screenshot', () => {
    it('should return a Buffer from Page.captureScreenshot', async () => {
      const engine = new CdpEngine();
      const mockWs = createMockWebSocket();
      vi.spyOn(engine as any, 'ensureConnected').mockResolvedValue(mockWs);
      vi.spyOn(engine as any, 'ensureSession').mockResolvedValue('session-1');
      (engine as any).tabs.set('tab-123', { tabId: 'tab-123', url: '', title: '', createdAt: Date.now(), lastUsedAt: Date.now() });

      const promise = engine.screenshot('tab-123');

      const ssMsg = mockWs.__sent.find((m: any) => m.method === 'Page.captureScreenshot');
      if (ssMsg) mockWs.__trigger('message', { id: ssMsg.id, result: { data: Buffer.from('test-image').toString('base64') } });

      const result = await promise;
      expect(Buffer.isBuffer(result)).toBe(true);
    });
  });

  describe('closeTab', () => {
    it('should close tab and remove from tabs map', async () => {
      const engine = new CdpEngine();
      const mockWs = createMockWebSocket();
      vi.spyOn(engine as any, 'ensureConnected').mockResolvedValue(mockWs);
      (engine as any).tabs.set('tab-123', { tabId: 'tab-123', url: '', title: '', createdAt: Date.now(), lastUsedAt: Date.now() });
      (engine as any).sessions.delete('tab-123');

      const promise = engine.closeTab('tab-123');

      const closeMsg = mockWs.__sent.find((m: any) => m.method === 'Target.closeTarget');
      if (closeMsg) mockWs.__trigger('message', { id: closeMsg.id, result: {} });

      await promise;
      expect((engine as any).tabs.has('tab-123')).toBe(false);
    });
  });

  describe('evaluate', () => {
    it('should return structured result', async () => {
      const engine = new CdpEngine();
      const mockWs = createMockWebSocket();
      vi.spyOn(engine as any, 'ensureConnected').mockResolvedValue(mockWs);
      vi.spyOn(engine as any, 'ensureSession').mockResolvedValue('session-1');
      (engine as any).tabs.set('tab-123', { tabId: 'tab-123', url: '', title: '', createdAt: Date.now(), lastUsedAt: Date.now() });

      const promise = engine.evaluate('tab-123', 'document.querySelectorAll(".issue").length');

      const evalMsg = mockWs.__sent.find((m: any) => m.method === 'Runtime.evaluate');
      if (evalMsg) mockWs.__trigger('message', { id: evalMsg.id, result: { result: { value: 5 } } });

      const result = await promise;
      expect(result.success).toBe(true);
      expect(result.result).toBe(5);
    });
  });

  describe('login detection', () => {
    it('should detect login page from snapshot', async () => {
      const engine = new CdpEngine();
      const result = (engine as any).detectLoginPage({
        title: 'Log in - Jira',
        url: 'https://jira.example.com/login',
        accessibilityTree: '<form><input type="password" /><button>Log in</button></form>',
      });
      expect(result.isLoginPage).toBe(true);
    });

    it('should not flag normal pages as login', async () => {
      const engine = new CdpEngine();
      const result = (engine as any).detectLoginPage({
        title: 'PROJ-123 - Jira',
        url: 'https://jira.example.com/browse/PROJ-123',
        accessibilityTree: '<h1>PROJ-123: Fix login bug</h1>',
      });
      expect(result.isLoginPage).toBe(false);
    });
  });
});
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `npx vitest run tests/server/web-access/cdp-engine.test.ts`
Expected: FAIL — `CdpEngine` does not exist

- [ ] **Step 3: Commit**

```bash
git add tests/server/web-access/cdp-engine.test.ts
git commit -m "test(web-access): add CdpEngine unit tests (RED)"
```

---

### Task 3: Implement CdpEngine

**Files:**
- Create: `server/web-access/cdp-engine.ts`

- [ ] **Step 1: Implement CdpEngine**

核心实现参考 `../web-access/scripts/cdp-proxy.mjs` 的 CDP 通信模式：
- `discoverChromePort()` — 读 DevToolsActivePort + 扫描常用端口
- `connectToChrome()` — WebSocket 连接 Chrome
- `sendCDP()` — 发送 CDP 命令，Promise 化
- `ensureSession()` — Target.attachToTarget 建立 session
- 所有公开方法实现 BrowserEngine 接口

关键实现点：
1. 端口发现复用 web-access 的 `discoverChromePort` + `checkPort` 逻辑
2. `sendCDP` 使用 `pending` Map + 超时机制（默认 30s）
3. `snapshot` 使用 `Runtime.evaluate` 提取可访问性信息
4. `click` 优先 JS click，`clickAt` 用 `Input.dispatchMouseEvent`（留作 P1）
5. `detectLoginPage` 分析 snapshot 内容检测登录表单
6. Node.js 22+ 原生 WebSocket，回退到 `ws` 模块

- [ ] **Step 2: Run tests to verify they pass**

Run: `npx vitest run tests/server/web-access/cdp-engine.test.ts`
Expected: ALL PASS

- [ ] **Step 3: Commit**

```bash
git add server/web-access/cdp-engine.ts
git commit -m "feat(web-access): implement CdpEngine (GREEN)"
```

---

## Chunk 2: WebAccessService + MCP Server

### Task 4: Write WebAccessService tests

**Files:**
- Create: `tests/server/web-access/web-access-service.test.ts`

- [ ] **Step 1: Write failing tests for WebAccessService**

```typescript
// tests/server/web-access/web-access-service.test.ts
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { WebAccessService } from '../../../server/web-access/web-access-service.js';
import { BrowserConnectionError } from '../../../server/web-access/browser-engine.js';

describe('WebAccessService', () => {
  let service: WebAccessService;

  beforeEach(() => {
    service = new WebAccessService();
  });

  describe('detectEngine', () => {
    it('should select CdpEngine when Chrome is available', async () => {
      const mockEngine = { isAvailable: vi.fn().mockResolvedValue(true) };
      vi.spyOn(service as any, 'createCdpEngine').mockReturnValue(mockEngine);

      await service.detectEngine();
      expect(service.getActiveEngineType()).toBe('cdp');
    });

    it('should have no engine when neither CDP nor Electron available', async () => {
      const mockCdpEngine = { isAvailable: vi.fn().mockResolvedValue(false) };
      vi.spyOn(service as any, 'createCdpEngine').mockReturnValue(mockCdpEngine);

      await service.detectEngine();
      expect(service.getActiveEngineType()).toBeNull();
    });
  });

  describe('tab management', () => {
    it('should isolate tabs by session ID', async () => {
      const mockEngine = {
        isAvailable: vi.fn().mockResolvedValue(true),
        newTab: vi.fn().mockResolvedValue({ tabId: 'tab-1', url: 'https://jira.example.com', title: 'Jira', createdAt: Date.now(), lastUsedAt: Date.now() }),
        closeTab: vi.fn().mockResolvedValue(undefined),
        dispose: vi.fn().mockResolvedValue(undefined),
      };
      (service as any).engine = mockEngine;
      (service as any).engineType = 'cdp';

      await service.createTab('session-a', 'https://jira.example.com');
      await service.createTab('session-a', 'https://gitlab.example.com');

      const tabsA = service.getSessionTabs('session-a');
      expect(tabsA).toHaveLength(2);

      const tabsB = service.getSessionTabs('session-b');
      expect(tabsB).toHaveLength(0);
    });

    it('should close all tabs for a session on closeSession', async () => {
      const mockEngine = {
        isAvailable: vi.fn().mockResolvedValue(true),
        newTab: vi.fn()
          .mockResolvedValueOnce({ tabId: 'tab-1', url: 'https://jira.example.com', title: 'Jira', createdAt: Date.now(), lastUsedAt: Date.now() })
          .mockResolvedValueOnce({ tabId: 'tab-2', url: 'https://gitlab.example.com', title: 'GitLab', createdAt: Date.now(), lastUsedAt: Date.now() }),
        closeTab: vi.fn().mockResolvedValue(undefined),
        dispose: vi.fn().mockResolvedValue(undefined),
      };
      (service as any).engine = mockEngine;
      (service as any).engineType = 'cdp';

      await service.createTab('session-a', 'https://jira.example.com');
      await service.createTab('session-a', 'https://gitlab.example.com');

      await service.closeSession('session-a');
      expect(mockEngine.closeTab).toHaveBeenCalledTimes(2);
      expect(service.getSessionTabs('session-a')).toHaveLength(0);
    });
  });

  describe('max tabs limit', () => {
    it('should reject when session exceeds max tabs', async () => {
      const mockEngine = {
        isAvailable: vi.fn().mockResolvedValue(true),
        newTab: vi.fn().mockImplementation(async (url: string) => ({
          tabId: `tab-${Math.random()}`, url, title: 'Page', createdAt: Date.now(), lastUsedAt: Date.now(),
        })),
        closeTab: vi.fn().mockResolvedValue(undefined),
        dispose: vi.fn().mockResolvedValue(undefined),
      };
      (service as any).engine = mockEngine;
      (service as any).engineType = 'cdp';

      // Create 5 tabs (max per session)
      for (let i = 0; i < 5; i++) {
        await service.createTab('session-a', `https://example.com/${i}`);
      }

      // 6th should fail
      await expect(service.createTab('session-a', 'https://example.com/6')).rejects.toThrow();
    });
  });

  describe('no engine available', () => {
    it('should throw BrowserConnectionError when engine is null', async () => {
      (service as any).engine = null;
      (service as any).engineType = null;

      await expect(service.createTab('session-a', 'https://example.com')).rejects.toThrow(BrowserConnectionError);
    });
  });
});
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `npx vitest run tests/server/web-access/web-access-service.test.ts`
Expected: FAIL

- [ ] **Step 3: Commit**

```bash
git add tests/server/web-access/web-access-service.test.ts
git commit -m "test(web-access): add WebAccessService unit tests (RED)"
```

---

### Task 5: Implement WebAccessService

**Files:**
- Create: `server/web-access/web-access-service.ts`

- [ ] **Step 1: Implement WebAccessService**

关键职责：
- `detectEngine()` — 探测 CDP 可用性
- `createTab(sessionId, url)` — 按 session 隔离 tab，限制每 session 5 个 tab
- `closeSession(sessionId)` — 关闭该 session 的所有 tab
- `getActiveEngineType()` — 返回 'cdp' | 'embedded' | null
- 代理所有 BrowserEngine 操作到 active engine
- `dispose()` — 全局清理

- [ ] **Step 2: Run tests to verify they pass**

Run: `npx vitest run tests/server/web-access/web-access-service.test.ts`
Expected: ALL PASS

- [ ] **Step 3: Commit**

```bash
git add server/web-access/web-access-service.ts
git commit -m "feat(web-access): implement WebAccessService (GREEN)"
```

---

### Task 6: Write MCP Server tests

**Files:**
- Create: `tests/server/web-access/mcp-server.test.ts`

- [ ] **Step 1: Write failing tests for MCP tools**

```typescript
// tests/server/web-access/mcp-server.test.ts
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { createWebAccessMcpServer } from '../../../server/web-access/mcp-server.js';

describe('Web Access MCP Server', () => {
  describe('tool definitions', () => {
    it('should register all expected tools', () => {
      const mockService = {
        getActiveEngineType: vi.fn().mockReturnValue('cdp'),
        createTab: vi.fn(),
        navigate: vi.fn(),
        snapshot: vi.fn(),
        screenshot: vi.fn(),
        click: vi.fn(),
        fill: vi.fn(),
        pressKey: vi.fn(),
        evaluate: vi.fn(),
        waitFor: vi.fn(),
        listTabs: vi.fn(),
        closeTab: vi.fn(),
        closeSession: vi.fn(),
      };

      const server = createWebAccessMcpServer(mockService as any);

      // Verify server was created with expected tools
      expect(server).toBeDefined();
      expect(server.name).toBe('web-access');
    });
  });

  describe('web_access_navigate tool', () => {
    it('should call service.createTab and return snapshot', async () => {
      const mockService = {
        getActiveEngineType: vi.fn().mockReturnValue('cdp'),
        createTab: vi.fn().mockResolvedValue({
          tabId: 'tab-1',
          snapshot: {
            title: 'PROJ-123 - Jira',
            url: 'https://jira.example.com/browse/PROJ-123',
            accessibilityTree: '<h1>PROJ-123</h1>',
            isLoginPage: false,
          },
        }),
      };

      // Test via tool handler invocation
      // (exact invocation depends on SDK test utilities)
      const result = await mockService.createTab('session-1', 'https://jira.example.com/browse/PROJ-123');
      expect(result.tabId).toBe('tab-1');
      expect(result.snapshot.isLoginPage).toBe(false);
    });
  });
});
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `npx vitest run tests/server/web-access/mcp-server.test.ts`
Expected: FAIL

- [ ] **Step 3: Commit**

```bash
git add tests/server/web-access/mcp-server.test.ts
git commit -m "test(web-access): add MCP Server tool tests (RED)"
```

---

### Task 7: Implement MCP Server

**Files:**
- Create: `server/web-access/mcp-server.ts`

- [ ] **Step 1: Implement MCP Server using SDK's createSdkMcpServer + tool**

```typescript
// server/web-access/mcp-server.ts
// 使用 @anthropic-ai/claude-agent-sdk 的 tool() + createSdkMcpServer()
```

注册的工具：
- `web_access_navigate` — 打开/跳转网页
- `web_access_snapshot` — 获取可访问性快照
- `web_access_screenshot` — 截图
- `web_access_click` — 点击元素
- `web_access_fill` — 填写表单
- `web_access_press_key` — 按键
- `web_access_evaluate` — 执行 JS
- `web_access_list_tabs` — 列出标签
- `web_access_close_tab` — 关闭标签

每个工具调用 WebAccessService 的对应方法，传入 sessionId 从 tool context 获取。

无引擎可用时，所有工具返回 `{ content: [{ type: 'text', text: '错误：浏览器不可用...' }], isError: true }`

- [ ] **Step 2: Run tests to verify they pass**

Run: `npx vitest run tests/server/web-access/mcp-server.test.ts`
Expected: ALL PASS

- [ ] **Step 3: Commit**

```bash
git add server/web-access/mcp-server.ts
git commit -m "feat(web-access): implement MCP Server with web_access_* tools (GREEN)"
```

---

## Chunk 3: Integration + Barrel Export

### Task 8: Create barrel export

**Files:**
- Create: `server/web-access/index.ts`

- [ ] **Step 1: Write barrel file**

```typescript
// server/web-access/index.ts
export { WebAccessService } from './web-access-service.js';
export type { BrowserEngine, TabContext, PageSnapshot, WaitCondition } from './browser-engine.js';
export { BrowserTimeoutError, BrowserConnectionError, LoginRequiredError } from './browser-engine.js';
export { createWebAccessMcpServer } from './mcp-server.js';
```

- [ ] **Step 2: Commit**

```bash
git add server/web-access/index.ts
git commit -m "feat(web-access): add barrel export"
```

---

### Task 9: Add WebAccessService to ClaudeSessionManager

**Files:**
- Modify: `server/claude-session.ts`

- [ ] **Step 1: Add `webAccessService` property and setter to ClaudeSessionManager**

在 `ClaudeSessionManager` 类中添加：

```typescript
// 新增 import（文件顶部）
import { createWebAccessMcpServer } from './web-access/index.js';
import type { WebAccessService } from './web-access/index.js';

// 新增属性（类体内，private 字段区域）
private webAccessService: WebAccessService | null = null;

// 新增 setter 方法
setWebAccessService(service: WebAccessService): void {
  this.webAccessService = service;
  this.log.info('WebAccessService injected');
}
```

- [ ] **Step 2: Modify buildSessionOptions to inject web-access MCP Server**

在 `buildSessionOptions` 方法中（第 188-193 行附近），修改 mcpServers 注入逻辑：

```typescript
// 替换现有的 mcpServers 构建
const mcpServers = buildMcpServers(this.config);
opts.mcpServers = Object.keys(mcpServers).length > 0 ? mcpServers : {};

// 注入 web-access MCP Server（进程内）
// 参考 hello-halo 的 sdk-config.ts 模式，McpSdkServerConfigWithInstance 可通过 as any 传递给 V2 session
if (this.webAccessService) {
  const webAccessServer = createWebAccessMcpServer(this.webAccessService);
  (opts.mcpServers as any)['web-access'] = webAccessServer;
}
```

注意：
- `opts.mcpServers` 始终初始化为 `{}`（即使没有外部 MCP），避免后续 `['web-access']` 赋值到 undefined
- `McpSdkServerConfigWithInstance` 通过 `as any` 传递（与 hello-halo 一致）

- [ ] **Step 3: Verify TypeScript compiles**

Run: `npx tsc --noEmit`
Expected: No errors

- [ ] **Step 4: Commit**

```bash
git add server/claude-session.ts
git commit -m "feat(web-access): add WebAccessService to ClaudeSessionManager"
```

---

### Task 10: Integrate WebAccessService lifecycle into Server

**Files:**
- Modify: `server/index.ts:70-80` (service initialization area)

- [ ] **Step 1: Initialize WebAccessService on server startup**

在 `server/index.ts` 中，`ClaudeSessionManager` 创建之后：

```typescript
import { WebAccessService } from './web-access/index.js';
// ...
const webAccessService = new WebAccessService();
webAccessService.detectEngine().then(() => {
  log.info(`WebAccess engine: ${webAccessService.getActiveEngineType() ?? 'unavailable'}`);
}).catch((err) => {
  log.warn(`WebAccess detection failed: ${err.message}`);
});

// 注入到 sessionManager（必须在 sessionManager.updateConfig 之前）
sessionManager.setWebAccessService(webAccessService);
```

在 `shutdown()` 函数中添加清理：

```typescript
await webAccessService.dispose();
```

- [ ] **Step 2: Verify server starts without errors**

Run: `npx tsx server/index.ts` (短时间运行，验证启动日志)
Expected: 日志中出现 `WebAccess engine: cdp` 或 `WebAccess engine: unavailable`

- [ ] **Step 3: Commit**

```bash
git add server/index.ts
git commit -m "feat(web-access): integrate WebAccessService lifecycle into server startup"
```

---

### Task 11: Run all tests and verify

- [ ] **Step 1: Run full test suite**

Run: `npx vitest run`
Expected: ALL PASS

- [ ] **Step 2: Run TypeScript check**

Run: `npx tsc --noEmit`
Expected: No errors

- [ ] **Step 3: Final commit**

```bash
git add -A
git commit -m "feat(web-access): P0 complete — CdpEngine + MCP Server integrated"
```

---

## Summary

| Task | Description | Files | Tests |
|------|-------------|-------|-------|
| 1 | BrowserEngine 接口定义 | `browser-engine.ts` | — |
| 2 | CdpEngine 测试 | — | `cdp-engine.test.ts` |
| 3 | CdpEngine 实现 | `cdp-engine.ts` | Pass |
| 4 | WebAccessService 测试 | — | `web-access-service.test.ts` |
| 5 | WebAccessService 实现 | `web-access-service.ts` | Pass |
| 6 | MCP Server 测试 | — | `mcp-server.test.ts` |
| 7 | MCP Server 实现 | `mcp-server.ts` | Pass |
| 8 | 桶文件导出 | `index.ts` | — |
| 9 | ClaudeSessionManager 集成（属性 + setter + MCP 注入） | `claude-session.ts` | tsc |
| 10 | Server 生命周期集成 | `index.ts` | 启动验证 |
| 11 | 全量测试 + 最终提交 | — | ALL PASS |
