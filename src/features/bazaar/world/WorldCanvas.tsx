// src/features/bazaar/world/WorldCanvas.tsx
// Canvas 容器组件 — 初始化渲染引擎 + 动画循环

import { useEffect, useRef, useCallback } from 'react';
import { WorldRenderer } from './WorldRenderer';
import { AgentEntity } from './AgentEntity';
import { DESIGN } from './palette';

interface WorldCanvasProps {
  rendererRef: React.MutableRefObject<WorldRenderer | null>;
}

export function WorldCanvas({ rendererRef }: WorldCanvasProps) {
  const canvasRef = useRef<HTMLCanvasElement>(null);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;

    const renderer = new WorldRenderer();
    renderer.init(canvas);
    rendererRef.current = renderer;

    // 添加示例 Agent（模拟在线 Agent）
    const demoAgents: Array<{ name: string; avatar: string; x: number; y: number; color: string }> = [
      { name: '张三', avatar: '🧙', x: 300, y: 350, color: '#41a6f6' },
      { name: '小李', avatar: '🧑‍💻', x: 500, y: 280, color: '#38b764' },
      { name: '老王', avatar: '🧑‍🎓', x: 200, y: 200, color: '#ef7d57' },
      { name: '小陈', avatar: '🦊', x: 800, y: 400, color: '#b13e53' },
      { name: '阿花', avatar: '🐱', x: 600, y: 500, color: '#ffcd75' },
    ];

    for (const a of demoAgents) {
      const entity = new AgentEntity({
        id: `demo-${a.name}`,
        name: a.name,
        avatar: a.avatar,
        reputation: Math.random() * 50,
        x: a.x,
        y: a.y,
        shirtColor: a.color,
      });
      renderer.addAgent(entity);
    }

    renderer.start();

    return () => {
      renderer.destroy();
      rendererRef.current = null;
    };
  }, [rendererRef]);

  const handleResize = useCallback(() => {
    const canvas = canvasRef.current;
    const renderer = rendererRef.current;
    if (!canvas || !renderer) return;

    const dpr = window.devicePixelRatio || 1;
    const rect = canvas.getBoundingClientRect();
    canvas.width = rect.width * dpr;
    canvas.height = rect.height * dpr;
    const ctx = canvas.getContext('2d');
    if (ctx) {
      ctx.scale(dpr, dpr);
      ctx.imageSmoothingEnabled = false;
    }
  }, [rendererRef]);

  useEffect(() => {
    window.addEventListener('resize', handleResize);
    return () => window.removeEventListener('resize', handleResize);
  }, [handleResize]);

  return (
    <canvas
      ref={canvasRef}
      style={{
        width: '100%',
        height: '100%',
        display: 'block',
        imageRendering: 'pixelated',
        background: '#1a1c2c',
      }}
    />
  );
}
