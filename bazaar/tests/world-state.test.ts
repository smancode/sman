import { describe, it, expect, beforeEach } from 'vitest';
import { WorldState } from '../src/world-state.js';

describe('WorldState', () => {
  let state: WorldState;
  let broadcasts: unknown[];
  let agentMessages: Map<string, unknown[]>;

  beforeEach(() => {
    broadcasts = [];
    agentMessages = new Map();
    state = new WorldState(
      (agentId: string, data: unknown) => {
        const list = agentMessages.get(agentId) ?? [];
        list.push(data);
        agentMessages.set(agentId, list);
      },
      (data: unknown) => broadcasts.push(data),
    );
  });

  describe('handleMove', () => {
    it('should track agent position', () => {
      state.handleMove('a1', 100, 300, 'walking', 'right');
      const agents = state.getAgentsInZone('plaza');
      expect(agents.length).toBeGreaterThan(0);
      expect(agents[0].agentId).toBe('a1');
    });

    it('should broadcast position update', () => {
      state.handleMove('a1', 100, 200, 'idle', 'down');
      expect(broadcasts.length).toBe(1);
      expect(broadcasts[0]).toMatchObject({ type: 'world.agent_update', agentId: 'a1' });
    });

    it('should throttle broadcasts to 5fps', () => {
      state.handleMove('a1', 100, 200, 'walking', 'right');
      state.handleMove('a1', 101, 201, 'walking', 'right');
      expect(broadcasts.length).toBe(1);
    });

    it('should broadcast immediately on idle', () => {
      state.handleMove('a1', 100, 200, 'walking', 'right');
      state.handleMove('a1', 101, 201, 'walking', 'right');
      state.handleMove('a1', 101, 201, 'idle', 'right');
      expect(broadcasts.length).toBe(2);
    });
  });

  describe('zones', () => {
    it('should detect zone change and broadcast', () => {
      state.handleMove('a1', 640, 400, 'idle', 'down');
      broadcasts.length = 0;
      state.handleMove('a1', 100, 100, 'idle', 'up');
      const zoneEvents = broadcasts.filter((m: any) => m.type === 'world.enter_zone' || m.type === 'world.leave_zone');
      expect(zoneEvents.length).toBeGreaterThanOrEqual(1);
    });
  });

  describe('handleAgentOnline', () => {
    it('should send snapshot to new agent', () => {
      state.handleMove('a1', 100, 200, 'idle', 'down');
      state.handleAgentOnline('a2');
      const msgs = agentMessages.get('a2');
      expect(msgs).toBeDefined();
      expect(msgs![0]).toMatchObject({ type: 'world.zone_snapshot' });
    });

    it('should broadcast agent_enter to all', () => {
      state.handleAgentOnline('a1');
      expect(broadcasts).toContainEqual(expect.objectContaining({ type: 'world.agent_enter', agentId: 'a1' }));
    });
  });

  describe('removeAgent', () => {
    it('should remove position and broadcast leave', () => {
      state.handleMove('a1', 100, 200, 'idle', 'down');
      broadcasts.length = 0;
      state.removeAgent('a1');
      expect(broadcasts).toContainEqual(expect.objectContaining({ type: 'world.agent_leave', agentId: 'a1' }));
    });
  });
});
