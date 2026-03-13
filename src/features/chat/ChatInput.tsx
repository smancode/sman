/**
 * Chat Input Component
 * Textarea with send button for web (no Electron file picker).
 * Enter to send, Shift+Enter for new line.
 * In web version, file attachments are not supported via native picker.
 */
import { useState, useRef, useEffect, useCallback } from 'react';
import { SendHorizontal, Square } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Textarea } from '@/components/ui/textarea';
import { cn } from '@/lib/utils';
import { useGatewayStore } from '@/stores/gateway';

export interface FileAttachment {
  id: string;
  fileName: string;
  mimeType: string;
  fileSize: number;
  stagedPath: string;
  preview: string | null;
  status: 'staging' | 'ready' | 'error';
  error?: string;
}

interface ChatInputProps {
  onSend: (text: string, attachments?: FileAttachment[], targetAgentId?: string | null) => void;
  onStop?: () => void;
  disabled?: boolean;
  sending?: boolean;
  isEmpty?: boolean;
}

export function ChatInput({ onSend, onStop, disabled = false, sending = false, isEmpty = false }: ChatInputProps) {
  const [input, setInput] = useState('');
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const isComposingRef = useRef(false);
  const gatewayStatus = useGatewayStore((s) => s.status);

  // Auto-resize textarea
  useEffect(() => {
    if (textareaRef.current) {
      textareaRef.current.style.height = 'auto';
      textareaRef.current.style.height = `${Math.min(textareaRef.current.scrollHeight, 200)}px`;
    }
  }, [input]);

  // Focus textarea on mount
  useEffect(() => {
    if (!disabled && textareaRef.current) {
      textareaRef.current.focus();
    }
  }, [disabled]);

  const canSend = input.trim().length > 0 && !disabled && !sending;
  const canStop = sending && !disabled && !!onStop;

  const handleSend = useCallback(() => {
    if (!canSend) return;
    const textToSend = input.trim();
    setInput('');
    if (textareaRef.current) {
      textareaRef.current.style.height = 'auto';
    }
    onSend(textToSend, undefined, null);
  }, [input, canSend, onSend]);

  const handleStop = useCallback(() => {
    if (!canStop) return;
    onStop?.();
  }, [canStop, onStop]);

  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      if (e.key === 'Enter' && !e.shiftKey) {
        const nativeEvent = e.nativeEvent as KeyboardEvent;
        if (isComposingRef.current || nativeEvent.isComposing || nativeEvent.keyCode === 229) {
          return;
        }
        e.preventDefault();
        handleSend();
      }
    },
    [handleSend],
  );

  return (
    <div
      className={cn(
        "p-4 pb-6 w-full mx-auto transition-all duration-300",
        isEmpty ? "max-w-3xl" : "max-w-4xl"
      )}
    >
      <div className="w-full">
        {/* Input Row */}
        <div className={`relative bg-white dark:bg-card rounded-[28px] shadow-sm border p-1.5 border-black/10 dark:border-white/10`}>
          <div className="flex items-end gap-1.5">
            {/* Textarea */}
            <div className="flex-1 relative">
              <Textarea
                ref={textareaRef}
                value={input}
                onChange={(e) => setInput(e.target.value)}
                onKeyDown={handleKeyDown}
                onCompositionStart={() => {
                  isComposingRef.current = true;
                }}
                onCompositionEnd={() => {
                  isComposingRef.current = false;
                }}
                placeholder={disabled ? 'Gateway disconnected' : 'Type a message...'}
                disabled={disabled}
                className="min-h-[40px] max-h-[200px] resize-none border-0 focus-visible:ring-0 focus-visible:ring-offset-0 shadow-none bg-transparent py-2.5 px-2 text-[15px] placeholder:text-muted-foreground/60 leading-relaxed"
                rows={1}
              />
            </div>

            {/* Send Button */}
            <Button
              onClick={sending ? handleStop : handleSend}
              disabled={sending ? !canStop : !canSend}
              size="icon"
              className={`shrink-0 h-10 w-10 rounded-full transition-colors ${
                (sending || canSend)
                  ? 'bg-black/5 dark:bg-white/10 text-foreground hover:bg-black/10 dark:hover:bg-white/20'
                  : 'text-muted-foreground/50 hover:bg-transparent bg-transparent'
              }`}
              variant="ghost"
              title={sending ? 'Stop' : 'Send'}
            >
              {sending ? (
                <Square className="h-4 w-4" fill="currentColor" />
              ) : (
                <SendHorizontal className="h-[18px] w-[18px]" strokeWidth={2} />
              )}
            </Button>
          </div>
        </div>
        <div className="mt-2.5 flex items-center justify-between gap-2 text-[11px] text-muted-foreground/60 px-4">
          <div className="flex items-center gap-1.5">
            <div className={cn("w-1.5 h-1.5 rounded-full", gatewayStatus.state === 'connected' ? "bg-green-500/80" : "bg-red-500/80")} />
            <span>
              {gatewayStatus.state === 'connected'
                ? `Gateway connected`
                : gatewayStatus.state}
            </span>
          </div>
        </div>
      </div>
    </div>
  );
}
