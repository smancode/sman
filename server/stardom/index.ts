// server/bazaar/index.ts
import { BazaarBridge } from './bazaar-bridge.js';
import type { BridgeDeps } from './types.js';
import { createLogger } from '../utils/logger.js';

const log = createLogger('BazaarInit');

let bridge: BazaarBridge | null = null;

export function initBazaarBridge(deps: BridgeDeps): void {
  try {
    const config = deps.settingsManager.getConfig();
    if (!config.bazaar?.server) {
      log.info('Bazaar not configured, skipping bridge initialization');
      return;
    }

    bridge = new BazaarBridge(deps);

    // 启动连接（异步，不阻塞主流程）
    bridge.start().catch((err) => {
      log.error('Bazaar bridge failed to start', { error: String(err) });
    });

    log.info('Bazaar bridge initialized');
  } catch (err) {
    // 构造函数异常不传播，保证一期服务正常
    log.error('Bazaar bridge initialization failed', { error: String(err) });
  }
}

export function getBazaarBridge(): BazaarBridge | null {
  return bridge;
}
