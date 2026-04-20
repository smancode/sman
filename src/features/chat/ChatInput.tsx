/**
 * Chat Input Component
 * Textarea with send button for web (no Electron file picker).
 * Enter to send, Shift+Enter for new line.
 * Supports image/PDF upload via file picker.
 * Type "/" to open skill picker.
 */
import { useState, useRef, useEffect, useCallback } from 'react';
import { SendHorizontal, RefreshCw, Brain, ImagePlus, X, Square } from 'lucide-react';
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

export interface StagedMedia {
  fileName: string;
  mimeType: string;
  base64Data: string;
  preview?: string;
}

interface Skill {
  id: string;
  name: string;
  description: string;
  content: string;
}

interface ChatInputProps {
  onSend: (text: string, attachments?: FileAttachment[], targetAgentId?: string | null, media?: StagedMedia[]) => void;
  disabled?: boolean;
  sending?: boolean;
  isEmpty?: boolean;
}

const MAX_FILE_SIZE = 10 * 1024 * 1024; // 10MB
const MAX_TOTAL_SIZE = 20 * 1024 * 1024; // 20MB
const ACCEPTED_TYPES = 'image/*,.pdf';

// Per-session input cache — survives session switches
const inputCache = new Map<string, { input: string; stagedMedia: StagedMedia[] }>();

function readFileAsBase64(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => {
      const result = reader.result as string;
      // Remove data URL prefix (e.g., "data:image/png;base64,")
      const base64 = result.split(',')[1];
      if (base64) {
        resolve(base64);
      } else {
        reject(new Error('Failed to read file as base64'));
      }
    };
    reader.onerror = () => reject(reader.error);
    reader.readAsDataURL(file);
  });
}

export function ChatInput({ onSend, disabled = false, isEmpty = false }: ChatInputProps) {
  const [input, setInput] = useState('');
  const [showSkillPicker, setShowSkillPicker] = useState(false);
  const [stagedMedia, setStagedMedia] = useState<StagedMedia[]>([]);
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const isComposingRef = useRef(false);
  const slashTriggeredRef = useRef(false);
  const abortingRef = useRef(false);

  // Chat toolbar states
  const refresh = useChatStore((s) => s.refresh);
  const loading = useChatStore((s) => s.loading);
  const sending = useChatStore((s) => s.sending);
  const abortRun = useChatStore((s) => s.abortRun);
  const currentSessionId = useChatStore((s) => s.currentSessionId);
  const showThinking = useChatStore((s) => s.showThinking);
  const toggleThinking = useChatStore((s) => s.toggleThinking);

  // Reset aborting flag when sending finishes
  useEffect(() => {
    if (!sending) abortingRef.current = false;
  }, [sending]);

  // Save/restore input on session switch
  const prevSessionIdRef = useRef(currentSessionId);
  useEffect(() => {
    const prevId = prevSessionIdRef.current;
    if (prevId === currentSessionId) return;

    // Save current input to previous session
    if (prevId) {
      inputCache.set(prevId, { input, stagedMedia });
    }

    // Restore input for new session (or reset if none cached)
    const cached = currentSessionId ? inputCache.get(currentSessionId) : undefined;
    setInput(cached?.input ?? '');
    setStagedMedia(cached?.stagedMedia ?? []);

    prevSessionIdRef.current = currentSessionId;
  }); // run every render to detect session changes

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

  const canSend = (input.trim().length > 0 || stagedMedia.length > 0) && !disabled;

  const handleFileSelect = useCallback(async (e: React.ChangeEvent<HTMLInputElement>) => {
    const files = e.target.files;
    if (!files) return;

    const newMedia: StagedMedia[] = [];
    let totalSize = stagedMedia.reduce((sum, m) => sum + m.base64Data.length, 0);

    for (const file of Array.from(files)) {
      if (file.size > MAX_FILE_SIZE) {
        console.warn(`File ${file.name} exceeds 10MB limit, skipping`);
        continue;
      }
      totalSize += file.size;
      if (totalSize > MAX_TOTAL_SIZE) {
        console.warn('Total upload size exceeds 20MB limit');
        break;
      }

      const base64Data = await readFileAsBase64(file);
      const media: StagedMedia = {
        fileName: file.name,
        mimeType: file.type || 'application/octet-stream',
        base64Data,
      };

      // Generate preview for images
      if (file.type.startsWith('image/')) {
        media.preview = `data:${file.type};base64,${base64Data}`;
      }

      newMedia.push(media);
    }

    setStagedMedia((prev) => [...prev, ...newMedia]);

    // Reset file input so the same file can be re-selected
    if (fileInputRef.current) {
      fileInputRef.current.value = '';
    }
  }, [stagedMedia]);

  const removeStagedMedia = useCallback((index: number) => {
    setStagedMedia((prev) => prev.filter((_, i) => i !== index));
  }, []);

  const handleSend = useCallback(() => {
    if (!canSend || sending) return;
    const textToSend = input.trim();
    setInput('');
    setStagedMedia([]);
    if (currentSessionId) inputCache.delete(currentSessionId);
    if (textareaRef.current) {
      textareaRef.current.style.height = 'auto';
    }
    onSend(textToSend || ' ', undefined, null, stagedMedia.length > 0 ? stagedMedia : undefined);
  }, [input, canSend, sending, onSend, stagedMedia, currentSessionId]);

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
    const wasEmpty = input.length === 0;
    setInput(value);

    // Preheat V2 session when input goes from empty to non-empty.
    // Covers all cases: new session, switch session, send then continue, next day return.
    // Backend deduplicates: if V2 process already exists, it's a no-op.
    if (wasEmpty && value.length > 0 && currentSessionId) {
      useChatStore.getState().preheatSession();
      // Refresh git branch in titlebar (user may have switched branches in terminal)
      (window as any).__sman_gitBranchRefresh?.();
    }

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
    // Insert /skillId into input (not the full content), add trailing space
    const cursorPosition = textareaRef.current?.selectionStart || input.length;
    const beforeSlash = input.slice(0, cursorPosition - 1); // Remove the "/"
    const afterCursor = input.slice(cursorPosition);

    // Add /skillId with trailing space
    const newInput = beforeSlash + '/' + skill.id + ' ' + afterCursor;

    setInput(newInput);
    setShowSkillPicker(false);
    slashTriggeredRef.current = false;

    // Focus back to textarea
    setTimeout(() => {
      if (textareaRef.current) {
        textareaRef.current.focus();
        const newCursorPos = beforeSlash.length + 1 + skill.id.length + 1; // +1 for the space
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
        "px-2 pt-2 pb-2 w-full mx-auto relative",
      )}
    >
      {/* Skill Picker - positioned above the input */}
      {currentSessionId && (
        <div className="absolute bottom-full left-2 right-2 mb-2 w-full overflow-hidden">
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
        {/* Media preview row */}
        {stagedMedia.length > 0 && (
          <div className="flex gap-2 p-2 pb-0 flex-wrap">
            {stagedMedia.map((media, idx) => (
              <div key={idx} className="relative group">
                {media.preview ? (
                  <img
                    src={media.preview}
                    alt={media.fileName}
                    className="h-16 w-16 object-cover rounded-lg border"
                  />
                ) : (
                  <div className="h-16 w-16 rounded-lg border bg-muted flex items-center justify-center text-xs text-muted-foreground p-1 text-center truncate">
                    {media.fileName}
                  </div>
                )}
                <button
                  type="button"
                  onClick={() => removeStagedMedia(idx)}
                  className="absolute -top-1.5 -right-1.5 h-5 w-5 rounded-full bg-destructive text-destructive-foreground opacity-0 group-hover:opacity-100 transition-opacity flex items-center justify-center"
                >
                  <X className="h-3 w-3" />
                </button>
              </div>
            ))}
          </div>
        )}

        <div className="flex items-end gap-1.5">
          {/* Hidden file input */}
          <input
            ref={fileInputRef}
            type="file"
            accept={ACCEPTED_TYPES}
            multiple
            className="hidden"
            onChange={handleFileSelect}
          />

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

          {/* Image Upload Button */}
          <Tooltip>
            <TooltipTrigger asChild>
              <Button
                variant="ghost"
                size="icon"
                className="h-9 w-9 rounded-lg shrink-0"
                onClick={() => fileInputRef.current?.click()}
                disabled={disabled}
              >
                <ImagePlus className="h-[18px] w-[18px]" />
              </Button>
            </TooltipTrigger>
            <TooltipContent>
              <p>上传图片或 PDF</p>
            </TooltipContent>
          </Tooltip>

          {/* Brain Toggle */}
          <Tooltip>
            <TooltipTrigger asChild>
              <Button
                variant="ghost"
                size="icon"
                className={cn(
                  'h-9 w-9 rounded-lg shrink-0',
                  showThinking && 'bg-black/5 dark:bg-white/10 text-foreground',
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

          {/* Send / Stop Button */}
          {sending ? (
            <Button
              variant="outline"
              onClick={() => {
                if (!abortingRef.current) {
                  abortingRef.current = true;
                  abortRun();
                }
              }}
              disabled={abortingRef.current}
              size="icon"
              className={cn(
                'shrink-0 h-9 w-9 rounded-lg transition-colors',
                abortingRef.current && 'opacity-50 cursor-not-allowed'
              )}
              title={abortingRef.current ? '正在停止...' : '停止'}
            >
              <Square className="h-[18px] w-[18px]" fill="currentColor" />
            </Button>
          ) : (
            <Button
              variant="outline"
              onClick={handleSend}
              disabled={!canSend}
              size="icon"
              className={cn(
                'shrink-0 h-9 w-9 rounded-lg transition-colors',
                !canSend && 'opacity-50'
              )}
              title="发送"
            >
              <SendHorizontal className="h-[18px] w-[18px]" strokeWidth={2} />
            </Button>
          )}
        </div>
      </div>
    </div>
  );
}
