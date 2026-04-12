// src/features/bazaar/world/BuildingRegistry.ts

export interface BuildingAction {
  panel: 'leaderboard' | 'tasks' | 'chat' | 'agents';
}

const DEFAULT_ACTIONS: Record<string, BuildingAction> = {
  stall: { panel: 'tasks' },
  reputation: { panel: 'leaderboard' },
  bounty: { panel: 'tasks' },
  search: { panel: 'tasks' },
  workshop: { panel: 'tasks' },
};

export class BuildingRegistry {
  private actions: Map<string, BuildingAction>;

  constructor(overrides?: Record<string, BuildingAction>) {
    this.actions = new Map(Object.entries({ ...DEFAULT_ACTIONS, ...overrides }));
  }

  getAction(buildingType: string): BuildingAction | null {
    return this.actions.get(buildingType) ?? null;
  }

  register(buildingType: string, action: BuildingAction): void {
    this.actions.set(buildingType, action);
  }
}
