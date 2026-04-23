// stardom/tests/capability-store.test.ts
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { CapabilityStore } from '../src/capability-store.js';
import fs from 'fs';
import path from 'path';
import os from 'os';

describe('CapabilityStore', () => {
  let store: CapabilityStore;
  let dbPath: string;

  beforeEach(() => {
    dbPath = path.join(os.tmpdir(), `capability-test-${Date.now()}.db`);
    store = new CapabilityStore(dbPath);
  });

  afterEach(() => {
    store.close();
    if (fs.existsSync(dbPath)) fs.unlinkSync(dbPath);
  });

  it('should publish and get a capability', () => {
    store.publish({
      name: 'payment-query',
      description: '支付系统查询工具',
      version: '1.0.0',
      category: '金融',
      packageUrl: 'https://packages.sman.dev/payment-query-1.0.0.tar.gz',
      readme: '# Payment Query\n查询支付流水和转账记录',
    });

    const cap = store.get('payment-query');
    expect(cap).toBeDefined();
    expect(cap!.description).toBe('支付系统查询工具');
    expect(cap!.version).toBe('1.0.0');
  });

  it('should search capabilities by keyword', () => {
    store.publish({ name: 'payment-query', description: '支付查询', version: '1.0.0', category: '金融', packageUrl: 'http://x' });
    store.publish({ name: 'risk-control', description: '风控规则引擎', version: '1.0.0', category: '金融', packageUrl: 'http://x' });
    store.publish({ name: 'deploy-tool', description: '部署工具', version: '1.0.0', category: '运维', packageUrl: 'http://x' });

    const results = store.search('支付');
    expect(results).toHaveLength(1);
    expect(results[0].name).toBe('payment-query');
  });

  it('should search by name and description', () => {
    store.publish({ name: 'log-analyzer', description: '日志分析工具', version: '1.0.0', category: '运维', packageUrl: 'http://x' });

    const byName = store.search('log');
    expect(byName).toHaveLength(1);

    const byDesc = store.search('分析');
    expect(byDesc).toHaveLength(1);
  });

  it('should update existing capability', () => {
    store.publish({ name: 'payment-query', description: '支付查询', version: '1.0.0', category: '金融', packageUrl: 'http://x' });
    store.publish({ name: 'payment-query', description: '支付系统查询（增强版）', version: '1.1.0', category: '金融', packageUrl: 'http://x/v2' });

    const cap = store.get('payment-query');
    expect(cap!.version).toBe('1.1.0');
    expect(cap!.description).toBe('支付系统查询（增强版）');
  });

  it('should list all capabilities', () => {
    store.publish({ name: 'a', description: 'A', version: '1.0.0', category: '通用', packageUrl: 'http://x' });
    store.publish({ name: 'b', description: 'B', version: '1.0.0', category: '通用', packageUrl: 'http://x' });

    expect(store.list()).toHaveLength(2);
  });

  it('should delete capability', () => {
    store.publish({ name: 'a', description: 'A', version: '1.0.0', category: '通用', packageUrl: 'http://x' });
    store.remove('a');
    expect(store.get('a')).toBeUndefined();
  });
});
