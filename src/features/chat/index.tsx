import { useEffect, useRef, useState } from 'react';
import { AlertCircle, Loader2 } from 'lucide-react';
import { useChatStore } from '@/stores/chat';
import { useWsConnection } from '@/stores/ws-connection';
import { ChatMessage } from './ChatMessage';
import { ChatInput } from './ChatInput';
import { cn } from '@/lib/utils';
import type { RawMessage } from '@/types/chat';

function safeTimestamp(createdAt: string): number | undefined {
  if (!createdAt) return undefined;
  const d = new Date(createdAt.includes('T') ? createdAt : createdAt.replace(' ', 'T') + 'Z');
  const ts = d.getTime() / 1000;
  return Number.isFinite(ts) ? ts : undefined;
}

function buildContent(text: string, blocks?: unknown[]): unknown {
  if (!blocks || blocks.length === 0) return text;
  // contentBlocks only has thinking/tool_use — prepend text block
  const hasTextBlock = (blocks as Array<{ type: string }>).some(b => b.type === 'text');
  if (hasTextBlock) return blocks;
  if (!text) return blocks;
  return [{ type: 'text', text }, ...blocks];
}

export function Chat() {
  const connectionStatus = useWsConnection((s) => s.status);
  const isConnected = connectionStatus === 'connected';

  const messages = useChatStore((s) => s.messages);
  const currentSessionId = useChatStore((s) => s.currentSessionId);
  const loading = useChatStore((s) => s.loading);
  const sending = useChatStore((s) => s.sending);
  const streamingText = useChatStore((s) => s.streamingText);
  const streamingThinking = useChatStore((s) => s.streamingThinking);
  const streamingTools = useChatStore((s) => s.streamingTools);
  const showThinking = useChatStore((s) => s.showThinking);
  const error = useChatStore((s) => s.error);
  const sendMessage = useChatStore((s) => s.sendMessage);
  const clearError = useChatStore((s) => s.clearError);

  const scrollRef = useRef<HTMLDivElement>(null);
  const prevMsgCount = useRef(0);
  const hasActiveSession = !!currentSessionId;

  // Load history when session changes
  useEffect(() => {
    if (isConnected && currentSessionId) {
      useChatStore.getState().loadHistory();
    }
  }, [isConnected, currentSessionId]);

  // Auto-scroll
  useEffect(() => {
    if (messages.length > prevMsgCount.current && scrollRef.current) {
      scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
    }
    prevMsgCount.current = messages.length;
  }, [messages.length]);

  // Scroll while streaming
  useEffect(() => {
    if ((streamingText || streamingThinking) && scrollRef.current) {
      scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
    }
  }, [streamingText, streamingThinking]);

  const isEmpty = messages.length === 0 && !sending;

  return (
    <div className="relative flex flex-col h-full transition-colors duration-500 dark:bg-background">
      {/* Messages */}
      <div ref={scrollRef} className="flex-1 overflow-y-auto">
        {/* Top fade mask - soft edge at scroll boundary */}
        <div className="sticky top-0 h-4 bg-gradient-to-b from-background from-30% to-transparent pointer-events-none z-10" />
        <div className={isEmpty ? 'h-full' : 'max-w-4xl mx-auto space-y-4 px-4 pt-3 pb-4'}>
          {isEmpty ? (
            <WelcomeScreen />
          ) : (
            <>
              {messages.map((msg) => (
                <ChatMessage
                  key={msg.id}
                  message={{
                    id: msg.id,
                    role: msg.role,
                    content: buildContent(msg.content, msg.contentBlocks),
                    timestamp: msg.timestamp ?? safeTimestamp(msg.createdAt),
                  } as RawMessage}
                  showThinking={showThinking}
                />
              ))}

              {/* Streaming message */}
              {sending && (streamingText.trim() || streamingThinking.trim() || streamingTools.length > 0) && (
                <ChatMessage
                  message={{
                    role: 'assistant',
                    content: streamingText,
                    timestamp: Date.now() / 1000,
                  } as RawMessage}
                  showThinking={showThinking}
                  isStreaming
                  streamingThinking={streamingThinking}
                  streamingTools={streamingTools.map(t => ({
                    id: t.id,
                    name: t.name,
                    status: t.status,
                    input: t.input as unknown,
                  }))}
                />
              )}

              {/* Typing indicator */}
              {sending && !streamingText.trim() && !streamingThinking.trim() && streamingTools.length === 0 && <TypingIndicator />}
            </>
          )}
        </div>
      </div>

      {/* Error bar */}
      {error && (
        <div className="px-4 py-2 bg-muted border-t border-border">
          <div className="max-w-4xl mx-auto flex items-center justify-between">
            <p className="text-sm text-foreground/80 flex items-center gap-2">
              <AlertCircle className="h-4 w-4 text-destructive" />
              {error}
            </p>
            <button onClick={clearError} className="text-xs text-muted-foreground hover:text-foreground underline">
              关闭
            </button>
          </div>
        </div>
      )}

      {/* Input */}
      {hasActiveSession && (
        <div className="mt-auto">
          <ChatInput onSend={sendMessage} disabled={!isConnected} isEmpty={isEmpty} />
        </div>
      )}

      {/* Loading overlay */}
      {loading && !sending && (
        <div className="absolute inset-0 z-50 flex items-center justify-center bg-background/30 backdrop-blur-sm pointer-events-auto">
          <div className="bg-card shadow-lg rounded-xl p-3 border border-border">
            <Loader2 className="h-5 w-5 animate-spin text-muted-foreground" />
          </div>
        </div>
      )}
    </div>
  );
}

function WelcomeScreen() {
  const [isDark, setIsDark] = useState(() => document.documentElement.classList.contains('dark'));

  useEffect(() => {
    const observer = new MutationObserver(() => {
      setIsDark(document.documentElement.classList.contains('dark'));
    });
    observer.observe(document.documentElement, { attributes: true, attributeFilter: ['class'] });
    return () => observer.disconnect();
  }, []);

  return (
    <div className="relative flex flex-col items-center justify-center text-center h-full overflow-hidden">
      {isDark ? <Starfield /> : <CandyBlobs />}
      <div className="relative z-10 text-center px-8">
        <h1 className="text-4xl md:text-5xl tracking-wide">
          <span className="font-light italic text-foreground/80">欢迎使用</span>{' '}
          <span className="font-black not-italic text-foreground">Sman</span>
        </h1>
        <div className="flex items-center justify-center gap-2 mt-6">
          <span className="hint-chip">选择业务系统</span>
          <span className="hint-chip">开始对话</span>
        </div>
      </div>
      <style>{`
        .hint-chip {
          font-size: 0.65rem; padding: 4px 12px; border-radius: 100px;
          background: hsl(var(--muted)); border: 1px solid hsl(var(--border));
          color: hsl(var(--muted-foreground)); letter-spacing: 0.04em;
          cursor: pointer; transition: all 0.15s ease;
        }
        .hint-chip:hover { background: hsl(var(--accent)); color: hsl(var(--foreground)); }
      `}</style>
    </div>
  );
}

const CANDY_COLORS = [
  'hsla(340,95%,72%,0.5)', 'hsla(260,90%,75%,0.45)', 'hsla(55,100%,65%,0.45)',
  'hsla(165,80%,60%,0.45)', 'hsla(20,100%,70%,0.45)', 'hsla(200,90%,70%,0.45)',
];

function CandyBlobs() {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;
    const parent = canvas.parentElement;
    canvas.width = parent?.clientWidth || window.innerWidth;
    canvas.height = parent?.clientHeight || window.innerHeight;
    const PERIOD = 10000;
    type Blob = { x: number; y: number; r: number; color: string; offset: number };
    function randomBlob(offset: number): Blob {
      return {
        x: 0.1 + Math.random() * 0.8, y: 0.1 + Math.random() * 0.8,
        r: 0.25 + Math.random() * 0.2,
        color: CANDY_COLORS[Math.floor(Math.random() * CANDY_COLORS.length)], offset,
      };
    }
    const blobs = Array.from({ length: 4 }, (_, i) => randomBlob(-(i / 4) * PERIOD));
    let rafId: number;
    function step(ts: number) {
      if (!canvas || !ctx) return;
      const W = canvas.width, H = canvas.height;
      ctx.clearRect(0, 0, W, H);
      for (const b of blobs) {
        const t = ((ts + b.offset * -1) % PERIOD + PERIOD) % PERIOD;
        const prog = t / PERIOD;
        let scale: number, alpha: number;
        if (prog < 0.3) { scale = 0.6 + prog / 0.3 * 0.8; alpha = prog / 0.3; }
        else if (prog < 0.7) { scale = 1.4 + (prog - 0.3) / 0.4 * 0.4; alpha = 1; }
        else { scale = 1.8; alpha = 1 - (prog - 0.7) / 0.3; }
        if (prog < 0.02) { b.x = 0.1 + Math.random() * 0.8; b.y = 0.1 + Math.random() * 0.8; b.r = 0.25 + Math.random() * 0.2; b.color = CANDY_COLORS[Math.floor(Math.random() * CANDY_COLORS.length)]; }
        const cx = b.x * W, cy = b.y * H, radius = b.r * Math.min(W, H) * scale;
        const grd = ctx.createRadialGradient(cx, cy, 0, cx, cy, radius);
        grd.addColorStop(0, b.color.replace(/[\d.]+\)$/, `${alpha * 0.45})`));
        grd.addColorStop(1, 'rgba(255,255,255,0)');
        ctx.beginPath(); ctx.arc(cx, cy, radius, 0, Math.PI * 2); ctx.fillStyle = grd; ctx.fill();
      }
      rafId = requestAnimationFrame(step);
    }
    rafId = requestAnimationFrame(step);
    const onResize = () => { const p = canvas.parentElement; canvas.width = p?.clientWidth || window.innerWidth; canvas.height = p?.clientHeight || window.innerHeight; };
    window.addEventListener('resize', onResize);
    return () => { cancelAnimationFrame(rafId); window.removeEventListener('resize', onResize); };
  }, []);
  return <canvas ref={canvasRef} className="absolute inset-0 pointer-events-none z-0" />;
}

function Starfield() {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;
    const parent = canvas.parentElement;
    canvas.width = parent?.clientWidth || window.innerWidth;
    canvas.height = parent?.clientHeight || window.innerHeight;
    const W = canvas.width, H = canvas.height, cx = W / 2, cy = H / 2;
    type Star = { x: number; y: number; z: number };
    function spawn(): Star { return { x: (Math.random() - 0.5) * W * 3, y: (Math.random() - 0.5) * H * 3, z: W }; }
    const stars: Star[] = Array.from({ length: 20 }, () => { const s = spawn(); s.z = W * (0.1 + Math.random() * 0.9); return s; });
    let rafId: number;
    function step() {
      if (!ctx) return;
      ctx.fillStyle = 'black'; ctx.fillRect(0, 0, W, H);
      for (const s of stars) {
        s.z -= 3; const px = (s.x / s.z) * W + cx, py = (s.y / s.z) * H + cy;
        if (s.z <= 0 || px < -60 || px > W + 60 || py < -60 || py > H + 60) { Object.assign(s, spawn()); continue; }
        const progress = 1 - s.z / W;
        const grd = ctx.createRadialGradient(px, py, 0, px, py, progress * 2.5 * 4);
        grd.addColorStop(0, `rgba(255,255,255,${progress * 0.85})`); grd.addColorStop(1, 'rgba(255,255,255,0)');
        ctx.beginPath(); ctx.arc(px, py, progress * 2.5 * 4, 0, Math.PI * 2); ctx.fillStyle = grd; ctx.fill();
      }
      rafId = requestAnimationFrame(step);
    }
    step();
    const onResize = () => { const p = canvas.parentElement; canvas.width = p?.clientWidth || window.innerWidth; canvas.height = p?.clientHeight || window.innerHeight; };
    window.addEventListener('resize', onResize);
    return () => { cancelAnimationFrame(rafId); window.removeEventListener('resize', onResize); };
  }, []);
  return <canvas ref={canvasRef} className="absolute inset-0 pointer-events-none z-0" style={{ background: 'black' }} />;
}

function TypingIndicator() {
  return (
    <div className="flex gap-3">
      <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg mt-1 bg-muted text-foreground">
        <img src="/favicon.svg" alt="AI" className="h-5 w-5" />
      </div>
      <div className="bg-muted text-foreground rounded-xl px-4 py-3">
        <div className="flex gap-1">
          <span className="w-2 h-2 bg-muted-foreground/50 rounded-full animate-bounce" style={{ animationDelay: '0ms' }} />
          <span className="w-2 h-2 bg-muted-foreground/50 rounded-full animate-bounce" style={{ animationDelay: '150ms' }} />
          <span className="w-2 h-2 bg-muted-foreground/50 rounded-full animate-bounce" style={{ animationDelay: '300ms' }} />
        </div>
      </div>
    </div>
  );
}

export default Chat;
