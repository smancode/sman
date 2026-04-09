// tests/server/capabilities/scanner-prompts.test.ts
import { describe, it, expect } from 'vitest';
import {
  getScannerPrompt,
  SCANNER_TYPES,
  type ScannerType,
} from '../../../server/capabilities/scanner-prompts.js';

describe('scanner-prompts', () => {
  it('exports 3 scanner types', () => {
    expect(SCANNER_TYPES).toHaveLength(3);
    expect(SCANNER_TYPES).toContain('structure');
    expect(SCANNER_TYPES).toContain('apis');
    expect(SCANNER_TYPES).toContain('external-calls');
  });

  it('each prompt contains output directory instruction', () => {
    for (const type of SCANNER_TYPES) {
      const prompt = getScannerPrompt(type as ScannerType, '/tmp/test-workspace');
      expect(prompt).toContain('.claude/skills/project-');
      expect(prompt).toContain(type);
    }
  });

  it('each prompt contains safety rules', () => {
    for (const type of SCANNER_TYPES) {
      const prompt = getScannerPrompt(type as ScannerType, '/tmp/test-workspace');
      expect(prompt).toContain('.env');
      expect(prompt).toContain('⚠️');
      expect(prompt).toContain('preprocessing aid');
      expect(prompt).toContain('Output language: English');
    }
  });

  it('each prompt contains workspace path', () => {
    const prompt = getScannerPrompt('structure', '/Users/x/projects/payment');
    expect(prompt).toContain('/Users/x/projects/payment');
  });

  it('throws for unknown scanner type', () => {
    expect(() => getScannerPrompt('unknown' as ScannerType, '/tmp')).toThrow();
  });

  it('prompt contains _scanned fields for all scanners', () => {
    for (const type of SCANNER_TYPES) {
      const prompt = getScannerPrompt(type as ScannerType, '/tmp/test');
      expect(prompt).toContain('_scanned:');
      expect(prompt).toContain('commitHash');
      expect(prompt).toContain('scannedAt');
      expect(prompt).toContain('branch');
    }
  });

  it('prompt accepts and injects git info', () => {
    const prompt = getScannerPrompt('structure', '/tmp/test', {
      commitHash: 'abc123',
      branch: 'main'
    });
    expect(prompt).toContain('abc123');
    expect(prompt).toContain('main');
  });

  it('prompt uses unknown when git info not provided', () => {
    const prompt = getScannerPrompt('structure', '/tmp/test');
    expect(prompt).toContain('unknown');
  });

  it('prompt contains ISO timestamp for scannedAt', () => {
    const before = new Date().toISOString();
    const prompt = getScannerPrompt('structure', '/tmp/test');
    const after = new Date().toISOString();
    // scannedAt should be a valid ISO date string (contains T and Z)
    expect(prompt).toMatch(/scannedAt.*\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}/);
  });

  it('prompt contains _scanned rule in shared rules', () => {
    const prompt = getScannerPrompt('structure', '/tmp/test');
    expect(prompt).toContain('_scanned.commitHash');
    expect(prompt).toContain('_scanned.scannedAt');
    expect(prompt).toContain('_scanned.branch');
  });
});
