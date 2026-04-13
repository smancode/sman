// src/features/bazaar/world/InteractionSystem.ts

import { DESIGN } from './palette';
import type { BuildingData } from './map-data';
import { BuildingRegistry, type BuildingAction } from './BuildingRegistry';
import type { AgentEntity } from './AgentEntity';

const TS = DESIGN.TILE_SIZE;

export interface HitResult {
  consumed: boolean;
  type: 'building' | 'agent';
  target: BuildingData | AgentEntity;
}

export class InteractionSystem {
  private buildings: BuildingData[];
  private registry: BuildingRegistry;

  constructor(buildings: BuildingData[], registry: BuildingRegistry) {
    this.buildings = buildings;
    this.registry = registry;
  }

  hitTestBuildings(worldX: number, worldY: number): HitResult | null {
    for (const b of this.buildings) {
      const bx = b.col * TS;
      const by = b.row * TS;
      if (worldX >= bx && worldX <= bx + b.width && worldY >= by && worldY <= by + b.height) {
        return { consumed: true, type: 'building', target: b };
      }
    }
    return null;
  }

  hitTestAgents(worldX: number, worldY: number, agents: ReadonlyArray<AgentEntity>): HitResult | null {
    for (const agent of agents) {
      if (agent.hitTest(worldX, worldY)) {
        return { consumed: true, type: 'agent', target: agent };
      }
    }
    return null;
  }

  /**
   * Hover 检测 — building priority > agent
   */
  hoverTest(worldX: number, worldY: number, agents: ReadonlyArray<AgentEntity>): HitResult | null {
    const buildingHit = this.hitTestBuildings(worldX, worldY);
    if (buildingHit) return buildingHit;
    return this.hitTestAgents(worldX, worldY, agents);
  }

  handleBuildingClick(building: BuildingData): BuildingAction | null {
    return this.registry.getAction(building.type);
  }
}
