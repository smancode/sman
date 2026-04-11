// src/features/bazaar/world/WorldRenderer.ts
// 世界渲染引擎 — 管理瓦片、建筑、Agent 的分层渲染

import { DESIGN } from './palette';
import { MAP_DATA, BUILDINGS } from './map-data';
import { TileMap } from './TileMap';
import { getAgentSprite, getBuildingSprite } from './SpriteSheet';
import { AgentEntity } from './AgentEntity';

const TS = DESIGN.TILE_SIZE;

export class WorldRenderer {
  private canvas: HTMLCanvasElement | null = null;
  private ctx: CanvasRenderingContext2D | null = null;
  private tileMap: TileMap;
  private agents: Map<string, AgentEntity> = new Map();
  private animFrameId: number = 0;
  private running = false;

  // 相机（视口偏移）
  private cameraX = 0;
  private cameraY = 0;

  // 碰撞边界（像素）
  private bounds = {
    minX: 0,
    maxX: DESIGN.MAP_COLS * TS,
    minY: 0,
    maxY: DESIGN.MAP_ROWS * TS,
  };

  // 建筑离屏缓存
  private buildingLayer: OffscreenCanvas | null = null;

  constructor() {
    this.tileMap = new TileMap(MAP_DATA as number[][]);
  }

  init(canvas: HTMLCanvasElement): void {
    this.canvas = canvas;
    this.ctx = canvas.getContext('2d')!;
    this.ctx.imageSmoothingEnabled = false;

    // HiDPI 适配
    const dpr = window.devicePixelRatio || 1;
    const rect = canvas.getBoundingClientRect();
    canvas.width = rect.width * dpr;
    canvas.height = rect.height * dpr;
    this.ctx.scale(dpr, dpr);

    // 居中相机
    this.cameraX = (this.bounds.maxX - rect.width) / 2;
    this.cameraY = (this.bounds.maxY - rect.height) / 2;
  }

  start(): void {
    if (this.running) return;
    this.running = true;
    this.loop();
  }

  stop(): void {
    this.running = false;
    if (this.animFrameId) {
      cancelAnimationFrame(this.animFrameId);
    }
  }

  addAgent(agent: AgentEntity): void {
    this.agents.set(agent.id, agent);
  }

  removeAgent(id: string): void {
    this.agents.delete(id);
  }

  getAgent(id: string): AgentEntity | undefined {
    return this.agents.get(id);
  }

  getAllAgents(): AgentEntity[] {
    return Array.from(this.agents.values());
  }

  private loop = (): void => {
    if (!this.running || !this.ctx || !this.canvas) return;

    const rect = this.canvas.getBoundingClientRect();

    // 更新所有 Agent
    for (const agent of this.agents.values()) {
      agent.update(this.bounds);
    }

    // 清空画布
    this.ctx.clearRect(0, 0, rect.width, rect.height);

    // Layer 0: 地面瓦片
    const groundCanvas = this.tileMap.render();
    this.ctx.drawImage(
      groundCanvas,
      this.cameraX, this.cameraY, rect.width, rect.height,
      0, 0, rect.width, rect.height,
    );

    // Layer 1: 建筑
    this.renderBuildings();

    // Layer 2: Agent 精灵（按 Y 坐标排序，实现遮挡）
    const sortedAgents = Array.from(this.agents.values()).sort((a, b) => a.y - b.y);
    for (const agent of sortedAgents) {
      this.renderAgent(agent);
    }

    this.animFrameId = requestAnimationFrame(this.loop);
  };

  private renderBuildings(): void {
    if (!this.ctx) return;

    for (const b of BUILDINGS) {
      const sprite = getBuildingSprite(b.type);
      const x = b.col * TS - this.cameraX;
      const y = b.row * TS - this.cameraY;

      this.ctx.drawImage(sprite, x, y);

      // 建筑标签
      this.ctx!.fillStyle = '#f4f4f4';
      this.ctx!.font = '10px monospace';
      this.ctx!.textAlign = 'center';

      const labelOffset = b.type === 'reputation' || b.type === 'bounty' ? 96 : 64;
      // 提取 emoji 标签
      const emoji = b.label.split(' ')[0];
      this.ctx!.fillStyle = '#f4f4f4';
      this.ctx!.font = '14px serif';
      this.ctx!.fillText(emoji, x + labelOffset / 2, y - 4);
    }
  }

  private renderAgent(agent: AgentEntity): void {
    if (!this.ctx) return;

    const screenX = agent.x - this.cameraX;
    const screenY = agent.y - this.cameraY;

    // 精灵
    const sprite = getAgentSprite(agent.facing, agent.frame, agent.shirtColor);
    this.ctx.drawImage(
      sprite,
      screenX - DESIGN.AGENT_W / 2,
      screenY - DESIGN.AGENT_H + 8, // 脚底对齐
    );

    // 名字 + emoji
    this.ctx.fillStyle = '#f4f4f4';
    this.ctx.font = 'bold 9px monospace';
    this.ctx.textAlign = 'center';
    this.ctx.fillText(
      agent.avatar,
      screenX,
      screenY - DESIGN.AGENT_H - 2,
    );

    // 名字背景
    const nameWidth = this.ctx.measureText(agent.name).width;
    this.ctx.fillStyle = 'rgba(26, 28, 44, 0.7)';
    this.ctx.fillRect(screenX - nameWidth / 2 - 2, screenY - DESIGN.AGENT_H + 6, nameWidth + 4, 12);

    this.ctx.fillStyle = '#f4f4f4';
    this.ctx.font = '8px monospace';
    this.ctx.fillText(agent.name, screenX, screenY - DESIGN.AGENT_H + 15);

    // 状态指示
    if (agent.state === 'busy') {
      this.ctx.fillStyle = '#ffcc44';
      this.ctx.font = '10px serif';
      this.ctx.fillText('💬', screenX + 14, screenY - DESIGN.AGENT_H - 2);
    }
  }

  destroy(): void {
    this.stop();
    this.canvas = null;
    this.ctx = null;
    this.agents.clear();
  }
}
