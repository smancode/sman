import { useEffect, useRef, useCallback, useState } from 'react';
import { AlertCircle, Loader2, Wrench, CheckCircle2, ChevronDown, ChevronRight } from 'lucide-react';
import { Streamdown } from 'streamdown';
import 'streamdown/styles.css';
import { useChatStore, type StreamingBlock } from '@/stores/chat';
import { useWsConnection } from '@/stores/ws-connection';
import { ChatMessage } from './ChatMessage';
import { ChatInput, type StagedMedia } from './ChatInput';
import { useCodePlugin } from '@/lib/streamdown-plugins';
import { cn } from '@/lib/utils';
import { streamdownComponents } from './streamdown-components';
import type { RawMessage } from '@/types/chat';

function safeTimestamp(createdAt: string): number | undefined {
  if (!createdAt) return undefined;
  const d = new Date(createdAt.includes('T') ? createdAt : createdAt.replace(' ', 'T') + 'Z');
  const ts = d.getTime() / 1000;
  return Number.isFinite(ts) ? ts : undefined;
}

function buildContent(text: string, blocks?: unknown[]): unknown {
  if (!blocks || blocks.length === 0) return text;
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
  const streamingBlocks = useChatStore((s) => s.streamingBlocks);
  const showThinking = useChatStore((s) => s.showThinking);
  const error = useChatStore((s) => s.error);
  const sendMessage = useChatStore((s) => s.sendMessage);
  const clearError = useChatStore((s) => s.clearError);

  const scrollRef = useRef<HTMLDivElement>(null);
  const hasActiveSession = !!currentSessionId;

  const handleSend = useCallback((_text: string, _attachments?: unknown, _targetAgentId?: unknown, media?: StagedMedia[]) => {
    const mediaForWs = media?.map(m => ({ type: 'image' as const, mimeType: m.mimeType, base64Data: m.base64Data, fileName: m.fileName }));
    sendMessage(_text, mediaForWs);
  }, [sendMessage]);

  useEffect(() => {
    if (isConnected && currentSessionId) {
      useChatStore.getState().loadHistory();
    }
  }, [isConnected, currentSessionId]);

  // Track scroll position to prevent jump when streaming → static transition
  const wasSendingRef = useRef(false);
  const savedScrollTopRef = useRef(0);

  // Save scroll position while streaming
  useEffect(() => {
    if (sending) {
      wasSendingRef.current = true;
    }
  }, [sending]);

  // Auto-scroll + restore position after streaming ends
  useEffect(() => {
    const el = scrollRef.current;
    if (!el) return;

    if (sending || streamingBlocks.length > 0) {
      // During streaming: follow content
      el.scrollTop = el.scrollHeight;
    } else if (wasSendingRef.current) {
      // Streaming just ended: lock position to prevent jump-back
      wasSendingRef.current = false;
      // Use requestAnimationFrame to let React finish rendering the new DOM
      requestAnimationFrame(() => {
        if (el) el.scrollTop = el.scrollHeight;
      });
    } else if (messages.length > 0) {
      // Normal case (e.g. page load): scroll to bottom
      el.scrollTop = el.scrollHeight;
    }
  }, [messages.length, sending, streamingBlocks.length]);

  const hasStreamingContent = streamingBlocks.length > 0;
  const isEmpty = messages.length === 0 && !sending;

  return (
    <div className="relative flex flex-col h-full transition-colors duration-500 bg-transparent">
      {/* Messages */}
      <div ref={scrollRef} className={isEmpty ? 'flex-1' : 'flex-1 overflow-y-auto'}>
        <div className={isEmpty ? 'relative h-full' : 'max-w-4xl mx-auto space-y-4 px-4 pt-3 pb-4'}>
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

              {/* Streaming blocks rendered as sequential segments */}
              {sending && hasStreamingContent && (
                <StreamingBlocksRenderer blocks={streamingBlocks} showThinking={showThinking} />
              )}

              {/* Typing indicator */}
              {sending && !hasStreamingContent && <TypingIndicator />}
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
          <ChatInput onSend={handleSend} disabled={!isConnected} isEmpty={isEmpty} />
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

// ── Streaming blocks renderer: shows text/tool_use/thinking in chronological order ──

function StreamingBlocksRenderer({
  blocks,
  showThinking,
}: {
  blocks: StreamingBlock[];
  showThinking: boolean;
}) {
  return (
    <div className="flex gap-3 flex-row">
      <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg mt-1 ai-avatar text-foreground overflow-hidden">
        <img src="/favicon.svg" alt="AI" className="h-5 w-5 object-contain" />
      </div>

      <div className="flex flex-col w-full min-w-0 max-w-[80%] space-y-2 items-start">
        {blocks.map((block, i) => {
          switch (block.type) {
            case 'thinking':
              if (!showThinking) return null;
              return <ThinkingBlock key={`think-${i}`} content={block.content} />;
            case 'text':
              return <StreamingTextBubble key={`text-${i}`} text={block.content} isStreaming={false} />;
            case 'text_live':
              return <StreamingTextBubble key={`live-${i}`} text={block.content} isStreaming={true} />;
            case 'tool_use':
              return (
                <StreamingToolStatus
                  key={`tool-${block.id || i}`}
                  tool={{
                    id: block.id,
                    name: block.name,
                    status: block.status,
                    durationMs: block.elapsedSeconds != null ? block.elapsedSeconds * 1000 : undefined,
                  }}
                />
              );
            default:
              return null;
          }
        })}
      </div>
    </div>
  );
}

// ── Streaming text bubble (frozen or live) ──

function StreamingTextBubble({ text, isStreaming }: { text: string; isStreaming: boolean }) {
  const codePlugin = useCodePlugin();

  if (!text.trim()) return null;

  return (
    <div className="relative rounded-xl px-4 py-3 w-full bg-muted text-foreground">
      <div className="markdown-content overflow-x-auto prose prose-sm dark:prose-invert max-w-none break-words break-all">
        <Streamdown
          mode={isStreaming ? 'streaming' : 'static'}
          components={streamdownComponents}
          controls={{ code: true, table: true }}
          plugins={codePlugin ? { code: codePlugin } : undefined}
        >
          {text}
        </Streamdown>
      </div>
      {isStreaming && (
        <span className="inline-block w-2 h-4 bg-foreground/50 animate-pulse ml-0.5" />
      )}
    </div>
  );
}

// ── Streaming thinking block ──

function ThinkingBlock({ content }: { content: string }) {
  const [expanded, setExpanded] = useState(false);
  const codePlugin = useCodePlugin();

  if (!content.trim()) return null;

  return (
    <div className="w-full rounded-xl border border-border bg-muted/50 text-[14px]">
      <button
        className="flex items-center gap-2 w-full px-3 py-2 text-muted-foreground hover:text-foreground transition-colors"
        onClick={() => setExpanded(!expanded)}
      >
        {expanded ? <ChevronDown className="h-3.5 w-3.5" /> : <ChevronRight className="h-3.5 w-3.5" />}
        <span className="font-medium">思考</span>
      </button>
      {expanded && (
        <div className="px-3 pb-3 text-muted-foreground">
          <div className="markdown-content overflow-x-auto prose prose-sm dark:prose-invert max-w-none opacity-75">
            <Streamdown
              mode="static"
              controls={{ code: true, table: true }}
              plugins={codePlugin ? { code: codePlugin } : undefined}
            >
              {content}
            </Streamdown>
          </div>
        </div>
      )}
    </div>
  );
}

// ── Streaming tool status (single tool) ──

function StreamingToolStatus({ tool }: {
  tool: {
    id?: string;
    name: string;
    status: 'running' | 'completed' | 'error';
    durationMs?: number;
  };
}) {
  const duration = formatDuration(tool.durationMs);
  const isRunning = tool.status === 'running';
  const isError = tool.status === 'error';

  return (
    <div
      className={cn(
        'flex items-center gap-2 rounded-lg border px-3 py-2 text-xs transition-colors w-full',
        isRunning && 'border-primary/30 bg-primary/5 text-foreground',
        !isRunning && !isError && 'border-border/50 bg-muted/20 text-muted-foreground',
        isError && 'border-destructive/30 bg-destructive/5 text-destructive',
      )}
    >
      {isRunning && <Loader2 className="h-3.5 w-3.5 animate-spin text-primary shrink-0" />}
      {!isRunning && !isError && <CheckCircle2 className="h-3.5 w-3.5 text-green-500 shrink-0" />}
      {isError && <AlertCircle className="h-3.5 w-3.5 text-destructive shrink-0" />}
      <Wrench className="h-3 w-3 shrink-0 opacity-60" />
      <span className="font-mono text-[12px] font-medium">{tool.name}</span>
      {duration && <span className="text-[11px] opacity-60">{duration}</span>}
    </div>
  );
}

function formatDuration(durationMs?: number): string | null {
  if (!durationMs || !Number.isFinite(durationMs)) return null;
  if (durationMs < 1000) return `${Math.round(durationMs)}ms`;
  return `${(durationMs / 1000).toFixed(1)}s`;
}

// ── Welcome screen & typing indicator ──

function WelcomeScreen() {
  return (
    <div className="relative flex flex-col items-center justify-center text-center h-full overflow-hidden">
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
          background: hsl(var(--muted) / 0.8); border: 1px solid hsl(var(--border));
          color: hsl(var(--muted-foreground)); letter-spacing: 0.04em;
          cursor: pointer; transition: all 0.15s ease;
          backdrop-filter: blur(4px);
        }
        .hint-chip:hover { background: hsl(var(--accent)); color: hsl(var(--foreground)); }
      `}</style>
    </div>
  );
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
