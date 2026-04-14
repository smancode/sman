// src/features/bazaar/world/WorldCanvas.tsx
// Canvas 容器组件 — 交互管道 + 数据同步 + 事件绑定

import { useEffect, useRef, useCallback } from 'react';
import { WorldRenderer } from './WorldRenderer';
import { CameraSystem } from './CameraSystem';
import { InputPipeline } from './InputPipeline';
import { InteractionSystem } from './InteractionSystem';
import { WorldSync } from './WorldSync';
import { BuildingRegistry } from './BuildingRegistry';
import { BUILDINGS } from './map-data';
import { DESIGN, CANVAS_BG } from './palette';
import { setAssetProvider } from './SpriteSheet';
import { ImageAssets } from './assets/ImageAssets';
import { useBazaarStore } from '@/stores/bazaar';
import type { ActivePanel } from './types';

interface WorldCanvasProps {
  rendererRef: React.MutableRefObject<WorldRenderer | null>;
  onPanelChange?: (panel: ActivePanel) => void;
  onAgentClick?: (agent: { id: string; name: string; avatar: string; reputation: number }) => void;
  onHover?: (data: { type: 'building'; label: string } | { type: 'agent'; name: string; avatar: string; status: string; reputation: number; isOldPartner: boolean } | null) => void;
}

export function WorldCanvas({ rendererRef, onPanelChange, onAgentClick, onHover }: WorldCanvasProps) {
  const canvasRef = useRef<HTMLCanvasElement>(null);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;

    // Try loading PNG assets (fallback to procedural)
    const imageAssets = new ImageAssets();
    imageAssets.load().then((ok) => {
      if (ok) setAssetProvider(imageAssets);
    });

    const rect = canvas.getBoundingClientRect();
    const renderer = new WorldRenderer();
    const camera = new CameraSystem(DESIGN.MAP_COLS * DESIGN.TILE_SIZE, DESIGN.MAP_ROWS * DESIGN.TILE_SIZE);
    camera.setViewport(rect.width, rect.height);

    const sync = new WorldSync(renderer, useBazaarStore);
    const registry = new BuildingRegistry();
    const interaction = new InteractionSystem(BUILDINGS, registry);
    const pipeline = new InputPipeline(renderer, camera, (wx, wy) => sync.moveSelfAgent(wx, wy), (screenX, screenY) => {
      // Hover event: detect what's under cursor
      const world = renderer.screenToWorld(screenX, screenY);
      const agents = renderer.getAllAgents();
      const hoverResult = interaction.hoverTest(world.x, world.y, agents);

      if (hoverResult) {
        if (hoverResult.type === 'building') {
          const building = hoverResult.target as typeof BUILDINGS[number];
          renderer.setHovered(building, null);
          onHover?.({ type: 'building', label: building.label });
        } else {
          const agent = hoverResult.target as import('./AgentEntity').AgentEntity;
          renderer.setHovered(null, agent);
          onHover?.({ type: 'agent', name: agent.name, avatar: agent.avatar, status: agent.state, reputation: agent.reputation, isOldPartner: false });
        }
      } else {
        renderer.setHovered(null, null);
        onHover?.(null);
      }
    });

    // Register handlers in priority order
    pipeline.register((worldX, worldY) => {
      const hit = interaction.hitTestBuildings(worldX, worldY);
      if (hit && hit.type === 'building') {
        const building = hit.target as typeof BUILDINGS[number];
        const action = interaction.handleBuildingClick(building);
        if (action) onPanelChange?.(action.panel);
        return hit;
      }
      return null;
    });

    pipeline.register((worldX, worldY) => {
      const hit = interaction.hitTestAgents(worldX, worldY, renderer.getAllAgents());
      if (hit && hit.type === 'agent') {
        const agent = hit.target as import('./AgentEntity').AgentEntity;
        onAgentClick?.({ id: agent.id, name: agent.name, avatar: agent.avatar, reputation: agent.reputation });
        // 点击爆发粒子（屏幕坐标）
        const rect = canvas.getBoundingClientRect();
        const dpr = window.devicePixelRatio || 1;
        renderer.spawnClickBurst(
          worldX - camera.getOffset().x,
          worldY - camera.getOffset().y,
        );
        return hit;
      }
      return null;
    });

    renderer.setCamera(camera);
    renderer.init(canvas);
    // 相机初始居中到中央广场
    camera.centerOn(19 * 32, 13 * 32);
    rendererRef.current = renderer;
    renderer.start();

    // First world visit: building pulse animation
    const hasSeenWorld = localStorage.getItem('bazaar-world-seen');
    if (!hasSeenWorld) {
      renderer.startBuildingPulse();
      localStorage.setItem('bazaar-world-seen', 'true');
    }

    // Canvas events
    canvas.addEventListener('mousedown', pipeline.onMouseDown);
    canvas.addEventListener('mousemove', pipeline.onMouseMove);
    canvas.addEventListener('mouseup', pipeline.onMouseUp);

    // Store → renderer sync
    const unsub = useBazaarStore.subscribe(() => sync.syncAgents());
    sync.syncAgents();

    // Init self agent
    const identity = useBazaarStore.getState().connection;
    if (identity.connected && identity.agentId) {
      sync.initSelfAgent(identity.agentId, identity.agentName ?? '我', '🧙');
    }

    return () => {
      unsub();
      renderer.destroy();
      rendererRef.current = null;
      canvas.removeEventListener('mousedown', pipeline.onMouseDown);
      canvas.removeEventListener('mousemove', pipeline.onMouseMove);
      canvas.removeEventListener('mouseup', pipeline.onMouseUp);
    };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

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
    renderer.getCamera()?.setViewport(rect.width, rect.height);
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
        background: CANVAS_BG,
        cursor: 'pointer',
      }}
    />
  );
}
