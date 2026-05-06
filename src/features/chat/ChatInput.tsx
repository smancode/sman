/**
 * Chat Input Component
 * Textarea with send button for web (no Electron file picker).
 * Enter to send, Shift+Enter for new line.
 * Supports image/PDF upload via file picker or drag-drop.
 * Type "/" to open skill picker.
 */
import { useState, useRef, useEffect, useCallback } from 'react';
import { SendHorizontal, RefreshCw, Brain, FileUp, X, Square, Zap } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Textarea } from '@/components/ui/textarea';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import { cn } from '@/lib/utils';
import { useChatStore } from '@/stores/chat';
import { SkillPicker } from '@/components/SkillPicker';

export interface StagedMedia {
  fileName: string;
  mimeType: string;
  base64Data: string;
  /** Local file path (Electron drag-drop or file picker). When set, base64Data is empty. */
  filePath?: string;
}

interface Skill {
  id: string;
  name: string;
  description: string;
  content: string;
}

interface ChatInputProps {
  onSend: (text: string, attachments?: unknown, targetAgentId?: string | null, media?: StagedMedia[]) => void;
  disabled?: boolean;
  sending?: boolean;
  isEmpty?: boolean;
}

const MAX_FILE_SIZE = 10 * 1024 * 1024; // 10MB
const ACCEPTED_TYPES = '*/*';

// Per-session input cache — survives session switches
const inputCache = new Map<string, { input: string; stagedMedia: StagedMedia[] }>();

function readFileAsBase64(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => {
      const result = reader.result as string;
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

  // Input history for up/down arrow navigation
  const historyIndexRef = useRef(-1);
  const savedInputRef = useRef('');

  // Chat toolbar states
  const refresh = useChatStore((s) => s.refresh);
  const loading = useChatStore((s) => s.loading);
  const sending = useChatStore((s) => s.sending);
  const abortRun = useChatStore((s) => s.abortRun);
  const currentSessionId = useChatStore((s) => s.currentSessionId);
  const showThinking = useChatStore((s) => s.showThinking);
  const toggleThinking = useChatStore((s) => s.toggleThinking);
  const autoConfirm = useChatStore((s) => s.autoConfirm);
  const toggleAutoConfirm = useChatStore((s) => s.toggleAutoConfirm);

  // Reset aborting flag when sending finishes
  useEffect(() => {
    if (!sending) abortingRef.current = false;
  }, [sending]);

  // Save/restore input on session switch
  const prevSessionIdRef = useRef(currentSessionId);
  const inputRef = useRef(input);
  inputRef.current = input;
  const stagedMediaRef = useRef(stagedMedia);
  stagedMediaRef.current = stagedMedia;
  useEffect(() => {
    const prevId = prevSessionIdRef.current;
    if (prevId === currentSessionId) return;

    if (prevId) {
      inputCache.set(prevId, { input: inputRef.current, stagedMedia: stagedMediaRef.current });
    }

    const cached = currentSessionId ? inputCache.get(currentSessionId) : undefined;
    setInput(cached?.input ?? '');
    setStagedMedia(cached?.stagedMedia ?? []);

    prevSessionIdRef.current = currentSessionId;
  }, [currentSessionId]);

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

  const processFiles = useCallback(async (fileList: File[]) => {
    const newMedia: StagedMedia[] = [];
    const getPathForFile = (window as any).sman?.getPathForFile as ((f: File) => string | undefined) | undefined;

    for (const file of fileList) {
      // Electron: get local file path via webUtils.getPathForFile (exposed by preload)
      const filePath = getPathForFile?.(file);

      if (filePath) {
        // Local file: store path only, no base64 — Claude Code reads files by path
        newMedia.push({
          fileName: file.name,
          mimeType: file.type || 'application/octet-stream',
          base64Data: '',
          filePath,
        });
      } else if (file.size <= MAX_FILE_SIZE) {
        // Web: read as base64
        const base64Data = await readFileAsBase64(file);
        newMedia.push({
          fileName: file.name,
          mimeType: file.type || 'application/octet-stream',
          base64Data,
        });
      }
    }

    if (newMedia.length > 0) {
      setStagedMedia((prev) => [...prev, ...newMedia]);
    }
  }, []);

  const handleFileSelect = useCallback(async (e: React.ChangeEvent<HTMLInputElement>) => {
    const files = e.target.files;
    if (!files) return;
    await processFiles(Array.from(files));
    if (fileInputRef.current) fileInputRef.current.value = '';
  }, [processFiles]);

  const removeStagedMedia = useCallback((index: number) => {
    setStagedMedia((prev) => prev.filter((_, i) => i !== index));
  }, []);

  // Drag-and-drop state
  const [isDragOver, setIsDragOver] = useState(false);

  const handleDragOver = useCallback((e: React.DragEvent) => {
    e.preventDefault();
    e.stopPropagation();
    if (!isDragOver) setIsDragOver(true);
  }, [isDragOver]);

  const handleDragLeave = useCallback((e: React.DragEvent) => {
    e.preventDefault();
    e.stopPropagation();
    setIsDragOver(false);
  }, []);

  const handleDrop = useCallback(async (e: React.DragEvent) => {
    e.preventDefault();
    e.stopPropagation();
    setIsDragOver(false);

    const files = Array.from(e.dataTransfer.files);
    if (files.length > 0) await processFiles(files);
  }, [processFiles]);

  const handlePaste = useCallback(async (e: React.ClipboardEvent) => {
    const files = Array.from(e.clipboardData.files);
    if (files.length > 0) {
      e.preventDefault();
      await processFiles(files);
    }
  }, [processFiles]);

  const handleSend = useCallback(() => {
    if (!canSend || useChatStore.getState().sending) return;
    const textToSend = input.trim();

    // Split into path-based (Electron local files) and base64-based (web uploads)
    const pathMedia = stagedMedia.filter(m => m.filePath);
    const base64Media = stagedMedia.filter(m => !m.filePath);

    // Append file paths directly into the message text for Claude
    let finalText = textToSend || ' ';
    if (pathMedia.length > 0) {
      const paths = pathMedia.map(m => m.filePath!);
      finalText += ` [用户文件路径:[${paths.join(',')}]]`;
    }

    // Clear input immediately — this renders in the current frame
    setInput('');
    setStagedMedia([]);
    historyIndexRef.current = -1;
    savedInputRef.current = '';
    if (currentSessionId) inputCache.delete(currentSessionId);
    if (textareaRef.current) {
      textareaRef.current.style.height = 'auto';
    }

    // Defer the actual send to the next frame so React can render the cleared input first
    const capturedMedia = base64Media.length > 0 ? base64Media : undefined;
    setTimeout(() => onSend(finalText, undefined, null, capturedMedia), 0);
  }, [input, canSend, onSend, stagedMedia, currentSessionId]);

  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      if (showSkillPicker) return;

      if (e.key === 'ArrowUp' || e.key === 'ArrowDown') {
        const textarea = textareaRef.current;
        if (!textarea) return;

        const cursorAtStart = textarea.selectionStart === 0 && textarea.selectionEnd === 0;
        const cursorAtEnd = textarea.selectionStart === input.length && textarea.selectionEnd === input.length;

        if (e.key === 'ArrowUp' && cursorAtStart) {
          e.preventDefault();
          const userMessages = useChatStore.getState().messages.filter(m => m.role === 'user' && m.content.trim());
          if (userMessages.length === 0) return;

          if (historyIndexRef.current === -1) {
            savedInputRef.current = input;
          }

          const newIndex = Math.min(historyIndexRef.current + 1, userMessages.length - 1);
          if (newIndex !== historyIndexRef.current) {
            historyIndexRef.current = newIndex;
            const historicalMsg = userMessages[userMessages.length - 1 - newIndex];
            setInput(historicalMsg.content);
          }
        } else if (e.key === 'ArrowDown' && cursorAtEnd) {
          e.preventDefault();
          if (historyIndexRef.current === -1) return;

          const userMessages = useChatStore.getState().messages.filter(m => m.role === 'user' && m.content.trim());
          const newIndex = historyIndexRef.current - 1;
          historyIndexRef.current = newIndex;

          if (newIndex === -1) {
            setInput(savedInputRef.current);
          } else {
            const historicalMsg = userMessages[userMessages.length - 1 - newIndex];
            setInput(historicalMsg.content);
          }
        }
        return;
      }

      if (e.key === 'Enter' && !e.shiftKey) {
        const nativeEvent = e.nativeEvent as KeyboardEvent;
        if (isComposingRef.current || nativeEvent.isComposing || nativeEvent.keyCode === 229) {
          return;
        }
        e.preventDefault();
        handleSend();
      }
    },
    [handleSend, showSkillPicker, input],
  );

  const handleInputChange = (e: React.ChangeEvent<HTMLTextAreaElement>) => {
    const value = e.target.value;
    setInput(value);

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
    const cursorPosition = textareaRef.current?.selectionStart || input.length;
    const beforeSlash = input.slice(0, cursorPosition - 1);
    const afterCursor = input.slice(cursorPosition);

    const newInput = beforeSlash + '/' + skill.id + ' ' + afterCursor;

    setInput(newInput);
    setShowSkillPicker(false);
    slashTriggeredRef.current = false;

    setTimeout(() => {
      if (textareaRef.current) {
        textareaRef.current.focus();
        const newCursorPos = beforeSlash.length + 1 + skill.id.length + 1;
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
      <div
        className={cn(
          "relative bg-card rounded-xl shadow-sm border p-1.5 transition-colors",
          isDragOver ? 'border-primary/50 bg-primary/5' : 'border-border',
        )}
        onDragOver={handleDragOver}
        onDragEnter={handleDragOver}
        onDragLeave={handleDragLeave}
        onDrop={handleDrop}
        onPaste={handlePaste}
      >
        {/* File attachments preview row */}
        {stagedMedia.length > 0 && (
          <div className="flex gap-1.5 p-2 pb-0 flex-wrap">
            {stagedMedia.map((media, idx) => {
              const isImage = media.mimeType.startsWith('image/');
              if (isImage && media.base64Data) {
                return (
                  <div key={idx} className="group relative w-20 h-20 rounded-lg border overflow-hidden border-border bg-muted/50">
                    <img
                      src={`data:${media.mimeType};base64,${media.base64Data}`}
                      alt={media.fileName}
                      className="w-full h-full object-cover"
                    />
                    <button
                      type="button"
                      onClick={() => removeStagedMedia(idx)}
                      className="absolute top-0.5 right-0.5 h-4 w-4 rounded-full bg-black/50 hover:bg-destructive text-white transition-colors flex items-center justify-center"
                    >
                      <X className="h-3 w-3" />
                    </button>
                  </div>
                );
              }
              return (
                <div key={idx} className="group relative flex items-center gap-1.5 rounded-lg border bg-muted/50 px-2.5 py-1.5 text-sm max-w-[200px]">
                  <FileUp className="h-3.5 w-3.5 shrink-0 text-muted-foreground" />
                  <span className="truncate">{media.fileName}</span>
                  <button
                    type="button"
                    onClick={() => removeStagedMedia(idx)}
                    className="shrink-0 h-4 w-4 rounded-full hover:bg-destructive/20 text-muted-foreground hover:text-destructive transition-colors flex items-center justify-center"
                  >
                    <X className="h-3 w-3" />
                  </button>
                </div>
              );
            })}
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
              placeholder={disabled ? '未连接' : '输入消息... (输入 / 选择 Skill，可拖入文件)'}
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
                <FileUp className="h-[18px] w-[18px]" />
              </Button>
            </TooltipTrigger>
            <TooltipContent>
              <p>上传文件</p>
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

          {/* Auto-Confirm Toggle */}
          <Tooltip>
            <TooltipTrigger asChild>
              <Button
                variant="ghost"
                size="icon"
                className={cn(
                  'h-9 w-9 rounded-lg shrink-0',
                  autoConfirm && 'bg-amber-100 dark:bg-amber-900/30 text-amber-600',
                )}
                onClick={toggleAutoConfirm}
              >
                <Zap className="h-[18px] w-[18px]" />
              </Button>
            </TooltipTrigger>
            <TooltipContent>
              <p>{autoConfirm ? 'AUTO 模式：自动确认所有提问' : '开启 AUTO 模式'}</p>
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
