import { describe, it, expect } from 'vitest';
import { getAppearance, hashAgentId } from '../appearance';

describe('appearance', () => {
  describe('hashAgentId', () => {
    it('should return a non-negative integer', () => {
      const hash = hashAgentId('test-agent');
      expect(hash).toBeGreaterThanOrEqual(0);
      expect(Number.isInteger(hash)).toBe(true);
    });

    it('should be deterministic', () => {
      expect(hashAgentId('abc')).toBe(hashAgentId('abc'));
    });

    it('should differ for different IDs', () => {
      expect(hashAgentId('a')).not.toBe(hashAgentId('b'));
    });
  });

  describe('getAppearance', () => {
    it('should return valid ranges', () => {
      const ap = getAppearance('any-id');
      expect(ap.hairStyle).toBeGreaterThanOrEqual(0);
      expect(ap.hairStyle).toBeLessThan(8);
      expect(ap.hairColor).toBeGreaterThanOrEqual(0);
      expect(ap.hairColor).toBeLessThan(7);
      expect(ap.skinTone).toBeGreaterThanOrEqual(0);
      expect(ap.skinTone).toBeLessThan(4);
      expect(ap.outfitColor).toBeGreaterThanOrEqual(0);
      expect(ap.outfitColor).toBeLessThan(7);
    });

    it('should be deterministic', () => {
      expect(getAppearance('agent-1')).toEqual(getAppearance('agent-1'));
    });

    it('should produce different appearances for different IDs', () => {
      const a = getAppearance('agent-x');
      const b = getAppearance('agent-y');
      // At least one dimension should differ
      const same = a.hairStyle === b.hairStyle && a.hairColor === b.hairColor &&
                   a.skinTone === b.skinTone && a.outfitColor === b.outfitColor;
      expect(same).toBe(false);
    });
  });
});
