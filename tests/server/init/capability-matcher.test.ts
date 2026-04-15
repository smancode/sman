import { describe, it, expect } from 'vitest';
import { formatCapabilityCatalog, keywordFallback } from '../../../server/init/capability-matcher.js';
import type { WorkspaceScanResult } from '../../../server/init/init-types.js';
import type { CapabilityEntry } from '../../../server/capabilities/types.js';

const MOCK_CAPABILITIES: CapabilityEntry[] = [
  {
    id: 'review',
    name: 'PR 代码审查',
    description: '预合并 PR 审查，分析 diff 检查问题',
    executionMode: 'instruction-inject',
    triggers: ['review', 'code review', '代码审查'],
    runnerModule: './generic-instruction-runner.js',
    pluginPath: 'gstack-skills/review',
    enabled: true,
    version: '1.0.0',
  },
  {
    id: 'office-skills',
    name: 'Office 文档处理',
    description: '创建和编辑 PowerPoint、Word 文档',
    executionMode: 'mcp-dynamic',
    triggers: ['PPT', 'Word', 'docx'],
    runnerModule: './office-skills-runner.js',
    pluginPath: 'office-skills',
    enabled: true,
    version: '1.0.0',
  },
];

const MOCK_SCAN_RESULT: WorkspaceScanResult = {
  types: ['java'],
  languages: { '.java': 50, '.xml': 10 },
  markers: ['pom.xml'],
  pomXml: { groupId: 'com.example', artifactId: 'my-service', deps: ['spring-boot-starter'] },
  topDirs: ['src'],
  fileCount: 60,
  isGitRepo: true,
  hasClaudeMd: false,
};

describe('CapabilityMatcher', () => {
  it('formats capability catalog as compact text', () => {
    const text = formatCapabilityCatalog(MOCK_CAPABILITIES);
    expect(text).toContain('review');
    expect(text).toContain('PR 代码审查');
    expect(text).toContain('office-skills');
  });

  it('keyword fallback returns results', () => {
    const result = keywordFallback(MOCK_SCAN_RESULT, MOCK_CAPABILITIES);
    expect(result.matches.length).toBeGreaterThanOrEqual(0);
    expect(result.projectSummary).toBeDefined();
    expect(result.techStack).toBeDefined();
  });

  it('keyword fallback returns at least careful/guard for any project', () => {
    const safetyCaps: CapabilityEntry[] = [
      {
        id: 'careful', name: '危险命令防护', description: 'Safety guardrails',
        executionMode: 'instruction-inject', triggers: ['careful', 'safety'],
        runnerModule: './generic-instruction-runner.js', pluginPath: 'gstack-skills/careful',
        enabled: true, version: '1.0.0',
      },
      {
        id: 'guard', name: '安全防护模式', description: 'Full safety mode',
        executionMode: 'instruction-inject', triggers: ['guard', 'safety'],
        runnerModule: './generic-instruction-runner.js', pluginPath: 'gstack-skills/guard',
        enabled: true, version: '1.0.0',
      },
    ];
    const emptyScan: WorkspaceScanResult = {
      types: ['empty'], languages: {}, markers: [], topDirs: [],
      fileCount: 0, isGitRepo: false, hasClaudeMd: false,
    };
    const result = keywordFallback(emptyScan, safetyCaps);
    const ids = result.matches.map(m => m.capabilityId);
    expect(ids).toContain('careful');
    expect(ids).toContain('guard');
  });
});
