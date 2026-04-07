import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { CapabilityRegistry } from '../../../server/capabilities/registry.js';
import { learnFromConversation } from '../../../server/capabilities/experience-learner.js';

describe('CapabilityRegistry — user capabilities (experience learning)', () => {
  let tmpDir: string;
  let registry: CapabilityRegistry;

  beforeEach(() => {
    tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'sman-usercap-'));
    fs.writeFileSync(
      path.join(tmpDir, 'capabilities.json'),
      JSON.stringify({ version: '1.0', capabilities: {} }),
    );
    registry = new CapabilityRegistry(tmpDir);
  });

  afterEach(() => {
    fs.rmSync(tmpDir, { recursive: true, force: true });
  });

  it('saves a user capability to user-capabilities.json', () => {
    registry.saveUserCapability({
      id: 'itsm-approval',
      name: 'ITSM 审批操作',
      pattern: '用户经常需要登录 ITSM 系统，查看待办，执行审批操作',
      learnedFrom: 'web-access',
      shortcuts: ['审批', '待办', 'ITSM'],
      usageCount: 8,
      createdAt: '2026-04-01T00:00:00Z',
      lastUsed: '2026-04-07T10:00:00Z',
    });

    const caps = registry.listUserCapabilities();
    expect(caps).toHaveLength(1);
    expect(caps[0].id).toBe('itsm-approval');
    expect(caps[0].name).toBe('ITSM 审批操作');

    // Persisted to disk
    const raw = JSON.parse(
      fs.readFileSync(path.join(tmpDir, 'user-capabilities.json'), 'utf-8'),
    );
    expect(raw.userCapabilities['itsm-approval'].shortcuts).toEqual([
      '审批', '待办', 'ITSM',
    ]);
  });

  it('getUserCapability returns specific capability', () => {
    registry.saveUserCapability({
      id: 'weekly-report',
      name: '周报生成',
      pattern: '每周五生成周报',
      learnedFrom: 'office-skills',
      shortcuts: ['周报', 'weekly report'],
      usageCount: 1,
      createdAt: '2026-04-07T00:00:00Z',
      lastUsed: null,
    });

    const cap = registry.getUserCapability('weekly-report');
    expect(cap).toBeDefined();
    expect(cap!.name).toBe('周报生成');
  });

  it('user capabilities are separate from standard capabilities', () => {
    // Standard capabilities
    fs.writeFileSync(
      path.join(tmpDir, 'capabilities.json'),
      JSON.stringify({
        version: '1.0',
        capabilities: {
          'office-skills': {
            id: 'office-skills',
            name: 'Office 文档处理',
            description: 'PPT Word Excel PDF',
            executionMode: 'mcp-dynamic',
            triggers: ['PPT', 'Word'],
            runnerModule: './office-skills-runner.js',
            pluginPath: 'office-skills',
            enabled: true,
            version: '1.0.0',
          },
        },
      }),
    );

    // User capabilities
    registry.saveUserCapability({
      id: 'my-custom-workflow',
      name: '自定义工作流',
      pattern: '我自己的工作流',
      learnedFrom: 'office-skills',
      shortcuts: ['我的流程'],
      usageCount: 1,
      createdAt: '2026-04-07T00:00:00Z',
      lastUsed: null,
    });

    // listCapabilities only returns standard ones
    registry.reload();
    const standard = registry.listCapabilities();
    expect(standard).toHaveLength(1);
    expect(standard[0].id).toBe('office-skills');

    // listUserCapabilities returns user ones
    const user = registry.listUserCapabilities();
    expect(user).toHaveLength(1);
    expect(user[0].id).toBe('my-custom-workflow');

    // Files are separate
    expect(fs.existsSync(path.join(tmpDir, 'capabilities.json'))).toBe(true);
    expect(fs.existsSync(path.join(tmpDir, 'user-capabilities.json'))).toBe(true);
  });

  it('searchByKeywords also matches user capabilities', () => {
    registry.saveUserCapability({
      id: 'itsm-approval',
      name: 'ITSM 审批操作',
      pattern: '登录 ITSM 查看待办',
      learnedFrom: 'web-access',
      shortcuts: ['审批', 'ITSM', '待办'],
      usageCount: 5,
      createdAt: '2026-04-01T00:00:00Z',
      lastUsed: '2026-04-07T00:00:00Z',
    });

    const results = registry.searchByKeywords(['ITSM']);
    expect(results).toHaveLength(1);
    expect(results[0].id).toBe('itsm-approval');
    expect(results[0].name).toBe('ITSM 审批操作');
  });
});

describe('learnFromConversation — LLM experience extraction', () => {
  let tmpDir: string;

  beforeEach(() => {
    tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'sman-learn-'));
  });

  afterEach(() => {
    fs.rmSync(tmpDir, { recursive: true, force: true });
    vi.restoreAllMocks();
  });

  it('calls LLM and saves learned capabilities', async () => {
    const mockFetch = vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve({
        content: [{
          type: 'text',
          text: JSON.stringify({
            newCapabilities: [{
              id: 'itsm-approval',
              name: 'ITSM 审批操作',
              pattern: '用户需要定期登录 ITSM 系统审批待办事项',
              learnedFrom: 'web-access',
              shortcuts: ['审批', 'ITSM', '待办'],
            }],
          }),
        }],
      }),
    });
    vi.stubGlobal('fetch', mockFetch);

    const registry = new CapabilityRegistry(tmpDir);
    // Pre-seed a user capability to test merge
    registry.saveUserCapability({
      id: 'existing-cap',
      name: '已有能力',
      pattern: '旧模式',
      learnedFrom: 'office-skills',
      shortcuts: ['旧'],
      usageCount: 3,
      createdAt: '2026-04-01T00:00:00Z',
      lastUsed: '2026-04-05T00:00:00Z',
    });

    await learnFromConversation(
      registry,
      '用户: 帮我审批ITSM里的待办\n助手: 已完成审批操作',
      { apiKey: 'test-key', model: 'test-model', baseUrl: 'https://api.test.com' },
    );

    // Verify new capability was saved
    const learned = registry.getUserCapability('itsm-approval');
    expect(learned).toBeDefined();
    expect(learned!.name).toBe('ITSM 审批操作');
    expect(learned!.learnedFrom).toBe('web-access');

    // Verify existing capability was preserved
    const existing = registry.getUserCapability('existing-cap');
    expect(existing).toBeDefined();
    expect(existing!.usageCount).toBe(3);

    vi.restoreAllMocks();
  });

  it('gracefully handles LLM returning no new capabilities', async () => {
    const mockFetch = vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve({
        content: [{
          type: 'text',
          text: JSON.stringify({ newCapabilities: [] }),
        }],
      }),
    });
    vi.stubGlobal('fetch', mockFetch);

    const registry = new CapabilityRegistry(tmpDir);
    await learnFromConversation(
      registry,
      '用户: 今天天气怎么样\n助手: 我无法查询天气',
      { apiKey: 'test-key', model: 'test-model', baseUrl: 'https://api.test.com' },
    );

    expect(registry.listUserCapabilities()).toHaveLength(0);
    vi.restoreAllMocks();
  });

  it('handles LLM errors gracefully', async () => {
    const mockFetch = vi.fn().mockRejectedValue(new Error('Network error'));
    vi.stubGlobal('fetch', mockFetch);

    const registry = new CapabilityRegistry(tmpDir);
    // Should not throw
    await expect(
      learnFromConversation(
        registry,
        'some conversation',
        { apiKey: 'k', model: 'm', baseUrl: 'https://api.test.com' },
      ),
    ).resolves.toBeUndefined();

    vi.restoreAllMocks();
  });
});
