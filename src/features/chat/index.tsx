import { useEffect, useLayoutEffect, useRef, useCallback, useState, useMemo, memo } from 'react';
import { AlertCircle, AlertTriangle, Key, WifiOff, Server, FileWarning, X, Loader2, Wrench, CheckCircle2, ChevronDown, ChevronRight, Info } from 'lucide-react';
import { Streamdown } from 'streamdown';
import 'streamdown/styles.css';
import { useChatStore, type StreamingBlock, type ChatError, ERROR_SUGGESTIONS, freezeLiveText, getStreamingBlocks, clearStreamingBlocks, sendingSessions } from '@/stores/chat';
import type { Message } from '@/stores/chat';
import { AskUserCard } from './AskUserCard';
import { useWsConnection } from '@/stores/ws-connection';
import { ChatMessage } from './ChatMessage';
import { ChatInput, type StagedMedia } from './ChatInput';
import { InitBanner } from './InitBanner';
import { useCodePlugin } from '@/lib/streamdown-plugins';
import { cn } from '@/lib/utils';
import { streamdownComponents, useCodeBlockCollapse } from './streamdown-components';
import { getToolDisplayName, formatToolSummary } from './message-utils';
import { CodeViewerProvider } from '@/features/code-viewer';
import { t, useLocale } from '@/locales';


// ── Module-level: 记住每个会话的滚动比例 ──
// key: sessionId, value: { ratio: scrollTop/scrollHeight (0~1), atBottom: boolean }
const scrollMemory = new Map<string, { ratio: number; atBottom: boolean }>();

// ── LazyMessage: IntersectionObserver-based lazy rendering ──
// Renders a cheap placeholder until the message scrolls near the viewport.
// Always renders the last N messages (user likely looking at recent content).
const LAZY_ALWAYS_RENDER_COUNT = 6;
const LAZY_ROOT_MARGIN = '800px 0px';

const LazyMessage = memo(function LazyMessage({
  msg,
  index,
  total,
  showThinking,
}: {
  msg: Message;
  index: number;
  total: number;
  showThinking: boolean;
}) {
  const [visible, setVisible] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  // Always render last N messages
  const nearEnd = index >= total - LAZY_ALWAYS_RENDER_COUNT;

  useEffect(() => {
    if (nearEnd || visible) return;
    const el = ref.current;
    if (!el) return;
    const obs = new IntersectionObserver(
      ([entry]) => { if (entry.isIntersecting) { setVisible(true); obs.disconnect(); } },
      { rootMargin: LAZY_ROOT_MARGIN },
    );
    obs.observe(el);
    return () => obs.disconnect();
  }, [nearEnd, visible]);

  if (nearEnd || visible) {
    return (
      <ChatMessage
        key={msg.id}
        id={`msg-${msg.id}`}
        message={msg}
        showThinking={showThinking}
      />
    );
  }

  return <div ref={ref} className="min-h-[80px]" />;
});

export function Chat() {
  useLocale();
  const connectionStatus = useWsConnection((s) => s.status);
  const isConnected = connectionStatus === 'connected';

  const messages = useChatStore((s) => s.messages);
  const currentSessionId = useChatStore((s) => s.currentSessionId);
  const loading = useChatStore((s) => s.loading);
  const sending = useChatStore((s) => s.sending);
  const streamingBlocks = useChatStore((s) => s.streamingBlocks);
  const showThinking = useChatStore((s) => s.showThinking);
  const error = useChatStore((s) => s.error);
  const contextWarning = useChatStore((s) => s.contextWarning);
  const waitingHint = useChatStore((s) => s.waitingHint);
  const sendMessage = useChatStore((s) => s.sendMessage);
  const clearError = useChatStore((s) => s.clearError);
  const clearContextWarning = useChatStore((s) => s.clearContextWarning);

  const scrollRef = useRef<HTMLDivElement>(null);
  const hasActiveSession = !!currentSessionId;

  const prevSessionIdRef = useRef(currentSessionId);
  const restoredRef = useRef(false);

  // 离开会话时保存滚动位置，切回来时恢复
  if (currentSessionId !== prevSessionIdRef.current) {
    const container = scrollRef.current;
    // 存旧会话的滚动位置
    if (prevSessionIdRef.current && container) {
      const atBottom = container.scrollHeight - container.scrollTop - container.clientHeight < 150;
      scrollMemory.set(prevSessionIdRef.current, {
        ratio: container.scrollHeight > 0 ? container.scrollTop / container.scrollHeight : 0,
        atBottom,
      });
    }
    restoredRef.current = false;
    prevSessionIdRef.current = currentSessionId;
  }

  // 恢复滚动位置
  useLayoutEffect(() => {
    if (restoredRef.current) return;
    const container = scrollRef.current;
    if (!container || !currentSessionId) return;
    if (messages.length === 0) return;

    const saved = scrollMemory.get(currentSessionId);
    if (!saved) {
      // 没有保存的位置，首次进入滚到底
      container.scrollTop = container.scrollHeight;
      restoredRef.current = true;
      return;
    }

    if (saved.atBottom) {
      container.scrollTop = container.scrollHeight;
    } else {
      container.scrollTop = Math.round(saved.ratio * container.scrollHeight);
    }
    restoredRef.current = true;
  }, [messages, currentSessionId]);

  // 流式输出时自动滚到底部
  useEffect(() => {
    const container = scrollRef.current;
    if (!container || !sending) return;
    requestAnimationFrame(() => {
      container.scrollTop = container.scrollHeight;
    });
  }, [messages.length, streamingBlocks.length, sending]);

  const handleSend = useCallback((_text: string, _attachments?: unknown, _targetAgentId?: unknown, media?: StagedMedia[]) => {
    const mediaForWs = media?.map(m => ({
      type: 'document' as const,
      mimeType: m.mimeType,
      base64Data: m.base64Data,
      fileName: m.fileName,
    }));
    sendMessage(_text, mediaForWs);
  }, [sendMessage]);

  // Load history on initial connect or reconnect.
  // Reset sending state on disconnect to prevent stuck streaming UI.
  const prevConnectedRef = useRef(false);
  useEffect(() => {
    if (isConnected && !prevConnectedRef.current && currentSessionId) {
      useChatStore.getState().loadHistory();
    }
    if (!isConnected && prevConnectedRef.current) {
      // WebSocket disconnected — reset sending state to prevent stuck UI
      const state = useChatStore.getState();
      if (state.sending) {
        // Freeze whatever we have so far into a message
        const blocks = getStreamingBlocks(state.currentSessionId);
        const frozen = freezeLiveText(blocks);
        const textContent = frozen
          .filter(b => b.type === 'text')
          .map(b => (b as { content: string }).content)
          .join('');
        if (textContent.trim()) {
          const assistantMsg: Message = {
            id: crypto.randomUUID(),
            sessionId: state.currentSessionId,
            role: 'assistant',
            content: textContent.trim(),
            createdAt: new Date().toISOString(),
          };
          useChatStore.setState({ messages: [...state.messages, assistantMsg], sending: false, streamingBlocks: [], waitingHint: null });
        } else {
          useChatStore.setState({ sending: false, streamingBlocks: [], waitingHint: null });
        }
        clearStreamingBlocks(state.currentSessionId);
        sendingSessions.delete(state.currentSessionId);
      }
    }
    prevConnectedRef.current = isConnected;
  }, [isConnected]);

  const hasStreamingContent = streamingBlocks.length > 0;
  const isEmpty = messages.length === 0 && !sending;

  return (
    <div className="relative flex flex-col h-full transition-colors duration-500 bg-transparent">
      <InitBanner />
      {/* Messages */}
      <div ref={scrollRef} className={isEmpty ? 'flex-1' : 'flex-1 overflow-y-auto'}>
        <div className={isEmpty ? 'relative h-full' : 'max-w-4xl mx-auto space-y-4 px-4 pt-3 pb-4'}>
          {isEmpty ? (
            <WelcomeScreen />
          ) : (
            <>
              {/* Render messages — lazy load off-screen */}
              {messages.map((msg, i) => (
                <LazyMessage
                  key={msg.id}
                  msg={msg}
                  index={i}
                  total={messages.length}
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

      {/* Context length warning */}
      {contextWarning && !error && (
        <ContextWarningCard
          level={contextWarning.level}
          inputTokens={contextWarning.inputTokens}
          message={contextWarning.message}
          onDismiss={clearContextWarning}
        />
      )}

      {/* Input */}
      {hasActiveSession && (
        <div className="mt-auto w-full mx-auto max-w-4xl px-2">
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

      <CodeViewerProvider />
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
                    summary: block.summary,
                    taskDescription: block.taskDescription,
                  }}
                />
              );
            case 'ask_user':
              return (
                <AskUserCard
                  key={`ask-${block.askId || i}`}
                  askId={block.askId}
                  questions={block.questions}
                  answered={block.answered}
                  answers={block.answers}
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
  const collapseRef = useCodeBlockCollapse<HTMLDivElement>();

  if (!text.trim()) return null;

  return (
    <div className="w-full text-foreground" ref={collapseRef}>
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

// ── Global streaming ThinkingBlock update scheduler ──
const streamThinkingSubscribers = new Set<() => void>();
let streamThinkingTimer: ReturnType<typeof setInterval> | null = null;

function ensureStreamThinkingTimer(): void {
  if (streamThinkingTimer) return;
  streamThinkingTimer = setInterval(() => {
    for (const cb of streamThinkingSubscribers) {
      try { cb(); } catch { /* ignore */ }
    }
  }, 200);
}

function removeStreamThinkingTimer(): void {
  if (streamThinkingSubscribers.size === 0 && streamThinkingTimer) {
    clearInterval(streamThinkingTimer);
    streamThinkingTimer = null;
  }
}

// ── Streaming thinking block ──

function ThinkingBlock({ content }: { content: string }) {
  const [expanded, setExpanded] = useState(false);
  const codePlugin = useCodePlugin();
  const [summary, setSummary] = useState('');
  const prevLenRef = useRef(0);
  const contentRef = useRef(content);
  contentRef.current = content;

  useEffect(() => {
    const update = () => {
      const c = contentRef.current;
      const lines = c.split('\n').map(l => l.trim()).filter(Boolean);
      if (lines.length === 0) { setSummary(''); prevLenRef.current = c.length; return; }
      const lastLine = lines[lines.length - 1];
      setSummary(lastLine.length > 120 ? '...' + lastLine.slice(-117) : lastLine);
      prevLenRef.current = c.length;
    };
    update();
    streamThinkingSubscribers.add(update);
    ensureStreamThinkingTimer();
    return () => {
      streamThinkingSubscribers.delete(update);
      removeStreamThinkingTimer();
    };
  }, []);

  if (!content.trim()) return null;

  return (
    <div className="w-full text-[12px] text-muted-foreground">
      <button
        className="flex items-center gap-2 w-full px-1 py-0.5 text-left hover:text-foreground transition-colors"
        onClick={() => setExpanded(!expanded)}
      >
        {expanded ? <ChevronDown className="h-3.5 w-3.5 shrink-0" /> : <ChevronRight className="h-3.5 w-3.5 shrink-0" />}
        <span className="font-medium shrink-0">{t('chat.thinking2')}</span>
        {!expanded && summary && (
          <span className="truncate min-w-0 flex-1 opacity-60">{summary}</span>
        )}
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
    summary?: string;
    taskDescription?: string;
  };
}) {
  const duration = formatDuration(tool.durationMs);
  const isRunning = tool.status === 'running';
  const isError = tool.status === 'error';
  const [expanded, setExpanded] = useState(false);

  // Human-readable display
  const displayName = getToolDisplayName(tool.name);
  const displayInput = formatToolInput(tool.name, tool.input);
  const displayResult = tool.result?.trim();

  // Build one-line status label:
  // Priority: SDK summary > task description > parsed input > just name
  const detail = tool.summary || tool.taskDescription || displayInput?.split('\n')[0]?.slice(0, 80) || '';
  const statusLabel = detail ? `${displayName}: ${detail}` : displayName;

  return (
    <div
      className={cn(
        'text-xs transition-colors w-full',
        isRunning && 'text-amber-600 dark:text-amber-400',
        !isRunning && !isError && 'text-muted-foreground',
        isError && 'text-destructive',
      )}
    >
      {/* Header row: icon + status label + duration */}
      <button
        className="flex items-center gap-2 w-full px-1 py-0.5 text-left"
        onClick={() => setExpanded(!expanded)}
      >
        {isRunning && <Loader2 className="h-3.5 w-3.5 animate-spin text-amber-500 shrink-0" />}
        {!isRunning && !isError && <CheckCircle2 className="h-3.5 w-3.5 text-green-500 shrink-0" />}
        {isError && <AlertCircle className="h-3.5 w-3.5 text-destructive shrink-0" />}
        <span className={cn(
          'text-[12px] truncate min-w-0',
          isRunning && 'font-medium',
        )}>
          {statusLabel}
        </span>
        {duration && <span className="text-[11px] opacity-60 shrink-0">{duration}</span>}
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

/** Format tool input JSON into a readable summary for streaming (string input version) */
function formatToolInput(name: string, input?: string): string | null {
  if (!input?.trim()) return null;
  return formatToolSummary(name, input) || null;
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
          <span className="font-light italic text-foreground/80">{t('chat.welcome2')}</span>{' '}
          <span className="font-black not-italic text-foreground">Sman</span>
        </h1>
        <div className="flex items-center justify-center gap-2 mt-6">
          <span className="hint-chip">{t('chat.selectSystem2')}</span>
          <span className="hint-chip">{t('chat.startChat2')}</span>
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

function ContextWarningCard({ level, inputTokens, message, onDismiss }: {
  level: 'warning' | 'critical';
  inputTokens: number;
  message: string;
  onDismiss: () => void;
}) {
  const isCritical = level === 'critical';
  return (
    <div className={cn(
      'px-4 py-2.5 border-t',
      isCritical ? 'bg-warning/10 border-warning/30' : 'bg-muted/50 border-border',
    )}>
      <div className="max-w-4xl mx-auto flex items-center justify-between gap-3">
        <div className="flex items-center gap-2 min-w-0">
          {isCritical ? (
            <FileWarning className="h-4 w-4 text-warning shrink-0" />
          ) : (
            <Info className="h-4 w-4 text-muted-foreground shrink-0" />
          )}
          <span className="text-xs text-muted-foreground">
            {(inputTokens / 1000).toFixed(0)}k tokens
          </span>
          <span className={cn('text-sm', isCritical ? 'text-foreground' : 'text-muted-foreground')}>
            {message}
          </span>
        </div>
        <button
          onClick={onDismiss}
          className="shrink-0 text-muted-foreground hover:text-foreground transition-colors p-0.5 rounded hover:bg-foreground/5"
        >
          <X className="h-3.5 w-3.5" />
        </button>
      </div>
    </div>
  );
}

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
                    {t('chat.viewDetail2')}
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
