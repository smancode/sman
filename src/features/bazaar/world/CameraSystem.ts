// src/features/bazaar/world/CameraSystem.ts

function clamp(value: number, min: number, max: number): number {
  return Math.max(min, Math.min(max, value));
}

export class CameraSystem {
  private mapWidth: number;
  private mapHeight: number;
  private cameraX = 0;
  private cameraY = 0;
  private viewportWidth = 0;
  private viewportHeight = 0;
  private maxX = 0;
  private maxY = 0;

  constructor(mapWidth: number, mapHeight: number) {
    this.mapWidth = mapWidth;
    this.mapHeight = mapHeight;
  }

  panBy(deltaX: number, deltaY: number): void {
    this.cameraX = clamp(this.cameraX + deltaX, 0, this.maxX);
    this.cameraY = clamp(this.cameraY + deltaY, 0, this.maxY);
  }

  centerOn(worldX: number, worldY: number): void {
    this.cameraX = clamp(worldX - this.viewportWidth / 2, 0, this.maxX);
    this.cameraY = clamp(worldY - this.viewportHeight / 2, 0, this.maxY);
  }

  getOffset(): { x: number; y: number } {
    return { x: this.cameraX, y: this.cameraY };
  }

  setViewport(width: number, height: number): void {
    this.viewportWidth = width;
    this.viewportHeight = height;
    this.maxX = Math.max(0, this.mapWidth - width);
    this.maxY = Math.max(0, this.mapHeight - height);

    if (width >= this.mapWidth) {
      this.cameraX = -(width - this.mapWidth) / 2;
    } else {
      this.cameraX = clamp(this.cameraX, 0, this.maxX);
    }

    if (height >= this.mapHeight) {
      this.cameraY = -(height - this.mapHeight) / 2;
    } else {
      this.cameraY = clamp(this.cameraY, 0, this.maxY);
    }
  }
}
