import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { CapabilityRegistry } from '../../../server/capabilities/registry.js';

describe('CapabilityRegistry — usage tracking', () => {
  let tmpDir: string;
  let registry: CapabilityRegistry;

  beforeEach(() => {
    tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'sman-usage-'));
    // Create empty capabilities.json so registry doesn't crash
    fs.writeFileSync(
      path.join(tmpDir, 'capabilities.json'),
      JSON.stringify({ version: '1.0', capabilities: {} }),
    );
    registry = new CapabilityRegistry(tmpDir);
  });

  afterEach(() => {
    fs.rmSync(tmpDir, { recursive: true, force: true });
  });

  it('records first usage of a capability', () => {
    registry.recordUsage('office-skills', '创建季度PPT', true);

    const usage = registry.getUsage('office-skills')!;
    expect(usage).toBeDefined();
    expect(usage.count).toBe(1);
    expect(usage.successRate).toBe(1);
    expect(usage.recentTasks).toContain('创建季度PPT');
    expect(usage.lastUsed).not.toBeNull();
  });

  it('increments count on subsequent usage', () => {
    registry.recordUsage('office-skills', 'task1', true);
    registry.recordUsage('office-skills', 'task2', true);
    registry.recordUsage('office-skills', 'task3', false);

    const usage = registry.getUsage('office-skills')!;
    expect(usage.count).toBe(3);
    expect(usage.successRate).toBeCloseTo(2 / 3, 1);
  });

  it('persists usage to disk (capability-usage.json)', () => {
    registry.recordUsage('office-skills', 'task1');
    registry.recordUsage('frontend-slides', 'task2');

    const usagePath = path.join(tmpDir, 'capability-usage.json');
    expect(fs.existsSync(usagePath)).toBe(true);

    const raw = JSON.parse(fs.readFileSync(usagePath, 'utf-8'));
    expect(raw.usage['office-skills'].count).toBe(1);
    expect(raw.usage['frontend-slides'].count).toBe(1);
  });

  it('keeps max 10 recent tasks', () => {
    for (let i = 0; i < 15; i++) {
      registry.recordUsage('office-skills', `task-${i}`);
    }

    const usage = registry.getUsage('office-skills')!;
    expect(usage.recentTasks).toHaveLength(10);
    // Most recent first
    expect(usage.recentTasks[0]).toBe('task-14');
  });

  it('searchByKeywords sorts by usage frequency', () => {
    // Set up capabilities
    fs.writeFileSync(
      path.join(tmpDir, 'capabilities.json'),
      JSON.stringify({
        version: '1.0',
        capabilities: {
          'office-skills': {
            id: 'office-skills', name: 'Office', description: 'PPT Word',
            executionMode: 'instruction-inject', triggers: ['PPT', 'Word'],
            runnerModule: './o.js', pluginPath: 'o', enabled: true, version: '1.0',
          },
          'frontend-slides': {
            id: 'frontend-slides', name: 'Slides', description: 'HTML slides',
            executionMode: 'instruction-inject', triggers: ['slides', 'HTML'],
            runnerModule: './s.js', pluginPath: 's', enabled: true, version: '1.0',
          },
        },
      }),
    );
    registry.reload();

    // Use office-skills 5 times, frontend-slides 1 time
    for (let i = 0; i < 5; i++) registry.recordUsage('office-skills', `task-${i}`);
    registry.recordUsage('frontend-slides', 'slide task');

    // Search with a broad keyword matching both
    const results = registry.searchByKeywords(['HTML']);
    // Both match, but office-skills used more → should come first IF it also matches
    // Actually HTML only matches frontend-slides via triggers/description
    // Let's use a keyword matching both: "PPT" matches office, "slides" matches frontend
    const broadResults = registry.searchByKeywords(['PPT', 'slides']);
    expect(broadResults).toHaveLength(2);
    // office-skills (5 uses) should be before frontend-slides (1 use)
    expect(broadResults[0].id).toBe('office-skills');
    expect(broadResults[1].id).toBe('frontend-slides');
  });

  it('getUsage returns undefined for unknown capability', () => {
    expect(registry.getUsage('nonexistent')).toBeUndefined();
  });
});
