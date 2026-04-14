// src/features/bazaar/world/WorldRenderer.ts
// 世界渲染引擎 — 冷色奇幻学院风格
// 分层渲染：Layer 0 地面 → Layer 0.5 粒子 → Layer 0.7 水面闪烁 → Layer 1 建筑+装饰 Y-sort → Layer 2 Agent

import { DESIGN, UI_COLORS, TILE_COLORS } from './palette';
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

  private camera: CameraSystem | null = null;

  private bounds = {
    minX: 0,
    maxX: DESIGN.MAP_COLS * TS,
    minY: 0,
    maxY: DESIGN.MAP_ROWS * TS,
  };

  private particles = new ParticleLayer();
  private decoLayer = new DecoLayer();

  // Hover state
  private hoveredBuilding: BuildingData | null = null;
  private hoveredAgent: AgentEntity | null = null;

  // Building pulse animation (first visit)
  private buildingPulseActive = false;
  private buildingPulseStart = 0;
  private static readonly BUILDING_PULSE_DURATION = 5000;

  // 建筑标签缓存（OffscreenCanvas）
  private buildingLabelCache = new Map<string, OffscreenCanvas>();

  constructor() {
    this.tileMap = new TileMap(MAP_DATA as number[][]);
    this.decoLayer.loadImages();
  }

  init(canvas: HTMLCanvasElement): void {
    this.canvas = canvas;
    this.ctx = canvas.getContext('2d')!;
    this.ctx.imageSmoothingEnabled = false;

    const dpr = window.devicePixelRatio || 1;
    const rect = canvas.getBoundingClientRect();
    canvas.width = rect.width * dpr;
    canvas.height = rect.height * dpr;
    this.ctx.scale(dpr, dpr);

    this.particles.setViewport(rect.width, rect.height);

    if (this.camera) {
      this.camera.setViewport(rect.width, rect.height);
    }

    // 预生成建筑标签缓存
    for (const b of BUILDINGS) {
      this.cacheBuildingLabel(b);
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

    // Layer 0.7: 水面闪烁 overlay
    this.renderWaterShimmer(cameraX, cameraY);

    // Layer 1: 建筑 + 装饰物（按 Y 排序混合）
    this.renderBuildingsAndDecos(cameraX, cameraY);

    // Layer 2: Agent 精灵（按 Y 坐标排序）
    const sortedAgents = Array.from(this.agents.values()).sort((a, b) => a.y - b.y);
    for (const agent of sortedAgents) {
      this.renderAgent(agent, cameraX, cameraY);
    }

    this.animFrameId = requestAnimationFrame(this.loop);
  };

  /** 水面闪烁 — 在地面缓存之上叠加半透明冰蓝 */
  private renderWaterShimmer(cameraX: number, cameraY: number): void {
    if (!this.ctx) return;
    const mapData = MAP_DATA as number[][];
    const alpha = 0.08 + Math.sin(this.frame * 0.06) * 0.04;
    this.ctx.fillStyle = `rgba(168,216,234,${alpha})`;
    for (let row = 0; row < mapData.length; row++) {
      for (let col = 0; col < mapData[row].length; col++) {
        if (mapData[row][col] === 3) { // 水瓦片
          this.ctx.fillRect(
            col * TS - cameraX,
            row * TS - cameraY,
            TS, TS,
          );
        }
      }
    }
  }

  /** 建筑 + 装饰物混合渲染 */
  private renderBuildingsAndDecos(cameraX: number, cameraY: number): void {
    if (!this.ctx) return;

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

    // Building pulse animation（白色半透明）
    if (this.buildingPulseActive) {
      const elapsed = performance.now() - this.buildingPulseStart;
      if (elapsed > WorldRenderer.BUILDING_PULSE_DURATION) {
        this.buildingPulseActive = false;
      } else {
        const alpha = 0.3 * (1 - elapsed / WorldRenderer.BUILDING_PULSE_DURATION);
        this.ctx!.strokeStyle = `rgba(255,255,255,${alpha})`;
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

    // Hover: 冰蓝半透明高亮
    if (this.hoveredBuilding && this.hoveredBuilding.id === b.id) {
      this.ctx.fillStyle = UI_COLORS.hoverBuildingFill;
      this.ctx.fillRect(x - 2, y - 2, b.width + 4, b.height + 4);
    }

    this.ctx.drawImage(sprite, x, y);

    // 建筑标签（从缓存）
    const label = this.buildingLabelCache.get(b.id);
    if (label) {
      this.ctx.drawImage(label, x + b.width / 2 - label.width / 2, y - label.height - 2);
    }

    // Hover 金色描边
    if (this.hoveredBuilding && this.hoveredBuilding.id === b.id) {
      this.ctx.strokeStyle = UI_COLORS.hoverBuildingStroke;
      this.ctx.lineWidth = 3;
      this.ctx.strokeRect(x - 1, y - 1, b.width + 2, b.height + 2);
    }
  }

  /** 预生成建筑标签到 OffscreenCanvas */
  private cacheBuildingLabel(b: BuildingData): void {
    // 摊位不显示独立标签
    if (b.type === 'stall') return;

    const emoji = b.label.split(' ')[0];
    const name = b.label.split(' ').slice(1).join(' ') || '';

    // 测量文字宽度
    const tempCanvas = new OffscreenCanvas(200, 30);
    const tempCtx = tempCanvas.getContext('2d')!;

    tempCtx.font = UI_COLORS.buildingEmojiFont;
    const emojiW = tempCtx.measureText(emoji).width;
    tempCtx.font = UI_COLORS.buildingLabelFont;
    const nameW = name ? tempCtx.measureText(name).width : 0;

    const totalW = Math.max(emojiW + (name ? nameW + 4 : 0), 20);
    const totalH = name ? 24 : 18;
    const padX = 4;

    const labelCanvas = new OffscreenCanvas(Math.ceil(totalW + padX * 2), totalH);
    const ctx = labelCanvas.getContext('2d')!;

    // 背景
    ctx.fillStyle = UI_COLORS.labelBg;
    ctx.beginPath();
    ctx.roundRect(0, 0, labelCanvas.width, labelCanvas.height, 3);
    ctx.fill();

    // emoji
    ctx.font = UI_COLORS.buildingEmojiFont;
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.fillStyle = UI_COLORS.labelEmoji;
    const centerY = totalH / 2;
    if (name) {
      ctx.fillText(emoji, padX + emojiW / 2, centerY - 2);
      // 中文名
      ctx.font = UI_COLORS.buildingLabelFont;
      ctx.fillStyle = UI_COLORS.labelText;
      ctx.fillText(name, padX + emojiW + 4 + nameW / 2, centerY);
    } else {
      ctx.fillText(emoji, labelCanvas.width / 2, centerY);
    }

    this.buildingLabelCache.set(b.id, labelCanvas);
  }

  private renderAgent(agent: AgentEntity, cameraX: number, cameraY: number): void {
    if (!this.ctx) return;

    const screenX = agent.x - cameraX;
    const screenY = agent.y - cameraY;

    // Hover: 冰蓝底光
    if (this.hoveredAgent && this.hoveredAgent.id === agent.id) {
      this.ctx.fillStyle = UI_COLORS.hoverAgentFill;
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

    // 状态气泡
    this.renderStatusBubble(agent, screenX, screenY);

    // 名字（11px 银白 + 描边）
    this.ctx.textAlign = 'center';
    this.ctx.font = UI_COLORS.nameFont;
    const nameWidth = this.ctx.measureText(agent.name).width;
    this.ctx.fillStyle = UI_COLORS.nameBg;
    this.roundRect(screenX - nameWidth / 2 - 3, screenY - DESIGN.AGENT_H + 5, nameWidth + 6, 14, 3);
    this.ctx.fill();
    this.ctx.strokeStyle = UI_COLORS.nameBorder;
    this.ctx.lineWidth = 0.5;
    this.ctx.stroke();
    // 描边文字提升可读性
    this.ctx.strokeStyle = '#1A2332';
    this.ctx.lineWidth = 1;
    this.ctx.strokeText(agent.name, screenX, screenY - DESIGN.AGENT_H + 16);
    this.ctx.fillStyle = UI_COLORS.nameText;
    this.ctx.fillText(agent.name, screenX, screenY - DESIGN.AGENT_H + 16);

    // Avatar emoji
    this.ctx.font = UI_COLORS.avatarFont;
    this.ctx.fillStyle = UI_COLORS.avatarEmoji;
    this.ctx.fillText(agent.avatar, screenX, screenY - DESIGN.AGENT_H - 2);

    // Hover 冰蓝光环
    if (this.hoveredAgent && this.hoveredAgent.id === agent.id) {
      this.ctx.strokeStyle = UI_COLORS.hoverAgentRing;
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
      textColor = UI_COLORS.bubbleBusyText;
      bgColor = UI_COLORS.bubbleBusyBg;
      borderColor = UI_COLORS.bubbleBusyBorder;
    } else if (agent.state === 'idle') {
      text = '···';
      textColor = UI_COLORS.bubbleIdleText;
      bgColor = UI_COLORS.bubbleIdleBg;
      borderColor = UI_COLORS.bubbleIdleBorder;
    } else {
      return;
    }

    const bubbleY = screenY - DESIGN.AGENT_H - 12;
    this.ctx.font = 'bold 8px monospace';
    const textW = this.ctx.measureText(text).width + 6;

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

    this.ctx.fillStyle = textColor;
    this.ctx.textAlign = 'center';
    this.ctx.fillText(text, screenX, bubbleY + 3);
  }

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
    this.buildingLabelCache.clear();
  }
}
