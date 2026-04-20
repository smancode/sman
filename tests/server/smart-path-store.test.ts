import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import fs from 'fs';
import path from 'path';
import os from 'os';
import { SmartPathStore } from '../../server/smart-path-store.js';

describe('SmartPathStore - FileSystem', () => {
  let tempDir: string;
  let store: SmartPathStore;

  beforeEach(() => {
    tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'sman-test-'));
    store = new SmartPathStore(tempDir);
  });

  afterEach(() => {
    fs.rmSync(tempDir, { recursive: true, force: true });
  });

  it('should create and save plan to .md file', () => {
    const plan = {
      id: 'test-plan-1',
      name: '测试计划',
      description: '测试描述',
      workspace: tempDir,
      steps: [{ mode: 'serial' as const, actions: [] }],
      status: 'draft' as const,
      createdAt: new Date().toISOString(),
    };

    const savedPath = store.savePlan(plan);
    expect(fs.existsSync(savedPath)).toBe(true);

    const content = fs.readFileSync(savedPath, 'utf-8');
    expect(content).toContain('name: 测试计划');
    expect(content).toContain('status: draft');
  });

  it('should list all plans in workspace', () => {
    const plan1 = {
      id: 'plan1',
      name: '计划1',
      workspace: tempDir,
      steps: [{ mode: 'serial' as const, actions: [] }],
      status: 'draft' as const,
      createdAt: new Date().toISOString(),
    };
    const plan2 = {
      id: 'plan2',
      name: '计划2',
      workspace: tempDir,
      steps: [{ mode: 'serial' as const, actions: [] }],
      status: 'draft' as const,
      createdAt: new Date().toISOString(),
    };

    store.savePlan(plan1);
    store.savePlan(plan2);

    const plans = store.listPlans(tempDir);
    expect(plans).toHaveLength(2);
    expect(plans[0].name).toBe('计划1');
  });

  it('should load plan from .md file', () => {
    const plan = {
      id: 'plan1',
      name: '计划1',
      workspace: tempDir,
      steps: [{ mode: 'serial' as const, actions: [] }],
      status: 'draft' as const,
      createdAt: new Date().toISOString(),
    };
    const savedPath = store.savePlan(plan);

    const loaded = store.loadPlan(savedPath);
    expect(loaded.name).toBe('计划1');
    expect(loaded.id).toBe('plan1');
  });

  it('should delete plan file', () => {
    const plan = {
      id: 'plan1',
      name: '计划1',
      workspace: tempDir,
      steps: [{ mode: 'serial' as const, actions: [] }],
      status: 'draft' as const,
      createdAt: new Date().toISOString(),
    };
    const savedPath = store.savePlan(plan);

    store.deletePlan(savedPath);
    expect(fs.existsSync(savedPath)).toBe(false);
  });

  it('should throw when name is empty', () => {
    const plan = {
      id: 'plan1',
      name: '',
      workspace: tempDir,
      steps: [{ mode: 'serial' as const, actions: [] }],
      status: 'draft' as const,
      createdAt: new Date().toISOString(),
    };

    expect(() => store.savePlan(plan)).toThrow('Plan name is required');
  });

  it('should throw when workspace is empty', () => {
    const plan = {
      id: 'plan1',
      name: '计划1',
      workspace: '',
      steps: [{ mode: 'serial' as const, actions: [] }],
      status: 'draft' as const,
      createdAt: new Date().toISOString(),
    };

    expect(() => store.savePlan(plan)).toThrow('Workspace is required');
  });
});
