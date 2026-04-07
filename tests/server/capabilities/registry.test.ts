import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { CapabilityRegistry } from '../../../server/capabilities/registry.js';
import { initCapabilities } from '../../../scripts/init-capabilities.js';

describe('CapabilityRegistry', () => {
  let tmpDir: string;
  let registry: CapabilityRegistry;

  beforeEach(() => {
    tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'sman-cap-test-'));
    registry = new CapabilityRegistry(tmpDir);
  });

  afterEach(() => {
    fs.rmSync(tmpDir, { recursive: true, force: true });
  });

  it('returns empty capabilities when no registry file exists', () => {
    const caps = registry.listCapabilities();
    expect(caps).toEqual([]);
  });

  it('loads capabilities from JSON file', () => {
    const manifest = {
      version: '1.0',
      capabilities: {
        'test-cap': {
          id: 'test-cap',
          name: 'Test Capability',
          description: 'A test capability',
          executionMode: 'instruction-inject',
          triggers: ['test', 'testing'],
          runnerModule: './test-runner.js',
          pluginPath: 'test-plugin',
          enabled: true,
          version: '1.0.0',
        },
      },
    };
    fs.writeFileSync(
      path.join(tmpDir, 'capabilities.json'),
      JSON.stringify(manifest),
    );

    const caps = registry.listCapabilities();
    expect(caps).toHaveLength(1);
    expect(caps[0].id).toBe('test-cap');
    expect(caps[0].name).toBe('Test Capability');
  });

  it('getCapability returns specific capability by ID', () => {
    const manifest = {
      version: '1.0',
      capabilities: {
        'cap-a': {
          id: 'cap-a',
          name: 'Capability A',
          description: 'Cap A',
          executionMode: 'mcp-dynamic',
          triggers: [],
          runnerModule: './a.js',
          pluginPath: 'a',
          enabled: true,
          version: '1.0.0',
        },
        'cap-b': {
          id: 'cap-b',
          name: 'Capability B',
          description: 'Cap B',
          executionMode: 'instruction-inject',
          triggers: [],
          runnerModule: './b.js',
          pluginPath: 'b',
          enabled: false,
        },
      },
    };
    fs.writeFileSync(
      path.join(tmpDir, 'capabilities.json'),
      JSON.stringify(manifest),
    );

    expect(registry.getCapability('cap-a')).toBeDefined();
    expect(registry.getCapability('cap-a')!.name).toBe('Capability A');
    expect(registry.getCapability('cap-b')).toBeDefined();
    expect(registry.getCapability('nonexistent')).toBeUndefined();
  });

  it('listCapabilities only returns enabled capabilities', () => {
    const manifest = {
      version: '1.0',
      capabilities: {
        'enabled-cap': {
          id: 'enabled-cap',
          name: 'Enabled',
          description: 'Enabled cap',
          executionMode: 'mcp-dynamic',
          triggers: [],
          runnerModule: './e.js',
          pluginPath: 'e',
          enabled: true,
          version: '1.0.0',
        },
        'disabled-cap': {
          id: 'disabled-cap',
          name: 'Disabled',
          description: 'Disabled cap',
          executionMode: 'mcp-dynamic',
          triggers: [],
          runnerModule: './d.js',
          pluginPath: 'd',
          enabled: false,
          version: '1.0.0',
        },
      },
    };
    fs.writeFileSync(
      path.join(tmpDir, 'capabilities.json'),
      JSON.stringify(manifest),
    );

    const caps = registry.listCapabilities();
    expect(caps).toHaveLength(1);
    expect(caps[0].id).toBe('enabled-cap');
  });

  it('search matches against id, name, description, triggers', () => {
    const manifest = {
      version: '1.0',
      capabilities: {
        'office-skills': {
          id: 'office-skills',
          name: 'Office 文档处理',
          description: '创建和编辑 PowerPoint、Word、Excel、PDF',
          executionMode: 'mcp-dynamic',
          triggers: ['PPT', 'Word', 'Excel', 'PDF'],
          runnerModule: './office.js',
          pluginPath: 'office-skills',
          enabled: true,
          version: '1.0.0',
        },
      },
    };
    fs.writeFileSync(
      path.join(tmpDir, 'capabilities.json'),
      JSON.stringify(manifest),
    );

    // Match by trigger
    expect(registry.search('PPT')).toHaveLength(1);
    // Match by name (partial)
    expect(registry.search('office')).toHaveLength(1);
    // Match by description
    expect(registry.search('powerpoint')).toHaveLength(1);
    // No match
    expect(registry.search('nonexistent')).toHaveLength(0);
  });

  it('reloads from disk on reload()', () => {
    // First load: empty
    expect(registry.listCapabilities()).toHaveLength(0);

    // Write a file
    const manifest = {
      version: '1.0',
      capabilities: {
        'new-cap': {
          id: 'new-cap',
          name: 'New',
          description: 'New cap',
          executionMode: 'instruction-inject',
          triggers: [],
          runnerModule: './n.js',
          pluginPath: 'n',
          enabled: true,
          version: '1.0.0',
        },
      },
    };
    fs.writeFileSync(
      path.join(tmpDir, 'capabilities.json'),
      JSON.stringify(manifest),
    );

    // Still cached empty
    expect(registry.listCapabilities()).toHaveLength(0);

    // Force reload
    const reloaded = registry.reload();
    expect(Object.keys(reloaded.capabilities)).toHaveLength(1);
    expect(registry.listCapabilities()).toHaveLength(1);
  });
});

describe('initCapabilities', () => {
  let tmpHome: string;
  let tmpPlugins: string;

  beforeEach(() => {
    tmpHome = fs.mkdtempSync(path.join(os.tmpdir(), 'sman-init-test-'));
    tmpPlugins = fs.mkdtempSync(path.join(os.tmpdir(), 'sman-plugins-'));
  });

  afterEach(() => {
    fs.rmSync(tmpHome, { recursive: true, force: true });
    fs.rmSync(tmpPlugins, { recursive: true, force: true });
  });

  it('generates capabilities.json with only installed plugins', () => {
    // Create office-skills plugin dir
    fs.mkdirSync(path.join(tmpPlugins, 'office-skills', 'public'), { recursive: true });

    const manifest = initCapabilities(tmpHome, tmpPlugins);
    const registryPath = path.join(tmpHome, 'capabilities.json');

    // File should be written
    expect(fs.existsSync(registryPath)).toBe(true);

    // Only office-skills should be present (frontend-slides dir doesn't exist)
    expect(Object.keys(manifest.capabilities)).toContain('office-skills');
    // frontend-slides should be skipped since plugin dir doesn't exist
    expect(Object.keys(manifest.capabilities)).not.toContain('frontend-slides');
  });

  it('preserves enabled/disabled preferences on re-init', () => {
    // First init
    fs.mkdirSync(path.join(tmpPlugins, 'office-skills', 'public'), { recursive: true });
    fs.mkdirSync(path.join(tmpPlugins, 'frontend-slides'), { recursive: true });
    initCapabilities(tmpHome, tmpPlugins);

    // Manually disable office-skills
    const raw = JSON.parse(fs.readFileSync(path.join(tmpHome, 'capabilities.json'), 'utf-8'));
    raw.capabilities['office-skills'].enabled = false;
    fs.writeFileSync(path.join(tmpHome, 'capabilities.json'), JSON.stringify(raw));

    // Re-init
    const manifest = initCapabilities(tmpHome, tmpPlugins);
    expect(manifest.capabilities['office-skills'].enabled).toBe(false);
    expect(manifest.capabilities['frontend-slides'].enabled).toBe(true);
  });

  it('creates homeDir if it does not exist', () => {
    const newHome = path.join(tmpHome, 'nested', 'dir');
    fs.mkdirSync(path.join(tmpPlugins, 'office-skills', 'public'), { recursive: true });

    initCapabilities(newHome, tmpPlugins);
    expect(fs.existsSync(path.join(newHome, 'capabilities.json'))).toBe(true);
  });
});
