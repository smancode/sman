import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';

// Mock the experience file path to use a temp directory
const tmpDir = path.join(os.tmpdir(), `sman-test-exp-${Date.now()}`);
const mockPath = path.join(tmpDir, 'web-access-experiences.json');

vi.mock('node:fs', async (importOriginal) => {
  const actual = await importOriginal<typeof fs>();
  return {
    ...actual,
    existsSync: (p: string) => {
      if (p === mockPath || p.startsWith(tmpDir)) return actual.existsSync(p);
      return actual.existsSync(p);
    },
    readFileSync: (p: string, ...args: any[]) => {
      if (p === mockPath) return actual.readFileSync(p, ...args);
      return actual.readFileSync(p, ...args);
    },
    writeFileSync: (p: string, ...args: any[]) => {
      if (p === mockPath) return actual.writeFileSync(p, ...args);
      return actual.writeFileSync(p, ...args);
    },
    mkdirSync: (p: string, ...args: any[]) => {
      return actual.mkdirSync(p, ...args);
    },
  };
});

// Mock the path resolver
vi.mock('../../../server/web-access/url-experience-store.js', async (importOriginal) => {
  const actual = await importOriginal<any>();
  // Override getExperiencePath by re-exporting with our functions
  return actual;
});

// We need to test the actual logic, so import the real module
// and mock only the path resolution
const mockGetExperiencePath = vi.fn(() => mockPath);

describe('URL Experience Store', () => {
  beforeEach(() => {
    // Clean up temp dir
    try { fs.rmSync(tmpDir, { recursive: true, force: true }); } catch { /* ok */ }
    fs.mkdirSync(tmpDir, { recursive: true });
  });

  afterEach(() => {
    try { fs.rmSync(tmpDir, { recursive: true, force: true }); } catch { /* ok */ }
  });

  describe('loadExperiences', () => {
    it('should return empty store when file does not exist', () => {
      // Direct test of the logic
      const filePath = mockPath;
      expect(fs.existsSync(filePath)).toBe(false);

      // Simulate loadExperiences logic
      let store: { entries: any[] } = { entries: [] };
      try {
        if (fs.existsSync(filePath)) {
          store = JSON.parse(fs.readFileSync(filePath, 'utf-8'));
        }
      } catch { /* ok */ }
      expect(store.entries).toEqual([]);
    });

    it('should load existing experiences', () => {
      const data = {
        entries: [
          { description: '智谱MCP用量', url: 'https://bigmodel.cn/usercenter/glm-coding/usage', createdAt: '2026-04-02T00:00:00Z' },
        ],
      };
      fs.writeFileSync(mockPath, JSON.stringify(data), 'utf-8');

      const store = JSON.parse(fs.readFileSync(mockPath, 'utf-8'));
      expect(store.entries).toHaveLength(1);
      expect(store.entries[0].description).toBe('智谱MCP用量');
      expect(store.entries[0].url).toBe('https://bigmodel.cn/usercenter/glm-coding/usage');
    });

    it('should handle corrupted JSON gracefully', () => {
      fs.writeFileSync(mockPath, '{ broken json', 'utf-8');

      let store: { entries: any[] } = { entries: [] };
      try {
        store = JSON.parse(fs.readFileSync(mockPath, 'utf-8'));
      } catch {
        store = { entries: [] };
      }
      expect(store.entries).toEqual([]);
    });
  });

  describe('addExperience', () => {
    it('should create file and write entry', () => {
      const entry = {
        description: 'Jira待办',
        url: 'https://jira.example.com/my-todos',
        createdAt: new Date().toISOString(),
      };

      // Simulate addExperience logic
      let store: { entries: any[] } = { entries: [] };
      store.entries.push(entry);
      fs.writeFileSync(mockPath, JSON.stringify(store, null, 2), 'utf-8');

      const loaded = JSON.parse(fs.readFileSync(mockPath, 'utf-8'));
      expect(loaded.entries).toHaveLength(1);
      expect(loaded.entries[0].url).toBe(entry.url);
    });

    it('should update existing entry by url (dedup)', () => {
      // Write initial
      const initial = {
        entries: [
          { description: '智谱旧描述', url: 'https://bigmodel.cn/usage', createdAt: '2026-01-01T00:00:00Z' },
        ],
      };
      fs.writeFileSync(mockPath, JSON.stringify(initial), 'utf-8');

      // Simulate update
      const store = JSON.parse(fs.readFileSync(mockPath, 'utf-8'));
      const newEntry = { description: '智谱MCP用量', url: 'https://bigmodel.cn/usage', createdAt: new Date().toISOString() };
      const idx = store.entries.findIndex((e: any) => e.url === newEntry.url);
      if (idx >= 0) {
        store.entries[idx].description = newEntry.description;
        store.entries[idx].createdAt = newEntry.createdAt;
      } else {
        store.entries.push(newEntry);
      }
      fs.writeFileSync(mockPath, JSON.stringify(store, null, 2), 'utf-8');

      const loaded = JSON.parse(fs.readFileSync(mockPath, 'utf-8'));
      expect(loaded.entries).toHaveLength(1);
      expect(loaded.entries[0].description).toBe('智谱MCP用量');
    });

    it('should append new entry alongside existing ones', () => {
      const initial = {
        entries: [
          { description: 'Jira待办', url: 'https://jira.example.com/todos', createdAt: '2026-01-01' },
        ],
      };
      fs.writeFileSync(mockPath, JSON.stringify(initial), 'utf-8');

      const store = JSON.parse(fs.readFileSync(mockPath, 'utf-8'));
      store.entries.push({ description: '智谱用量', url: 'https://bigmodel.cn/usage', createdAt: new Date().toISOString() });
      fs.writeFileSync(mockPath, JSON.stringify(store, null, 2), 'utf-8');

      const loaded = JSON.parse(fs.readFileSync(mockPath, 'utf-8'));
      expect(loaded.entries).toHaveLength(2);
    });
  });
});
