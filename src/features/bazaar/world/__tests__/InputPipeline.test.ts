import { describe, it, expect, beforeEach } from 'vitest';
import { InputPipeline } from '../InputPipeline';
import { CameraSystem } from '../CameraSystem';
import type { WorldRenderer } from '../WorldRenderer';

describe('InputPipeline', () => {
  let pipeline: InputPipeline;
  let camera: CameraSystem;
  let groundClicks: Array<{ x: number; y: number }>;
  let handlerResults: string[];

  function createMockRenderer(): WorldRenderer {
    return {
      screenToWorld: (sx: number, sy: number) => ({ x: sx, y: sy }),
    } as unknown as WorldRenderer;
  }

  beforeEach(() => {
    camera = new CameraSystem(1280, 960);
    camera.setViewport(800, 600);
    groundClicks = [];
    handlerResults = [];
    const renderer = createMockRenderer();
    pipeline = new InputPipeline(renderer, camera, (wx, wy) => {
      groundClicks.push({ x: wx, y: wy });
    });
  });

  function fakeMouseDown(x: number, y: number) {
    pipeline.onMouseDown({ clientX: x, clientY: y, button: 0, target: { getBoundingClientRect: () => ({ left: 0, top: 0 }) } } as unknown as MouseEvent);
  }
  function fakeMouseMove(x: number, y: number) {
    pipeline.onMouseMove({ clientX: x, clientY: y, target: {} } as MouseEvent);
  }
  function fakeMouseUp(x: number, y: number) {
    pipeline.onMouseUp({ clientX: x, clientY: y, button: 0, target: { getBoundingClientRect: () => ({ left: 0, top: 0 }) } } as unknown as MouseEvent);
  }

  it('should detect click and trigger ground click', () => {
    fakeMouseDown(100, 100);
    fakeMouseUp(102, 101);
    expect(groundClicks).toHaveLength(1);
  });

  it('should NOT trigger ground click on drag', () => {
    fakeMouseDown(100, 100);
    fakeMouseMove(150, 150);
    fakeMouseUp(150, 150);
    expect(groundClicks).toHaveLength(0);
  });

  it('should pan camera on drag', () => {
    const before = { ...camera.getOffset() };
    fakeMouseDown(100, 100);
    fakeMouseMove(150, 150);
    const after = camera.getOffset();
    expect(after.x).not.toBe(before.x);
    expect(after.y).not.toBe(before.y);
  });

  it('should let first handler consume and prevent ground click', () => {
    pipeline.register(() => {
      handlerResults.push('h1');
      return { consumed: true, type: 'building' as const, target: {} as any };
    });
    fakeMouseDown(100, 100);
    fakeMouseUp(101, 100);
    expect(handlerResults).toEqual(['h1']);
    expect(groundClicks).toHaveLength(0);
  });

  it('should pass to next handler if first does not consume', () => {
    pipeline.register(() => {
      handlerResults.push('h1');
      return null;
    });
    pipeline.register(() => {
      handlerResults.push('h2');
      return { consumed: true, type: 'agent' as const, target: {} as any };
    });
    fakeMouseDown(100, 100);
    fakeMouseUp(101, 100);
    expect(handlerResults).toEqual(['h1', 'h2']);
    expect(groundClicks).toHaveLength(0);
  });

  it('should call onHover on mousemove when not dragging', () => {
    const hoverCalls: Array<{ x: number; y: number }> = [];
    const mockRenderer = createMockRenderer();
    const hoverPipeline = new InputPipeline(mockRenderer, camera, undefined, (screenX: number, screenY: number) => {
      hoverCalls.push({ x: screenX, y: screenY });
    });

    // Simulate mouse move without mouse down
    hoverPipeline.onMouseMove({ clientX: 200, clientY: 150, target: { getBoundingClientRect: () => ({ left: 10, top: 20 }) } } as unknown as MouseEvent);

    expect(hoverCalls).toHaveLength(1);
    expect(hoverCalls[0].x).toBe(190); // 200 - 10
    expect(hoverCalls[0].y).toBe(130); // 150 - 20
  });

  it('should NOT call onHover when dragging', () => {
    const hoverCalls: Array<{ x: number; y: number }> = [];
    const mockRenderer = createMockRenderer();
    const hoverPipeline = new InputPipeline(mockRenderer, camera, undefined, (screenX: number, screenY: number) => {
      hoverCalls.push({ x: screenX, y: screenY });
    });

    // Mouse down then move = drag
    hoverPipeline.onMouseDown({ clientX: 100, clientY: 100, button: 0, target: { getBoundingClientRect: () => ({ left: 0, top: 0 }) } } as unknown as MouseEvent);
    hoverPipeline.onMouseMove({ clientX: 200, clientY: 150, target: {} } as MouseEvent);

    expect(hoverCalls).toHaveLength(0);
  });
});
