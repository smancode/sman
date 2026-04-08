import { useEffect, useRef, useState } from 'react';
import { Outlet, useLocation } from 'react-router-dom';
import { Sidebar } from './Sidebar';
import { Titlebar } from './Titlebar';
import { useChatStore } from '@/stores/chat';
import { cn } from '@/lib/utils';

// 浅色主题流动色块配色
const CANDY_COLORS = [
  'hsla(340,95%,72%,0.5)', 'hsla(260,90%,75%,0.45)', 'hsla(55,100%,65%,0.45)',
  'hsla(165,80%,60%,0.45)', 'hsla(20,100%,70%,0.45)', 'hsla(200,90%,70%,0.45)',
];

// 深色主题星云配色
const NEBULA_COLORS = [
  'hsla(260,80%,60%,0.25)', 'hsla(200,90%,55%,0.20)', 'hsla(320,70%,55%,0.20)',
  'hsla(180,80%,50%,0.18)', 'hsla(240,90%,65%,0.22)', 'hsla(280,75%,60%,0.20)',
];

export function MainLayout() {
  const [isDark, setIsDark] = useState(() => document.documentElement.classList.contains('dark'));
  const [isMaximized, setIsMaximized] = useState(false);
  const location = useLocation();
  const messages = useChatStore((s) => s.messages);
  const hasMessages = messages.length > 0;
  const inChat = location.pathname === '/chat';
  const isWelcome = inChat && !hasMessages;
  const isWindows = window.sman?.platform === 'win32';

  useEffect(() => {
    const observer = new MutationObserver(() => {
      setIsDark(document.documentElement.classList.contains('dark'));
    });
    observer.observe(document.documentElement, { attributes: true, attributeFilter: ['class'] });
    return () => observer.disconnect();
  }, []);

  // Listen for maximize state on Windows (to toggle rounded corners)
  useEffect(() => {
    if (!window.sman?.onMaximizeChanged) return;
    const unsubscribe = window.sman.onMaximizeChanged(setIsMaximized);
    window.sman.windowIsMaximized?.().then(setIsMaximized);
    return unsubscribe;
  }, []);

  return (
    <div className={cn(
      'flex flex-col h-screen overflow-hidden relative bg-background',
      isWindows && !isMaximized && 'rounded-lg',
    )}>
      {/* 全局流动背景 - 覆盖整个 UI */}
      <div
        className="absolute inset-0 pointer-events-none z-0 transition-opacity duration-700"
        style={{ opacity: isWelcome ? 1 : 0.15 }}
      >
        {isDark ? <NebulaFlow /> : <CandyBlobs />}
      </div>

      {/* 内容层 - 半透明以透出背景 */}
      <div className="relative z-10 flex flex-col h-full">
        {/* Sidebar 全通：从顶部到底部，绝对定位 */}
        <div className="absolute inset-y-0 left-0 z-20 w-64">
          <Sidebar />
        </div>
        {/* 右侧区域：Titlebar + 主内容 */}
        <div className="flex flex-col flex-1 ml-64 overflow-hidden">
          <Titlebar />
          <main className="flex-1 overflow-y-auto bg-transparent">
            <Outlet />
          </main>
        </div>
      </div>
    </div>
  );
}

// ── 浅色主题 - 糖果色流动色块 ──────────────────────────────────

function CandyBlobs() {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    const resize = () => {
      canvas.width = window.innerWidth;
      canvas.height = window.innerHeight;
    };
    resize();

    const PERIOD = 15000;
    type Blob = { x: number; y: number; r: number; color: string; offset: number; vx: number; vy: number };

    function randomBlob(offset: number): Blob {
      return {
        x: 0.1 + Math.random() * 0.8,
        y: 0.1 + Math.random() * 0.8,
        r: 0.3 + Math.random() * 0.25,
        color: CANDY_COLORS[Math.floor(Math.random() * CANDY_COLORS.length)],
        offset,
        vx: (Math.random() - 0.5) * 0.0003,
        vy: (Math.random() - 0.5) * 0.0003,
      };
    }

    const blobs = Array.from({ length: 5 }, (_, i) => randomBlob(-(i / 5) * PERIOD));
    let rafId: number;

    function step(ts: number) {
      if (!canvas || !ctx) return;
      const W = canvas.width, H = canvas.height;

      // 清空画布，只保留色块（不画不透明底色）
      ctx.clearRect(0, 0, W, H);

      for (const b of blobs) {
        b.x += b.vx;
        b.y += b.vy;
        if (b.x < -0.2) b.x = 1.2;
        if (b.x > 1.2) b.x = -0.2;
        if (b.y < -0.2) b.y = 1.2;
        if (b.y > 1.2) b.y = -0.2;

        const t = ((ts + b.offset * -1) % PERIOD + PERIOD) % PERIOD;
        const prog = t / PERIOD;
        let scale: number, alpha: number;

        if (prog < 0.3) {
          scale = 0.8 + prog / 0.3 * 0.6;
          alpha = prog / 0.3 * 0.8;
        } else if (prog < 0.7) {
          scale = 1.4 + (prog - 0.3) / 0.4 * 0.4;
          alpha = 0.8;
        } else {
          scale = 1.8 - (prog - 0.7) / 0.3 * 0.4;
          alpha = 0.8 - (prog - 0.7) / 0.3 * 0.8;
        }

        if (prog < 0.02) {
          b.color = CANDY_COLORS[Math.floor(Math.random() * CANDY_COLORS.length)];
        }

        const cx = b.x * W, cy = b.y * H;
        const radius = b.r * Math.min(W, H) * scale;
        const grd = ctx.createRadialGradient(cx, cy, 0, cx, cy, radius);
        grd.addColorStop(0, b.color.replace(/[\d.]+\)$/, `${alpha * 0.5})`));
        grd.addColorStop(1, 'rgba(255,255,255,0)');

        ctx.beginPath();
        ctx.arc(cx, cy, radius, 0, Math.PI * 2);
        ctx.fillStyle = grd;
        ctx.fill();
      }

      rafId = requestAnimationFrame(step);
    }

    rafId = requestAnimationFrame(step);
    window.addEventListener('resize', resize);
    return () => { cancelAnimationFrame(rafId); window.removeEventListener('resize', resize); };
  }, []);

  return <canvas ref={canvasRef} className="absolute inset-0 w-full h-full" />;
}

// ── 深色主题 - 星云流动效果 ────────────────────────────────────

function NebulaFlow() {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    const resize = () => {
      canvas.width = window.innerWidth;
      canvas.height = window.innerHeight;
    };
    resize();

    const PERIOD = 20000;
    type Nebula = {
      x: number; y: number; r: number; color: string; offset: number;
      vx: number; vy: number; vr: number;
    };

    function randomNebula(offset: number): Nebula {
      return {
        x: 0.05 + Math.random() * 0.9,
        y: 0.05 + Math.random() * 0.9,
        r: 0.4 + Math.random() * 0.3,
        color: NEBULA_COLORS[Math.floor(Math.random() * NEBULA_COLORS.length)],
        offset,
        vx: (Math.random() - 0.5) * 0.0002,
        vy: (Math.random() - 0.5) * 0.0002,
        vr: (Math.random() - 0.5) * 0.0001,
      };
    }

    const nebulas = Array.from({ length: 6 }, (_, i) => randomNebula(-(i / 6) * PERIOD));
    let rafId: number;

    function step(ts: number) {
      if (!canvas || !ctx) return;
      const W = canvas.width, H = canvas.height;

      // 清空画布，只保留星云色块
      ctx.clearRect(0, 0, W, H);

      for (const n of nebulas) {
        n.x += n.vx;
        n.y += n.vy;
        n.r += n.vr;

        if (n.x < -0.3) n.x = 1.3;
        if (n.x > 1.3) n.x = -0.3;
        if (n.y < -0.3) n.y = 1.3;
        if (n.y > 1.3) n.y = -0.3;
        if (n.r < 0.3 || n.r > 0.7) n.vr *= -1;

        const t = ((ts + n.offset * -1) % PERIOD + PERIOD) % PERIOD;
        const prog = t / PERIOD;
        let alpha: number;

        if (prog < 0.25) {
          alpha = prog / 0.25 * 0.6;
        } else if (prog < 0.75) {
          alpha = 0.6;
        } else {
          alpha = 0.6 - (prog - 0.75) / 0.25 * 0.6;
        }

        if (prog < 0.02) {
          n.color = NEBULA_COLORS[Math.floor(Math.random() * NEBULA_COLORS.length)];
        }

        const cx = n.x * W, cy = n.y * H;
        const radius = n.r * Math.min(W, H);

        const grd = ctx.createRadialGradient(cx, cy, 0, cx, cy, radius);
        grd.addColorStop(0, n.color.replace(/[\d.]+\)$/, `${alpha})`));
        grd.addColorStop(0.4, n.color.replace(/[\d.]+\)$/, `${alpha * 0.5})`));
        grd.addColorStop(1, 'rgba(0,0,0,0)');

        ctx.beginPath();
        ctx.arc(cx, cy, radius, 0, Math.PI * 2);
        ctx.fillStyle = grd;
        ctx.fill();
      }

      rafId = requestAnimationFrame(step);
    }

    rafId = requestAnimationFrame(step);
    window.addEventListener('resize', resize);
    return () => { cancelAnimationFrame(rafId); window.removeEventListener('resize', resize); };
  }, []);

  return <canvas ref={canvasRef} className="absolute inset-0 w-full h-full" />;
}
