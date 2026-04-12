import { describe, it, expect, beforeEach } from 'vitest';
import { InteractionSystem } from '../InteractionSystem';
import { BuildingRegistry } from '../BuildingRegistry';
import type { BuildingData } from '../map-data';

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
});
