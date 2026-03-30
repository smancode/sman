/**
 * WebAccessService — unified service layer for web access.
 *
 * Manages engine selection, session-based tab isolation,
 * and proxies operations to the active engine.
 */

import type { BrowserEngine, TabContext, PageSnapshot, WaitCondition } from './browser-engine.js';
import { BrowserConnectionError } from './browser-engine.js';
import { CdpEngine } from './cdp-engine.js';

const MAX_TABS_PER_SESSION = 5;

type EngineType = 'cdp' | 'embedded';

export class WebAccessService {
  private engine: BrowserEngine | null = null;
  private engineType: EngineType | null = null;
  /** sessionId → tabId[] */
  private sessionTabs = new Map<string, string[]>();

  /** Probe and select the best available engine */
  async detectEngine(): Promise<void> {
    // Try CDP first
    const cdpEngine = this.createCdpEngine();
    if (await cdpEngine.isAvailable()) {
      this.engine = cdpEngine;
      this.engineType = 'cdp';
      return;
    }
    // No engine available
    this.engine = null;
    this.engineType = null;
  }

  /** Factory for CDP engine — overridable in tests */
  protected createCdpEngine(): BrowserEngine {
    return new CdpEngine();
  }

  getActiveEngineType(): EngineType | null {
    return this.engineType;
  }

  private requireEngine(): BrowserEngine {
    if (!this.engine) {
      throw new BrowserConnectionError('No browser engine available. Start Chrome with --remote-debugging-port=9222');
    }
    return this.engine;
  }

  // --- Tab management ---

  async createTab(sessionId: string, url: string): Promise<{ tabId: string; snapshot: PageSnapshot }> {
    const engine = this.requireEngine();
    const tabs = this.sessionTabs.get(sessionId) || [];
    if (tabs.length >= MAX_TABS_PER_SESSION) {
      throw new Error(`Session ${sessionId} exceeds max tabs limit (${MAX_TABS_PER_SESSION})`);
    }

    const tab = await engine.newTab(url, sessionId);
    tabs.push(tab.tabId);
    this.sessionTabs.set(sessionId, tabs);

    const snapshot = await engine.snapshot(tab.tabId);
    return { tabId: tab.tabId, snapshot };
  }

  getSessionTabs(sessionId: string): TabContext[] {
    const tabIds = this.sessionTabs.get(sessionId) || [];
    // Return lightweight info — actual data is in engine
    return tabIds.map(id => ({ tabId: id, url: '', title: '', createdAt: 0, lastUsedAt: 0 }));
  }

  async closeSession(sessionId: string): Promise<void> {
    const engine = this.requireEngine();
    const tabIds = this.sessionTabs.get(sessionId) || [];
    for (const tabId of tabIds) {
      try {
        await engine.closeTab(tabId);
      } catch { /* best effort */ }
    }
    this.sessionTabs.delete(sessionId);
  }

  // --- Proxy operations ---

  async navigate(tabId: string, url: string): Promise<PageSnapshot> {
    return this.requireEngine().navigate(tabId, url);
  }

  async snapshot(tabId: string): Promise<PageSnapshot> {
    return this.requireEngine().snapshot(tabId);
  }

  async screenshot(tabId: string): Promise<Buffer> {
    return this.requireEngine().screenshot(tabId);
  }

  async click(tabId: string, selector: string): Promise<PageSnapshot> {
    return this.requireEngine().click(tabId, selector);
  }

  async fill(tabId: string, selector: string, value: string): Promise<PageSnapshot> {
    return this.requireEngine().fill(tabId, selector, value);
  }

  async pressKey(tabId: string, key: string): Promise<PageSnapshot> {
    return this.requireEngine().pressKey(tabId, key);
  }

  async evaluate(tabId: string, expression: string): Promise<{ success: boolean; result?: unknown; error?: string }> {
    return this.requireEngine().evaluate(tabId, expression);
  }

  async waitFor(tabId: string, condition: WaitCondition, timeoutMs?: number): Promise<void> {
    return this.requireEngine().waitFor(tabId, condition, timeoutMs);
  }

  async listTabs(): Promise<TabContext[]> {
    return this.requireEngine().listTabs();
  }

  async closeTab(tabId: string): Promise<void> {
    // Remove from all sessions
    for (const [sessionId, tabs] of this.sessionTabs) {
      const idx = tabs.indexOf(tabId);
      if (idx >= 0) {
        tabs.splice(idx, 1);
        if (tabs.length === 0) this.sessionTabs.delete(sessionId);
        break;
      }
    }
    return this.requireEngine().closeTab(tabId);
  }

  // --- Lifecycle ---

  async dispose(): Promise<void> {
    if (this.engine) {
      await this.engine.dispose();
    }
    this.engine = null;
    this.engineType = null;
    this.sessionTabs.clear();
  }
}
