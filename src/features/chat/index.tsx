import { useEffect, useLayoutEffect, useRef, useCallback, useState } from 'react';
import { AlertCircle, AlertTriangle, Key, WifiOff, Server, FileWarning, X, Loader2, Wrench, CheckCircle2, ChevronDown, ChevronRight } from 'lucide-react';
import { Streamdown } from 'streamdown';
import 'streamdown/styles.css';
import { useChatStore, type StreamingBlock, type ChatError, ERROR_SUGGESTIONS } from '@/stores/chat';
import { useWsConnection } from '@/stores/ws-connection';
import { ChatMessage } from './ChatMessage';
import { ChatInput, type StagedMedia } from './ChatInput';
import { InitBanner } from './InitBanner';
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

// ── Module-level scroll position cache ──
// Survives route changes (component unmount/remount) and session switches
const scrollPositions = new Map<string, number>();

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
  const waitingHint = useChatStore((s) => s.waitingHint);
  const sendMessage = useChatStore((s) => s.sendMessage);
  const clearError = useChatStore((s) => s.clearError);

  const scrollRef = useRef<HTMLDivElement>(null);
  const hasActiveSession = !!currentSessionId;

  // Track whether user is near bottom (within 150px) for smart auto-scroll
  const isNearBottomRef = useRef(true);
  const handleScroll = useCallback(() => {
    const el = scrollRef.current;
    if (!el) return;
    isNearBottomRef.current = el.scrollHeight - el.scrollTop - el.clientHeight < 150;
    // Continuously save scroll position on every scroll event
    if (currentSessionId) {
      scrollPositions.set(currentSessionId, el.scrollTop);
    }
  }, [currentSessionId]);

  // Flag: when session changes or component mounts, restore scroll after React renders
  const pendingRestoreRef = useRef<string | null>(currentSessionId);
  const prevSessionIdRef = useRef(currentSessionId);

  // Detect session change during render
  if (currentSessionId !== prevSessionIdRef.current) {
    // Don't save here — handleScroll already saves on every scroll event
    pendingRestoreRef.current = currentSessionId;
    prevSessionIdRef.current = currentSessionId;
    isNearBottomRef.current = true;
  }

  // After React commits to DOM, restore scroll position
  useLayoutEffect(() => {
    const sid = pendingRestoreRef.current;
    if (!sid) return;
    pendingRestoreRef.current = null;

    const el = scrollRef.current;
    if (!el) return;

    const saved = scrollPositions.get(sid);
    if (saved !== undefined) {
      el.scrollTop = saved;
      isNearBottomRef.current = el.scrollHeight - el.scrollTop - el.clientHeight < 150;
    } else {
      el.scrollTop = el.scrollHeight;
      isNearBottomRef.current = true;
    }
  }); // Run after every commit when there's a pending restore

  // Smart auto-scroll: only follow when actively streaming AND user is near bottom
  useEffect(() => {
    const el = scrollRef.current;
    if (!el) return;
    if (!isNearBottomRef.current) return;
    if (!sending) return;
    if (pendingRestoreRef.current) return;

    requestAnimationFrame(() => {
      el.scrollTop = el.scrollHeight;
    });
  }, [messages.length, sending, streamingBlocks.length]);

  const handleSend = useCallback((_text: string, _attachments?: unknown, _targetAgentId?: unknown, media?: StagedMedia[]) => {
    const mediaForWs = media?.map(m => ({ type: 'image' as const, mimeType: m.mimeType, base64Data: m.base64Data, fileName: m.fileName }));
    // When user sends a message, they want to see the response — mark as near bottom
    isNearBottomRef.current = true;
    sendMessage(_text, mediaForWs);
  }, [sendMessage]);

  // Load history on initial connect or reconnect.
  // Session switching is handled by switchSession() internally.
  const prevConnectedRef = useRef(false);
  useEffect(() => {
    if (isConnected && !prevConnectedRef.current && currentSessionId) {
      useChatStore.getState().loadHistory();
    }
    prevConnectedRef.current = isConnected;
  }, [isConnected]);

  const hasStreamingContent = streamingBlocks.length > 0;
  const isEmpty = messages.length === 0 && !sending;

  return (
    <div className="relative flex flex-col h-full transition-colors duration-500 bg-transparent">
      <InitBanner />
      {/* Messages */}
      <div ref={scrollRef} className={isEmpty ? 'flex-1' : 'flex-1 overflow-y-auto'} onScroll={handleScroll}>
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
                    content: msg.resolvedContent ?? buildContent(msg.content, msg.contentBlocks),
                    timestamp: msg.timestamp ?? safeTimestamp(msg.createdAt),
                  } as RawMessage}
                  showThinking={showThinking}
                />
              ))}

              {/* Streaming blocks rendered as sequential segments */}
              {sending && hasStreamingContent && (
                <StreamingBlocksRenderer blocks={streamingBlocks} showThinking={showThinking} />
              )}

              {/* Typing indicator with optional waiting hint */}
              {sending && !hasStreamingContent && (
                <div>
                  <TypingIndicator />
                  {waitingHint && (
                    <p className="text-xs text-muted-foreground/70 mt-2 ml-11 animate-pulse">
                      {waitingHint}
                    </p>
                  )}
                </div>
              )}
            </>
          )}
        </div>
      </div>

      {/* Error card */}
      {error && <ErrorCard error={error} onDismiss={clearError} />}

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
                    input: block.input,
                    status: block.status,
                    durationMs: block.elapsedSeconds != null ? block.elapsedSeconds * 1000 : undefined,
                    result: block.result,
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
    <div className="w-full text-foreground">
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
    <div className="w-full text-[14px]">
      <button
        className="flex items-center gap-2 px-1 py-0.5 text-muted-foreground hover:text-foreground transition-colors"
        onClick={() => setExpanded(!expanded)}
      >
        {expanded ? <ChevronDown className="h-3.5 w-3.5" /> : <ChevronRight className="h-3.5 w-3.5" />}
        <span className="font-medium">思考</span>
      </button>
      {expanded && (
        <div className="text-muted-foreground">
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
    input?: string;
    status: 'running' | 'completed' | 'error';
    durationMs?: number;
    result?: string;
  };
}) {
  const duration = formatDuration(tool.durationMs);
  const isRunning = tool.status === 'running';
  const isError = tool.status === 'error';
  const [expanded, setExpanded] = useState(false);

  // Format input for display
  const displayInput = formatToolInput(tool.name, tool.input);
  const displayResult = tool.result?.trim();

  return (
    <div
      className={cn(
        'text-xs transition-colors w-full',
        isRunning && 'text-foreground',
        !isRunning && !isError && 'text-muted-foreground',
        isError && 'text-destructive',
      )}
    >
      {/* Header row: icon + name + duration + expand toggle */}
      <button
        className="flex items-center gap-2 w-full px-1 py-0.5 text-left"
        onClick={() => setExpanded(!expanded)}
      >
        {isRunning && <Loader2 className="h-3.5 w-3.5 animate-spin text-primary shrink-0" />}
        {!isRunning && !isError && <CheckCircle2 className="h-3.5 w-3.5 text-green-500 shrink-0" />}
        {isError && <AlertCircle className="h-3.5 w-3.5 text-destructive shrink-0" />}
        <Wrench className="h-3 w-3 shrink-0 opacity-60" />
        <span className="font-mono text-[12px] font-medium">{tool.name}</span>
        {duration && <span className="text-[11px] opacity-60">{duration}</span>}
        {(displayInput || displayResult) && (
          expanded
            ? <ChevronDown className="h-3 w-3 ml-auto shrink-0 opacity-50" />
            : <ChevronRight className="h-3 w-3 ml-auto shrink-0 opacity-50" />
        )}
      </button>

      {/* Expanded content: input + result */}
      {expanded && (displayInput || displayResult) && (
        <div className="px-3 py-2 space-y-1.5">
          {displayInput && (
            <div>
              <span className="text-[10px] uppercase tracking-wider opacity-50 font-semibold">Input</span>
              <pre className="text-[11px] text-foreground/80 whitespace-pre-wrap break-all mt-0.5 font-mono leading-relaxed max-h-48 overflow-y-auto">
                {displayInput}
              </pre>
            </div>
          )}
          {displayResult && (
            <div>
              <span className="text-[10px] uppercase tracking-wider opacity-50 font-semibold">Output</span>
              <pre className="text-[11px] text-foreground/80 whitespace-pre-wrap break-all mt-0.5 font-mono leading-relaxed max-h-48 overflow-y-auto">
                {displayResult.length > 2000 ? displayResult.slice(0, 2000) + '...' : displayResult}
              </pre>
            </div>
          )}
        </div>
      )}
    </div>
  );
}

/** Format tool input JSON into a readable summary */
function formatToolInput(name: string, input?: string): string | null {
  if (!input?.trim()) return null;

  // Try to parse as JSON and format key fields
  try {
    const obj = JSON.parse(input);

    // Common tool-specific formatting
    if (name === 'Bash' && obj.command) return `$ ${obj.command}`;
    if (name === 'Read' && obj.file_path) return obj.file_path;
    if (name === 'Write' && obj.file_path) return `${obj.file_path}`;
    if (name === 'Edit' && obj.file_path) return `${obj.file_path}`;
    if (name === 'Grep' && obj.pattern) return `pattern: ${obj.pattern}${obj.path ? ` in ${obj.path}` : ''}`;
    if (name === 'Glob' && obj.pattern) return obj.pattern;
    if (name === 'WebSearch' && obj.query) return obj.query;
    if (name === 'WebFetch' && obj.url) return obj.url;

    // Generic: pretty print compact JSON
    const compact = Object.entries(obj)
      .map(([k, v]) => `${k}: ${typeof v === 'string' ? v : JSON.stringify(v)}`)
      .join('\n');
    return compact;
  } catch {
    return input.trim();
  }
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
      <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg mt-1 ai-avatar text-foreground overflow-hidden">
        <img src="/favicon.svg" alt="AI" className="h-5 w-5" />
      </div>
      <div className="text-foreground flex items-center">
        <div className="flex gap-1">
          <span className="w-2 h-2 bg-muted-foreground/50 rounded-full animate-bounce" style={{ animationDelay: '0ms' }} />
          <span className="w-2 h-2 bg-muted-foreground/50 rounded-full animate-bounce" style={{ animationDelay: '150ms' }} />
          <span className="w-2 h-2 bg-muted-foreground/50 rounded-full animate-bounce" style={{ animationDelay: '300ms' }} />
        </div>
      </div>
    </div>
  );
}

// ── Error card with categorized display ──

type ErrorSeverity = 'warning' | 'error';

const ERROR_STYLES: Record<string, { icon: typeof AlertCircle; severity: ErrorSeverity }> = {
  rate_limit:      { icon: AlertCircle,    severity: 'warning' },
  overloaded:      { icon: Server,         severity: 'warning' },
  server_error:    { icon: Server,         severity: 'error' },
  bad_request:     { icon: AlertTriangle,  severity: 'warning' },
  auth_error:      { icon: Key,            severity: 'error' },
  forbidden:       { icon: Key,            severity: 'error' },
  not_found:       { icon: AlertTriangle,  severity: 'warning' },
  context_too_long:{ icon: FileWarning,    severity: 'warning' },
  network_error:   { icon: WifiOff,        severity: 'error' },
  unknown:         { icon: AlertCircle,    severity: 'error' },
};

function ErrorCard({ error, onDismiss }: { error: ChatError; onDismiss: () => void }) {
  const style = ERROR_STYLES[error.errorCode] ?? ERROR_STYLES.unknown;
  const Icon = style.icon;
  const suggestion = ERROR_SUGGESTIONS[error.errorCode];
  const isWarning = style.severity === 'warning';

  return (
    <div className={cn(
      'px-4 py-3 border-t',
      isWarning
        ? 'bg-warning/10 border-warning/30'
        : 'bg-destructive/10 border-destructive/30',
    )}>
      <div className="max-w-4xl mx-auto">
        <div className="flex items-start justify-between gap-3">
          <div className="flex items-start gap-2.5 min-w-0">
            <Icon className={cn(
              'h-4 w-4 mt-0.5 shrink-0',
              isWarning ? 'text-warning' : 'text-destructive',
            )} />
            <div className="min-w-0">
              <p className="text-sm font-medium text-foreground">{error.message}</p>
              {suggestion && (
                <p className="text-xs text-muted-foreground mt-1">{suggestion}</p>
              )}
              {error.errorCode === 'unknown' && error.rawError && (
                <details className="mt-1.5">
                  <summary className="text-xs text-muted-foreground cursor-pointer hover:text-foreground transition-colors">
                    查看详情
                  </summary>
                  <pre className="text-[11px] text-foreground/60 whitespace-pre-wrap break-all mt-1 font-mono leading-relaxed max-h-32 overflow-y-auto">
                    {error.rawError.slice(0, 300)}{error.rawError.length > 300 ? '...' : ''}
                  </pre>
                </details>
              )}
            </div>
          </div>
          <button
            onClick={onDismiss}
            className="shrink-0 text-muted-foreground hover:text-foreground transition-colors p-0.5 rounded hover:bg-foreground/5"
          >
            <X className="h-3.5 w-3.5" />
          </button>
        </div>
      </div>
    </div>
  );
}

export default Chat;
