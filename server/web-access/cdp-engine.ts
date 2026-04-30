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
import http from 'node:http';
import { type ChildProcess, spawn } from 'node:child_process';
import type { BrowserEngine, TabContext, PageSnapshot, WaitCondition, AxNode } from './browser-engine.js';
import { BrowserTimeoutError, BrowserConnectionError, LoginRequiredError } from './browser-engine.js';
import { createLogger } from '../utils/logger.js';

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
const log = createLogger('CdpEngine');

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
  private chromeProcess: ChildProcess | null = null;
  private chromePid: number | null = null;
  private launchedByUs = false;
  private launchingPromise: Promise<void> | null = null;

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

    // Scan common debugging ports
    for (const port of [9222, 9229, 9333, 9515]) {
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

  // --- Chrome executable and profile discovery ---

  /** Get user's Chrome user-data-dir (parent of Default profile) */
  private static getChromeUserDataDir(): string | null {
    const home = os.homedir();
    const platform = os.platform();

    if (platform === 'darwin') {
      return path.join(home, 'Library/Application Support/Google/Chrome');
    }
    if (platform === 'linux') {
      return path.join(home, '.config/google-chrome');
    }
    if (platform === 'win32') {
      const localAppData = process.env.LOCALAPPDATA || '';
      if (!localAppData) return null;
      return path.join(localAppData, 'Google/Chrome/User Data');
    }
    return null;
  }

  /** Find Chrome executable on the current platform */
  private static findChromeExecutable(): string | null {
    const platform = os.platform();

    if (platform === 'darwin') {
      const p = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
      if (fs.existsSync(p)) return p;
      // Chromium fallback
      const cp = '/Applications/Chromium.app/Contents/MacOS/Chromium';
      if (fs.existsSync(cp)) return cp;
    }
    if (platform === 'linux') {
      for (const p of ['/usr/bin/google-chrome', '/usr/bin/google-chrome-stable', '/usr/bin/chromium-browser', '/usr/bin/chromium']) {
        if (fs.existsSync(p)) return p;
      }
    }
    if (platform === 'win32') {
      const paths = [
        `${process.env.ProgramFiles}\\Google\\Chrome\\Application\\chrome.exe`,
        `${process.env['ProgramFiles(x86)']}\\Google\\Chrome\\Application\\chrome.exe`,
        `${process.env.LOCALAPPDATA}\\Google\\Chrome\\Application\\chrome.exe`,
      ];
      for (const p of paths) {
        if (p && fs.existsSync(p)) return p;
      }
    }
    return null;
  }

  // --- Chrome auto-launch ---

  /** Launch Chrome with user's original profile (direct cookies/session sharing) */
  private async launchChrome(): Promise<{ port: number; wsUrl: string | null }> {
    const chromePath = CdpEngine.findChromeExecutable();
    if (!chromePath) {
      throw new BrowserConnectionError(
        'Chrome executable not found. Install Google Chrome to use web access.',
      );
    }

    const userDataDir = CdpEngine.getChromeUserDataDir();
    if (!userDataDir) {
      throw new BrowserConnectionError(
        'Chrome user data directory not found.',
      );
    }

    const port = 9333;
    const args = [
      `--user-data-dir=${userDataDir}`,
      `--remote-debugging-port=${port}`,
      '--no-first-run',
      '--no-default-browser-check',
      '--disable-popup-blocking',
      '--window-size=1024,768',
      'about:blank',
    ];

    log.info(`Launching Chrome with user profile: ${chromePath}`);

    const child = spawn(chromePath, args, {
      detached: false,
      stdio: 'ignore',
    });

    this.chromeProcess = child;
    this.chromePid = child.pid ?? null;
    this.launchedByUs = true;

    child.on('exit', (code) => {
      log.info(`Chrome process exited with code ${code}`);
      this.chromeProcess = null;
      this.chromePid = null;
    });

    return this.waitForCdpReady(port);
  }

  /** Full WebSocket URL for CDP browser connection */
  private cdpWsUrl: string | null = null;

  /** Wait for CDP HTTP endpoint to respond (reliable cross-platform) */
  private async waitForCdpReady(port: number, timeoutMs = 15_000): Promise<{ port: number; wsUrl: string | null }> {
    const start = Date.now();

    while (Date.now() - start < timeoutMs) {
      if (this.chromeProcess === null && this.chromePid === null) {
        throw new BrowserConnectionError('Chrome process exited unexpectedly during startup');
      }

      try {
        const info = await this.fetchCdpVersion(port);
        if (info) {
          log.info(`Chrome CDP ready on port ${port}`);
          return { port, wsUrl: info.webSocketDebuggerUrl || null };
        }
      } catch { /* not ready yet */ }

      await new Promise(r => setTimeout(r, 300));
    }

    throw new BrowserConnectionError(
      `Chrome did not start within ${timeoutMs}ms`,
    );
  }

  /** Fetch CDP version info via HTTP, returns parsed JSON or null */
  private fetchCdpVersion(port: number): Promise<any | null> {
    return new Promise((resolve) => {
      const req = http.get(`http://127.0.0.1:${port}/json/version`, (res) => {
        let data = '';
        res.on('data', (chunk: string) => data += chunk);
        res.on('end', () => {
          try { resolve(JSON.parse(data)); } catch { resolve(null); }
        });
      });
      req.setTimeout(2000, () => { req.destroy(); resolve(null); });
      req.on('error', () => resolve(null));
    });
  }

  /** Kill Chrome process we spawned */
  private async killLaunchedChrome(): Promise<void> {
    if (!this.chromeProcess && !this.chromePid) return;

    const pid = this.chromePid;
    this.chromeProcess = null;
    this.chromePid = null;
    this.cdpWsUrl = null;

    if (pid) {
      log.info(`Killing Chrome process ${pid}`);
      try {
        if (os.platform() === 'win32') {
          process.kill(pid);
        } else {
          process.kill(pid, 'SIGTERM');
          await new Promise(r => setTimeout(r, 2000));
          try {
            process.kill(pid, 'SIGKILL');
          } catch { /* already dead */ }
        }
      } catch { /* already dead */ }
    }
  }

  // --- Connection management ---

  private getWebSocketUrl(): string {
    // Prefer full URL from CDP /json/version response
    if (this.cdpWsUrl) return this.cdpWsUrl;
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
    const WS = await this.getWebSocketConstructor();
    this.connectingPromise = new Promise((resolve, reject) => {
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
        this.cdpWsUrl = null;
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

  private async getWebSocketConstructor(): Promise<any> {
    if (typeof globalThis.WebSocket !== 'undefined') return globalThis.WebSocket;
    // Fallback: dynamic import ws (works in ESM context where require() fails)
    try {
      const wsModule = await import('ws');
      return wsModule.WebSocket || wsModule.default || wsModule;
    } catch {
      throw new BrowserConnectionError('No WebSocket available. Need Node.js 22+ or ws package.');
    }
  }

  private async ensureConnected(): Promise<WebSocketLike> {
    if (this.disposed) throw new BrowserConnectionError('Engine disposed');
    if (this.ws && this.ws.readyState === OPEN_STATE) return this.ws;

    if (!this.chromePort) {
      // 1. Try to discover already-running Chrome with remote debugging
      const discovered = await this.discoverChromePort();
      if (discovered) {
        log.info(`Discovered running Chrome on port ${discovered.port}, connecting...`);
        this.chromePort = discovered.port;
        this.chromeWsPath = discovered.wsPath;
        try {
          await this.connectToChrome();
          if (this.ws && this.ws.readyState === OPEN_STATE) {
            log.info('Connected to running Chrome');
            return this.ws;
          }
        } catch (e: any) {
          log.warn(`Failed to connect to discovered Chrome: ${e.message}`);
          this.chromePort = null;
          this.chromeWsPath = null;
        }
      }

      // 2. Launch Chrome with user's original profile (cookies/sessions directly available)
      if (!this.chromePort) {
        if (!this.launchingPromise) {
          this.launchingPromise = (async () => {
            await this.killLaunchedChrome();
            const launched = await this.launchChrome();
            this.chromePort = launched.port;
            this.cdpWsUrl = launched.wsUrl;
          })();
          try {
            await this.launchingPromise;
          } finally {
            this.launchingPromise = null;
          }
        } else {
          await this.launchingPromise;
        }
      }
    }

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

  /** AX roles that carry no semantic value and should be pruned */
  private static readonly SKIP_ROLES = new Set([
    'generic', 'StaticText', 'whitespace', 'presentation', 'none',
    'inlineTextBox', 'layoutTable', 'layoutTableCell', 'layoutTableRow',
    'layoutGrid', 'layoutGridCell',
  ]);

  /** AX roles that are interactive — always shown even without name */
  private static readonly INTERACTIVE_ROLES = new Set([
    'button', 'link', 'textbox', 'combobox', 'checkbox',
    'radio', 'slider', 'switch', 'menuitem', 'tab',
    'treeitem', 'option', 'searchbox', 'spinbutton',
    'menuitemcheckbox', 'menuitemradio',
  ]);

  /** Fetch accessibility tree via CDP */
  private async fetchAxTree(sessionId: string): Promise<AxNode[]> {
    const resp = await this.sendCDP('Accessibility.getFullAXTree', {}, sessionId);
    return resp.result?.nodes || [];
  }

  /**
   * Serialize AX nodes into a compact indented string.
   * Each line: [role] "name" = "value" [key=value, ...]
   */
  static serializeAxTree(nodes: AxNode[], maxNodes = 200, maxDepth = 12): string {
    const nodeMap = new Map<string, AxNode>();
    for (const n of nodes) nodeMap.set(n.nodeId, n);

    const rootNode = nodes.find(n => {
      const role = n.role?.value || '';
      return role === 'RootWebArea' || role === 'WebArea';
    });
    if (!rootNode) return '';

    const lines: string[] = [];
    let count = 0;

    function getProp(node: AxNode, propName: string): any {
      return node.properties?.find(p => p.name === propName)?.value?.value;
    }

    function shouldInclude(node: AxNode): boolean {
      const role = node.role?.value || '';
      if (CdpEngine.SKIP_ROLES.has(role)) return false;
      if (CdpEngine.INTERACTIVE_ROLES.has(role)) return true;
      // Semantic landmarks and structural roles
      const semanticRoles = new Set([
        'heading', 'navigation', 'main', 'complementary', 'banner',
        'contentinfo', 'region', 'dialog', 'alertdialog', 'alert',
        'table', 'grid', 'tree', 'list', 'listitem', 'form',
        'article', 'section', 'group', 'img', 'image',
        'progressIndicator', 'meter', 'status', 'log', 'marquee',
        'timer', 'tooltip', 'document', 'definition', 'term',
        'note', 'separator', 'scrollbar',
      ]);
      if (semanticRoles.has(role)) return true;
      // Include nodes with meaningful name or value
      if (node.name?.value?.trim()) return true;
      if (node.value?.value) return true;
      return false;
    }

    function formatNode(node: AxNode): string {
      const role = node.role?.value || '?';
      const name = node.name?.value || '';
      const value = node.value?.value || '';
      let line = `[${role}]`;
      if (name) line += ` "${name}"`;
      if (value && value !== name) line += ` = "${value}"`;

      const extras: string[] = [];
      const level = getProp(node, 'level');
      if (level) extras.push(`level=${level}`);
      const checked = getProp(node, 'checked');
      if (checked !== undefined) extras.push(`checked=${checked}`);
      const expanded = getProp(node, 'expanded');
      if (expanded !== undefined) extras.push(`expanded=${expanded}`);
      const required = getProp(node, 'required');
      if (required) extras.push('required');
      const inputType = getProp(node, 'inputType');
      if (inputType && inputType !== 'text') extras.push(`type=${inputType}`);
      const url = getProp(node, 'url');
      if (url) extras.push(`url="${url}"`);
      const disabled = getProp(node, 'disabled');
      if (disabled) extras.push('disabled');
      const selected = getProp(node, 'selected');
      if (selected) extras.push('selected');
      const placeholder = getProp(node, 'placeholder');
      if (placeholder) extras.push(`placeholder="${placeholder}"`);

      if (extras.length) line += ` [${extras.join(', ')}]`;
      return line;
    }

    function walk(nodeId: string, depth: number): void {
      if (count >= maxNodes || depth > maxDepth) return;
      const node = nodeMap.get(nodeId);
      if (!node) return;

      const included = shouldInclude(node);
      if (included) {
        lines.push('  '.repeat(depth) + formatNode(node));
        count++;
      }

      if (node.childIds) {
        for (const childId of node.childIds) {
          walk(childId, included ? depth + 1 : depth);
        }
      }
    }

    walk(rootNode.nodeId, 0);

    if (count >= maxNodes) {
      lines.push(`... (${nodes.length - maxNodes} more nodes omitted)`);
    }

    return lines.join('\n');
  }

  /** Full snapshot with AX tree — used by snapshot() and navigate() */
  private async takeFullSnapshot(targetId: string): Promise<PageSnapshot> {
    const sessionId = await this.ensureSession(targetId);

    const [pageInfoResp, axNodes] = await Promise.all([
      this.sendCDP('Runtime.evaluate', {
        expression: `JSON.stringify({title: document.title, url: location.href})`,
        returnByValue: true,
      }, sessionId),
      this.fetchAxTree(sessionId).catch(() => [] as AxNode[]),
    ]);

    const raw = pageInfoResp.result?.result?.value;
    const parsed = raw ? JSON.parse(raw) : { title: '', url: '' };

    const serialized = CdpEngine.serializeAxTree(axNodes);

    const snapshot: PageSnapshot = {
      title: parsed.title || '',
      url: parsed.url || '',
      accessibilityTree: serialized,
      axNodes: axNodes.length > 0 ? axNodes : undefined,
      isLoginPage: false,
    };

    const detected = this.detectLoginPage(snapshot);
    snapshot.isLoginPage = detected.isLoginPage;
    snapshot.loginUrl = detected.loginUrl;

    this.updateTabContext(targetId, snapshot);
    return snapshot;
  }

  /** Lightweight snapshot — only title/url, no AX tree */
  private async takeLightSnapshot(targetId: string): Promise<PageSnapshot> {
    const sessionId = await this.ensureSession(targetId);
    const resp = await this.sendCDP('Runtime.evaluate', {
      expression: `JSON.stringify({title: document.title, url: location.href})`,
      returnByValue: true,
    }, sessionId);

    const raw = resp.result?.result?.value;
    const parsed = raw ? JSON.parse(raw) : { title: '', url: '' };

    const snapshot: PageSnapshot = {
      title: parsed.title || '',
      url: parsed.url || '',
      accessibilityTree: '',
      isLoginPage: false,
    };

    const detected = this.detectLoginPage(snapshot);
    snapshot.isLoginPage = detected.isLoginPage;
    snapshot.loginUrl = detected.loginUrl;

    this.updateTabContext(targetId, snapshot);
    return snapshot;
  }

  /** Update tab context metadata */
  private updateTabContext(targetId: string, snapshot: PageSnapshot): void {
    const tab = this.tabs.get(targetId);
    if (tab) {
      tab.url = snapshot.url;
      tab.title = snapshot.title;
      tab.lastUsedAt = Date.now();
    }
  }

  /** Detect login page from snapshot content */
  detectLoginPage(snapshot: { title: string; url: string; accessibilityTree: string; axNodes?: AxNode[] }): { isLoginPage: boolean; loginUrl?: string } {
    const lowerTitle = snapshot.title.toLowerCase();
    const lowerUrl = snapshot.url.toLowerCase();

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

    // Accessibility tree text patterns (fallback when no axNodes)
    const lowerTree = snapshot.accessibilityTree.toLowerCase();
    const treePasswordPatterns = ['password field', 'password input', 'type="password"'];
    if (treePasswordPatterns.some(p => lowerTree.includes(p)) && lowerTree.includes('form')) {
      return { isLoginPage: true, loginUrl: snapshot.url };
    }

    // AX node analysis (precise: checks actual password input fields)
    if (snapshot.axNodes?.length) {
      const hasPasswordField = snapshot.axNodes.some(n => {
        const role = n.role?.value || '';
        const name = (n.name?.value || '').toLowerCase();
        const inputType = n.properties?.find(p => p.name === 'inputType')?.value?.value;
        return role === 'textbox' && (inputType === 'password' || name.includes('password'));
      });
      const hasForm = snapshot.axNodes.some(n => n.role?.value === 'form');
      if (hasPasswordField && hasForm) {
        return { isLoginPage: true, loginUrl: snapshot.url };
      }
    }

    return { isLoginPage: false };
  }

  // --- BrowserEngine interface ---

  async isAvailable(): Promise<boolean> {
    return !!CdpEngine.findChromeExecutable();
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
    return this.takeFullSnapshot(tabId);
  }

  async snapshot(tabId: string): Promise<PageSnapshot> {
    return this.takeFullSnapshot(tabId);
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
    return this.takeFullSnapshot(tabId);
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
    // Kill Chrome if we launched it
    await this.killLaunchedChrome();
  }
}
