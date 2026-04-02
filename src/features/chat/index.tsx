import { useEffect, useRef } from 'react';
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
