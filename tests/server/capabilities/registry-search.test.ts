import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { CapabilityRegistry } from '../../../server/capabilities/registry.js';
import type { CapabilityManifest, CapabilityEntry } from '../../../server/capabilities/types.js';

function makeEntry(overrides: Partial<CapabilityEntry> & { id: string }): CapabilityEntry {
  return {
    name: overrides.id,
    description: '',
    executionMode: 'instruction-inject',
    triggers: [],
    runnerModule: `./${overrides.id}.js`,
    pluginPath: overrides.id,
    enabled: true,
    version: '1.0.0',
    ...overrides,
  };
}

describe('CapabilityRegistry — keyword OR search', () => {
  let tmpDir: string;
  let registry: CapabilityRegistry;

  const officeCap = makeEntry({
    id: 'office-skills',
    name: 'Office 文档处理',
    description: '创建和编辑 PowerPoint、Word、Excel、PDF',
    triggers: ['PPT', 'PowerPoint', 'Word', 'docx', 'Excel', 'xlsx', 'PDF', '文档'],
  });

  const slidesCap = makeEntry({
    id: 'frontend-slides',
    name: 'HTML 幻灯片创建',
    description: '创建动画丰富的 HTML 演示文稿',
    triggers: ['演示', '幻灯片', 'slides', 'HTML presentation'],
  });

  const browserCap = makeEntry({
    id: 'browser-automation',
    name: '浏览器自动化',
    description: '操作浏览器，自动化 Web 任务',
    triggers: ['浏览器', '网页', 'QA', 'testing', 'E2E'],
  });

  beforeEach(() => {
    tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'sman-cap-search-'));
    const manifest: CapabilityManifest = {
      version: '1.0',
      capabilities: {
        'office-skills': officeCap,
        'frontend-slides': slidesCap,
        'browser-automation': browserCap,
      },
    };
    fs.writeFileSync(
      path.join(tmpDir, 'capabilities.json'),
      JSON.stringify(manifest),
    );
    registry = new CapabilityRegistry(tmpDir);
  });

  afterEach(() => {
    fs.rmSync(tmpDir, { recursive: true, force: true });
  });

  it('single keyword matches one capability via triggers', () => {
    const results = registry.searchByKeywords(['PPT']);
    expect(results).toHaveLength(1);
    expect(results[0].id).toBe('office-skills');
  });

  it('single keyword matches via name (case-insensitive)', () => {
    const results = registry.searchByKeywords(['html']);
    expect(results).toHaveLength(1);
    expect(results[0].id).toBe('frontend-slides');
  });

  it('single keyword matches via description', () => {
    const results = registry.searchByKeywords(['PowerPoint']);
    expect(results).toHaveLength(1);
    expect(results[0].id).toBe('office-skills');
  });

  it('OR logic: multiple keywords match multiple capabilities', () => {
    const results = registry.searchByKeywords(['PPT', 'slides']);
    expect(results).toHaveLength(2);
    const ids = results.map((r) => r.id).sort();
    expect(ids).toEqual(['frontend-slides', 'office-skills']);
  });

  it('OR logic: one keyword matches, another does not', () => {
    const results = registry.searchByKeywords(['Word', 'nonexistent']);
    expect(results).toHaveLength(1);
    expect(results[0].id).toBe('office-skills');
  });

  it('no keywords match returns empty array', () => {
    const results = registry.searchByKeywords(['nonexistent', 'xyz']);
    expect(results).toHaveLength(0);
  });

  it('empty keywords array returns empty array', () => {
    const results = registry.searchByKeywords([]);
    expect(results).toHaveLength(0);
  });

  it('duplicate results deduplicated when multiple keywords hit same capability', () => {
    const results = registry.searchByKeywords(['PPT', 'Word', 'Excel']);
    expect(results).toHaveLength(1);
    expect(results[0].id).toBe('office-skills');
  });
});

describe('CapabilityRegistry — semantic search (LLM fallback)', () => {
  let tmpDir: string;
  let registry: CapabilityRegistry;

  const officeCap = makeEntry({
    id: 'office-skills',
    name: 'Office 文档处理',
    description: '创建和编辑 PowerPoint、Word、Excel、PDF',
    triggers: ['PPT', 'PowerPoint', 'Word', 'docx', 'Excel', 'xlsx', 'PDF'],
  });

  const slidesCap = makeEntry({
    id: 'frontend-slides',
    name: 'HTML 幻灯片创建',
    description: '创建动画丰富的 HTML 演示文稿',
    triggers: ['演示', '幻灯片', 'slides'],
  });

  beforeEach(() => {
    tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'sman-cap-semantic-'));
    const manifest: CapabilityManifest = {
      version: '1.0',
      capabilities: {
        'office-skills': officeCap,
        'frontend-slides': slidesCap,
      },
    };
    fs.writeFileSync(
      path.join(tmpDir, 'capabilities.json'),
      JSON.stringify(manifest),
    );
    registry = new CapabilityRegistry(tmpDir);
  });

  afterEach(() => {
    fs.rmSync(tmpDir, { recursive: true, force: true });
  });

  it('returns empty when keywords empty and no natural language', async () => {
    const results = await registry.searchSemantic('', []);
    expect(results).toEqual([]);
  });

  it('returns LLM-matched capability IDs when keywords miss', async () => {
    // Mock the LLM call to return office-skills
    const mockFetch = vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve({
        content: [{ type: 'text', text: '["office-skills"]' }],
      }),
    });
    vi.stubGlobal('fetch', mockFetch);

    const results = await registry.searchSemantic(
      '我需要做一个季度销售总结的报告文档',
      [],
      {
        apiKey: 'test-key',
        model: 'test-model',
        baseUrl: 'https://api.test.com',
      },
    );

    expect(results).toEqual(['office-skills']);
    expect(mockFetch).toHaveBeenCalled();

    vi.restoreAllMocks();
  });

  it('returns empty array when LLM says no match', async () => {
    const mockFetch = vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve({
        content: [{ type: 'text', text: '[]' }],
      }),
    });
    vi.stubGlobal('fetch', mockFetch);

    const results = await registry.searchSemantic(
      '帮我搜索一下今天的天气',
      [],
      { apiKey: 'test-key', model: 'test-model', baseUrl: 'https://api.test.com' },
    );

    expect(results).toEqual([]);
    vi.restoreAllMocks();
  });

  it('includes capability summary in LLM prompt', async () => {
    const mockFetch = vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve({
        content: [{ type: 'text', text: '[]' }],
      }),
    });
    vi.stubGlobal('fetch', mockFetch);

    await registry.searchSemantic(
      '帮我做一个PPT',
      [],
      { apiKey: 'test-key', model: 'test-model', baseUrl: 'https://api.test.com' },
    );

    const callArgs = mockFetch.mock.calls[0];
    const body = JSON.parse(callArgs[1].body);
    // system is a top-level field in Anthropic Messages API
    const systemPrompt = body.system ?? body.messages?.[0]?.content ?? '';
    // The prompt should contain capability summaries
    expect(systemPrompt).toContain('office-skills');
    expect(systemPrompt).toContain('frontend-slides');
    vi.restoreAllMocks();
  });
});
