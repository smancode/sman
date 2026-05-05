/**
 * CodePanel — right side of the code viewer overlay.
 *
 * Shows file content with Shiki syntax highlighting, line numbers,
 * highlighted line, and Ctrl+Click navigation.
 */

import { memo, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { ThemedToken } from 'shiki';
import { Loader2, AlertTriangle, FileQuestion, ChevronUp, ChevronDown } from 'lucide-react';
import { highlightCode } from '@/lib/shiki-worker-client';
import { useCodeViewerStore, type FileContent, type BinaryFileInfo } from '@/stores/code-viewer';
import { cn } from '@/lib/utils';
import { CodeNavigator } from './CodeNavigator';

// ── Constants ──────────────────────────────────────────────────────

const THEMES: [string, string] = ['one-light', 'one-dark-pro'];

function getIsDark(): boolean {
  return document.documentElement.classList.contains('dark');
}

function getTokenColor(token: ThemedToken, isDark: boolean): string | undefined {
  if (token.htmlStyle) {
    if (isDark) {
      const dark = token.htmlStyle['--shiki-dark'];
      if (dark) return dark;
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
      <div className="flex-1 min-h-0 flex items-center justify-center">
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
      <div className="flex-1 min-h-0 flex items-center justify-center">
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
      <div className="flex-1 min-h-0 flex items-center justify-center">
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
    <div className="flex-1 min-h-0 flex items-center justify-center">
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
  const [searchQuery, setSearchQuery] = useState('');
  const [matchIndex, setMatchIndex] = useState(-1);
  const containerRef = useRef<HTMLDivElement>(null);
  const highlightLineRef = useRef<HTMLDivElement>(null);
  const matchRefs = useRef<(HTMLSpanElement | null)[]>([]);

  const filePath = file.path;
  const language = file.language;
  const lines = useMemo(() => file.content.split('\n'), [file.content]);

  // Compute search matches
  const { matches, totalMatches } = useMemo(() => {
    if (!searchQuery) return { matches: [] as number[], totalMatches: 0 };
    const query = searchQuery.toLowerCase();
    const ms: number[] = [];
    lines.forEach((line, i) => {
      if (line.toLowerCase().includes(query)) ms.push(i);
    });
    return { matches: ms, totalMatches: ms.length };
  }, [searchQuery, lines]);

  // Reset match index when search changes
  useEffect(() => {
    if (matches.length > 0) {
      setMatchIndex(0);
    } else {
      setMatchIndex(-1);
    }
  }, [matches.length]);

  // Scroll to current match
  useEffect(() => {
    if (matchIndex >= 0 && matchRefs.current[matchIndex]) {
      matchRefs.current[matchIndex]?.scrollIntoView({ block: 'center', behavior: 'smooth' });
    }
  }, [matchIndex]);

  const handlePrev = useCallback(() => {
    if (matches.length === 0) return;
    setMatchIndex((prev) => (prev <= 0 ? matches.length - 1 : prev - 1));
  }, [matches.length]);

  const handleNext = useCallback(() => {
    if (matches.length === 0) return;
    setMatchIndex((prev) => (prev >= matches.length - 1 ? 0 : prev + 1));
  }, [matches.length]);

  // Keyboard shortcut: F3 / Shift+F3
  useEffect(() => {
    const handleKey = (e: KeyboardEvent) => {
      if (e.key === 'F3') {
        e.preventDefault();
        if (e.shiftKey) handlePrev();
        else handleNext();
      }
    };
    window.addEventListener('keydown', handleKey);
    return () => window.removeEventListener('keydown', handleKey);
  }, [handlePrev, handleNext]);

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
    <div className="flex flex-col h-full min-h-0 relative">
      {/* File header */}
      <FileHeader
        filePath={filePath}
        language={language}
        lineCount={file.totalLines}
        truncated={file.truncated}
      />

      {/* Search bar */}
      <SearchBar
        query={searchQuery}
        onChange={setSearchQuery}
        onPrev={handlePrev}
        onNext={handleNext}
        currentMatch={matchIndex >= 0 ? matchIndex + 1 : 0}
        totalMatches={totalMatches}
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
                searchQuery={searchQuery}
                isSearchMatch={matches.includes(i)}
                isCurrentMatch={matchIndex >= 0 && matches[matchIndex] === i}
                matchRef={(el) => { matchRefs.current[matches.indexOf(i)] = el; }}
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
                searchQuery={searchQuery}
                isSearchMatch={matches.includes(i)}
                isCurrentMatch={matchIndex >= 0 && matches[matchIndex] === i}
                matchRef={(el) => { matchRefs.current[matches.indexOf(i)] = el; }}
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

// ── SearchBar ──────────────────────────────────────────────────────

interface SearchBarProps {
  query: string;
  onChange: (q: string) => void;
  onPrev: () => void;
  onNext: () => void;
  currentMatch: number;
  totalMatches: number;
}

const SearchBar = memo(function SearchBar({
  query,
  onChange,
  onPrev,
  onNext,
  currentMatch,
  totalMatches,
}: SearchBarProps) {
  const inputRef = useRef<HTMLInputElement>(null);

  // Ctrl+F to focus search
  useEffect(() => {
    const handleKey = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key === 'f') {
        e.preventDefault();
        inputRef.current?.focus();
      }
    };
    window.addEventListener('keydown', handleKey);
    return () => window.removeEventListener('keydown', handleKey);
  }, []);

  return (
    <div className="flex items-center gap-2 px-4 py-1.5 border-b border-[hsl(var(--border))] shrink-0 bg-[hsl(var(--card))]">
      <span className="text-[12px] text-muted-foreground shrink-0">查找</span>
      <input
        ref={inputRef}
        type="text"
        value={query}
        onChange={(e) => onChange(e.target.value)}
        className="w-48 px-2 py-0.5 text-[12px] rounded border border-[hsl(var(--border))] bg-[hsl(var(--background))] text-[hsl(var(--foreground))] focus:outline-none focus:ring-1 focus:ring-[hsl(var(--ring))]"
        placeholder="输入搜索内容..."
      />
      <button
        onClick={onPrev}
        disabled={totalMatches === 0}
        className="flex items-center gap-0.5 px-2 py-0.5 text-[12px] rounded border border-[hsl(var(--border))] hover:bg-[hsl(var(--muted))] disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
        title="上一个 (Shift+F3)"
      >
        <ChevronUp className="h-3 w-3" />
        上一个
      </button>
      <button
        onClick={onNext}
        disabled={totalMatches === 0}
        className="flex items-center gap-0.5 px-2 py-0.5 text-[12px] rounded border border-[hsl(var(--border))] hover:bg-[hsl(var(--muted))] disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
        title="下一个 (F3)"
      >
        <ChevronDown className="h-3 w-3" />
        下一个
      </button>
      <span className="text-[12px] text-muted-foreground shrink-0 min-w-[3rem] text-right">
        {totalMatches > 0 ? `${currentMatch} / ${totalMatches}` : '0 / 0'}
      </span>
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
  searchQuery: string;
  isSearchMatch: boolean;
  isCurrentMatch: boolean;
  matchRef: (el: HTMLSpanElement | null) => void;
}

const TokenLine = memo(function TokenLine({
  lineIndex,
  tokens,
  isDark,
  isHighlighted,
  highlightRef,
  onTokenClick,
  searchQuery,
  isSearchMatch,
  isCurrentMatch,
  matchRef,
}: TokenLineProps) {
  const lineNum = lineIndex + 1;

  const renderTokens = () => {
    if (tokens.length === 0) return '\n';
    if (!searchQuery) {
      return tokens.map((token, ti) => (
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
      ));
    }

    // Highlight search matches within tokens
    const parts: React.ReactNode[] = [];
    let key = 0;
    const query = searchQuery.toLowerCase();

    for (const token of tokens) {
      const content = token.content;
      let remaining = content;
      let pos = 0;

      while (remaining.length > 0) {
        const idx = remaining.toLowerCase().indexOf(query);
        if (idx === -1) {
          parts.push(
            <span
              key={key++}
              style={{ color: getTokenColor({ ...token, content: remaining }, isDark) }}
              className="cursor-text"
              onClick={(e) => {
                if (e.ctrlKey || e.metaKey) {
                  onTokenClick(e, remaining, lineIndex);
                }
              }}
            >
              {remaining}
            </span>
          );
          break;
        }

        if (idx > 0) {
          const before = remaining.slice(0, idx);
          parts.push(
            <span
              key={key++}
              style={{ color: getTokenColor({ ...token, content: before }, isDark) }}
              className="cursor-text"
              onClick={(e) => {
                if (e.ctrlKey || e.metaKey) {
                  onTokenClick(e, before, lineIndex);
                }
              }}
            >
              {before}
            </span>
          );
        }

        const match = remaining.slice(idx, idx + query.length);
        const isCurrent = isCurrentMatch && idx === 0; // simplified: first match in line
        parts.push(
          <span
            key={key++}
            ref={isCurrent ? matchRef : undefined}
            className={cn(
              'rounded-sm px-0.5',
              isCurrent
                ? 'bg-amber-400/40 text-[hsl(var(--foreground))]'
                : 'bg-yellow-300/30 text-[hsl(var(--foreground))]'
            )}
          >
            {match}
          </span>
        );

        remaining = remaining.slice(idx + query.length);
        pos += idx + query.length;
      }
    }

    return parts;
  };

  return (
    <div
      ref={highlightRef}
      className={cn(
        'flex hover:bg-[hsl(var(--muted))]/30',
        isHighlighted && (isDark
          ? 'bg-[rgba(31,111,235,0.15)]'
          : 'bg-[rgba(84,174,255,0.15)]'),
        isSearchMatch && !isHighlighted && 'bg-yellow-100/30 dark:bg-yellow-900/20',
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
        className="flex-1 min-w-0"
        style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-all' }}
      >
        {renderTokens()}
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
  searchQuery: string;
  isSearchMatch: boolean;
  isCurrentMatch: boolean;
  matchRef: (el: HTMLSpanElement | null) => void;
}

const PlainLine = memo(function PlainLine({
  lineIndex,
  text,
  isHighlighted,
  highlightRef,
  searchQuery,
  isSearchMatch,
  isCurrentMatch,
  matchRef,
}: PlainLineProps) {
  const lineNum = lineIndex + 1;

  const renderText = () => {
    if (!searchQuery) return text;

    const parts: React.ReactNode[] = [];
    let key = 0;
    const query = searchQuery.toLowerCase();
    let remaining = text;
    let foundCurrent = false;

    while (remaining.length > 0) {
      const idx = remaining.toLowerCase().indexOf(query);
      if (idx === -1) {
        parts.push(<span key={key++}>{remaining}</span>);
        break;
      }

      if (idx > 0) {
        parts.push(<span key={key++}>{remaining.slice(0, idx)}</span>);
      }

      const match = remaining.slice(idx, idx + query.length);
      const isCurrent = isCurrentMatch && !foundCurrent;
      if (isCurrent) foundCurrent = true;

      parts.push(
        <span
          key={key++}
          ref={isCurrent ? matchRef : undefined}
          className={cn(
            'rounded-sm px-0.5',
            isCurrent
              ? 'bg-amber-400/40 text-[hsl(var(--foreground))]'
              : 'bg-yellow-300/30 text-[hsl(var(--foreground))]'
          )}
        >
          {match}
        </span>
      );

      remaining = remaining.slice(idx + query.length);
    }

    return parts;
  };

  return (
    <div
      ref={highlightRef}
      className={cn(
        'flex hover:bg-[hsl(var(--muted))]/30',
        isHighlighted && 'bg-[rgba(31,111,235,0.15)]',
        isSearchMatch && !isHighlighted && 'bg-yellow-100/30 dark:bg-yellow-900/20',
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
        {renderText()}
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
    [filePath, loadFile, searchSymbols],
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
