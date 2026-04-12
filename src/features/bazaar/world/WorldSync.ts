// src/features/bazaar/world/WorldSync.ts
// Store ↔ Renderer 双向数据桥

import type { WorldRenderer } from './WorldRenderer';
import { AgentEntity } from './AgentEntity';
import { DESIGN } from './palette';
import { useBazaarStore } from '@/stores/bazaar';
import type { BazaarAgentInfo, WorldAgentPosition } from '@/types/bazaar';

const TS = DESIGN.TILE_SIZE;

const AGENT_COLORS = ['#41a6f6', '#38b764', '#ef7d57', '#b13e53', '#ffcd75', '#a7f070', '#73eff7'];

export class WorldSync {
  private renderer: WorldRenderer;
  private store: typeof useBazaarStore;
  private selfAgentId: string | null = null;
  private colorIndex = 0;

  constructor(renderer: WorldRenderer, store: typeof useBazaarStore) {
    this.renderer = renderer;
    this.store = store;
  }

  syncAgents(): void {
    const state = this.store.getState();
    const onlineAgents = state.onlineAgents;
    const worldPositions = state.worldPositions;
    const currentIds = new Set(this.renderer.getAllAgents().map(a => a.id));
    const onlineIds = new Set(onlineAgents.map(a => a.agentId));

    // Add / update
    for (const agent of onlineAgents) {
      if (!currentIds.has(agent.agentId)) {
        this.addAgentFromStore(agent);
      } else {
        this.updateAgentFromStore(agent);
      }
      // Sync server position to renderer (skip self — local control)
      if (agent.agentId !== this.selfAgentId) {
        const pos = worldPositions.get(agent.agentId);
        if (pos) {
          const entity = this.renderer.getAgent(agent.agentId);
          if (entity) entity.moveTo(pos.x, pos.y);
        }
      }
    }

    // Remove: both sources must be absent
    for (const id of currentIds) {
      if (!onlineIds.has(id) && !worldPositions.has(id) && id !== this.selfAgentId) {
        this.renderer.removeAgent(id);
      }
    }
  }

  moveSelfAgent(worldX: number, worldY: number): void {
    if (!this.selfAgentId) return;
    const agent = this.renderer.getAgent(this.selfAgentId);
    if (!agent) return;
    agent.moveTo(worldX, worldY);
    const { sendWorldMove } = this.store.getState();
    sendWorldMove(Math.round(worldX), Math.round(worldY), 'walking', agent.facing);
  }

  initSelfAgent(agentId: string, name: string, avatar: string): void {
    this.selfAgentId = agentId;
    const entity = new AgentEntity({
      id: agentId,
      name,
      avatar,
      reputation: 0,
      x: 20 * TS,
      y: 12 * TS,
      shirtColor: '#41a6f6',
    });
    entity.isSelf = true;
    this.renderer.addAgent(entity);
  }

  private addAgentFromStore(agent: BazaarAgentInfo): void {
    const worldPos = this.store.getState().worldPositions.get(agent.agentId);
    const entity = new AgentEntity({
      id: agent.agentId,
      name: agent.name,
      avatar: agent.avatar,
      reputation: agent.reputation,
      x: worldPos?.x ?? 20 * TS,
      y: worldPos?.y ?? 12 * TS,
      shirtColor: AGENT_COLORS[this.colorIndex++ % AGENT_COLORS.length],
    });
    this.renderer.addAgent(entity);
  }

  private updateAgentFromStore(agent: BazaarAgentInfo): void {
    const entity = this.renderer.getAgent(agent.agentId);
    if (!entity) return;
    entity.name = agent.name;
    entity.avatar = agent.avatar;
    entity.reputation = agent.reputation;
  }
}
