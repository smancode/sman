/**
 * Browser Engine — unified interface for web access capabilities.
 *
 * Two implementations:
 * - CdpEngine: connects to user's Chrome via CDP (reuses existing cookies)
 * - EmbeddedEngine: Electron BrowserView (P1, for VDI environments)
 *
 * Design: Agent never knows which engine is active. MCP tools call
 * WebAccessService, which delegates to the active engine.
 */

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
