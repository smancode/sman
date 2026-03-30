import { describe, it, expect, vi, beforeEach } from 'vitest';
import { BrowserTimeoutError, BrowserConnectionError } from '../../../server/web-access/browser-engine.js';
import { CdpEngine } from '../../../server/web-access/cdp-engine.js';

/** Helper to set up engine with mocked sendCDP */
function setupEngine(opts?: { defaultTimeoutMs?: number }) {
  const engine = new CdpEngine(opts);
  const mockWs = { readyState: 1, send: vi.fn(), close: vi.fn() };
  (engine as any).ws = mockWs;
  vi.spyOn(engine as any, 'ensureConnected').mockResolvedValue(mockWs);
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
      // sendCDP is NOT mocked — it will time out since no one responds
      vi.spyOn(engine as any, 'ensureConnected').mockResolvedValue(mockWs);

      await expect(engine.newTab('https://example.com')).rejects.toThrow(BrowserTimeoutError);
    });
  });

  describe('navigate', () => {
    it('should navigate and return page snapshot', async () => {
      const engine = setupEngine();
      (engine as any).tabs.set('tab-123', { tabId: 'tab-123', url: '', title: '', createdAt: Date.now(), lastUsedAt: Date.now() });
      vi.spyOn(engine as any, 'ensureSession').mockResolvedValue('session-1');
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

      const snapshot = await engine.navigate('tab-123', 'https://jira.example.com/browse/PROJ-123');
      expect(snapshot.url).toContain('jira.example.com');
      expect(snapshot.title).toBe('PROJ-123 - Jira');
    });
  });

  describe('click', () => {
    it('should click element using JS click first', async () => {
      const engine = setupEngine();
      (engine as any).tabs.set('tab-123', { tabId: 'tab-123', url: '', title: '', createdAt: Date.now(), lastUsedAt: Date.now() });
      vi.spyOn(engine as any, 'ensureSession').mockResolvedValue('session-1');
      vi.spyOn(engine as any, 'sendCDP').mockImplementation(async (method: string, params: any) => {
        if (params?.expression?.includes('click')) {
          return { result: { result: { value: { clicked: true, tag: 'BUTTON' } } } };
        }
        if (params?.expression?.includes('document.title')) {
          return { result: { result: { value: '{"title":"Create Issue","url":"https://jira.example.com/create","body":"form"}' } } };
        }
        return { result: {} };
      });

      const snapshot = await engine.click('tab-123', '#create-issue');
      expect(snapshot.title).toBe('Create Issue');
    });
  });

  describe('fill', () => {
    it('should set input value and dispatch events', async () => {
      const engine = setupEngine();
      (engine as any).tabs.set('tab-123', { tabId: 'tab-123', url: '', title: '', createdAt: Date.now(), lastUsedAt: Date.now() });
      vi.spyOn(engine as any, 'ensureSession').mockResolvedValue('session-1');
      vi.spyOn(engine as any, 'sendCDP').mockImplementation(async (method: string, params: any) => {
        if (params?.expression?.includes('#summary')) {
          return { result: { result: { value: { filled: true } } } };
        }
        if (params?.expression?.includes('document.title')) {
          return { result: { result: { value: '{"title":"Create Issue","url":"https://jira.example.com/create","body":"form"}' } } };
        }
        return { result: {} };
      });

      const snapshot = await engine.fill('tab-123', '#summary', 'Fix login bug');
      expect(snapshot).toBeDefined();
      expect(snapshot.title).toBe('Create Issue');
    });
  });

  describe('screenshot', () => {
    it('should return a Buffer from Page.captureScreenshot', async () => {
      const engine = setupEngine();
      (engine as any).tabs.set('tab-123', { tabId: 'tab-123', url: '', title: '', createdAt: Date.now(), lastUsedAt: Date.now() });
      vi.spyOn(engine as any, 'ensureSession').mockResolvedValue('session-1');
      vi.spyOn(engine as any, 'sendCDP').mockResolvedValue({
        result: { data: Buffer.from('test-image').toString('base64') },
      });

      const result = await engine.screenshot('tab-123');
      expect(Buffer.isBuffer(result)).toBe(true);
    });
  });

  describe('closeTab', () => {
    it('should close tab and remove from tabs map', async () => {
      const engine = setupEngine();
      (engine as any).tabs.set('tab-123', { tabId: 'tab-123', url: '', title: '', createdAt: Date.now(), lastUsedAt: Date.now() });
      vi.spyOn(engine as any, 'sendCDP').mockResolvedValue({ result: {} });

      await engine.closeTab('tab-123');
      expect((engine as any).tabs.has('tab-123')).toBe(false);
    });
  });

  describe('evaluate', () => {
    it('should return structured result', async () => {
      const engine = setupEngine();
      (engine as any).tabs.set('tab-123', { tabId: 'tab-123', url: '', title: '', createdAt: Date.now(), lastUsedAt: Date.now() });
      vi.spyOn(engine as any, 'ensureSession').mockResolvedValue('session-1');
      vi.spyOn(engine as any, 'sendCDP').mockResolvedValue({
        result: { result: { value: 5 } },
      });

      const result = await engine.evaluate('tab-123', 'document.querySelectorAll(".issue").length');
      expect(result.success).toBe(true);
      expect(result.result).toBe(5);
    });

    it('should return error on exception', async () => {
      const engine = setupEngine();
      (engine as any).tabs.set('tab-123', { tabId: 'tab-123', url: '', title: '', createdAt: Date.now(), lastUsedAt: Date.now() });
      vi.spyOn(engine as any, 'ensureSession').mockResolvedValue('session-1');
      vi.spyOn(engine as any, 'sendCDP').mockResolvedValue({
        result: { exceptionDetails: { text: 'SyntaxError' } },
      });

      const result = await engine.evaluate('tab-123', 'invalid{{');
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
      (engine as any).tabs.set('tab-1', { tabId: 'tab-1', url: '', title: '', createdAt: Date.now(), lastUsedAt: Date.now() });
      (engine as any).tabs.set('tab-2', { tabId: 'tab-2', url: '', title: '', createdAt: Date.now(), lastUsedAt: Date.now() });
      vi.spyOn(engine as any, 'sendCDP').mockResolvedValue({ result: {} });

      await engine.dispose();
      expect((engine as any).tabs.size).toBe(0);
      expect((engine as any).disposed).toBe(true);
    });
  });
});
