// tests/server/bazaar/bazaar-mcp-ranking.test.ts
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { BazaarStore } from '../../../server/bazaar/bazaar-store.js';
import fs from 'fs';
import path from 'path';
import os from 'os';

describe('bazaar_search ranking', () => {
  let store: BazaarStore;
  let dbPath: string;

  beforeEach(() => {
    dbPath = path.join(os.tmpdir(), `bazaar-ranking-test-${Date.now()}.db`);
    store = new BazaarStore(dbPath);
  });

  afterEach(() => {
    store.close();
    if (fs.existsSync(dbPath)) fs.unlinkSync(dbPath);
  });

  it('should mark results with experience as [有经验]', () => {
    store.saveLearnedRoute({
      capability: '支付查询',
      agentId: 'a1',
      agentName: '小李',
      experience: '用 JOIN 优化查询',
    });

    const routes = store.findLearnedRoutes('支付');
    expect(routes).toHaveLength(1);
    expect(routes[0].experience).toBeTruthy();
    expect(routes[0].experience).toContain('JOIN');
  });

  it('should search experience field', () => {
    store.saveLearnedRoute({
      capability: '数据库操作',
      agentId: 'a1',
      agentName: '小李',
      experience: '风控规则需要配置白名单',
    });

    const routes = store.findLearnedRoutes('风控');
    expect(routes).toHaveLength(1);
  });

  it('should not match empty experience', () => {
    store.saveLearnedRoute({
      capability: '支付查询',
      agentId: 'a1',
      agentName: '小李',
    });

    const routes = store.findLearnedRoutes('风控');
    expect(routes).toHaveLength(0);
  });
});
