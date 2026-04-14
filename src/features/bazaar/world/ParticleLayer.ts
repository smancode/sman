// src/features/bazaar/world/ParticleLayer.ts
// 轻量粒子系统 — 冰晶飘落 + 点击爆发（冷色系）

import { PARTICLE_COLORS } from './palette';

interface Particle {
  x: number;
  y: number;
  vx: number;
  vy: number;
  life: number;
  maxLife: number;
  size: number;
  color: string;
  phase: number;
}

const AMBIENT_MAX = 30;
const BURST_MAX = 20;

const ICE_COLORS = PARTICLE_COLORS.ambient;
const BURST_COLORS = PARTICLE_COLORS.burst;

export class ParticleLayer {
  private ambient: Particle[] = [];
  private burst: Particle[] = [];
  private viewW = 0;
  private viewH = 0;

  setViewport(w: number, h: number): void {
    this.viewW = w;
    this.viewH = h;
    while (this.ambient.length < AMBIENT_MAX) {
      this.ambient.push(this.spawnIce());
    }
  }

  spawnBurst(x: number, y: number): void {
    const count = Math.min(12, BURST_MAX - this.burst.length);
    for (let i = 0; i < count; i++) {
      const angle = (Math.PI * 2 / 12) * i + Math.random() * 0.3;
      const speed = 1.5 + Math.random() * 2;
      this.burst.push({
        x, y,
        vx: Math.cos(angle) * speed,
        vy: Math.sin(angle) * speed - 1.5,
        life: 1,
        maxLife: 1,
        size: 2 + Math.random(),
        color: BURST_COLORS[Math.floor(Math.random() * BURST_COLORS.length)],
        phase: 0,
      });
    }
  }

  update(): void {
    for (const p of this.ambient) {
      p.x += p.vx + Math.sin(p.phase) * 0.1;
      p.y += p.vy;
      p.phase += 0.02;
      if (p.y < -10) { p.y = this.viewH + 10; p.x = Math.random() * this.viewW; }
      if (p.x < -10) p.x = this.viewW + 10;
      if (p.x > this.viewW + 10) p.x = -10;
    }

    for (let i = this.burst.length - 1; i >= 0; i--) {
      const p = this.burst[i];
      p.x += p.vx;
      p.y += p.vy;
      p.vy += 0.08;
      p.life -= 0.025;
      if (p.life <= 0) this.burst.splice(i, 1);
    }
  }

  render(ctx: CanvasRenderingContext2D): void {
    // 冰晶飘落
    for (const p of this.ambient) {
      const alpha = p.life * (0.5 + Math.sin(p.phase * 3) * 0.2);
      ctx.globalAlpha = alpha;
      ctx.fillStyle = p.color;
      ctx.fillRect(Math.round(p.x), Math.round(p.y), Math.round(p.size), Math.round(p.size));
    }

    // 爆发粒子
    for (const p of this.burst) {
      ctx.globalAlpha = p.life;
      ctx.fillStyle = p.color;
      ctx.fillRect(Math.round(p.x) - 1, Math.round(p.y) - 1, 3, 3);
    }

    ctx.globalAlpha = 1;
  }

  private spawnIce(): Particle {
    return {
      x: Math.random() * (this.viewW || 800),
      y: Math.random() * (this.viewH || 600),
      vx: (Math.random() - 0.5) * 0.3,
      vy: -Math.random() * 0.15 - 0.03,
      life: Math.random() * 0.4 + 0.3,
      maxLife: 1,
      size: Math.random() * 1.5 + 1,
      color: ICE_COLORS[Math.floor(Math.random() * ICE_COLORS.length)],
      phase: Math.random() * Math.PI * 2,
    };
  }
}
