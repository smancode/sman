// tests/server/capabilities/knowledge-loader.test.ts
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { loadKnowledgeOverviews } from '../../../server/capabilities/knowledge-loader.js';

describe('loadKnowledgeOverviews', () => {
  let workspace: string;

  beforeEach(() => {
    workspace = fs.mkdtempSync(path.join(os.tmpdir(), 'sman-knowledge-'));
  });

  afterEach(() => {
    fs.rmSync(workspace, { recursive: true, force: true });
  });

  it('returns null when no manifest.json exists', () => {
    expect(loadKnowledgeOverviews(workspace)).toBeNull();
  });

  it('returns null when manifest exists but no overview files', () => {
    const knowledgeDir = path.join(workspace, '.claude', 'knowledge');
    fs.mkdirSync(knowledgeDir, { recursive: true });
    fs.writeFileSync(
      path.join(knowledgeDir, 'manifest.json'),
      JSON.stringify({ version: '1.0', scannedAt: new Date().toISOString(), scanners: {} }),
    );
    expect(loadKnowledgeOverviews(workspace)).toBeNull();
  });

  it('concatenates available overview files with separator', () => {
    const knowledgeDir = path.join(workspace, '.claude', 'knowledge');
    fs.mkdirSync(path.join(knowledgeDir, 'structure'), { recursive: true });
    fs.mkdirSync(path.join(knowledgeDir, 'apis'), { recursive: true });
    fs.mkdirSync(path.join(knowledgeDir, 'external-calls'), { recursive: true });

    fs.writeFileSync(
      path.join(knowledgeDir, 'manifest.json'),
      JSON.stringify({ version: '1.0', scannedAt: new Date().toISOString(), scanners: {} }),
    );
    fs.writeFileSync(
      path.join(knowledgeDir, 'structure', 'overview.md'),
      '# Structure\n- Java 17 + Spring Boot',
    );
    fs.writeFileSync(
      path.join(knowledgeDir, 'apis', 'overview.md'),
      '# APIs\n| POST | /api/payment |',
    );

    const result = loadKnowledgeOverviews(workspace);
    expect(result).not.toBeNull();
    expect(result!).toContain('# Structure');
    expect(result!).toContain('# APIs');
    expect(result!).toContain('---');
    expect(result!).toContain('.claude/knowledge/{category}/');
    // external-calls has no overview.md, should not appear as a heading
    expect(result!).not.toContain('external-calls');
  });

  it('handles partial overviews (only structure)', () => {
    const knowledgeDir = path.join(workspace, '.claude', 'knowledge');
    fs.mkdirSync(path.join(knowledgeDir, 'structure'), { recursive: true });

    fs.writeFileSync(
      path.join(knowledgeDir, 'manifest.json'),
      JSON.stringify({ version: '1.0', scannedAt: new Date().toISOString(), scanners: {} }),
    );
    fs.writeFileSync(
      path.join(knowledgeDir, 'structure', 'overview.md'),
      '# Structure\n- Node.js project',
    );

    const result = loadKnowledgeOverviews(workspace);
    expect(result).toContain('# Structure');
    expect(result).toContain('Node.js project');
  });
});
