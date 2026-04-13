// src/features/bazaar/world/InputPipeline.ts

import type { WorldRenderer } from './WorldRenderer';
import type { CameraSystem } from './CameraSystem';

export interface HitResult {
  consumed: boolean;
  type: 'building' | 'agent';
  target: unknown;
}

export type InputHandler = (worldX: number, worldY: number) => HitResult | null;

export class InputPipeline {
  private renderer: WorldRenderer;
  private camera: CameraSystem;
  private handlers: InputHandler[] = [];
  private onGroundClick: ((worldX: number, worldY: number) => void) | null;
  private onHover: ((screenX: number, screenY: number) => void) | null;
  private dragThreshold = 5;

  private isDragging = false;
  private mouseDownX = 0;
  private mouseDownY = 0;
  private lastMoveX = 0;
  private lastMoveY = 0;
  private mouseIsDown = false;

  constructor(
    renderer: WorldRenderer,
    camera: CameraSystem,
    onGroundClick?: (worldX: number, worldY: number) => void,
    onHover?: (screenX: number, screenY: number) => void,
  ) {
    this.renderer = renderer;
    this.camera = camera;
    this.onGroundClick = onGroundClick ?? null;
    this.onHover = onHover ?? null;
  }

  register(handler: InputHandler): void {
    this.handlers.push(handler);
  }

  onMouseDown = (e: MouseEvent): void => {
    if (e.button !== 0) return;
    this.mouseIsDown = true;
    this.isDragging = false;
    this.mouseDownX = e.clientX;
    this.mouseDownY = e.clientY;
    this.lastMoveX = e.clientX;
    this.lastMoveY = e.clientY;
  };

  onMouseMove = (e: MouseEvent): void => {
    if (!this.mouseIsDown) {
      // Non-dragging state — hover event
      if (this.onHover) {
        const rect = (e.target as HTMLElement).getBoundingClientRect();
        this.onHover(e.clientX - rect.left, e.clientY - rect.top);
      }
      return;
    }

    const dx = e.clientX - this.lastMoveX;
    const dy = e.clientY - this.lastMoveY;
    const totalDx = e.clientX - this.mouseDownX;
    const totalDy = e.clientY - this.mouseDownY;

    if (!this.isDragging && Math.sqrt(totalDx * totalDx + totalDy * totalDy) >= this.dragThreshold) {
      this.isDragging = true;
    }

    if (this.isDragging) {
      this.camera.panBy(dx, dy);
      this.lastMoveX = e.clientX;
      this.lastMoveY = e.clientY;
    }
  };

  onMouseUp = (e: MouseEvent): void => {
    if (!this.mouseIsDown) return;
    this.mouseIsDown = false;

    if (this.isDragging) return;

    const rect = (e.target as HTMLElement).getBoundingClientRect();
    const screenX = e.clientX - rect.left;
    const screenY = e.clientY - rect.top;
    const world = this.renderer.screenToWorld(screenX, screenY);

    for (const handler of this.handlers) {
      const result = handler(world.x, world.y);
      if (result?.consumed) return;
    }

    this.onGroundClick?.(world.x, world.y);
  };
}
