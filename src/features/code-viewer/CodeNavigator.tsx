import { useCallback } from 'react';
import { Loader2, X } from 'lucide-react';
import { useCodeViewerStore, type SearchMatch } from '@/stores/code-viewer';
import { cn } from '@/lib/utils';

interface CodeNavigatorProps {
  workspace: string;
  currentFilePath: string;
}

export function CodeNavigator({ workspace, currentFilePath }: CodeNavigatorProps) {
  const searchResults = useCodeViewerStore((s) => s.searchResults);
  const searching = useCodeViewerStore((s) => s.searching);
  const searchSymbol = useCodeViewerStore((s) => s.searchSymbol);
  const clearSearch = useCodeViewerStore((s) => s.clearSearch);
  const loadFile = useCodeViewerStore((s) => s.loadFile);

  // Nothing to show
  if (!searching && searchResults.length === 0 && !searchSymbol) {
    return null;
  }

  const handleMatchClick = useCallback(
    (match: SearchMatch) => {
      useCodeViewerStore.setState({ lineNumber: match.line });
      loadFile(match.filePath);
    },
    [loadFile],
  );

  const hasSearched = searchSymbol !== '' && !searching && searchResults.length === 0;

  return (
    <div className="absolute bottom-4 right-4 w-80 max-h-64 bg-[hsl(var(--card))] border border-[hsl(var(--border))] rounded-lg shadow-lg z-10 flex flex-col">
      {/* Header */}
      <div className="flex items-center justify-between px-3 py-2 border-b border-[hsl(var(--border))] shrink-0">
        <span className="text-[13px] font-medium truncate">
          {searching ? '搜索中...' : `"${searchSymbol}" 的引用`}
        </span>
        <button
          className="shrink-0 p-0.5 rounded hover:bg-[hsl(var(--muted))] transition-colors text-muted-foreground"
          onClick={clearSearch}
        >
          <X className="h-3.5 w-3.5" />
        </button>
      </div>

      {/* Body */}
      {searching ? (
        <div className="flex items-center justify-center gap-2 py-6 text-[13px] text-muted-foreground">
          <Loader2 className="h-4 w-4 animate-spin" />
          搜索中...
        </div>
      ) : hasSearched ? (
        <div className="py-6 text-center text-[13px] text-muted-foreground">未找到匹配</div>
      ) : (
        <div className="overflow-y-auto flex-1 min-h-0">
          {searchResults.map((match, i) => (
            <MatchItem
              key={`${match.filePath}:${match.line}:${i}`}
              match={match}
              isCurrentFile={match.filePath === currentFilePath}
              onClick={handleMatchClick}
            />
          ))}
        </div>
      )}
    </div>
  );
}

// ── MatchItem ──

interface MatchItemProps {
  match: SearchMatch;
  isCurrentFile: boolean;
  onClick: (match: SearchMatch) => void;
}

function MatchItem({ match, isCurrentFile, onClick }: MatchItemProps) {
  const handleClick = useCallback(() => {
    onClick(match);
  }, [match, onClick]);

  return (
    <button
      className={cn(
        'w-full text-left px-3 py-1.5 hover:bg-[hsl(var(--muted))] transition-colors',
        'border-b border-[hsl(var(--border))]/50 last:border-b-0',
      )}
      onClick={handleClick}
    >
      {/* File path + line number */}
      <div className="flex items-center gap-1.5 text-[12px]">
        <span className="truncate flex-1 min-w-0 text-muted-foreground">
          {match.filePath}
        </span>
        <span className="shrink-0 text-muted-foreground/60">:{match.line}</span>
        {isCurrentFile && (
          <span className="shrink-0 text-[10px] px-1 py-0.5 rounded bg-[hsl(var(--accent))] text-[hsl(var(--accent-foreground))]">
            当前
          </span>
        )}
      </div>

      {/* Line content preview */}
      {match.lineContent && (
        <div className="text-[12px] text-[hsl(var(--foreground))]/70 truncate mt-0.5 font-mono">
          {match.lineContent.trim()}
        </div>
      )}
    </button>
  );
}
