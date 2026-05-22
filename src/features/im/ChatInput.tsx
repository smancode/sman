/**
 * IM Chat Input — identical look & feel to session ChatInput.
 * Removed: / SkillPicker, Zap (auto-confirm), Brain toggle.
 * Added: @ agent mention.
 */
import React, { useState, useRef, useCallback, useEffect, memo } from 'react';
import { SendHorizontal, FileUp, X, Scissors, ChevronDown } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Textarea } from '@/components/ui/textarea';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import {
  DropdownMenu,
  DropdownMenuTrigger,
  DropdownMenuContent,
  DropdownMenuItem,
} from '@/components/ui/dropdown-menu';
import { cn } from '@/lib/utils';
import { useIMStore } from '@/stores/im';
import { useChatStore } from '@/stores/chat';
import { useSendMessage } from '@/queries/use-im';
import { t } from '@/locales';
import { getAgentColor } from './utils';

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

interface MentionableAgent {
  id: string;
  name: string;
  workspace: string;
}

interface StagedMedia {
  fileName: string;
  mimeType: string;
  base64Data: string;
  filePath?: string;
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function getWorkspaceAgents(sessions: { workspace?: string; key: string }[]): MentionableAgent[] {
  const seen = new Set<string>();
  const agents: MentionableAgent[] = [];
  for (const s of sessions) {
    const ws = s.workspace;
    if (!ws || seen.has(ws)) continue;
    seen.add(ws);
    const name = ws.split('/').pop() || ws;
    agents.push({ id: ws, name, workspace: ws });
  }
  return agents;
}

function extractMentions(text: string): string[] {
  const matches = text.match(/@(\S+)/g);
  if (!matches) return [];
  return [...new Set(matches.map((m) => m.slice(1)))];
}

function readFileAsBase64(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => {
      const result = reader.result as string;
      const base64 = result.split(',')[1];
      if (base64) resolve(base64);
      else reject(new Error('Failed to read file as base64'));
    };
    reader.onerror = () => reject(reader.error);
    reader.readAsDataURL(file);
  });
}

const MAX_FILE_SIZE = 10 * 1024 * 1024; // 10MB

// ---------------------------------------------------------------------------
// MentionPopup
// ---------------------------------------------------------------------------

function MentionPopup({ agents, filter, onSelect, onClose }: {
  agents: MentionableAgent[];
  filter: string;
  onSelect: (agent: MentionableAgent) => void;
  onClose: () => void;
}) {
  const popupRef = useRef<HTMLDivElement>(null);
  const filtered = filter
    ? agents.filter((a) => a.name.toLowerCase().includes(filter.toLowerCase()))
    : agents;

  useEffect(() => {
    function handleClick(e: MouseEvent) {
      if (popupRef.current && !popupRef.current.contains(e.target as Node)) onClose();
    }
    document.addEventListener('mousedown', handleClick);
    return () => document.removeEventListener('mousedown', handleClick);
  }, [onClose]);

  useEffect(() => {
    function handleKey(e: globalThis.KeyboardEvent) {
      if (e.key === 'Escape') onClose();
    }
    document.addEventListener('keydown', handleKey);
    return () => document.removeEventListener('keydown', handleKey);
  }, [onClose]);

  if (agents.length === 0) {
    return (
      <div ref={popupRef} className="bg-card border border-border rounded-xl shadow-2xl p-4 text-center">
        <p className="text-sm text-muted-foreground">{t('im.noAgents')}</p>
      </div>
    );
  }

  if (filtered.length === 0) return null;

  return (
    <div
      ref={popupRef}
      className="absolute bottom-full left-2 right-2 mb-2 bg-card border border-border rounded-xl shadow-2xl max-h-60 overflow-y-auto z-50"
    >
      <div className="px-3 py-2 text-xs text-muted-foreground border-b border-border">
        {t('im.agents')}
      </div>
      {filtered.map((agent) => (
        <button
          key={agent.id}
          className="w-full flex items-center gap-3 px-3 py-2.5 text-left cursor-pointer transition-colors hover:bg-muted"
          onMouseDown={(e) => { e.preventDefault(); onSelect(agent); }}
        >
          <span
            className="w-8 h-8 rounded-lg flex items-center justify-center text-xs font-bold text-white flex-shrink-0"
            style={{ backgroundColor: getAgentColor(agent.id) }}
          >
            {agent.name.charAt(0).toUpperCase()}
          </span>
          <div className="min-w-0">
            <div className="text-sm text-foreground truncate">{agent.name}</div>
            <div className="text-xs text-muted-foreground truncate">{agent.workspace}</div>
          </div>
        </button>
      ))}
    </div>
  );
}

// ---------------------------------------------------------------------------
// ChatInput
// ---------------------------------------------------------------------------

interface ChatInputProps {
  roomId: string;
  initialContent?: string;
  onContentConsumed?: () => void;
}

export const ChatInput = memo(function ChatInput({
  roomId,
  initialContent,
  onContentConsumed,
}: ChatInputProps) {
  const [input, setInput] = useState('');
  const [showMention, setShowMention] = useState(false);
  const [mentionFilter, setMentionFilter] = useState('');
  const [mentionStart, setMentionStart] = useState(-1);
  const [stagedMedia, setStagedMedia] = useState<StagedMedia[]>([]);
  const [isDragOver, setIsDragOver] = useState(false);
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const isComposingRef = useRef(false);
  const initialConsumed = useRef(false);

  // IM data
  const sessions = useChatStore((s) => s.sessions);
  const agents = getWorkspaceAgents(sessions);
  const touchRoom = useIMStore((s) => s.touchRoom);
  const sendMessage = useSendMessage();

  const canSend = input.trim().length > 0 || stagedMedia.length > 0;

  // Consume initialContent once
  useEffect(() => {
    if (initialContent && !initialConsumed.current) {
      setInput(initialContent);
      initialConsumed.current = true;
      onContentConsumed?.();
      textareaRef.current?.focus();
    }
  }, [initialContent, onContentConsumed]);

  // Auto-resize textarea
  useEffect(() => {
    const el = textareaRef.current;
    if (!el) return;
    const raf = requestAnimationFrame(() => {
      el.style.height = 'auto';
      el.style.height = `${Math.min(el.scrollHeight, 200)}px`;
    });
    return () => cancelAnimationFrame(raf);
  }, [input]);

  // Focus textarea on mount
  useEffect(() => {
    if (textareaRef.current) textareaRef.current.focus();
  }, []);

  // --- File handling (same as session ChatInput) ---

  const processFiles = useCallback(async (fileList: File[]) => {
    const newMedia: StagedMedia[] = [];
    const getPathForFile = (window as any).sman?.getPathForFile as ((f: File) => string | undefined) | undefined;

    for (const file of fileList) {
      const filePath = getPathForFile?.(file);
      if (filePath) {
        newMedia.push({
          fileName: file.name,
          mimeType: file.type || 'application/octet-stream',
          base64Data: '',
          filePath,
        });
      } else if (file.size <= MAX_FILE_SIZE) {
        const base64Data = await readFileAsBase64(file);
        newMedia.push({
          fileName: file.name,
          mimeType: file.type || 'application/octet-stream',
          base64Data,
        });
      }
    }
    if (newMedia.length > 0) setStagedMedia((prev) => [...prev, ...newMedia]);
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

  // Screenshot capture
  const startScreenshot = useCallback(async (hideWindow: boolean) => {
    if (!window.sman?.startCapture) return;
    const unsub = window.sman.onCaptureResult((dataUrl: string) => {
      unsub();
      if (!dataUrl.startsWith('data:image/png;base64,')) return;
      const base64Data = dataUrl.split(',')[1];
      setStagedMedia((prev) => [...prev, {
        fileName: `screenshot-${Date.now()}.png`,
        mimeType: 'image/png',
        base64Data,
      }]);
    });
    await window.sman.startCapture({ hideWindow });
  }, []);

  // Drag-and-drop
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

  // --- Mention logic ---

  const handleInputChange = useCallback((e: React.ChangeEvent<HTMLTextAreaElement>) => {
    const value = e.target.value;
    setInput(value);

    const cursorPos = e.target.selectionStart;
    let atPos = -1;
    for (let i = cursorPos - 1; i >= 0; i--) {
      if (value[i] === '@') { atPos = i; break; }
      if (value[i] === ' ' || value[i] === '\n') break;
    }

    if (atPos >= 0 && (atPos === 0 || value[atPos - 1] === ' ' || value[atPos - 1] === '\n')) {
      setShowMention(true);
      setMentionStart(atPos);
      setMentionFilter(value.slice(atPos + 1, cursorPos));
    } else {
      setShowMention(false);
      setMentionStart(-1);
      setMentionFilter('');
    }
  }, []);

  const handleMentionSelect = useCallback((agent: MentionableAgent) => {
    if (mentionStart < 0) return;
    const before = input.slice(0, mentionStart);
    const after = input.slice(textareaRef.current?.selectionStart ?? input.length);
    const newInput = `${before}@${agent.name} ${after}`;
    setInput(newInput);
    setShowMention(false);
    setMentionStart(-1);
    setMentionFilter('');
    setTimeout(() => {
      const pos = before.length + agent.name.length + 2;
      textareaRef.current?.setSelectionRange(pos, pos);
      textareaRef.current?.focus();
    }, 0);
  }, [input, mentionStart]);

  const handleMentionButton = useCallback(() => {
    if (showMention) {
      setShowMention(false);
      return;
    }
    const textarea = textareaRef.current;
    if (!textarea) return;
    const pos = textarea.selectionStart;
    const before = input.slice(0, pos);
    const after = input.slice(pos);
    const needSpace = pos > 0 && before[pos - 1] !== ' ' && before[pos - 1] !== '\n';
    const insert = needSpace ? ' @' : '@';
    const newContent = before + insert + after;
    setInput(newContent);
    setShowMention(true);
    setMentionStart(pos + (needSpace ? 1 : 0));
    setMentionFilter('');
    setTimeout(() => {
      const newPos = pos + insert.length;
      textarea.setSelectionRange(newPos, newPos);
      textarea.focus();
    }, 0);
  }, [input, showMention]);

  // --- Send ---

  const handleSend = useCallback(() => {
    if (!canSend) return;
    const trimmed = input.trim();
    const mentionedAgents = extractMentions(trimmed);

    // Build content text — append file paths for local files
    let finalText = trimmed || ' ';
    const pathMedia = stagedMedia.filter(m => m.filePath);
    if (pathMedia.length > 0) {
      const paths = pathMedia.map(m => m.filePath!);
      finalText += ` [用户文件路径:[${paths.join(',')}]]`;
    }

    touchRoom(roomId);

    // Optimistic insert — show message immediately
    const tempId = `temp-${Date.now()}`;
    const mySenderId = useIMStore.getState().mySenderId;
    if (mySenderId) {
      useIMStore.getState().addOptimisticMessage({
        id: tempId,
        roomId,
        sender: mySenderId,
        content: finalText,
        type: 'text',
        timestamp: Date.now(),
        mentionedAgents,
        seq: 0,
      });
    }

    setInput('');
    setStagedMedia([]);
    setShowMention(false);
    setMentionStart(-1);
    setMentionFilter('');
    if (textareaRef.current) textareaRef.current.style.height = 'auto';

    sendMessage.mutate({ roomId, content: finalText, mentionedAgents });
  }, [canSend, input, stagedMedia, roomId, sendMessage]);

  // --- Keyboard ---

  const handleKeyDown = useCallback((e: React.KeyboardEvent) => {
    if (showMention && e.key === 'Escape') {
      e.preventDefault();
      setShowMention(false);
      setMentionStart(-1);
      setMentionFilter('');
      return;
    }
    if (e.key === 'Enter' && !e.shiftKey) {
      const nativeEvent = e.nativeEvent as KeyboardEvent;
      if (isComposingRef.current || nativeEvent.isComposing || nativeEvent.keyCode === 229) return;
      e.preventDefault();
      handleSend();
    }
  }, [showMention, handleSend]);

  return (
    <div className="px-2 pt-2 pb-2 w-full mx-auto relative">
      {/* Mention Popup */}
      {showMention && (
        <div className="absolute bottom-full left-2 right-2 mb-2 w-full overflow-hidden z-50">
          <MentionPopup
            agents={agents}
            filter={mentionFilter}
            onSelect={handleMentionSelect}
            onClose={() => { setShowMention(false); setMentionStart(-1); setMentionFilter(''); }}
          />
        </div>
      )}

      {/* Input Row — identical to session ChatInput */}
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
            accept="*/*"
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
              onCompositionStart={() => { isComposingRef.current = true; }}
              onCompositionEnd={() => { isComposingRef.current = false; }}
              placeholder={t('im.input.placeholder')}
              className="min-h-[40px] max-h-[200px] resize-none border-0 focus-visible:ring-0 focus-visible:ring-offset-0 shadow-none bg-transparent py-2.5 px-2 text-[15px] placeholder:text-muted-foreground/60 leading-relaxed"
              rows={1}
            />
          </div>

          {/* @ Agent Button */}
          <Tooltip>
            <TooltipTrigger asChild>
              <Button
                variant="ghost"
                size="icon"
                className={cn(
                  'h-9 w-9 rounded-lg shrink-0 font-bold text-sm',
                  showMention && 'bg-primary/20 text-primary',
                )}
                onClick={handleMentionButton}
              >
                @
              </Button>
            </TooltipTrigger>
            <TooltipContent><p>@ Agent</p></TooltipContent>
          </Tooltip>

          {/* File Upload Button */}
          <Tooltip>
            <TooltipTrigger asChild>
              <Button
                variant="ghost"
                size="icon"
                className="h-9 w-9 rounded-lg shrink-0"
                onClick={() => fileInputRef.current?.click()}
              >
                <FileUp className="h-[18px] w-[18px]" />
              </Button>
            </TooltipTrigger>
            <TooltipContent><p>{t('chat.uploadFile')}</p></TooltipContent>
          </Tooltip>

          {/* Screenshot Button */}
          {window.sman?.startCapture && (
            <DropdownMenu>
              <Tooltip>
                <TooltipTrigger asChild>
                  <div className="flex shrink-0">
                    <Button
                      variant="ghost"
                      size="icon"
                      className="h-9 w-9 rounded-lg rounded-r-none pr-0.5"
                      onClick={() => startScreenshot(false)}
                    >
                      <Scissors className="h-[18px] w-[18px] -rotate-90" />
                    </Button>
                    <DropdownMenuTrigger asChild>
                      <Button
                        variant="ghost"
                        size="icon"
                        className="h-9 rounded-lg rounded-l-none pl-0 w-5"
                      >
                        <ChevronDown className="h-2.5 w-2.5" />
                      </Button>
                    </DropdownMenuTrigger>
                  </div>
                </TooltipTrigger>
                <TooltipContent><p>{t('chat.screenshot')}</p></TooltipContent>
              </Tooltip>
              <DropdownMenuContent align="end">
                <DropdownMenuItem onClick={() => startScreenshot(true)}>
                  {t('chat.screenshot.hideWindow')}
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
          )}

          {/* Send Button */}
          <Button
            variant="outline"
            onClick={handleSend}
            disabled={!canSend}
            size="icon"
            className={cn(
              'shrink-0 h-9 w-9 rounded-lg transition-colors',
              !canSend && 'opacity-50',
            )}
            title={t('chat.send.button')}
          >
            <SendHorizontal className="h-[18px] w-[18px]" strokeWidth={2} />
          </Button>
        </div>
      </div>
    </div>
  );
});
