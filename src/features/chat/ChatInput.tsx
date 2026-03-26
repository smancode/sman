/**
 * Chat Input Component
 * Textarea with send button for web (no Electron file picker).
 * Enter to send, Shift+Enter for new line.
 * In web version, file attachments are not supported via native picker.
 * Type "/" to open skill picker.
 */
import { useState, useRef, useEffect, useCallback } from 'react';
import { SendHorizontal, RefreshCw, Brain } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Textarea } from '@/components/ui/textarea';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import { cn } from '@/lib/utils';
import { useChatStore } from '@/stores/chat';
import { SkillPicker } from '@/components/SkillPicker';

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

interface Skill {
  id: string;
  name: string;
  description: string;
  content: string;
}

interface ChatInputProps {
  onSend: (text: string, attachments?: FileAttachment[], targetAgentId?: string | null) => void;
  disabled?: boolean;
  sending?: boolean;
  isEmpty?: boolean;
}

export function ChatInput({ onSend, disabled = false, isEmpty = false }: ChatInputProps) {
  const [input, setInput] = useState('');
  const [showSkillPicker, setShowSkillPicker] = useState(false);
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const isComposingRef = useRef(false);
  const slashTriggeredRef = useRef(false);

  // Chat toolbar states
  const refresh = useChatStore((s) => s.refresh);
  const loading = useChatStore((s) => s.loading);
  const showThinking = useChatStore((s) => s.showThinking);
  const toggleThinking = useChatStore((s) => s.toggleThinking);
  const currentSessionId = useChatStore((s) => s.currentSessionId);

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

  const canSend = input.trim().length > 0 && !disabled;

  const handleSend = useCallback(() => {
    if (!canSend) return;
    const textToSend = input.trim();
    setInput('');
    if (textareaRef.current) {
      textareaRef.current.style.height = 'auto';
    }
    onSend(textToSend, undefined, null);
  }, [input, canSend, onSend]);

  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      // Don't handle if skill picker is open
      if (showSkillPicker) return;

      if (e.key === 'Enter' && !e.shiftKey) {
        const nativeEvent = e.nativeEvent as KeyboardEvent;
        if (isComposingRef.current || nativeEvent.isComposing || nativeEvent.keyCode === 229) {
          return;
        }
        e.preventDefault();
        handleSend();
      }
    },
    [handleSend, showSkillPicker],
  );

  const handleInputChange = (e: React.ChangeEvent<HTMLTextAreaElement>) => {
    const value = e.target.value;
    setInput(value);

    // Check if user just typed "/" at the beginning or after a space
    const cursorPosition = e.target.selectionStart;
    const charBeforeCursor = value[cursorPosition - 1];
    const charBeforeSlash = value[cursorPosition - 2];

    if (
      charBeforeCursor === '/' &&
      (!charBeforeSlash || charBeforeSlash === ' ' || charBeforeSlash === '\n') &&
      !slashTriggeredRef.current
    ) {
      slashTriggeredRef.current = true;
      setShowSkillPicker(true);
    } else if (charBeforeCursor !== '/') {
      slashTriggeredRef.current = false;
    }
  };

  const handleSkillSelect = (skill: Skill) => {
    // Insert /skillId into input (not the full content)
    const cursorPosition = textareaRef.current?.selectionStart || input.length;
    const beforeSlash = input.slice(0, cursorPosition - 1); // Remove the "/"
    const afterCursor = input.slice(cursorPosition);

    // Add /skillId
    const newInput = beforeSlash + '/' + skill.id + afterCursor;

    setInput(newInput);
    setShowSkillPicker(false);
    slashTriggeredRef.current = false;

    // Focus back to textarea
    setTimeout(() => {
      if (textareaRef.current) {
        textareaRef.current.focus();
        const newCursorPos = beforeSlash.length + 1 + skill.id.length;
        textareaRef.current.setSelectionRange(newCursorPos, newCursorPos);
      }
    }, 0);
  };

  const handleSkillPickerClose = () => {
    setShowSkillPicker(false);
    slashTriggeredRef.current = false;
  };

  return (
    <div
      className={cn(
        "px-2 pt-2 pb-2 w-full mx-auto transition-all duration-300 relative",
        isEmpty ? "max-w-3xl" : "max-w-4xl"
      )}
    >
      {/* Skill Picker - positioned above the input */}
      {currentSessionId && (
        <div className="absolute bottom-full left-2 right-2 mb-2">
          <SkillPicker
            open={showSkillPicker}
            onClose={handleSkillPickerClose}
            onSelect={handleSkillSelect}
            sessionId={currentSessionId}
          />
        </div>
      )}

      {/* Input Row */}
      <div className="relative bg-card rounded-xl shadow-sm border p-1.5 border-border">
        <div className="flex items-end gap-1.5">
          {/* Textarea */}
          <div className="flex-1 relative">
            <Textarea
              ref={textareaRef}
              value={input}
              onChange={handleInputChange}
              onKeyDown={handleKeyDown}
              onCompositionStart={() => {
                isComposingRef.current = true;
              }}
              onCompositionEnd={() => {
                isComposingRef.current = false;
              }}
              placeholder={disabled ? '未连接' : '输入消息... (输入 / 选择 Skill)'}
              disabled={disabled}
              className="min-h-[40px] max-h-[200px] resize-none border-0 focus-visible:ring-0 focus-visible:ring-offset-0 shadow-none bg-transparent py-2.5 px-2 text-[15px] placeholder:text-muted-foreground/60 leading-relaxed"
              rows={1}
            />
          </div>

          {/* Refresh Button */}
          <Tooltip>
            <TooltipTrigger asChild>
              <Button
                variant="ghost"
                size="icon"
                className="h-9 w-9 rounded-lg shrink-0"
                onClick={() => refresh()}
                disabled={loading}
              >
                <RefreshCw className={cn('h-[18px] w-[18px]', loading && 'animate-spin')} />
              </Button>
            </TooltipTrigger>
            <TooltipContent>
              <p>刷新</p>
            </TooltipContent>
          </Tooltip>

          {/* Thinking Toggle Button */}
          <Tooltip>
            <TooltipTrigger asChild>
              <Button
                variant="ghost"
                size="icon"
                className={cn(
                  'h-9 w-9 rounded-lg shrink-0',
                  showThinking && 'bg-muted text-foreground',
                )}
                onClick={toggleThinking}
              >
                <Brain className="h-[18px] w-[18px]" />
              </Button>
            </TooltipTrigger>
            <TooltipContent>
              <p>{showThinking ? '隐藏思考与工具' : '显示思考与工具'}</p>
            </TooltipContent>
          </Tooltip>

          {/* Send Button */}
          <Button
            onClick={handleSend}
            disabled={!canSend}
            size="icon"
            className={cn(
              'shrink-0 h-9 w-9 rounded-lg transition-colors',
              canSend
                ? 'bg-primary text-primary-foreground hover:bg-primary/90'
                : 'text-muted-foreground/50 hover:bg-transparent bg-transparent'
            )}
            title="发送"
          >
            <SendHorizontal className="h-[18px] w-[18px]" strokeWidth={2} />
          </Button>
        </div>
      </div>
    </div>
  );
}
