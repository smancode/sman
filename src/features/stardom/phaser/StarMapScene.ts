// src/features/stardom/phaser/StarMapScene.ts
// StarMap Scene — core Phaser rendering for Collaboration Atlas
// Agent entities + star paths + task pulses + capability orbits + search wave + zoom/pan

import Phaser from 'phaser';
import { getReputationLevel } from '../components/ReputationUtils';
import type { StardomAgentInfo, StardomTask } from '@/types/stardom';

// ── Color palette ──

const C = {
  bg: 0x0a0e17,
  grid: 0x00e5ff,
  starDim: 0x334466,
  starBright: 0x88aacc,
  cyan: 0x00e5ff,
  green: 0x00ff88,
  amber: 0xffaa00,
  purple: 0xaa44ff,
  blue: 0x4488ff,
  red: 0xff4444,
  pathDim: 0x334455,
  searchWave: 0x00e5ff,
};

const LEVEL_COLORS = [0x667788, 0x00ff88, 0x00e5ff, 0xaa44ff, 0xffaa00];

// ── Layout result ──

interface LayoutData {
  selfNode: { x: number; y: number };
  taskNodes: Array<{ x: number; y: number; taskId: string; status: string; direction: string; question: string }>;
  agentNodes: Array<{ x: number; y: number; agentId: string; name: string; reputation: number; status: string }>;
}

// ── Scene ──

export class StarMapScene extends Phaser.Scene {
  // Rendered objects
  private agentContainers: Phaser.GameObjects.Container[] = [];
  private pathGraphics: Phaser.GameObjects.Graphics[] = [];
  private pulseObjects: Phaser.GameObjects.GameObject[] = [];
  private searchWaves: Phaser.GameObjects.Arc[] = [];
  private emptyLabel: Phaser.GameObjects.Text | null = null;

  // Camera drag
  private isDragging = false;
  private dragStartX = 0;
  private dragStartY = 0;
  private camStartX = 0;
  private camStartY = 0;

  // Store sync
  private storeUnsub: (() => void) | null = null;
  private pendingRebuild = false;
  private rebuildTimer: ReturnType<typeof setTimeout> | null = null;
  private lastSnapshot = '';

  constructor() {
    super({ key: 'StarMapScene' });
  }

  create() {
    const { width, height } = this.scale;

    this.cameras.main.setBackgroundColor('#0a0e17');
    this.createStarField(width, height);
    this.drawGrid(width, height);
    this.setupCameraControls();

    // Initial sync
    this.syncFromStore();

    // Subscribe with throttled rebuild (100ms)
    const bridge = (this as any).__storeBridge;
    if (bridge) {
      this.storeUnsub = bridge.subscribe(() => this.requestRebuild());
    }

    // Resize
    this.scale.on('resize', (size: Phaser.Structs.Size) => {
      this.requestRebuild();
    });
  }

  update() {
    // Search waves self-clean via tween onComplete
  }

  // ── Throttled rebuild ──

  private requestRebuild() {
    if (this.rebuildTimer) return;
    this.rebuildTimer = setTimeout(() => {
      this.rebuildTimer = null;
      this.syncFromStore();
    }, 100);
  }

  // ── Store sync ──

  private syncFromStore() {
    const bridge = (this as any).__storeBridge;
    if (!bridge) return;

    const state = bridge.getState();
    if (!state?.connection) return;

    const { tasks = [], onlineAgents = [], connection } = state;
    const selfId = connection.agentId ?? '';
    const rep = connection.reputation ?? 0;

    // Snapshot comparison to skip redundant rebuilds
    const snap = `${selfId}:${rep}:${tasks.length}:${onlineAgents.length}:${tasks.map((t: StardomTask) => `${t.taskId}=${t.status}`).join(',')}`;
    if (snap === this.lastSnapshot) return;
    this.lastSnapshot = snap;

    this.rebuildScene(selfId, rep, tasks, onlineAgents);
  }

  // ── Full rebuild ──

  private rebuildScene(
    selfId: string,
    selfReputation: number,
    tasks: StardomTask[],
    agents: StardomAgentInfo[],
  ) {
    if (!this.scene.isActive()) return;

    this.clearAll();

    const { width, height } = this.scale;
    const cx = width / 2;
    const cy = height / 2;

    const layout = this.computeLayout(cx, cy, width, height, selfId, tasks, agents);

    // Empty state
    if (tasks.length === 0 && agents.length <= 1) {
      this.showEmptyState(cx, cy);
      return;
    }

    if (this.emptyLabel) {
      this.emptyLabel.destroy();
      this.emptyLabel = null;
    }

    // Nebulae
    this.drawNebulae(layout);

    // Star paths
    this.drawStarPaths(layout);

    // Agent nodes (others first, self on top)
    for (const a of layout.agentNodes) {
      this.createAgentNode(a.x, a.y, {
        id: a.agentId, name: a.name,
        reputation: a.reputation, status: a.status, isSelf: false,
      });
    }

    // Task pulses and search waves
    this.createTaskEffects(layout);

    // Self node (always on top)
    this.createAgentNode(layout.selfNode.x, layout.selfNode.y, {
      id: '__self__', name: '本节点',
      reputation: selfReputation, status: 'idle', isSelf: true,
    });
  }

  // ── Layout ──

  private computeLayout(
    cx: number, cy: number, w: number, h: number,
    selfId: string, tasks: StardomTask[], agents: StardomAgentInfo[],
  ): LayoutData {
    const activeTasks = tasks.filter(t =>
      ['searching', 'offered', 'matched', 'chatting'].includes(t.status)
    );

    const taskR = Math.min(w, h) * 0.22;
    const taskNodes = activeTasks.map((task, i) => {
      const angle = (2 * Math.PI * i) / Math.max(activeTasks.length, 1) - Math.PI / 2;
      return {
        x: cx + Math.cos(angle) * taskR,
        y: cy + Math.sin(angle) * taskR,
        taskId: task.taskId, status: task.status,
        direction: task.direction, question: task.question,
      };
    });

    const others = agents.filter(a => a.agentId !== selfId).slice(0, 12);
    const agentR = Math.min(w, h) * 0.42;
    const agentNodes = others.map((agent, i) => {
      const angle = (2 * Math.PI * i) / Math.max(others.length, 1) - Math.PI / 2;
      return {
        x: cx + Math.cos(angle) * agentR,
        y: cy + Math.sin(angle) * agentR,
        agentId: agent.agentId, name: agent.name,
        reputation: agent.reputation, status: agent.status,
      };
    });

    return { selfNode: { x: cx, y: cy }, taskNodes, agentNodes };
  }

  // ── Star field ──

  private createStarField(w: number, h: number) {
    for (let i = 0; i < 180; i++) {
      const x = Phaser.Math.Between(-300, w + 300);
      const y = Phaser.Math.Between(-300, h + 300);
      const brightness = Math.random();
      const color = brightness > 0.7 ? C.starBright : C.starDim;
      const alpha = 0.15 + brightness * 0.6;
      const star = this.add.circle(x, y, Math.random() * 1.2 + 0.3, color, alpha).setDepth(0);

      if (brightness > 0.6) {
        this.tweens.add({
          targets: star,
          alpha: { from: alpha, to: alpha * 0.2 },
          duration: 1000 + Math.random() * 3000,
          yoyo: true, repeat: -1,
          ease: 'Sine.easeInOut',
          delay: Math.random() * 2000,
        });
      }
    }
  }

  // ── Grid ──

  private drawGrid(w: number, h: number) {
    const g = this.add.graphics().setDepth(1);
    g.lineStyle(1, C.grid, 0.025);
    const sp = 60;
    const ext = Math.max(w, h) * 2;
    for (let x = -ext; x <= ext; x += sp) g.lineBetween(x, -ext, x, ext);
    for (let y = -ext; y <= ext; y += sp) g.lineBetween(-ext, y, ext, y);
  }

  // ── Camera ──

  private setupCameraControls() {
    const cam = this.cameras.main;

    this.input.on('pointerdown', (p: Phaser.Input.Pointer) => {
      if (p.rightButtonDown()) return;
      this.isDragging = true;
      this.dragStartX = p.x; this.dragStartY = p.y;
      this.camStartX = cam.scrollX; this.camStartY = cam.scrollY;
    });

    this.input.on('pointermove', (p: Phaser.Input.Pointer) => {
      if (!this.isDragging) return;
      cam.scrollX = this.camStartX - (p.x - this.dragStartX) / cam.zoom;
      cam.scrollY = this.camStartY - (p.y - this.dragStartY) / cam.zoom;
    });

    this.input.on('pointerup', () => { this.isDragging = false; });

    this.input.on('wheel', (_p: Phaser.Input.Pointer, _go: any[], _dx: number, dy: number) => {
      cam.setZoom(Phaser.Math.Clamp(cam.zoom - dy * 0.001, 0.3, 3));
    });
  }

  // ── Nebulae ──

  private drawNebulae(layout: LayoutData) {
    const g = this.add.graphics().setDepth(2);
    this.pathGraphics.push(g);

    // Central glow
    this.radialGlow(g, layout.selfNode.x, layout.selfNode.y, 80, C.cyan, 0.06);

    // Active task nebulae
    for (const t of layout.taskNodes) {
      if (t.status === 'chatting' || t.status === 'matched') {
        this.radialGlow(g, t.x, t.y, 35, t.direction === 'outgoing' ? C.green : C.blue, 0.04);
      }
    }

    // High-rep agent nebulae
    for (const a of layout.agentNodes) {
      if (a.reputation >= 50) {
        const lvl = getReputationLevel(a.reputation);
        const c = LEVEL_COLORS[lvl.level - 1] ?? C.cyan;
        const b = Math.min(1, a.reputation / 100);
        this.radialGlow(g, a.x, a.y, 20 + b * 15, c, 0.03 + b * 0.02);
      }
    }
  }

  private radialGlow(g: Phaser.GameObjects.Graphics, x: number, y: number, r: number, color: number, alpha: number) {
    for (let i = 3; i >= 0; i--) {
      g.fillStyle(color, Math.max(0, alpha * (1 - i * 0.25)));
      g.fillCircle(x, y, r * (1 + i * 0.5));
    }
  }

  // ── Star paths ──

  private drawStarPaths(layout: LayoutData) {
    const sx = layout.selfNode.x;
    const sy = layout.selfNode.y;

    // Task paths
    for (const t of layout.taskNodes) {
      const isActive = t.status === 'chatting' || t.status === 'matched';
      const g = this.add.graphics().setDepth(3);
      this.pathGraphics.push(g);

      const color = t.direction === 'outgoing'
        ? (isActive ? C.green : C.amber)
        : (isActive ? C.blue : C.purple);

      g.lineStyle(isActive ? 2 : 0.8, color, isActive ? 0.6 : 0.2);
      if (isActive) {
        this.dashedLine(g, sx, sy, t.x, t.y, 8, 5);
      } else {
        g.lineBetween(sx, sy, t.x, t.y);
      }
    }

    // Agent paths
    for (const a of layout.agentNodes) {
      const g = this.add.graphics().setDepth(3);
      this.pathGraphics.push(g);
      const b = Math.min(1, a.reputation / 100);
      g.lineStyle(0.5, C.pathDim, 0.1 + b * 0.2);
      g.lineBetween(sx, sy, a.x, a.y);
    }
  }

  private dashedLine(g: Phaser.GameObjects.Graphics, x1: number, y1: number, x2: number, y2: number, dash: number, gap: number) {
    const dx = x2 - x1, dy = y2 - y1;
    const dist = Math.sqrt(dx * dx + dy * dy);
    if (dist === 0) return;
    const nx = dx / dist, ny = dy / dist;
    const steps = Math.ceil(dist / (dash + gap));

    g.beginPath();
    for (let i = 0; i < steps; i++) {
      const s = i * (dash + gap);
      const e = Math.min(s + dash, dist);
      g.moveTo(x1 + nx * s, y1 + ny * s);
      g.lineTo(x1 + nx * e, y1 + ny * e);
    }
    g.strokePath();
  }

  // ── Agent node ──

  private createAgentNode(
    x: number, y: number,
    opts: { id: string; name: string; reputation: number; status: string; isSelf: boolean },
  ) {
    const { id, name, reputation, status, isSelf } = opts;
    const lvl = getReputationLevel(reputation);
    const li = lvl.level - 1;
    const color = LEVEL_COLORS[li] ?? C.cyan;
    const b = Math.min(1, reputation / 100);
    const r = isSelf ? 18 + li * 4 : 6 + li * 3 + b * 4;

    const container = this.add.container(x, y).setDepth(isSelf ? 10 : 5);
    this.agentContainers.push(container);

    // Outer glow
    const outer = this.add.circle(0, 0, r + 10, color, 0.04);
    container.add(outer);

    // Mid glow
    container.add(this.add.circle(0, 0, r + 4, color, 0.08));

    // Body
    container.add(this.add.circle(0, 0, r, color, 0.15 + b * 0.2));

    // Core
    const core = this.add.circle(0, 0, r * 0.45, color, 0.7 + b * 0.3);
    container.add(core);

    // Status ring
    let sColor = color;
    if (status === 'busy') sColor = C.amber;
    else if (status === 'afk' || status === 'offline') sColor = 0x444444;
    const sRing = this.add.circle(0, 0, r + 7, sColor, 0);
    sRing.setStrokeStyle(0.8, sColor, 0.3);
    container.add(sRing);

    // Capability orbits (Lv2+)
    if (lvl.level >= 2) {
      const count = Math.min(lvl.level, 4);
      for (let i = 0; i < count; i++) {
        const oR = r + 14 + i * 8;
        const startAngle = (Math.PI * 2 * i) / count;

        // Orbit ring
        const orbitRing = this.add.circle(0, 0, oR, color, 0);
        orbitRing.setStrokeStyle(0.3, color, 0.15);
        container.add(orbitRing);

        // Orbiting dot
        const dot = this.add.circle(
          Math.cos(startAngle) * oR,
          Math.sin(startAngle) * oR,
          2, color, 0.7,
        );
        container.add(dot);

        this.tweens.addCounter({
          from: startAngle,
          to: startAngle + Math.PI * 2,
          duration: 4000 + i * 1500,
          repeat: -1,
          ease: 'Linear',
          onUpdate: (tween) => {
            const a = tween.getValue() ?? 0;
            dot.x = Math.cos(a) * oR;
            dot.y = Math.sin(a) * oR;
          },
        });
      }
    }

    // Label
    container.add(
      this.add.text(0, r + 14, name, {
        fontSize: isSelf ? '13px' : '10px',
        fontFamily: 'JetBrains Mono, ui-monospace, monospace',
        color: isSelf ? '#ccddee' : '#667788',
      }).setOrigin(0.5).setAlpha(0.5 + b * 0.5)
    );

    // Level badge
    if (lvl.level >= 2) {
      container.add(
        this.add.text(r + 8, -r - 4, `Lv${lvl.level}`, {
          fontSize: '8px',
          fontFamily: 'JetBrains Mono, ui-monospace, monospace',
          color: `#${color.toString(16).padStart(6, '0')}`,
        }).setOrigin(0, 0.5).setAlpha(0.6)
      );
    }

    // Breathing / idle animation
    if (isSelf) {
      this.tweens.add({
        targets: outer,
        alpha: { from: 0.04, to: 0.12 },
        scaleX: { from: 1, to: 1.1 }, scaleY: { from: 1, to: 1.1 },
        duration: 3000, yoyo: true, repeat: -1, ease: 'Sine.easeInOut',
      });
    } else {
      this.tweens.add({
        targets: core,
        alpha: { from: 0.7, to: 0.5 },
        duration: 2000 + Math.random() * 2000,
        yoyo: true, repeat: -1, ease: 'Sine.easeInOut',
      });
    }

    // "ONLINE" label for self
    if (isSelf) {
      container.add(
        this.add.text(0, 3, 'ONLINE', {
          fontSize: '8px',
          fontFamily: 'JetBrains Mono, ui-monospace, monospace',
          color: '#00e5ff',
        }).setOrigin(0.5).setAlpha(0.8)
      );
    }
  }

  // ── Task effects ──

  private createTaskEffects(layout: LayoutData) {
    for (const t of layout.taskNodes) {
      const isActive = t.status === 'chatting' || t.status === 'matched';
      const isSearching = t.status === 'searching';
      const color = t.direction === 'outgoing'
        ? (isActive ? C.green : C.amber)
        : (isActive ? C.blue : C.purple);

      // Active task pulse
      if (isActive) {
        const g = this.add.graphics().setDepth(8);
        g.fillStyle(color, 0.15);
        g.fillCircle(t.x, t.y, 14);
        this.pulseObjects.push(g);

        const ring = this.add.circle(t.x, t.y, 14, color, 0).setDepth(8);
        ring.setStrokeStyle(1.5, color, 0.5);
        this.pulseObjects.push(ring);

        this.tweens.add({
          targets: ring,
          scaleX: { from: 1, to: 2.5 }, scaleY: { from: 1, to: 2.5 },
          alpha: { from: 0.5, to: 0 },
          duration: 1500, repeat: -1, ease: 'Sine.easeOut',
        });

        // Task label
        const label = this.add.text(t.x, t.y + 22, t.question.slice(0, 16), {
          fontSize: '9px', fontFamily: 'JetBrains Mono, ui-monospace, monospace',
          color: `#${color.toString(16).padStart(6, '0')}`,
        }).setOrigin(0.5).setDepth(8).setAlpha(0.7);
        this.pulseObjects.push(label);

        const statusText = t.status === 'chatting' ? 'ACTIVE' : 'LINKED';
        const tag = this.add.text(t.x, t.y - 20, statusText, {
          fontSize: '7px', fontFamily: 'JetBrains Mono, ui-monospace, monospace',
          color: `#${color.toString(16).padStart(6, '0')}`,
        }).setOrigin(0.5).setDepth(8).setAlpha(0.6);
        this.pulseObjects.push(tag);
      }

      // Searching → search wave emanating from self
      if (isSearching) {
        this.spawnSearchWave(layout.selfNode.x, layout.selfNode.y);
      }
    }
  }

  // ── Search wave ──

  private spawnSearchWave(cx: number, cy: number) {
    const ring = this.add.circle(cx, cy, 10, C.searchWave, 0)
      .setStrokeStyle(1.5, C.searchWave, 0.4)
      .setDepth(7);
    this.searchWaves.push(ring);

    this.tweens.add({
      targets: ring,
      radius: { from: 10, to: 200 },
      alpha: { from: 0.4, to: 0 },
      duration: 2000,
      ease: 'Sine.easeOut',
      onComplete: () => {
        const idx = this.searchWaves.indexOf(ring);
        if (idx >= 0) this.searchWaves.splice(idx, 1);
        if (ring.active) ring.destroy();
      },
    });
  }

  // ── Empty state ──

  private showEmptyState(cx: number, cy: number) {
    this.emptyLabel = this.add.text(cx, cy, '星图待绘制', {
      fontSize: '16px',
      fontFamily: 'JetBrains Mono, ui-monospace, monospace',
      color: '#667788',
      align: 'center',
    }).setOrigin(0.5).setDepth(20);

    const sub = this.add.text(cx, cy + 24, '连接协作服务器后，协作星路将在此显现', {
      fontSize: '10px',
      fontFamily: 'JetBrains Mono, ui-monospace, monospace',
      color: '#445566',
      align: 'center',
    }).setOrigin(0.5).setDepth(20);
    this.pulseObjects.push(sub);

    // Gentle pulse on empty state text
    this.tweens.add({
      targets: this.emptyLabel,
      alpha: { from: 0.6, to: 0.3 },
      duration: 2000, yoyo: true, repeat: -1, ease: 'Sine.easeInOut',
    });
  }

  // ── Cleanup ──

  private clearAll() {
    // Kill all active tweens once (not per container)
    this.tweens.killAll();

    for (const c of this.agentContainers) c.destroy(true);
    this.agentContainers = [];

    for (const g of this.pathGraphics) g.destroy(true);
    this.pathGraphics = [];

    for (const o of this.pulseObjects) o.destroy(true);
    this.pulseObjects = [];

    for (const w of this.searchWaves) w.destroy();
    this.searchWaves = [];

    if (this.emptyLabel) {
      this.emptyLabel.destroy();
      this.emptyLabel = null;
    }
  }

  shutdown() {
    this.storeUnsub?.();
    if (this.rebuildTimer) {
      clearTimeout(this.rebuildTimer);
      this.rebuildTimer = null;
    }
    this.clearAll();
  }
}
