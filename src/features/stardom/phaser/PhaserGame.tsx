// src/features/stardom/phaser/PhaserGame.tsx
// Phaser 4 + React bridge — manages Phaser.Game lifecycle

import { useEffect, useRef } from 'react';
import Phaser from 'phaser';
import { StarMapScene } from './StarMapScene';
import { useStardomStore } from '@/stores/stardom';

export function PhaserGame() {
  const containerRef = useRef<HTMLDivElement>(null);
  const gameRef = useRef<Phaser.Game | null>(null);

  useEffect(() => {
    const container = containerRef.current;
    if (!container || gameRef.current) return;

    // Wait one frame for the container to have layout dimensions
    const initTimer = requestAnimationFrame(() => {
      if (!container || gameRef.current) return;

      const w = container.clientWidth || 800;
      const h = container.clientHeight || 400;

      const scene = new StarMapScene();
      let unsub: (() => void) | null = null;

      gameRef.current = new Phaser.Game({
        type: Phaser.WEBGL,
        parent: container,
        width: w,
        height: h,
        backgroundColor: '#0a0e17',
        scale: {
          mode: Phaser.Scale.RESIZE,
          autoCenter: Phaser.Scale.CENTER_BOTH,
        },
        scene,
        render: {
          antialias: true,
          pixelArt: false,
          roundPixels: false,
        },
        audio: { noAudio: true },
        input: {
          keyboard: false,
          mouse: true,
          touch: true,
        },
      });

      // Expose store bridge on scene for data sync
      (scene as any).__storeBridge = {
        getState: () => useStardomStore.getState(),
        subscribe: (cb: () => void) => {
          unsub = useStardomStore.subscribe(cb);
          return unsub;
        },
      };
    });

    return () => {
      cancelAnimationFrame(initTimer);
      const game = gameRef.current;
      if (game) {
        game.destroy(true);
        gameRef.current = null;
      }
    };
  }, []);

  return (
    <div
      ref={containerRef}
      style={{
        width: '100%',
        height: '100%',
        position: 'absolute',
        top: 0,
        left: 0,
        right: 0,
        bottom: 0,
      }}
    />
  );
}
