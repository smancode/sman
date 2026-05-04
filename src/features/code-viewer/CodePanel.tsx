/**
 * CodePanel — right side of the code viewer overlay.
 *
 * Shows file content with Shiki syntax highlighting, line numbers,
 * highlighted line, and Ctrl+Click navigation.
 */

import { memo, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { ThemedToken } from 'shiki';
import { Loader2, AlertTriangle, FileQuestion } from 'lucide-react';
import { highlightCode } from '@/lib/shiki-worker-client';
import { useCodeViewerStore, type FileContent, type BinaryFileInfo } from '@/stores/code-viewer';
import { cn } from '@/lib/utils';
import { CodeNavigator } from './CodeNavigator';

// ── Constants ──────────────────────────────────────────────────────

const THEMES: [string, string] = ['one-light', 'one-dark-pro'];
const CSS_VAR_PREFIX = '--shiki-';

/** Detect current dark mode */
function getIsDark(): boolean {
  return document.documentElement.classList.contains('dark');
}

/** Get the resolved token color for the current theme */
function getTokenColor(token: ThemedToken, isDark: boolean): string | undefined {
  if (token.htmlStyle) {
    if (isDark) {
      const darkVar = token.htmlStyle[`${CSS_VAR_PREFIX}dark`];
      if (darkVar) return darkVar;
    }
    return token.htmlStyle.color;
  }
  return token.color;
}

// ── CodePanel (top-level) ──────────────────────────────────────────

interface CodePanelProps {
  workspace: string;
}

export function CodePanel({ workspace }: CodePanelProps) {
  const currentFile = useCodeViewerStore((s) => s.currentFile);
  const loading = useCodeViewerStore((s) => s.loading);
  const error = useCodeViewerStore((s) => s.error);
  const lineNumber = useCodeViewerStore((s) => s.lineNumber);

  // Loading state
  if (loading && !currentFile) {
    return (
      <div className="flex-1 flex items-center justify-center">
        <div className="flex flex-col items-center gap-3 text-muted-foreground">
          <Loader2 className="h-8 w-8 animate-spin" />
          <span className="text-[13px]">加载文件中...</span>
        </div>
      </div>
    );
  }

  // Error state
  if (error) {
    return (
      <div className="flex-1 flex items-center justify-center">
        <div className="flex flex-col items-center gap-3 text-destructive max-w-md px-4 text-center">
          <AlertTriangle className="h-8 w-8 shrink-0" />
          <span className="text-[13px]">{error}</span>
        </div>
      </div>
    );
  }

  // No file selected
  if (!currentFile) {
    return (
      <div className="flex-1 flex items-center justify-center">
        <div className="flex flex-col items-center gap-3 text-muted-foreground">
          <FileQuestion className="h-8 w-8 shrink-0" />
          <span className="text-[13px]">点击左侧文件查看内容</span>
        </div>
      </div>
    );
  }

  // Binary file
  if ('type' in currentFile && currentFile.type === 'binary') {
    return <BinaryPanel file={currentFile} />;
  }

  // Text file
  return (
    <CodeContent
      file={currentFile as FileContent}
      highlightLine={lineNumber}
      workspace={workspace}
    />
  );
}

// ── BinaryPanel ────────────────────────────────────────────────────

function BinaryPanel({ file }: { file: BinaryFileInfo }) {
  const sizeStr = useMemo(() => {
    const bytes = file.size;
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  }, [file.size]);

  return (
    <div className="flex-1 flex items-center justify-center">
      <div className="flex flex-col items-center gap-3 text-muted-foreground text-center">
        <FileQuestion className="h-8 w-8 shrink-0" />
        <span className="text-[13px] font-medium">二进制文件</span>
        <span className="text-[12px]">
          {file.fileName} ({sizeStr}, {file.mimeType})
        </span>
        <span className="text-[12px] text-muted-foreground/60">
          此文件类型无法预览
        </span>
      </div>
    </div>
  );
}

// ── CodeContent ────────────────────────────────────────────────────

interface CodeContentProps {
  file: FileContent;
  highlightLine: number | null;
  workspace: string;
}

function CodeContent({ file, highlightLine, workspace }: CodeContentProps) {
  const [tokens, setTokens] = useState<ThemedToken[][] | null>(null);
  const [isDark, setIsDark] = useState(getIsDark);
  const [highlightError, setHighlightError] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);
  const highlightLineRef = useRef<HTMLDivElement>(null);

  const filePath = file.path;
  const language = file.language;
  const lines = useMemo(() => file.content.split('\n'), [file.content]);

  // Listen for dark mode changes
  useEffect(() => {
    const observer = new MutationObserver(() => {
      setIsDark(getIsDark());
    });
    observer.observe(document.documentElement, {
      attributes: true,
      attributeFilter: ['class'],
    });
    return () => observer.disconnect();
  }, []);

  // Highlight code with Shiki worker
  useEffect(() => {
    if (!file.content || !language) {
      setTokens(null);
      return;
    }

    setHighlightError(false);
    let cancelled = false;

    highlightCode(file.content, language, THEMES)
      .then((result) => {
        if (!cancelled) {
          setTokens(result.tokens ?? null);
        }
      })
      .catch((err) => {
        console.error('[CodePanel] Shiki highlight failed:', err);
        if (!cancelled) {
          setHighlightError(true);
          setTokens(null);
        }
      });

    return () => {
      cancelled = true;
    };
  }, [file.content, language]);

  // Scroll highlighted line into view
  useEffect(() => {
    if (highlightLine && highlightLineRef.current) {
      highlightLineRef.current.scrollIntoView({ block: 'center', behavior: 'smooth' });
    }
  }, [highlightLine, tokens]);

  const handleTokenClick = useTokenClickHandler(filePath, workspace);

  return (
    <div className="flex flex-col flex-1 min-h-0 relative">
      {/* File header */}
      <FileHeader
        filePath={filePath}
        language={language}
        lineCount={file.totalLines}
        truncated={file.truncated}
      />

      {/* Code area */}
      <div ref={containerRef} className="flex-1 min-h-0 overflow-auto">
        <div
          className="font-mono text-[13px] leading-[1.65]"
          style={{
            color: isDark ? '#abb2bf' : '#383a42',
            backgroundColor: isDark ? '#282c34' : '#fafafa',
          }}
        >
          {tokens && !highlightError ? (
            tokens.map((lineTokens, i) => (
              <TokenLine
                key={i}
                lineIndex={i}
                tokens={lineTokens}
                isDark={isDark}
                isHighlighted={highlightLine === i + 1}
                highlightRef={highlightLine === i + 1 ? highlightLineRef : undefined}
                onTokenClick={handleTokenClick}
                lineText={lines[i] ?? ''}
              />
            ))
          ) : (
            // Fallback: plain text rendering (no highlighting or highlight failed)
            lines.map((line, i) => (
              <PlainLine
                key={i}
                lineIndex={i}
                text={line}
                isHighlighted={highlightLine === i + 1}
                highlightRef={highlightLine === i + 1 ? highlightLineRef : undefined}
              />
            ))
          )}
        </div>
      </div>

      {/* Navigator overlay */}
      <CodeNavigator workspace={workspace} currentFilePath={filePath} />
    </div>
  );
}

// ── FileHeader ─────────────────────────────────────────────────────

interface FileHeaderProps {
  filePath: string;
  language: string;
  lineCount: number;
  truncated: boolean;
}

const FileHeader = memo(function FileHeader({
  filePath,
  language,
  lineCount,
  truncated,
}: FileHeaderProps) {
  return (
    <div className="flex items-center gap-2 px-4 py-2 border-b border-[hsl(var(--border))] shrink-0 bg-[hsl(var(--card))]">
      <span className="text-[13px] text-[hsl(var(--foreground))] font-medium truncate">
        {filePath}
      </span>
      <span className="text-[11px] text-muted-foreground/60 shrink-0">
        {language}
      </span>
      <span className="text-[11px] text-muted-foreground/60 shrink-0">
        {lineCount} 行
      </span>
      {truncated && (
        <span className="text-[11px] text-amber-500 shrink-0">
          (已截断)
        </span>
      )}
    </div>
  );
});

// ── TokenLine ──────────────────────────────────────────────────────

interface TokenLineProps {
  lineIndex: number;
  tokens: ThemedToken[];
  isDark: boolean;
  isHighlighted: boolean;
  highlightRef?: React.RefObject<HTMLDivElement | null>;
  onTokenClick: (e: React.MouseEvent, tokenContent: string, lineIndex: number) => void;
  lineText: string;
}

const TokenLine = memo(function TokenLine({
  lineIndex,
  tokens,
  isDark,
  isHighlighted,
  highlightRef,
  onTokenClick,
  lineText,
}: TokenLineProps) {
  const lineNum = lineIndex + 1;

  return (
    <div
      ref={highlightRef}
      className={cn(
        'flex hover:bg-[hsl(var(--muted))]/30',
        isHighlighted && (isDark
          ? 'bg-[rgba(31,111,235,0.15)]'
          : 'bg-[rgba(84,174,255,0.15)]'),
      )}
    >
      {/* Line number */}
      <span
        className="select-none shrink-0 text-right pr-3 pl-2 text-muted-foreground/50"
        style={{ width: 48, minWidth: 48 }}
      >
        {lineNum}
      </span>

      {/* Code content */}
      <span
        className="white-space-pre-wrap break-all flex-1 min-w-0"
        style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-all' }}
      >
        {tokens.length === 0 ? (
          '\n'
        ) : (
          tokens.map((token, ti) => (
            <span
              key={ti}
              style={{ color: getTokenColor(token, isDark) }}
              className="cursor-text"
              onClick={(e) => {
                if (e.ctrlKey || e.metaKey) {
                  onTokenClick(e, token.content, lineIndex);
                }
              }}
            >
              {token.content}
            </span>
          ))
        )}
      </span>
    </div>
  );
});

// ── PlainLine (fallback without highlighting) ──────────────────────

interface PlainLineProps {
  lineIndex: number;
  text: string;
  isHighlighted: boolean;
  highlightRef?: React.RefObject<HTMLDivElement | null>;
}

const PlainLine = memo(function PlainLine({
  lineIndex,
  text,
  isHighlighted,
  highlightRef,
}: PlainLineProps) {
  const lineNum = lineIndex + 1;

  return (
    <div
      ref={highlightRef}
      className={cn(
        'flex hover:bg-[hsl(var(--muted))]/30',
        isHighlighted && 'bg-[rgba(31,111,235,0.15)]',
      )}
    >
      <span
        className="select-none shrink-0 text-right pr-3 pl-2 text-muted-foreground/50"
        style={{ width: 48, minWidth: 48 }}
      >
        {lineNum}
      </span>
      <span
        className="flex-1 min-w-0"
        style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-all' }}
      >
        {text}
      </span>
    </div>
  );
});

// ── Ctrl+Click navigation hook ─────────────────────────────────────

function useTokenClickHandler(filePath: string, workspace: string) {
  const loadFile = useCodeViewerStore((s) => s.loadFile);
  const searchSymbols = useCodeViewerStore((s) => s.searchSymbols);

  return useCallback(
    (e: React.MouseEvent, tokenContent: string, lineIndex: number) => {
      // Only trigger on Ctrl+Click or Cmd+Click
      if (!e.ctrlKey && !e.metaKey) return;

      const identifier = tokenContent.trim();
      if (!identifier || !/^[a-zA-Z_]\w*$/.test(identifier)) return;

      // Read the full line from the current file to check for import patterns
      const lines = (useCodeViewerStore.getState().currentFile as FileContent | null)
        ?.content.split('\n');
      const lineText = lines?.[lineIndex] ?? '';

      // Check if this is an import line with a relative path
      const importMatch = lineText.match(/from\s+['"](\.[^'"]+)['"]/);
      if (importMatch) {
        const importPath = importMatch[1];
        const resolvedPath = resolveImportPath(filePath, importPath);
        if (resolvedPath) {
          loadFile(resolvedPath);
          return;
        }
      }

      // Otherwise, search for symbol references
      const ext = filePath.includes('.') ? filePath.split('.').pop() : undefined;
      searchSymbols(identifier, ext);
    },
    [filePath, workspace, loadFile, searchSymbols],
  );
}

/**
 * Resolve a relative import path against the current file's directory.
 * Returns a normalized relative path or null.
 */
function resolveImportPath(currentFilePath: string, importPath: string): string | null {
  if (!importPath.startsWith('.')) return null;

  const dir = currentFilePath.includes('/')
    ? currentFilePath.substring(0, currentFilePath.lastIndexOf('/'))
    : '';

  const parts = (dir + '/' + importPath).split('/').filter(Boolean);
  const resolved: string[] = [];

  for (const part of parts) {
    if (part === '..') {
      if (resolved.length === 0) return null;
      resolved.pop();
    } else if (part !== '.') {
      resolved.push(part);
    }
  }

  return resolved.join('/');
}
