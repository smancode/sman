import { describe, it, expect, beforeEach } from 'vitest';
import { InteractionSystem } from '../InteractionSystem';
import { BuildingRegistry } from '../BuildingRegistry';
import type { BuildingData } from '../map-data';
import { AgentEntity } from '../AgentEntity';

const TEST_BUILDINGS: BuildingData[] = [
  { id: 'stall-1', type: 'stall', col: 2, row: 1, label: '摊位', width: 64, height: 64 },
  { id: 'reputation', type: 'reputation', col: 33, row: 1, label: '🏆 声望榜', width: 96, height: 96 },
];

describe('InteractionSystem', () => {
  let system: InteractionSystem;

  beforeEach(() => {
    const registry = new BuildingRegistry();
    system = new InteractionSystem(TEST_BUILDINGS, registry);
  });

  describe('hitTestBuildings', () => {
    it('should hit a building at its tile position', () => {
      const result = system.hitTestBuildings(96, 64);
      expect(result).not.toBeNull();
      expect(result!.type).toBe('building');
      expect((result!.target as BuildingData).id).toBe('stall-1');
    });

    it('should hit building at edge', () => {
      const result = system.hitTestBuildings(64, 32);
      expect(result).not.toBeNull();
      expect((result!.target as BuildingData).id).toBe('stall-1');
    });

    it('should miss outside building', () => {
      const result = system.hitTestBuildings(10, 10);
      expect(result).toBeNull();
    });

    it('should hit reputation board (96x96)', () => {
      const result = system.hitTestBuildings(1100, 80);
      expect(result).not.toBeNull();
      expect((result!.target as BuildingData).id).toBe('reputation');
    });
  });

  describe('handleBuildingClick', () => {
    it('should return panel action from registry', () => {
      const action = system.handleBuildingClick(TEST_BUILDINGS[0]);
      expect(action).toEqual({ panel: 'tasks' });
    });

    it('should return leaderboard for reputation', () => {
      const action = system.handleBuildingClick(TEST_BUILDINGS[1]);
      expect(action).toEqual({ panel: 'leaderboard' });
    });
  });

  describe('hover detection', () => {
    it('should detect building hover', () => {
      const result = system.hoverTest(96, 64, []);
      expect(result).not.toBeNull();
      expect(result!.type).toBe('building');
    });

    it('should detect agent hover', () => {
      const agent = new AgentEntity({ id: 'a1', name: 'Test', avatar: '🧙', reputation: 0, x: 500, y: 500 });
      // hitTest circle center at (500, 484), radius 16
      const result = system.hoverTest(500, 484, [agent]);
      expect(result).not.toBeNull();
      expect(result!.type).toBe('agent');
    });

    it('should return null when hovering nothing', () => {
      const result = system.hoverTest(0, 0, []);
      expect(result).toBeNull();
    });

    it('should prioritize building over agent at same position', () => {
      const agent = new AgentEntity({ id: 'a1', name: 'Test', avatar: '🧙', reputation: 0, x: 96, y: 80 });
      // Both building (stall-1 at tile col=2,row=1 → pixel 64,32) and agent nearby
      const result = system.hoverTest(96, 64, [agent]);
      expect(result).not.toBeNull();
      expect(result!.type).toBe('building');
    });
  });
});
