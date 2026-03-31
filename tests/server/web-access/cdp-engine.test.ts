import { describe, it, expect, vi, beforeEach } from 'vitest';
import { BrowserTimeoutError, BrowserConnectionError } from '../../../server/web-access/browser-engine.js';
import { CdpEngine } from '../../../server/web-access/cdp-engine.js';

const TAB_ID = 'tab-123';
const SESSION_ID = 'session-1';

function makeTabContext(id = TAB_ID) {
  return { tabId: id, url: '', title: '', createdAt: Date.now(), lastUsedAt: Date.now() };
}

/** Helper to set up engine with mocked WebSocket and ensureConnected */
function setupEngine(opts?: { defaultTimeoutMs?: number }) {
  const engine = new CdpEngine(opts);
  const mockWs = { readyState: 1, send: vi.fn(), close: vi.fn() };
  (engine as any).ws = mockWs;
  vi.spyOn(engine as any, 'ensureConnected').mockResolvedValue(mockWs);
  return engine;
}

/** Setup engine + register a tab + mock ensureSession + mock waitForDomStable */
function setupEngineWithTab(opts?: { defaultTimeoutMs?: number }) {
  const engine = setupEngine(opts);
  (engine as any).tabs.set(TAB_ID, makeTabContext());
  vi.spyOn(engine as any, 'ensureSession').mockResolvedValue(SESSION_ID);
  vi.spyOn(engine as any, 'waitForDomStable').mockResolvedValue(undefined);
  return engine;
}

describe('CdpEngine', () => {
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
      const engine = setupEngine();
      vi.spyOn(engine as any, 'sendCDP').mockImplementation(async (method: string) => {
        if (method === 'Target.createTarget') return { result: { targetId: 'tab-123' } };
        return { result: {} };
      });

      const tab = await engine.newTab('https://jira.example.com');
      expect(tab.tabId).toBe('tab-123');
      expect(tab.url).toBe('https://jira.example.com');
    });

    it('should throw BrowserTimeoutError on timeout', async () => {
      const engine = new CdpEngine({ defaultTimeoutMs: 50 });
      const mockWs = { readyState: 1, send: vi.fn(), close: vi.fn() };
      (engine as any).ws = mockWs;
      vi.spyOn(engine as any, 'ensureConnected').mockResolvedValue(mockWs);

      await expect(engine.newTab('https://example.com')).rejects.toThrow(BrowserTimeoutError);
    });
  });

  describe('navigate', () => {
    it('should navigate and return page snapshot', async () => {
      const engine = setupEngineWithTab();
      vi.spyOn(engine as any, 'sendCDP').mockImplementation(async (method: string, params: any) => {
        if (method === 'Page.navigate') return { result: { frameId: 'frame-1' } };
        if (method === 'Page.enable') return { result: {} };
        if (params?.expression?.includes('readyState')) {
          return { result: { result: { value: 'complete' } } };
        }
        if (params?.expression?.includes('document.title')) {
          return { result: { result: { value: '{"title":"PROJ-123 - Jira","url":"https://jira.example.com/browse/PROJ-123","body":"content"}' } } };
        }
        return { result: {} };
      });

      const snapshot = await engine.navigate(TAB_ID, 'https://jira.example.com/browse/PROJ-123');
      expect(snapshot.url).toContain('jira.example.com');
      expect(snapshot.title).toBe('PROJ-123 - Jira');
    });
  });

  describe('click', () => {
    it('should click element using JS click first', async () => {
      const engine = setupEngineWithTab();
      vi.spyOn(engine as any, 'sendCDP').mockImplementation(async (_method: string, params: any) => {
        if (params?.expression?.includes('click')) {
          return { result: { result: { value: { clicked: true, tag: 'BUTTON' } } } };
        }
        if (params?.expression?.includes('document.title')) {
          return { result: { result: { value: '{"title":"Create Issue","url":"https://jira.example.com/create","body":"form"}' } } };
        }
        return { result: {} };
      });

      const snapshot = await engine.click(TAB_ID, '#create-issue');
      expect(snapshot.title).toBe('Create Issue');
    });
  });

  describe('fill', () => {
    it('should set input value and dispatch events', async () => {
      const engine = setupEngineWithTab();
      vi.spyOn(engine as any, 'sendCDP').mockImplementation(async (_method: string, params: any) => {
        if (params?.expression?.includes('#summary')) {
          return { result: { result: { value: { filled: true } } } };
        }
        if (params?.expression?.includes('document.title')) {
          return { result: { result: { value: '{"title":"Create Issue","url":"https://jira.example.com/create","body":"form"}' } } };
        }
        return { result: {} };
      });

      const snapshot = await engine.fill(TAB_ID, '#summary', 'Fix login bug');
      expect(snapshot).toBeDefined();
      expect(snapshot.title).toBe('Create Issue');
    });
  });

  describe('screenshot', () => {
    it('should return a Buffer from Page.captureScreenshot', async () => {
      const engine = setupEngine();
      (engine as any).tabs.set(TAB_ID, makeTabContext());
      vi.spyOn(engine as any, 'ensureSession').mockResolvedValue(SESSION_ID);
      vi.spyOn(engine as any, 'sendCDP').mockResolvedValue({
        result: { data: Buffer.from('test-image').toString('base64') },
      });

      const result = await engine.screenshot(TAB_ID);
      expect(Buffer.isBuffer(result)).toBe(true);
    });
  });

  describe('closeTab', () => {
    it('should close tab and remove from tabs map', async () => {
      const engine = setupEngine();
      (engine as any).tabs.set(TAB_ID, makeTabContext());
      vi.spyOn(engine as any, 'sendCDP').mockResolvedValue({ result: {} });

      await engine.closeTab(TAB_ID);
      expect((engine as any).tabs.has(TAB_ID)).toBe(false);
    });
  });

  describe('evaluate', () => {
    it('should return structured result', async () => {
      const engine = setupEngine();
      (engine as any).tabs.set(TAB_ID, makeTabContext());
      vi.spyOn(engine as any, 'ensureSession').mockResolvedValue(SESSION_ID);
      vi.spyOn(engine as any, 'sendCDP').mockResolvedValue({
        result: { result: { value: 5 } },
      });

      const result = await engine.evaluate(TAB_ID, 'document.querySelectorAll(".issue").length');
      expect(result.success).toBe(true);
      expect(result.result).toBe(5);
    });

    it('should return error on exception', async () => {
      const engine = setupEngine();
      (engine as any).tabs.set(TAB_ID, makeTabContext());
      vi.spyOn(engine as any, 'ensureSession').mockResolvedValue(SESSION_ID);
      vi.spyOn(engine as any, 'sendCDP').mockResolvedValue({
        result: { exceptionDetails: { text: 'SyntaxError' } },
      });

      const result = await engine.evaluate(TAB_ID, 'invalid{{');
      expect(result.success).toBe(false);
      expect(result.error).toBe('SyntaxError');
    });
  });

  describe('login detection', () => {
    it('should detect login page from URL pattern', () => {
      const engine = new CdpEngine();
      const result = (engine as any).detectLoginPage({
        title: 'Sign In',
        url: 'https://jira.example.com/login',
        accessibilityTree: '',
      });
      expect(result.isLoginPage).toBe(true);
    });

    it('should detect login page from title pattern', () => {
      const engine = new CdpEngine();
      const result = (engine as any).detectLoginPage({
        title: 'Log in - Jira',
        url: 'https://jira.example.com/auth',
        accessibilityTree: '',
      });
      expect(result.isLoginPage).toBe(true);
    });

    it('should detect login page from password field in form', () => {
      const engine = new CdpEngine();
      const result = (engine as any).detectLoginPage({
        title: 'Welcome',
        url: 'https://jira.example.com/welcome',
        accessibilityTree: 'Please enter your credentials. Password field. Form submit.',
      });
      expect(result.isLoginPage).toBe(true);
    });

    it('should not flag normal pages as login', () => {
      const engine = new CdpEngine();
      const result = (engine as any).detectLoginPage({
        title: 'PROJ-123 - Jira',
        url: 'https://jira.example.com/browse/PROJ-123',
        accessibilityTree: '<h1>PROJ-123: Fix login bug</h1>',
      });
      expect(result.isLoginPage).toBe(false);
    });
  });

  describe('dispose', () => {
    it('should clean up all resources', async () => {
      const engine = setupEngine();
      (engine as any).tabs.set('tab-1', makeTabContext('tab-1'));
      (engine as any).tabs.set('tab-2', makeTabContext('tab-2'));
      vi.spyOn(engine as any, 'sendCDP').mockResolvedValue({ result: {} });

      await engine.dispose();
      expect((engine as any).tabs.size).toBe(0);
      expect((engine as any).disposed).toBe(true);
    });
  });

  describe('CDP event dispatch', () => {
    it('should dispatch CDP events to registered handlers', () => {
      const engine = new CdpEngine();
      const handler = vi.fn();
      engine.onCdpEvent('Network.requestWillBeSent', handler);

      engine.onMessage({ data: JSON.stringify({
        method: 'Network.requestWillBeSent',
        params: { requestId: 'req-1', request: { url: 'https://example.com/api' } },
      }) });

      expect(handler).toHaveBeenCalledWith({
        requestId: 'req-1',
        request: { url: 'https://example.com/api' },
      });
    });

    it('should remove specific handler', () => {
      const engine = new CdpEngine();
      const handler1 = vi.fn();
      const handler2 = vi.fn();
      engine.onCdpEvent('Page.loadEventFired', handler1);
      engine.onCdpEvent('Page.loadEventFired', handler2);
      engine.removeCdpEventListener('Page.loadEventFired', handler1);

      engine.onMessage({ data: JSON.stringify({
        method: 'Page.loadEventFired',
        params: { timestamp: 12345 },
      }) });

      expect(handler1).not.toHaveBeenCalled();
      expect(handler2).toHaveBeenCalledWith({ timestamp: 12345 });
    });
  });

  describe('waitForDomStable', () => {
    it('should resolve when network idle and DOM stable', async () => {
      const engine = setupEngine();
      vi.spyOn(engine as any, 'ensureSession').mockResolvedValue(SESSION_ID);

      vi.spyOn(engine as any, 'sendCDP').mockImplementation(async (method: string, params: any) => {
        if (method === 'Network.enable') return { result: {} };
        if (params?.expression?.includes('new MutationObserver')) {
          return { result: { result: { value: 'installed' } } };
        }
        if (params?.expression?.includes('__domLastMutation')) {
          return { result: { result: { value: JSON.stringify({ elapsed: 1000, mutations: 3 }) } } };
        }
        return { result: {} };
      });

      const result = engine.waitForDomStable(TAB_ID, {
        timeoutMs: 5000,
        networkIdleMs: 200,
        domStableMs: 300,
      });

      // Simulate network events becoming idle
      engine.onMessage({ data: JSON.stringify({
        method: 'Network.requestWillBeSent',
        params: { requestId: 'req-1' },
      }) });
      engine.onMessage({ data: JSON.stringify({
        method: 'Network.loadingFinished',
        params: { requestId: 'req-1' },
      }) });

      await expect(result).resolves.toBeUndefined();
    });

    it('should timeout when DOM keeps changing', async () => {
      const engine = setupEngine({ defaultTimeoutMs: 200 });
      vi.spyOn(engine as any, 'ensureSession').mockResolvedValue(SESSION_ID);

      vi.spyOn(engine as any, 'sendCDP').mockImplementation(async (method: string, params: any) => {
        if (method === 'Network.enable') return { result: {} };
        if (params?.expression?.includes('new MutationObserver')) {
          return { result: { result: { value: 'installed' } } };
        }
        if (params?.expression?.includes('__domLastMutation')) {
          return { result: { result: { value: JSON.stringify({ elapsed: 0, mutations: 999 }) } } };
        }
        return { result: {} };
      });

      await expect(engine.waitForDomStable(TAB_ID, {
        timeoutMs: 300,
        networkIdleMs: 100,
        domStableMs: 200,
      })).rejects.toThrow(BrowserTimeoutError);
    });

    it('should use default options when none provided', async () => {
      const engine = setupEngine();
      vi.spyOn(engine as any, 'ensureSession').mockResolvedValue(SESSION_ID);

      const sendCDPSpy = vi.spyOn(engine as any, 'sendCDP').mockImplementation(async (method: string, params: any) => {
        if (method === 'Network.enable') return { result: {} };
        if (params?.expression?.includes('new MutationObserver')) {
          return { result: { result: { value: 'installed' } } };
        }
        if (params?.expression?.includes('__domLastMutation')) {
          return { result: { result: { value: JSON.stringify({ elapsed: 10000, mutations: 0 }) } } };
        }
        return { result: {} };
      });

      await engine.waitForDomStable(TAB_ID);
      expect(sendCDPSpy).toHaveBeenCalledWith('Network.enable', {}, SESSION_ID);
    });
  });

  describe('smart wait in actions', () => {
    it('click should use waitForDomStable instead of fixed delay', async () => {
      const engine = setupEngineWithTab();

      const domStableSpy = vi.spyOn(engine as any, 'waitForDomStable').mockResolvedValue(undefined);
      vi.spyOn(engine as any, 'sendCDP').mockImplementation(async (_method: string, params: any) => {
        if (params?.expression?.includes('click')) {
          return { result: { result: { value: { clicked: true, tag: 'BUTTON' } } } };
        }
        if (params?.expression?.includes('document.title')) {
          return { result: { result: { value: '{"title":"After Click","url":"https://example.com/after","body":"result"}' } } };
        }
        return { result: {} };
      });

      await engine.click(TAB_ID, '#btn');
      expect(domStableSpy).toHaveBeenCalledWith(TAB_ID, expect.objectContaining({
        timeoutMs: 5000,
      }));
    });

    it('fill should use waitForDomStable instead of fixed delay', async () => {
      const engine = setupEngineWithTab();

      const domStableSpy = vi.spyOn(engine as any, 'waitForDomStable').mockResolvedValue(undefined);
      vi.spyOn(engine as any, 'sendCDP').mockImplementation(async (_method: string, params: any) => {
        if (params?.expression?.includes('#input')) {
          return { result: { result: { value: { filled: true } } } };
        }
        if (params?.expression?.includes('document.title')) {
          return { result: { result: { value: '{"title":"Filled","url":"https://example.com","body":""}' } } };
        }
        return { result: {} };
      });

      await engine.fill(TAB_ID, '#input', 'hello');
      expect(domStableSpy).toHaveBeenCalledWith(TAB_ID, expect.objectContaining({
        timeoutMs: 5000,
      }));
    });
  });
});
