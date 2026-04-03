export { WebAccessService } from './web-access-service.js';
export type { BrowserEngine, TabContext, PageSnapshot, WaitCondition } from './browser-engine.js';
export { BrowserTimeoutError, BrowserConnectionError, LoginRequiredError } from './browser-engine.js';
export { CdpEngine } from './cdp-engine.js';
export { createWebAccessMcpServer } from './mcp-server.js';

// Utility: copy files that may be locked by Chrome (Windows EBUSY bypass)
import { CdpEngine } from './cdp-engine.js';
export const copyFileLocked = (srcPath: string, destPath: string): void =>
  CdpEngine.copyFileLocked(srcPath, destPath);
