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
});
