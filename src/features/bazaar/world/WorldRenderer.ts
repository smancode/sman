// src/features/bazaar/world/WorldRenderer.ts
// 世界渲染引擎 — 管理瓦片、建筑、Agent、粒子、装饰的分层渲染

import { DESIGN } from './palette';
import { MAP_DATA, BUILDINGS } from './map-data';
import type { BuildingData } from './map-data';
import { DECORATIONS, DecoLayer } from './DecoLayer';
import type { DecoData } from './DecoLayer';
import { TileMap } from './TileMap';
import { getAgentSprite, getBuildingSprite } from './SpriteSheet';
import { AgentEntity } from './AgentEntity';
import { CameraSystem } from './CameraSystem';
import { ParticleLayer } from './ParticleLayer';

const TS = DESIGN.TILE_SIZE;

export class WorldRenderer {
  private canvas: HTMLCanvasElement | null = null;
  private ctx: CanvasRenderingContext2D | null = null;
  private tileMap: TileMap;
  private agents: Map<string, AgentEntity> = new Map();
  private animFrameId: number = 0;
  private running = false;
  private frame = 0;

  // 相机系统（外部注入）
  private camera: CameraSystem | null = null;

  // 碰撞边界（像素）
  private bounds = {
    minX: 0,
    maxX: DESIGN.MAP_COLS * TS,
    minY: 0,
    maxY: DESIGN.MAP_ROWS * TS,
  };

  // 建筑离屏缓存
  private buildingLayer: OffscreenCanvas | null = null;

  // 新增层
  private particles = new ParticleLayer();
  private decoLayer = new DecoLayer();

  // Hover state
  private hoveredBuilding: BuildingData | null = null;
  private hoveredAgent: AgentEntity | null = null;

  // Building pulse animation (first visit)
  private buildingPulseActive = false;
  private buildingPulseStart = 0;
  private static readonly BUILDING_PULSE_DURATION = 3000;

  constructor() {
    this.tileMap = new TileMap(MAP_DATA as number[][]);
    // 异步加载装饰物图片（不影响启动）
    this.decoLayer.loadImages();
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

    // 粒子层视口
    this.particles.setViewport(rect.width, rect.height);

    // 相机视口
    if (this.camera) {
      this.camera.setViewport(rect.width, rect.height);
    }
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

  setCamera(camera: CameraSystem): void {
    this.camera = camera;
  }

  setHovered(building: BuildingData | null, agent: AgentEntity | null): void {
    this.hoveredBuilding = building;
    this.hoveredAgent = agent;
  }

  startBuildingPulse(): void {
    this.buildingPulseActive = true;
    this.buildingPulseStart = performance.now();
  }

  getCamera(): CameraSystem | null {
    return this.camera;
  }

  /** 触发点击爆发粒子 */
  spawnClickBurst(screenX: number, screenY: number): void {
    this.particles.spawnBurst(screenX, screenY);
  }

  private loop = (): void => {
    if (!this.running || !this.ctx || !this.canvas || !this.camera) return;

    this.frame++;
    const rect = this.canvas.getBoundingClientRect();
    const { x: cameraX, y: cameraY } = this.camera.getOffset();

    // 更新
    for (const agent of this.agents.values()) {
      agent.update(this.bounds);
    }
    this.particles.update();

    // 清空画布
    this.ctx.clearRect(0, 0, rect.width, rect.height);

    // Layer 0: 地面瓦片
    const groundCanvas = this.tileMap.render();
    this.ctx.drawImage(
      groundCanvas,
      cameraX, cameraY, rect.width, rect.height,
      0, 0, rect.width, rect.height,
    );

    // Layer 0.5: 环境粒子（建筑后面）
    this.particles.render(this.ctx);

    // Layer 1: 建筑 + 装饰物（按 Y 排序混合）
    this.renderBuildingsAndDecos(cameraX, cameraY);

    // Layer 2: Agent 精灵（按 Y 坐标排序，实现遮挡）
    const sortedAgents = Array.from(this.agents.values()).sort((a, b) => a.y - b.y);
    for (const agent of sortedAgents) {
      this.renderAgent(agent, cameraX, cameraY);
    }

    this.animFrameId = requestAnimationFrame(this.loop);
  };

  /** 建筑 + 装饰物混合渲染（按 Y 排序） */
  private renderBuildingsAndDecos(cameraX: number, cameraY: number): void {
    if (!this.ctx) return;

    // 收集所有可渲染项的 Y 坐标并排序
    type RenderItem =
      | { kind: 'building'; data: BuildingData }
      | { kind: 'deco'; data: DecoData };

    const items: RenderItem[] = [];

    for (const b of BUILDINGS) {
      items.push({ kind: 'building', data: b });
    }
    for (const d of DECORATIONS) {
      items.push({ kind: 'deco', data: d });
    }

    // 按 Y 坐标排序
    items.sort((a, b) => {
      const ay = a.kind === 'building' ? a.data.row * TS : this.decoLayer.getSortY(a.data);
      const by = b.kind === 'building' ? b.data.row * TS : this.decoLayer.getSortY(b.data);
      return ay - by;
    });

    for (const item of items) {
      if (item.kind === 'building') {
        this.renderBuilding(item.data, cameraX, cameraY);
      } else {
        this.decoLayer.render(this.ctx!, item.data, cameraX, cameraY, this.frame);
      }
    }

    // Building pulse animation
    if (this.buildingPulseActive) {
      const elapsed = performance.now() - this.buildingPulseStart;
      if (elapsed > WorldRenderer.BUILDING_PULSE_DURATION) {
        this.buildingPulseActive = false;
      } else {
        const alpha = 0.3 * (1 - elapsed / WorldRenderer.BUILDING_PULSE_DURATION);
        this.ctx!.strokeStyle = `rgba(255, 204, 68, ${alpha})`;
        this.ctx!.lineWidth = 3;
        for (const b of BUILDINGS) {
          const bx = b.col * TS - cameraX;
          const by = b.row * TS - cameraY;
          this.ctx!.strokeRect(bx, by, b.width, b.height);
        }
      }
    }
  }

  private renderBuilding(b: BuildingData, cameraX: number, cameraY: number): void {
    if (!this.ctx) return;

    const sprite = getBuildingSprite(b.type);
    const x = b.col * TS - cameraX;
    const y = b.row * TS - cameraY;

    // Hover: 半透明金色填充高亮
    if (this.hoveredBuilding && this.hoveredBuilding.id === b.id) {
      this.ctx.fillStyle = 'rgba(255, 215, 0, 0.15)';
      this.ctx.fillRect(x - 2, y - 2, b.width + 4, b.height + 4);
    }

    this.ctx.drawImage(sprite, x, y);

    // 建筑标签
    const labelOffset = b.type === 'reputation' || b.type === 'bounty' ? 96 : 64;
    const emoji = b.label.split(' ')[0];
    this.ctx.font = '14px serif';
    this.ctx.textAlign = 'center';
    this.ctx.fillStyle = '#f4f4f4';
    this.ctx.fillText(emoji, x + labelOffset / 2, y - 4);

    // Hover 描边
    if (this.hoveredBuilding && this.hoveredBuilding.id === b.id) {
      this.ctx.strokeStyle = 'rgba(255, 215, 0, 0.7)';
      this.ctx.lineWidth = 2;
      this.ctx.strokeRect(x - 1, y - 1, b.width + 2, b.height + 2);
    }
  }

  private renderAgent(agent: AgentEntity, cameraX: number, cameraY: number): void {
    if (!this.ctx) return;

    const screenX = agent.x - cameraX;
    const screenY = agent.y - cameraY;

    // Hover: 金色底光
    if (this.hoveredAgent && this.hoveredAgent.id === agent.id) {
      this.ctx.fillStyle = 'rgba(255, 215, 0, 0.1)';
      this.ctx.beginPath();
      this.ctx.arc(screenX, screenY - 8, 22, 0, Math.PI * 2);
      this.ctx.fill();
    }

    // 精灵
    const sprite = getAgentSprite(agent.facing, agent.frame, agent.appearance);
    this.ctx.drawImage(
      sprite,
      screenX - DESIGN.AGENT_W / 2,
      screenY - DESIGN.AGENT_H + agent.bounceOffset,
    );

    // 状态气泡（idle/busy/老搭档）
    this.renderStatusBubble(agent, screenX, screenY);

    // 名字
    this.ctx.textAlign = 'center';
    this.ctx.font = 'bold 9px monospace';
    const nameWidth = this.ctx.measureText(agent.name).width;
    this.ctx.fillStyle = 'rgba(61, 37, 16, 0.75)';
    this.roundRect(screenX - nameWidth / 2 - 3, screenY - DESIGN.AGENT_H + 5, nameWidth + 6, 13, 3);
    this.ctx.fill();
    this.ctx.strokeStyle = 'rgba(255, 224, 102, 0.2)';
    this.ctx.lineWidth = 0.5;
    this.ctx.stroke();
    this.ctx.fillStyle = '#FFE066';
    this.ctx.fillText(agent.name, screenX, screenY - DESIGN.AGENT_H + 14);

    // Avatar emoji
    this.ctx.font = '12px serif';
    this.ctx.fillStyle = '#f4f4f4';
    this.ctx.fillText(agent.avatar, screenX, screenY - DESIGN.AGENT_H - 2);

    // Hover 描边光环
    if (this.hoveredAgent && this.hoveredAgent.id === agent.id) {
      this.ctx.strokeStyle = 'rgba(255, 215, 0, 0.7)';
      this.ctx.lineWidth = 2;
      this.ctx.beginPath();
      this.ctx.arc(screenX, screenY - 8, 20, 0, Math.PI * 2);
      this.ctx.stroke();
    }
  }

  /** Agent 头顶状态气泡 */
  private renderStatusBubble(agent: AgentEntity, screenX: number, screenY: number): void {
    if (!this.ctx) return;

    let text: string;
    let textColor: string;
    let bgColor: string;
    let borderColor: string;

    if (agent.state === 'busy') {
      text = '⚙';
      textColor = '#E65100';
      bgColor = '#FFF3E0';
      borderColor = '#E65100';
    } else if (agent.state === 'idle') {
      text = '···';
      textColor = '#888';
      bgColor = 'rgba(255,255,255,0.85)';
      borderColor = '#bbb';
    } else {
      return; // walking 等状态不显示气泡
    }

    const bubbleY = screenY - DESIGN.AGENT_H - 12;
    this.ctx.font = 'bold 8px monospace';
    const textW = this.ctx.measureText(text).width + 6;

    // 气泡背景
    this.ctx.fillStyle = bgColor;
    this.roundRect(screenX - textW / 2, bubbleY - 5, textW, 11, 4);
    this.ctx.fill();
    this.ctx.strokeStyle = borderColor;
    this.ctx.lineWidth = 1;
    this.ctx.stroke();

    // 气泡尾巴
    this.ctx.fillStyle = bgColor;
    this.ctx.beginPath();
    this.ctx.moveTo(screenX - 2, bubbleY + 6);
    this.ctx.lineTo(screenX + 2, bubbleY + 6);
    this.ctx.lineTo(screenX, bubbleY + 9);
    this.ctx.fill();

    // 文字
    this.ctx.fillStyle = textColor;
    this.ctx.textAlign = 'center';
    this.ctx.fillText(text, screenX, bubbleY + 3);
  }

  /** 圆角矩形工具 */
  private roundRect(x: number, y: number, w: number, h: number, r: number): void {
    if (!this.ctx) return;
    this.ctx.beginPath();
    this.ctx.moveTo(x + r, y);
    this.ctx.lineTo(x + w - r, y);
    this.ctx.quadraticCurveTo(x + w, y, x + w, y + r);
    this.ctx.lineTo(x + w, y + h - r);
    this.ctx.quadraticCurveTo(x + w, y + h, x + w - r, y + h);
    this.ctx.lineTo(x + r, y + h);
    this.ctx.quadraticCurveTo(x, y + h, x, y + h - r);
    this.ctx.lineTo(x, y + r);
    this.ctx.quadraticCurveTo(x, y, x + r, y);
    this.ctx.closePath();
  }

  /** 屏幕坐标 → 世界坐标 */
  screenToWorld(screenX: number, screenY: number): { x: number; y: number } {
    if (!this.camera) return { x: screenX, y: screenY };
    const { x, y } = this.camera.getOffset();
    return { x: screenX + x, y: screenY + y };
  }

  destroy(): void {
    this.stop();
    this.canvas = null;
    this.ctx = null;
    this.agents.clear();
  }
}
