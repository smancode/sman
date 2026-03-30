import { describe, it, expect, vi, beforeEach } from 'vitest';
import { BrowserTimeoutError, BrowserConnectionError } from '../../../server/web-access/browser-engine.js';

// Mock WebSocket
function createMockWebSocket() {
  const listeners: Record<string, Function[]> = {};
  const sent: any[] = [];
  const ws = {
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
    __listeners: listeners,
    __sent: sent,
    __trigger(event: string, data: any) {
      for (const handler of (listeners[event] || [])) handler({ data: JSON.stringify(data) });
    },
    __setReadyState(state: number) { ws.readyState = state; },
  };
  return ws;
}

// Must import after mocks are ready — use dynamic import
describe('CdpEngine', () => {
  let CdpEngine: typeof import('../../../server/web-access/cdp-engine.js').CdpEngine;

  beforeEach(async () => {
    vi.resetModules();
    // Mock WebSocket globally before import
    const mockWsConstructor = vi.fn(function (this: any, url: string) {
      const mockWs = createMockWebSocket();
      Object.assign(this, mockWs);
      this.url = url;
      // Store reference for test access
      (mockWsConstructor as any).__lastInstance = this;
    });
    (globalThis as any).WebSocket = mockWsConstructor;
    vi.doMock('../../../server/web-access/cdp-engine.js', () => {
      // Re-import to get fresh module
      return vi.importActual('../../../server/web-access/cdp-engine.js');
    });
    const mod = await import('../../../server/web-access/cdp-engine.js');
    CdpEngine = mod.CdpEngine;
  });

  describe('isAvailable', () => {
    it('should return false when no Chrome debug port found', async () => {
      const engine = new CdpEngine();
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

      const promise = engine.newTab('https://jira.example.com');
      // Find the pending CDP command and respond
      const cdpMsg = mockWs.__sent.find((m: any) => m.method === 'Target.createTarget');
      expect(cdpMsg).toBeDefined();
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
