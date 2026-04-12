import { describe, it, expect } from 'vitest';
import { CameraSystem } from '../CameraSystem';

describe('CameraSystem', () => {
  it('should clamp pan to map boundaries', () => {
    const cam = new CameraSystem(1280, 960);
    cam.setViewport(800, 600);
    cam.panBy(9999, 9999);
    const { x, y } = cam.getOffset();
    expect(x).toBe(480);
    expect(y).toBe(360);
  });

  it('should not go below 0', () => {
    const cam = new CameraSystem(1280, 960);
    cam.setViewport(800, 600);
    cam.panBy(-9999, -9999);
    const { x, y } = cam.getOffset();
    expect(x).toBe(0);
    expect(y).toBe(0);
  });

  it('should center when viewport larger than map', () => {
    const cam = new CameraSystem(1280, 960);
    cam.setViewport(1600, 1200);
    const { x, y } = cam.getOffset();
    expect(x).toBe(-160);
    expect(y).toBe(-120);
  });

  it('should center on a world point', () => {
    const cam = new CameraSystem(1280, 960);
    cam.setViewport(800, 600);
    cam.centerOn(640, 480);
    const { x, y } = cam.getOffset();
    expect(x).toBe(240);
    expect(y).toBe(180);
  });

  it('should update bounds on viewport resize', () => {
    const cam = new CameraSystem(1280, 960);
    cam.setViewport(800, 600);
    cam.panBy(9999, 0);
    expect(cam.getOffset().x).toBe(480);
    cam.setViewport(1000, 600);
    expect(cam.getOffset().x).toBe(280);
  });
});
