/**
 * Chat Message Component
 * Renders user / assistant / system / toolresult messages
 * with markdown, thinking sections, images, and tool cards.
 * Ported from ClawX - removed Electron IPC dependencies.
 */
import { useState, useCallback, useEffect, useMemo, useRef, memo } from 'react';
import { Sparkles, Copy, Check, ChevronDown, ChevronRight, Wrench, FileText, Film, Music, FileArchive, File, X, ZoomIn, Loader2, CheckCircle2, AlertCircle } from 'lucide-react';
import { Streamdown } from 'streamdown';
import 'streamdown/styles.css';
import { createPortal } from 'react-dom';
import { Button } from '@/components/ui/button';
import { cn } from '@/lib/utils';
import type { RawMessage } from '@/types/chat';
import type { Message, AttachedFileMeta } from '@/stores/chat';
import { extractText, extractThinking, extractImages, extractToolUse, formatTimestamp, getToolDisplayName, formatToolSummary, buildContent, safeTimestamp } from './message-utils';
import { useCodePlugin } from '@/lib/streamdown-plugins';
import { streamdownComponents, useCodeBlockCollapse } from './streamdown-components';

interface ChatMessageProps {
  message: Message;
  showThinking: boolean;
  isStreaming?: boolean;
  streamingThinking?: string;
  streamingTools?: Array<{
    id?: string;
    toolCallId?: string;
    name: string;
    status: 'running' | 'completed' | 'error';
    durationMs?: number;
    summary?: string;
  }>;
}

interface ExtractedImage { url?: string; data?: string; mimeType: string; }

/** Resolve an ExtractedImage to a displayable src string, or null if not possible. */
function imageSrc(img: ExtractedImage): string | null {
  if (img.url) return img.url;
  if (img.data) return `data:${img.mimeType};base64,${img.data}`;
  return null;
}

export const ChatMessage = memo(function ChatMessage({
  message,
  showThinking,
  isStreaming = false,
  streamingThinking,
  streamingTools = [],
}: ChatMessageProps) {
  const isUser = message.role === 'user';
  const role = typeof message.role === 'string' ? message.role.toLowerCase() : '';
  const isToolResult = role === 'toolresult' || role === 'tool_result';

  // Stable RawMessage reference — only recomputed when message content actually changes
  // Use message.id + content hash as dependency to avoid recompute on parent re-render
  const rawMessage = useMemo((): RawMessage => {
    // Strip file path tags from display — file cards already show the files
    const cleanContent = message.content.replace(/\s*\[用户文件路径:\[[^\]]+\]\]/g, '');
    const cleanBlocks = message.contentBlocks?.map(b =>
      b.type === 'text' ? { ...b, text: (b as any).text?.replace(/\s*\[用户文件路径:\[[^\]]+\]\]/g, '') } : b
    );
    const resolved = message.resolvedContent
      ? (Array.isArray(message.resolvedContent)
          ? (message.resolvedContent as any[]).map(b =>
              b.type === 'text' ? { ...b, text: b.text?.replace(/\s*\[用户文件路径:\[[^\]]+\]\]/g, '') } : b
            )
          : typeof message.resolvedContent === 'string'
            ? (message.resolvedContent as string).replace(/\s*\[用户文件路径:\[[^\]]+\]\]/g, '')
            : message.resolvedContent)
      : undefined;
    return {
      id: message.id,
      role: message.role,
      content: resolved ?? buildContent(cleanContent, cleanBlocks),
      timestamp: message.timestamp ?? safeTimestamp(message.createdAt),
    };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [message.id, message.content, message.contentBlocks?.length, message.resolvedContent, message.timestamp, message.createdAt]);

  const text = extractText(rawMessage);
  const hasText = text.trim().length > 0;
  const thinking = extractThinking(rawMessage);
  const images = extractImages(rawMessage);
  const tools = extractToolUse(rawMessage);
  const visibleThinking = showThinking ? thinking : null;
  const visibleTools = showThinking ? tools : [];

  const attachedFiles = message._attachedFiles || [];
  const [lightboxImg, setLightboxImg] = useState<{ src: string; fileName: string; filePath?: string; base64?: string; mimeType?: string } | null>(null);

  // Never render tool result messages in chat UI
  if (isToolResult) return null;

  const hasStreamingToolStatus = isStreaming && streamingTools.length > 0;
  const hasStreamingThinking = isStreaming && !!streamingThinking?.trim();
  if (!hasText && !visibleThinking && !hasStreamingThinking && images.length === 0 && visibleTools.length === 0 && attachedFiles.length === 0 && !hasStreamingToolStatus) return null;

  return (
    <div
      className={cn(
        'flex gap-3 group chat-message',
        isUser ? 'flex-row-reverse' : 'flex-row',
      )}
    >
      {/* Avatar */}
      {!isUser && (
        <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg mt-1 ai-avatar text-foreground overflow-hidden">
          <img src="/favicon.svg" alt="AI" className="h-5 w-5 object-contain" />
        </div>
      )}

      {/* Content */}
      <div
        className={cn(
          'flex flex-col w-full min-w-0 max-w-[85%] space-y-2',
          isUser ? 'items-end' : 'items-start',
        )}
      >
        {isStreaming && !isUser && streamingTools.length > 0 && showThinking && (
          <ToolStatusBar tools={streamingTools} />
        )}

        {/* Thinking section */}
        {(visibleThinking || (isStreaming && streamingThinking)) && showThinking && (
          <ThinkingBlock content={isStreaming ? (streamingThinking || visibleThinking || '') : (visibleThinking || '')} />
        )}

        {/* Tool use cards — collapsed summary by default */}
        {visibleTools.length > 0 && (
          <CollapsedToolSummary tools={visibleTools} />
        )}

        {/* Images — rendered ABOVE text bubble for user messages */}
        {/* Images from content blocks (Gateway session data / channel push photos) */}
        {isUser && images.length > 0 && (
          <div className="flex flex-wrap gap-2">
            {images.map((img, i) => {
              const src = imageSrc(img);
              if (!src) return null;
              return (
                <ImageThumbnail
                  key={`content-${i}`}
                  src={src}
                  fileName="image"
                  base64={img.data}
                  mimeType={img.mimeType}
                  onPreview={() => setLightboxImg({ src, fileName: 'image', base64: img.data, mimeType: img.mimeType })}
                />
              );
            })}
          </div>
        )}

        {/* File attachments — images above text for user, file cards below */}
        {isUser && attachedFiles.length > 0 && (
          <div className="flex flex-wrap gap-2">
            {attachedFiles.map((file, i) => {
              const isImage = file.mimeType.startsWith('image/');
              // Skip image attachments if we already have images from content blocks
              if (isImage && images.length > 0) return null;
              if (isImage) {
                return file.preview ? (
                  <ImagePreviewCard
                    key={`local-${i}`}
                    src={file.preview}
                    fileName={file.fileName}
                    filePath={file.filePath}
                    mimeType={file.mimeType}
                    onPreview={() => setLightboxImg({ src: file.preview!, fileName: file.fileName, filePath: file.filePath, mimeType: file.mimeType })}
                  />
                ) : (
                  <div
                    key={`local-${i}`}
                    className="w-36 h-36 rounded-xl border border-black/10 dark:border-white/10 bg-black/5 dark:bg-white/5 flex items-center justify-center text-muted-foreground"
                  >
                    <File className="h-8 w-8" />
                  </div>
                );
              }
              // Non-image files → file card
              return <FileCard key={`local-${i}`} file={file} />;
            })}
          </div>
        )}

        {/* Main text bubble */}
        {hasText && (
          <MessageBubble
            text={text}
            isUser={isUser}
            isStreaming={isStreaming}
          />
        )}

        {/* Hover row for user messages — timestamp only */}
        {isUser && rawMessage.timestamp && (
          <span className="text-xs text-muted-foreground opacity-0 group-hover:opacity-100 transition-opacity duration-200 select-none">
            {formatTimestamp(rawMessage.timestamp)}
          </span>
        )}

        {/* Hover row for assistant messages — only when there is real text content */}
        {!isUser && hasText && (
          <AssistantHoverBar text={text} timestamp={rawMessage.timestamp} />
        )}

        {/* Images from content blocks — assistant messages (below text) */}
        {!isUser && images.length > 0 && (
          <div className="flex flex-wrap gap-2">
            {images.map((img, i) => {
              const src = imageSrc(img);
              if (!src) return null;
              return (
                <ImagePreviewCard
                  key={`content-${i}`}
                  src={src}
                  fileName="image"
                  base64={img.data}
                  mimeType={img.mimeType}
                  onPreview={() => setLightboxImg({ src, fileName: 'image', base64: img.data, mimeType: img.mimeType })}
                />
              );
            })}
          </div>
        )}

        {/* File attachments — assistant messages (below text) */}
        {!isUser && attachedFiles.length > 0 && (
          <div className="flex flex-wrap gap-2">
            {attachedFiles.map((file, i) => {
              const isImage = file.mimeType.startsWith('image/');
              if (isImage && images.length > 0) return null;
              if (isImage && file.preview) {
                return (
                  <ImagePreviewCard
                    key={`local-${i}`}
                    src={file.preview}
                    fileName={file.fileName}
                    filePath={file.filePath}
                    mimeType={file.mimeType}
                    onPreview={() => setLightboxImg({ src: file.preview!, fileName: file.fileName, filePath: file.filePath, mimeType: file.mimeType })}
                  />
                );
              }
              if (isImage && !file.preview) {
                return (
                  <div key={`local-${i}`} className="w-36 h-36 rounded-xl border border-border bg-muted/50 flex items-center justify-center text-muted-foreground">
                    <File className="h-8 w-8" />
                  </div>
                );
              }
              return <FileCard key={`local-${i}`} file={file} />;
            })}
          </div>
        )}

      </div>

      {/* Image lightbox portal */}
      {lightboxImg && (
        <ImageLightbox
          src={lightboxImg.src}
          fileName={lightboxImg.fileName}
          filePath={lightboxImg.filePath}
          base64={lightboxImg.base64}
          mimeType={lightboxImg.mimeType}
          onClose={() => setLightboxImg(null)}
        />
      )}
    </div>
  );
});

function formatDuration(durationMs?: number): string | null {
  if (!durationMs || !Number.isFinite(durationMs)) return null;
  if (durationMs < 1000) return `${Math.round(durationMs)}ms`;
  return `${(durationMs / 1000).toFixed(1)}s`;
}

function ToolStatusBar({
  tools,
}: {
  tools: Array<{
    id?: string;
    toolCallId?: string;
    name: string;
    status: 'running' | 'completed' | 'error';
    durationMs?: number;
    summary?: string;
  }>;
}) {
  return (
    <div className="w-full space-y-1">
      {tools.map((tool) => {
        const duration = formatDuration(tool.durationMs);
        const isRunning = tool.status === 'running';
        const isError = tool.status === 'error';
        const label = getToolDisplayName(tool.name);
        const displayLabel = tool.summary ? `${label}: ${tool.summary}` : label;
        return (
          <div
            key={tool.toolCallId || tool.id || tool.name}
            className={cn(
              'flex items-center gap-2 text-xs transition-colors',
              isRunning && 'text-amber-600 dark:text-amber-400',
              !isRunning && !isError && 'text-muted-foreground',
              isError && 'text-destructive',
            )}
          >
            {isRunning && <Loader2 className="h-3.5 w-3.5 animate-spin text-amber-500 shrink-0" />}
            {!isRunning && !isError && <CheckCircle2 className="h-3.5 w-3.5 text-green-500 shrink-0" />}
            {isError && <AlertCircle className="h-3.5 w-3.5 text-destructive shrink-0" />}
            <span className={cn('text-[12px] truncate min-w-0', isRunning && 'font-medium')}>{displayLabel}</span>
            {duration && <span className="text-[11px] opacity-60 shrink-0">{duration}</span>}
          </div>
        );
      })}
    </div>
  );
}

// ── Assistant hover bar (timestamp + copy, shown on group hover) ─

function AssistantHoverBar({ text, timestamp }: { text: string; timestamp?: number }) {
  const [copied, setCopied] = useState(false);

  const copyContent = useCallback(() => {
    navigator.clipboard.writeText(text);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  }, [text]);

  return (
    <div className="flex items-center justify-between w-full opacity-0 group-hover:opacity-100 transition-opacity duration-200 select-none px-1">
      <span className="text-xs text-muted-foreground">
        {timestamp ? formatTimestamp(timestamp) : ''}
      </span>
      <Button
        variant="ghost"
        size="icon"
        className="h-6 w-6"
        onClick={copyContent}
      >
        {copied ? <Check className="h-3 w-3 text-green-500" /> : <Copy className="h-3 w-3" />}
      </Button>
    </div>
  );
}

// ── Message Bubble ──────────────────────────────────────────────

function MessageBubble({
  text,
  isUser,
  isStreaming,
}: {
  text: string;
  isUser: boolean;
  isStreaming: boolean;
}) {
  const codePlugin = useCodePlugin();

  if (isUser) {
    return (
      <div
        className={cn(
          'relative rounded-xl px-4 py-3 border',
          'border-[hsl(var(--user-bubble-fg))]/20 text-[hsl(var(--user-bubble-fg))]',
        )}
      >
        <p className="whitespace-pre-wrap break-words break-all text-sm">{text}</p>
      </div>
    );
  }

  // Assistant message: no bubble — plain text rendering
  const collapseRef = useCodeBlockCollapse<HTMLDivElement>();

  return (
    <div className="w-full" ref={collapseRef}>
      <div className="markdown-content overflow-x-auto prose prose-sm dark:prose-invert max-w-none break-words break-all text-foreground">
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

// ── Global ThinkingBlock update scheduler ───────────────────────
// Single shared timer across all ThinkingBlock instances to avoid
// linear growth of setInterval timers in long conversations.
const thinkingSubscribers = new Set<() => void>();
let thinkingTimer: ReturnType<typeof setInterval> | null = null;

function ensureThinkingTimer(): void {
  if (thinkingTimer) return;
  thinkingTimer = setInterval(() => {
    for (const cb of thinkingSubscribers) {
      try { cb(); } catch { /* ignore */ }
    }
  }, 200);
}

function removeThinkingTimer(): void {
  if (thinkingSubscribers.size === 0 && thinkingTimer) {
    clearInterval(thinkingTimer);
    thinkingTimer = null;
  }
}

// ── Thinking Block ──────────────────────────────────────────────

function ThinkingBlock({ content }: { content: string }) {
  const [expanded, setExpanded] = useState(false);
  const codePlugin = useCodePlugin();
  const collapseRef = useCodeBlockCollapse<HTMLDivElement>();
  const [summary, setSummary] = useState('');
  const prevLenRef = useRef(0);

  // Update summary — subscribe to global timer instead of per-instance interval
  useEffect(() => {
    const update = () => {
      if (content.length === prevLenRef.current) return;
      const lines = content.split('\n').map(l => l.trim()).filter(Boolean);
      if (lines.length === 0) { setSummary(''); prevLenRef.current = content.length; return; }
      // Show last meaningful line — gives a sense of progression
      const lastLine = lines[lines.length - 1];
      setSummary(lastLine.length > 120 ? '...' + lastLine.slice(-117) : lastLine);
      prevLenRef.current = content.length;
    };
    update(); // initial
    thinkingSubscribers.add(update);
    ensureThinkingTimer();
    return () => {
      thinkingSubscribers.delete(update);
      removeThinkingTimer();
    };
  }, [content]);

  if (!content.trim()) return null;

  return (
    <div className="w-full text-[12px] text-muted-foreground">
      <button
        className="flex items-center gap-2 w-full px-1 py-0.5 text-left hover:text-foreground transition-colors"
        onClick={() => setExpanded(!expanded)}
      >
        {expanded ? <ChevronDown className="h-3.5 w-3.5 shrink-0" /> : <ChevronRight className="h-3.5 w-3.5 shrink-0" />}
        <span className="font-medium shrink-0">思考</span>
        {!expanded && summary && (
          <span className="truncate min-w-0 flex-1 opacity-60">{summary}</span>
        )}
      </button>
      {expanded && (
        <div className="text-muted-foreground" ref={collapseRef}>
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

// ── File Card (for user-uploaded non-image files) ───────────────

function formatFileSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(2)} MB`;
  return `${(bytes / (1024 * 1024 * 1024)).toFixed(2)} GB`;
}

function FileIcon({ mimeType, className }: { mimeType: string; className?: string }) {
  if (mimeType.startsWith('video/')) return <Film className={className} />;
  if (mimeType.startsWith('audio/')) return <Music className={className} />;
  if (mimeType.startsWith('text/') || mimeType === 'application/json' || mimeType === 'application/xml') return <FileText className={className} />;
  if (mimeType.includes('zip') || mimeType.includes('compressed') || mimeType.includes('archive') || mimeType.includes('tar') || mimeType.includes('rar') || mimeType.includes('7z')) return <FileArchive className={className} />;
  if (mimeType === 'application/pdf') return <FileText className={className} />;
  return <File className={className} />;
}

function FileCard({ file }: { file: AttachedFileMeta }) {
  // In web version, we cannot open files directly - just show the card
  return (
    <div
      className={cn(
        "flex items-center gap-3 rounded-xl border border-border px-3 py-2.5 bg-muted/50 max-w-[220px]"
      )}
    >
      <FileIcon mimeType={file.mimeType} className="h-5 w-5 shrink-0 text-muted-foreground" />
      <div className="min-w-0 overflow-hidden">
        <p className="text-xs font-medium truncate">{file.fileName}</p>
        <p className="text-[10px] text-muted-foreground">
          {file.fileSize > 0 ? formatFileSize(file.fileSize) : '文件'}
        </p>
      </div>
    </div>
  );
}

// ── Image Thumbnail (user bubble — square crop with zoom hint) ──

function ImageThumbnail({
  src,
  fileName,
  filePath,
  base64,
  mimeType,
  onPreview,
}: {
  src: string;
  fileName: string;
  filePath?: string;
  base64?: string;
  mimeType?: string;
  onPreview: () => void;
}) {
  void filePath; void base64; void mimeType;
  return (
    <div
      className="relative w-36 h-36 rounded-xl border overflow-hidden border-border bg-muted/50 group/img cursor-zoom-in"
      onClick={onPreview}
    >
      <img src={src} alt={fileName} className="w-full h-full object-cover" loading="lazy" />
      <div className="absolute inset-0 bg-black/0 group-hover/img:bg-black/25 transition-colors flex items-center justify-center">
        <ZoomIn className="h-6 w-6 text-white opacity-0 group-hover/img:opacity-100 transition-opacity drop-shadow" />
      </div>
    </div>
  );
}

// ── Image Preview Card (assistant bubble — natural size with overlay actions) ──

function ImagePreviewCard({
  src,
  fileName,
  filePath,
  base64,
  mimeType,
  onPreview,
}: {
  src: string;
  fileName: string;
  filePath?: string;
  base64?: string;
  mimeType?: string;
  onPreview: () => void;
}) {
  void filePath; void base64; void mimeType;
  return (
    <div
      className="relative max-w-xs rounded-xl border overflow-hidden border-border bg-muted/50 group/img cursor-zoom-in"
      onClick={onPreview}
    >
      <img src={src} alt={fileName} className="block w-full" loading="lazy" />
      <div className="absolute inset-0 bg-black/0 group-hover/img:bg-black/20 transition-colors flex items-center justify-center">
        <ZoomIn className="h-6 w-6 text-white opacity-0 group-hover/img:opacity-100 transition-opacity drop-shadow" />
      </div>
    </div>
  );
}

// ── Image Lightbox ───────────────────────────────────────────────

function ImageLightbox({
  src,
  fileName,
  filePath,
  base64,
  mimeType,
  onClose,
}: {
  src: string;
  fileName: string;
  filePath?: string;
  base64?: string;
  mimeType?: string;
  onClose: () => void;
}) {
  void src; void base64; void mimeType; void fileName; void filePath;

  useEffect(() => {
    const handleKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', handleKey);
    return () => window.removeEventListener('keydown', handleKey);
  }, [onClose]);

  // In web version, no "show in folder" button - just close

  return createPortal(
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/80 backdrop-blur-sm"
      onClick={onClose}
    >
      {/* Image + buttons stacked */}
      <div
        className="flex flex-col items-center gap-3"
        onClick={(e) => e.stopPropagation()}
      >
        <img
          src={src}
          alt={fileName}
          className="max-w-[90vw] max-h-[85vh] rounded-lg shadow-2xl object-contain"
        />

        {/* Action buttons below image */}
        <div className="flex items-center gap-2">
          <Button
            variant="ghost"
            size="icon"
            className="h-8 w-8 bg-white/10 hover:bg-white/20 text-white"
            onClick={onClose}
            title="关闭"
          >
            <X className="h-4 w-4" />
          </Button>
        </div>
      </div>
    </div>,
    document.body,
  );
}

// ── Tool Card ───────────────────────────────────────────────────

/** Collapsed tool summary — shows tool names as compact pills, expandable for details */
function CollapsedToolSummary({ tools }: { tools: Array<{ id?: string; name: string; input?: unknown }> }) {
  const [expanded, setExpanded] = useState(false);

  // Deduplicate by name
  const uniqueNames = [...new Set(tools.map(t => t.name))];

  return (
    <div className="text-xs">
      <button
        className="flex items-center gap-1.5 text-muted-foreground hover:text-foreground transition-colors group/summary"
        onClick={() => setExpanded(!expanded)}
      >
        <Wrench className="h-3 w-3 shrink-0 opacity-60" />
        <span>{tools.length} 个工具调用</span>
        <span className="flex gap-1 flex-wrap">
          {uniqueNames.map(name => (
            <span key={name} className="px-1 py-0.5 bg-muted rounded text-[11px] font-mono">{name}</span>
          ))}
        </span>
        {expanded
          ? <ChevronDown className="h-3 w-3 ml-0.5" />
          : <ChevronRight className="h-3 w-3 ml-0.5" />
        }
      </button>
      {expanded && (
        <div className="mt-1 space-y-0.5 pl-1">
          {tools.map((tool, i) => (
            <CompletedToolItem key={tool.id || i} name={tool.name} input={tool.input} />
          ))}
        </div>
      )}
    </div>
  );
}

/** Completed tool item — green ✓ + name + one-line summary, expandable for full input */
function CompletedToolItem({ name, input }: { name: string; input: unknown }) {
  const [expanded, setExpanded] = useState(false);
  const displayName = getToolDisplayName(name);
  const summary = formatToolSummary(name, input);
  const displayLabel = summary ? `${displayName}: ${summary}` : displayName;

  return (
    <div className="text-[14px]">
      <button
        className="flex items-center gap-2 w-full px-1 py-0.5 text-muted-foreground hover:text-foreground transition-colors"
        onClick={() => setExpanded(!expanded)}
      >
        <CheckCircle2 className="h-3.5 w-3.5 text-green-500 shrink-0" />
        <span className="text-xs truncate min-w-0">{displayLabel}</span>
        {input != null && (
          expanded
            ? <ChevronDown className="h-3 w-3 ml-auto shrink-0 opacity-50" />
            : <ChevronRight className="h-3 w-3 ml-auto shrink-0 opacity-50" />
        )}
      </button>
      {expanded && input != null && (
        <pre className="pl-5 text-xs text-muted-foreground whitespace-pre-wrap break-all">
          {typeof input === 'string' ? input : JSON.stringify(input, null, 2) as string}
        </pre>
      )}
    </div>
  );
}
