// bazaar/tests/reputation.test.ts
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { ReputationEngine } from '../src/reputation.js';
import type { AgentStore } from '../src/agent-store.js';

function createMockAgentStore(): AgentStore {
  return {
    updateReputation: vi.fn(),
    logReputation: vi.fn(),
    getReputationCountToday: vi.fn(() => 0),
    getAgent: vi.fn(() => ({ reputation: 10 })),
    logAudit: vi.fn(),
  } as unknown as AgentStore;
}

describe('ReputationEngine', () => {
  let engine: ReputationEngine;
  let store: AgentStore;

  beforeEach(() => {
    store = createMockAgentStore();
    engine = new ReputationEngine(store);
  });

  describe('onTaskComplete', () => {
    it('should calculate helper reputation: base + quality', () => {
      const result = engine.onTaskComplete('task-1', 'req-1', 'help-1', 4);

      // helperDelta = 1.0 + 4*0.5 = 3.0
      expect(result.helperDelta).toBe(3.0);
      expect(result.requesterDelta).toBe(0.3);
      expect(store.updateReputation).toHaveBeenCalledWith('help-1', 3.0);
      expect(store.updateReputation).toHaveBeenCalledWith('req-1', 0.3);
    });

    it('should give minimum helper score even with rating 1', () => {
      const result = engine.onTaskComplete('task-1', 'req-1', 'help-1', 1);
      // helper: 1.0 + 1*0.5 = 1.5
      expect(result.helperDelta).toBe(1.5);
    });

    it('should cap helper score at rating 5', () => {
      const result = engine.onTaskComplete('task-1', 'req-1', 'help-1', 5);
      // helper: 1.0 + 5*0.5 = 3.5
      expect(result.helperDelta).toBe(3.5);
    });

    it('should skip helper if daily cap reached (3 per pair)', () => {
      (store.getReputationCountToday as any).mockReturnValue(3);
      const result = engine.onTaskComplete('task-1', 'req-1', 'help-1', 5);
      expect(result.helperDelta).toBe(0);
      expect(store.updateReputation).not.toHaveBeenCalledWith('help-1', expect.anything());
    });

    it('should log reputation events', () => {
      engine.onTaskComplete('task-1', 'req-1', 'help-1', 4);
      expect(store.logReputation).toHaveBeenCalledWith('help-1', 'task-1', 1.0, 'base', 'req-1');
      expect(store.logReputation).toHaveBeenCalledWith('help-1', 'task-1', 2.0, 'quality', 'req-1');
      expect(store.logReputation).toHaveBeenCalledWith('req-1', 'task-1', 0.3, 'question_bonus', 'help-1');
    });
  });
});
