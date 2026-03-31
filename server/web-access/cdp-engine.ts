/**
 * CdpEngine — Chrome DevTools Protocol engine.
 *
 * Connects to user's running Chrome via CDP WebSocket,
 * reusing existing cookies and sessions.
 */

import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import net from 'node:net';
import type { BrowserEngine, TabContext, PageSnapshot, WaitCondition } from './browser-engine.js';
import { BrowserTimeoutError, BrowserConnectionError, LoginRequiredError } from './browser-engine.js';

/** WebSocket constructor type */
type WebSocketLike = {
  readyState: number;
  send(data: string): void;
  close(): void;
  addEventListener(event: string, handler: (ev: any) => void): void;
  removeEventListener(event: string, handler: (ev: any) => void): void;
  on(event: string, handler: (ev: any) => void): void;
};

const OPEN_STATE = 1;

interface CdpEngineOptions {
  defaultTimeoutMs?: number;
}

export interface DomStableOptions {
  timeoutMs?: number;
  networkIdleMs?: number;
  domStableMs?: number;
}

export class CdpEngine implements BrowserEngine {
  private ws: WebSocketLike | null = null;
  private cmdId = 0;
  private pending = new Map<number, { resolve: (value: any) => void; timer: NodeJS.Timeout }>();
  private tabs = new Map<string, TabContext>();
  private sessions = new Map<string, string>(); // targetId -> sessionId
  private cdpEventHandlers = new Map<string, Array<(params: any) => void>>();
  private chromePort: number | null = null;
  private chromeWsPath: string | null = null;
  private connectingPromise: Promise<void> | null = null;
  private defaultTimeoutMs: number;
  private disposed = false;

  constructor(opts?: CdpEngineOptions) {
    this.defaultTimeoutMs = opts?.defaultTimeoutMs ?? 30_000;
  }

  // --- Port discovery (borrowed from cdp-proxy.mjs) ---

  async discoverChromePort(): Promise<{ port: number; wsPath: string | null } | null> {
    const possiblePaths: string[] = [];
    const platform = os.platform();

    if (platform === 'darwin') {
      const home = os.homedir();
      possiblePaths.push(
        path.join(home, 'Library/Application Support/Google/Chrome/DevToolsActivePort'),
        path.join(home, 'Library/Application Support/Google/Chrome Canary/DevToolsActivePort'),
        path.join(home, 'Library/Application Support/Chromium/DevToolsActivePort'),
      );
    } else if (platform === 'linux') {
      const home = os.homedir();
      possiblePaths.push(
        path.join(home, '.config/google-chrome/DevToolsActivePort'),
        path.join(home, '.config/chromium/DevToolsActivePort'),
      );
    } else if (platform === 'win32') {
      const localAppData = process.env.LOCALAPPDATA || '';
      possiblePaths.push(
        path.join(localAppData, 'Google/Chrome/User Data/DevToolsActivePort'),
        path.join(localAppData, 'Chromium/User Data/DevToolsActivePort'),
      );
    }

    for (const p of possiblePaths) {
      try {
        const content = fs.readFileSync(p, 'utf-8').trim();
        const lines = content.split('\n');
        const port = parseInt(lines[0]);
        if (port > 0 && port < 65536) {
          const ok = await this.checkPort(port);
          if (ok) {
            const wsPath = lines[1] || null;
            return { port, wsPath };
          }
        }
      } catch { /* file not found, continue */ }
    }

    // Scan common ports
    for (const port of [9222, 9229, 9333]) {
      const ok = await this.checkPort(port);
      if (ok) return { port, wsPath: null };
    }

    return null;
  }

  /** TCP probe — avoids triggering Chrome auth dialog from WebSocket connect */
  private checkPort(port: number): Promise<boolean> {
    return new Promise((resolve) => {
      const socket = net.createConnection(port, '127.0.0.1');
      const timer = setTimeout(() => { socket.destroy(); resolve(false); }, 2000);
      socket.once('connect', () => { clearTimeout(timer); socket.destroy(); resolve(true); });
      socket.once('error', () => { clearTimeout(timer); resolve(false); });
    });
  }

  // --- Connection management ---

  private getWebSocketUrl(): string {
    const port = this.chromePort!;
    if (this.chromeWsPath) return `ws://127.0.0.1:${port}${this.chromeWsPath}`;
    return `ws://127.0.0.1:${port}/devtools/browser`;
  }

  async connectToChrome(): Promise<void> {
    if (this.ws && this.ws.readyState === OPEN_STATE) return;
    if (this.connectingPromise) return this.connectingPromise;

    if (!this.chromePort) {
      const discovered = await this.discoverChromePort();
      if (!discovered) {
        throw new BrowserConnectionError(
          'Chrome remote debugging not found. Start Chrome with --remote-debugging-port=9222',
        );
      }
      this.chromePort = discovered.port;
      this.chromeWsPath = discovered.wsPath;
    }

    const wsUrl = this.getWebSocketUrl();
    this.connectingPromise = new Promise((resolve, reject) => {
      const WS = this.getWebSocketConstructor();
      const ws = new WS(wsUrl) as WebSocketLike;
      this.ws = ws;

      const onOpen = () => {
        cleanup();
        this.connectingPromise = null;
        resolve();
      };
      const onError = (e: any) => {
        cleanup();
        this.connectingPromise = null;
        const msg = e.message || e.error?.message || 'Connection failed';
        reject(new BrowserConnectionError(msg));
      };
      const onClose = () => {
        this.ws = null;
        this.chromePort = null;
        this.chromeWsPath = null;
        this.sessions.clear();
        this.cdpEventHandlers.clear();
      };
      const onMessage = (evt: any) => {
        this.onMessage(evt);
      };

      const cleanup = () => {
        if (ws.removeEventListener) {
          ws.removeEventListener('open', onOpen);
          ws.removeEventListener('error', onError);
        }
      };

      // Support both Node native WebSocket (addEventListener) and ws module (on)
      if (ws.on) {
        ws.on('open', onOpen);
        ws.on('error', onError);
        ws.on('close', onClose);
        ws.on('message', onMessage);
      } else {
        ws.addEventListener('open', onOpen);
        ws.addEventListener('error', onError);
        ws.addEventListener('close', onClose);
        ws.addEventListener('message', onMessage);
      }
    });

    return this.connectingPromise;
  }

  private getWebSocketConstructor(): any {
    if (typeof globalThis.WebSocket !== 'undefined') return globalThis.WebSocket;
    // Lazy-load ws module as fallback
    try {
      // eslint-disable-next-line @typescript-eslint/no-require-imports
      return require('ws');
    } catch {
      throw new BrowserConnectionError('No WebSocket available. Need Node.js 22+ or ws package.');
    }
  }

  private async ensureConnected(): Promise<WebSocketLike> {
    if (this.disposed) throw new BrowserConnectionError('Engine disposed');
    if (this.ws && this.ws.readyState === OPEN_STATE) return this.ws;
    await this.connectToChrome();
    if (!this.ws || this.ws.readyState !== OPEN_STATE) {
      throw new BrowserConnectionError('WebSocket not connected');
    }
    return this.ws;
  }

  // --- CDP command ---

  private sendCDP(method: string, params: Record<string, any> = {}, sessionId?: string | null): Promise<any> {
    return new Promise((resolve, reject) => {
      if (!this.ws || this.ws.readyState !== OPEN_STATE) {
        return reject(new BrowserConnectionError('WebSocket not connected'));
      }
      const id = ++this.cmdId;
      const msg: any = { id, method, params };
      if (sessionId) msg.sessionId = sessionId;
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new BrowserTimeoutError(method, this.defaultTimeoutMs));
      }, this.defaultTimeoutMs);
      this.pending.set(id, { resolve, timer });
      this.ws.send(JSON.stringify(msg));
    });
  }

  private async ensureSession(targetId: string): Promise<string> {
    if (this.sessions.has(targetId)) return this.sessions.get(targetId)!;
    const resp = await this.sendCDP('Target.attachToTarget', { targetId, flatten: true });
    if (resp.result?.sessionId) {
      this.sessions.set(targetId, resp.result.sessionId);
      return resp.result.sessionId;
    }
    throw new BrowserConnectionError('Target.attachToTarget failed: ' + JSON.stringify(resp.error));
  }

  // --- CDP event dispatch ---

  onCdpEvent(method: string, handler: (params: any) => void): void {
    if (!this.cdpEventHandlers.has(method)) {
      this.cdpEventHandlers.set(method, []);
    }
    this.cdpEventHandlers.get(method)!.push(handler);
  }

  removeCdpEventListener(method: string, handler: (params: any) => void): void {
    const handlers = this.cdpEventHandlers.get(method);
    if (handlers) {
      const idx = handlers.indexOf(handler);
      if (idx >= 0) handlers.splice(idx, 1);
    }
  }

  /** Extracted onMessage handler — callable from connectToChrome and tests */
  onMessage(evt: any): void {
    const data = typeof evt === 'string' ? evt : (evt.data || evt);
    const msg = JSON.parse(typeof data === 'string' ? data : data.toString());

    // Built-in: Target.attachedToTarget
    if (msg.method === 'Target.attachedToTarget') {
      const { sessionId, targetInfo } = msg.params;
      this.sessions.set(targetInfo.targetId, sessionId);
    }
    // Command response correlation
    if (msg.id && this.pending.has(msg.id)) {
      const { resolve, timer } = this.pending.get(msg.id)!;
      clearTimeout(timer);
      this.pending.delete(msg.id);
      resolve(msg);
    }
    // Generic event dispatch
    if (msg.method && this.cdpEventHandlers.has(msg.method)) {
      for (const handler of this.cdpEventHandlers.get(msg.method)!) {
        handler(msg.params);
      }
    }
  }

  // --- DOM stability detection ---

  /** Default options for post-action DOM stability checks (short wait) */
  private static readonly ACTION_STABLE_OPTS: DomStableOptions = {
    timeoutMs: 5_000,
    networkIdleMs: 200,
    domStableMs: 300,
  };

  async waitForDomStable(tabId: string, opts: DomStableOptions = {}): Promise<void> {
    const {
      timeoutMs = 10_000,
      networkIdleMs = 500,
      domStableMs = 800,
    } = opts;
    const sessionId = await this.ensureSession(tabId);

    // Inject MutationObserver
    const installExpr = `(() => {
      if (window.__domStableObserver) return 'already-installed';
      window.__domMutations = 0;
      window.__domLastMutation = Date.now();
      window.__domStableObserver = new MutationObserver(() => {
        window.__domMutations++;
        window.__domLastMutation = Date.now();
      });
      if (document.body) {
        window.__domStableObserver.observe(document.body, {
          childList: true, subtree: true, attributes: true
        });
      }
      return 'installed';
    })()`;
    await this.sendCDP('Runtime.evaluate', { expression: installExpr, returnByValue: true }, sessionId);

    // Enable Network domain for request tracking
    await this.sendCDP('Network.enable', {}, sessionId);

    // Track network requests via events — use a Set of active requestId for clarity
    const activeRequests = new Set<string>();
    let lastNetworkActivity = Date.now();

    const onRequestStart = (params: any) => {
      if (params.requestId) {
        activeRequests.add(params.requestId);
        lastNetworkActivity = Date.now();
      }
    };
    const onRequestDone = (params: any) => {
      if (activeRequests.delete(params.requestId)) {
        lastNetworkActivity = Date.now();
      }
    };

    this.onCdpEvent('Network.requestWillBeSent', onRequestStart);
    this.onCdpEvent('Network.loadingFinished', onRequestDone);
    this.onCdpEvent('Network.loadingFailed', onRequestDone);

    try {
      const start = Date.now();
      const checkExpr = `(() => {
        const elapsed = Date.now() - (window.__domLastMutation || 0);
        const mutations = window.__domMutations || 0;
        return JSON.stringify({ elapsed, mutations });
      })()`;

      while (Date.now() - start < timeoutMs) {
        const domResp = await this.sendCDP('Runtime.evaluate', {
          expression: checkExpr,
          returnByValue: true,
        }, sessionId);
        const domState = JSON.parse(domResp.result?.result?.value || '{"elapsed":0,"mutations":0}');

        const networkIdle = activeRequests.size === 0 && (Date.now() - lastNetworkActivity) >= networkIdleMs;
        const domStable = domState.elapsed >= domStableMs;

        if (networkIdle && domStable) return;

        await new Promise(r => setTimeout(r, 200));
      }

      throw new BrowserTimeoutError('waitForDomStable', timeoutMs);
    } finally {
      this.removeCdpEventListener('Network.requestWillBeSent', onRequestStart);
      this.removeCdpEventListener('Network.loadingFinished', onRequestDone);
      this.removeCdpEventListener('Network.loadingFailed', onRequestDone);
    }
  }

  // --- Wait for page load ---

  private async waitForLoad(sessionId: string, tabId: string, timeoutMs = 15_000): Promise<void> {
    await this.sendCDP('Page.enable', {}, sessionId);

    const start = Date.now();
    while (Date.now() - start < timeoutMs) {
      try {
        const resp = await this.sendCDP('Runtime.evaluate', {
          expression: 'document.readyState',
          returnByValue: true,
        }, sessionId);
        if (resp.result?.result?.value === 'complete') break;
      } catch { /* ignore */ }
      await new Promise(r => setTimeout(r, 500));
    }

    // Wait for DOM stability after readyState is complete
    await this.waitForDomStable(tabId, {
      timeoutMs: Math.max(1_000, timeoutMs - (Date.now() - start)),
      networkIdleMs: 500,
      domStableMs: 800,
    }).catch(() => { /* non-fatal */ });
  }

  // --- Snapshot helpers ---

  private async takeSnapshot(targetId: string): Promise<PageSnapshot> {
    const sessionId = await this.ensureSession(targetId);
    const evalExpr = `JSON.stringify({title: document.title, url: location.href, body: document.body?.innerText?.slice(0, 5000) || ''})`;
    const resp = await this.sendCDP('Runtime.evaluate', {
      expression: evalExpr,
      returnByValue: true,
    }, sessionId);

    const raw = resp.result?.result?.value;
    const parsed = raw ? JSON.parse(raw) : { title: '', url: '', body: '' };

    const snapshot: PageSnapshot = {
      title: parsed.title || '',
      url: parsed.url || '',
      accessibilityTree: parsed.body || '',
      isLoginPage: false,
    };

    const detected = this.detectLoginPage(snapshot);
    snapshot.isLoginPage = detected.isLoginPage;
    snapshot.loginUrl = detected.loginUrl;

    // Update tab context
    const tab = this.tabs.get(targetId);
    if (tab) {
      tab.url = snapshot.url;
      tab.title = snapshot.title;
      tab.lastUsedAt = Date.now();
    }

    return snapshot;
  }

  /** Detect login page from snapshot content */
  detectLoginPage(snapshot: { title: string; url: string; accessibilityTree: string }): { isLoginPage: boolean; loginUrl?: string } {
    const lowerTitle = snapshot.title.toLowerCase();
    const lowerUrl = snapshot.url.toLowerCase();
    const lowerTree = snapshot.accessibilityTree.toLowerCase();

    // URL patterns
    const loginUrlPatterns = ['/login', '/signin', '/auth', '/sso', '/oauth'];
    if (loginUrlPatterns.some(p => lowerUrl.includes(p))) {
      return { isLoginPage: true, loginUrl: snapshot.url };
    }

    // Title patterns
    const loginTitlePatterns = ['log in', 'sign in', 'login', '登录', 'single sign-on'];
    if (loginTitlePatterns.some(p => lowerTitle.includes(p))) {
      return { isLoginPage: true, loginUrl: snapshot.url };
    }

    // Content patterns — password field in form
    if (lowerTree.includes('password') && lowerTree.includes('form')) {
      return { isLoginPage: true, loginUrl: snapshot.url };
    }

    return { isLoginPage: false };
  }

  // --- BrowserEngine interface ---

  async isAvailable(): Promise<boolean> {
    try {
      const discovered = await this.discoverChromePort();
      if (!discovered) return false;
      this.chromePort = discovered.port;
      this.chromeWsPath = discovered.wsPath;
      await this.connectToChrome();
      return true;
    } catch {
      return false;
    }
  }

  async newTab(url: string, _sessionId?: string): Promise<TabContext> {
    await this.ensureConnected();
    const resp = await this.sendCDP('Target.createTarget', { url, background: true });
    const targetId = resp.result.targetId;

    const tab: TabContext = {
      tabId: targetId,
      url,
      title: '',
      createdAt: Date.now(),
      lastUsedAt: Date.now(),
    };
    this.tabs.set(targetId, tab);

    // Wait for page load (non-fatal)
    if (url !== 'about:blank') {
      try {
        const sid = await this.ensureSession(targetId);
        await this.waitForLoad(sid, targetId);
      } catch { /* non-fatal */ }
    }

    return tab;
  }

  async navigate(tabId: string, url: string): Promise<PageSnapshot> {
    const sessionId = await this.ensureSession(tabId);
    await this.sendCDP('Page.navigate', { url }, sessionId);
    await this.waitForLoad(sessionId, tabId);
    return this.takeSnapshot(tabId);
  }

  async snapshot(tabId: string): Promise<PageSnapshot> {
    return this.takeSnapshot(tabId);
  }

  async screenshot(tabId: string): Promise<Buffer> {
    await this.ensureConnected();
    const sessionId = await this.ensureSession(tabId);
    const resp = await this.sendCDP('Page.captureScreenshot', {
      format: 'png',
    }, sessionId);
    return Buffer.from(resp.result.data, 'base64');
  }

  /** Execute JS in the tab session, then wait for DOM stability and take a snapshot. */
  private async execAndSnapshot(tabId: string, expression: string, awaitPromise = true): Promise<PageSnapshot> {
    const sessionId = await this.ensureSession(tabId);
    const resp = await this.sendCDP('Runtime.evaluate', {
      expression,
      returnByValue: true,
      awaitPromise,
    }, sessionId);
    if (resp.result?.result?.value?.error) {
      throw new Error(resp.result.result.value.error);
    }
    await this.waitForDomStable(tabId, CdpEngine.ACTION_STABLE_OPTS).catch(() => { /* non-fatal */ });
    return this.takeSnapshot(tabId);
  }

  async click(tabId: string, selector: string): Promise<PageSnapshot> {
    await this.ensureConnected();
    const selectorJson = JSON.stringify(selector);
    const js = `(() => {
      const el = document.querySelector(${selectorJson});
      if (!el) return { error: 'Element not found: ' + ${selectorJson} };
      el.scrollIntoView({ block: 'center' });
      el.click();
      return { clicked: true, tag: el.tagName };
    })()`;
    return this.execAndSnapshot(tabId, js);
  }

  async fill(tabId: string, selector: string, value: string): Promise<PageSnapshot> {
    await this.ensureConnected();
    const selectorJson = JSON.stringify(selector);
    const valueJson = JSON.stringify(value);
    const js = `(() => {
      const el = document.querySelector(${selectorJson});
      if (!el) return { error: 'Element not found: ' + ${selectorJson} };
      el.focus();
      el.value = ${valueJson};
      el.dispatchEvent(new Event('input', { bubbles: true }));
      el.dispatchEvent(new Event('change', { bubbles: true }));
      return { filled: true };
    })()`;
    return this.execAndSnapshot(tabId, js);
  }

  async pressKey(tabId: string, key: string): Promise<PageSnapshot> {
    await this.ensureConnected();
    const keyJson = JSON.stringify(key);
    const js = `(() => {
      document.activeElement.dispatchEvent(new KeyboardEvent('keydown', { key: ${keyJson}, bubbles: true }));
      document.activeElement.dispatchEvent(new KeyboardEvent('keypress', { key: ${keyJson}, bubbles: true }));
      document.activeElement.dispatchEvent(new KeyboardEvent('keyup', { key: ${keyJson}, bubbles: true }));
      return true;
    })()`;
    return this.execAndSnapshot(tabId, js);
  }

  async evaluate(tabId: string, expression: string): Promise<{ success: boolean; result?: unknown; error?: string }> {
    await this.ensureConnected();
    const sessionId = await this.ensureSession(tabId);
    const resp = await this.sendCDP('Runtime.evaluate', {
      expression,
      returnByValue: true,
      awaitPromise: true,
    }, sessionId);
    if (resp.result?.exceptionDetails) {
      return { success: false, error: resp.result.exceptionDetails.text || 'Evaluation error' };
    }
    return { success: true, result: resp.result?.result?.value };
  }

  async waitFor(tabId: string, condition: WaitCondition, timeoutMs?: number): Promise<void> {
    const timeout = timeoutMs ?? this.defaultTimeoutMs;
    const start = Date.now();

    if (condition.type === 'selector') {
      while (Date.now() - start < timeout) {
        const sessionId = await this.ensureSession(tabId);
        const selectorJson = JSON.stringify(condition.selector);
        const resp = await this.sendCDP('Runtime.evaluate', {
          expression: `!!document.querySelector(${selectorJson})`,
          returnByValue: true,
        }, sessionId);
        if (resp.result?.result?.value) return;
        await new Promise(r => setTimeout(r, 500));
      }
      throw new BrowserTimeoutError('waitFor(selector)', timeout);
    }

    if (condition.type === 'navigation') {
      const navTimeout = condition.timeoutMs ?? timeout;
      while (Date.now() - start < navTimeout) {
        try {
          const sessionId = await this.ensureSession(tabId);
          const resp = await this.sendCDP('Runtime.evaluate', {
            expression: 'document.readyState',
            returnByValue: true,
          }, sessionId);
          if (resp.result?.result?.value === 'complete') return;
        } catch { /* ignore */ }
        await new Promise(r => setTimeout(r, 500));
      }
      throw new BrowserTimeoutError('waitFor(navigation)', navTimeout);
    }

    if (condition.type === 'idle') {
      const idleMs = condition.idleMs ?? 1000;
      await this.waitForDomStable(tabId, {
        timeoutMs: Math.max(1_000, timeout - (Date.now() - start)),
        networkIdleMs: idleMs,
        domStableMs: idleMs,
      });
      return;
    }
  }

  async listTabs(): Promise<TabContext[]> {
    return Array.from(this.tabs.values());
  }

  async closeTab(tabId: string): Promise<void> {
    await this.ensureConnected();
    await this.sendCDP('Target.closeTarget', { targetId: tabId });
    this.tabs.delete(tabId);
    this.sessions.delete(tabId);
  }

  async dispose(): Promise<void> {
    this.disposed = true;
    // Close all tabs
    for (const tabId of this.tabs.keys()) {
      try {
        await this.sendCDP('Target.closeTarget', { targetId: tabId });
      } catch { /* best effort */ }
    }
    this.tabs.clear();
    this.sessions.clear();
    this.cdpEventHandlers.clear();
    // Clear pending
    for (const { timer } of this.pending.values()) {
      clearTimeout(timer);
    }
    this.pending.clear();
    // Close WebSocket
    if (this.ws) {
      this.ws.close();
      this.ws = null;
    }
  }
}
