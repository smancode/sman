// src/features/bazaar/world/AgentEntity.ts
// Agent 实体 — 位置、状态、动画

type Facing = 'down' | 'up' | 'left' | 'right';
type AgentState = 'idle' | 'walking' | 'busy';

export class AgentEntity {
  id: string;
  name: string;
  avatar: string; // emoji
  reputation: number;
  shirtColor: string;

  // 像素坐标
  x: number;
  y: number;
  targetX: number;
  targetY: number;

  facing: Facing = 'down';
  state: AgentState = 'idle';
  frame: number = 0;

  // 动画计时器
  private animTimer = 0;
  private walkSpeed = 1.5; // 像素/帧

  // 自主行为：随机游走目标
  private wanderTimer = 0;
  private wanderInterval = 120 + Math.random() * 180; // 4-10 秒后换方向

  constructor(opts: {
    id: string;
    name: string;
    avatar: string;
    reputation: number;
    x: number;
    y: number;
    shirtColor?: string;
  }) {
    this.id = opts.id;
    this.name = opts.name;
    this.avatar = opts.avatar;
    this.reputation = opts.reputation;
    this.x = opts.x;
    this.y = opts.y;
    this.targetX = opts.x;
    this.targetY = opts.y;
    this.shirtColor = opts.shirtColor ?? '#41a6f6';
  }

  /** 设置移动目标 */
  moveTo(tx: number, ty: number): void {
    this.targetX = tx;
    this.targetY = ty;
    this.state = 'walking';
  }

  /** 随机游走（自主行为） */
  wander(bounds: { minX: number; maxX: number; minY: number; maxY: number }): void {
    const padding = 48;
    this.moveTo(
      padding + Math.random() * (bounds.maxX - padding * 2),
      padding + Math.random() * (bounds.maxY - padding * 2),
    );
  }

  /** 每帧更新 */
  update(bounds: { minX: number; maxX: number; minY: number; maxY: number }): void {
    // 移动插值
    const dx = this.targetX - this.x;
    const dy = this.targetY - this.y;
    const dist = Math.sqrt(dx * dx + dy * dy);

    if (dist > 2) {
      this.x += (dx / dist) * this.walkSpeed;
      this.y += (dy / dist) * this.walkSpeed;
      this.state = 'walking';

      // 更新朝向
      if (Math.abs(dx) > Math.abs(dy)) {
        this.facing = dx > 0 ? 'right' : 'left';
      } else {
        this.facing = dy > 0 ? 'down' : 'up';
      }

      // 走路动画帧
      this.animTimer++;
      if (this.animTimer >= 8) {
        this.frame = this.frame === 0 ? 1 : 0;
        this.animTimer = 0;
      }
    } else {
      this.x = this.targetX;
      this.y = this.targetY;
      this.state = 'idle';
      this.frame = 0;

      // 自主游走计时
      this.wanderTimer++;
      if (this.wanderTimer >= this.wanderInterval) {
        this.wanderTimer = 0;
        this.wanderInterval = 120 + Math.random() * 180;
        this.wander(bounds);
      }
    }
  }
}
