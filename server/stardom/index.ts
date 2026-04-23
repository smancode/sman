// server/stardom/index.ts
import { StardomBridge } from './stardom-bridge.js';
import type { BridgeDeps } from './types.js';
import { createLogger } from '../utils/logger.js';

const log = createLogger('StardomInit');

let bridge: StardomBridge | null = null;

export function initStardomBridge(deps: BridgeDeps): void {
  try {
    const config = deps.settingsManager.getConfig();
    if (!config.stardom?.server) {
      log.info('Stardom not configured, skipping bridge initialization');
      return;
    }

    bridge = new StardomBridge(deps);

    // 启动连接（异步，不阻塞主流程）
    bridge.start().catch((err) => {
      log.error('Stardom bridge failed to start', { error: String(err) });
    });

    log.info('Stardom bridge initialized');
  } catch (err) {
    // 构造函数异常不传播，保证一期服务正常
    log.error('Stardom bridge initialization failed', { error: String(err) });
  }
}

export function getStardomBridge(): StardomBridge | null {
  return bridge;
}
