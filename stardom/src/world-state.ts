// stardom/src/world-state.ts

interface AgentPosition {
  agentId: string;
  x: number;
  y: number;
  state: string;
  facing: string;
  lastMoveAt: number;
  zone: string | null;
}

interface Zone {
  id: string;
  minX: number;
  minY: number;
  maxX: number;
  maxY: number;
}

const ZONES: Zone[] = [
  { id: 'plaza',      minX: 0,    minY: 288, maxX: 1280, maxY: 480 },
  { id: 'stalls',     minX: 0,    minY: 0,   maxX: 448,  maxY: 192 },
  { id: 'reputation', minX: 1056, minY: 0,   maxX: 1280, maxY: 288 },
  { id: 'bounty',     minX: 0,    minY: 608, maxX: 288,  maxY: 768 },
  { id: 'search',     minX: 1056, minY: 608, maxX: 1280, maxY: 768 },
  { id: 'workshop',   minX: 1056, minY: 736, maxX: 1280, maxY: 832 },
];

const BROADCAST_INTERVAL_MS = 200;

export class WorldState {
  private positions = new Map<string, AgentPosition>();
  private lastBroadcastAt = new Map<string, number>();
  private broadcastToAgent: (agentId: string, data: unknown) => void;
  private broadcastToAll: (data: unknown) => void;

  constructor(
    broadcastToAgent: (agentId: string, data: unknown) => void,
    broadcastToAll: (data: unknown) => void,
  ) {
    this.broadcastToAgent = broadcastToAgent;
    this.broadcastToAll = broadcastToAll;
  }

  handleMove(agentId: string, x: number, y: number, state: string, facing: string): void {
    const prev = this.positions.get(agentId);
    const prevZone = prev?.zone ?? null;
    const now = Date.now();

    const zone = this.findZone(x, y);
    this.positions.set(agentId, { agentId, x, y, state, facing, lastMoveAt: now, zone });

    if (prevZone !== zone) {
      if (prevZone) this.broadcastToAll({ type: 'world.leave_zone', agentId, zone: prevZone });
      if (zone) this.broadcastToAll({ type: 'world.enter_zone', agentId, zone });
    }

    const lastBroadcast = this.lastBroadcastAt.get(agentId) ?? 0;
    if (state === 'idle' || now - lastBroadcast >= BROADCAST_INTERVAL_MS) {
      this.broadcastToAll({ type: 'world.agent_update', agentId, x, y, state, facing });
      this.lastBroadcastAt.set(agentId, now);
    }
  }

  removeAgent(agentId: string): void {
    const pos = this.positions.get(agentId);
    if (pos?.zone) {
      this.broadcastToAll({ type: 'world.leave_zone', agentId, zone: pos.zone });
    }
    this.positions.delete(agentId);
    this.lastBroadcastAt.delete(agentId);
    this.broadcastToAll({ type: 'world.agent_leave', agentId });
  }

  handleAgentOnline(agentId: string): void {
    const snapshot = Array.from(this.positions.values());
    this.broadcastToAgent(agentId, { type: 'world.zone_snapshot', agents: snapshot });
    this.broadcastToAll({ type: 'world.agent_enter', agentId });
  }

  getAgentsInZone(zoneId: string): AgentPosition[] {
    return Array.from(this.positions.values()).filter(p => p.zone === zoneId);
  }

  private findZone(x: number, y: number): string | null {
    for (const z of ZONES) {
      if (x >= z.minX && x <= z.maxX && y >= z.minY && y <= z.maxY) return z.id;
    }
    return null;
  }
}
