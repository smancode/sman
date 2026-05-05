import { memo, useCallback, useEffect, useRef, useState } from 'react';
import {
  ChevronDown,
  ChevronRight,
  File,
  Folder,
  FolderOpen,
  Search,
  Loader2,
  X,
} from 'lucide-react';
import { ScrollArea } from '@/components/ui/scroll-area';
import { useCodeViewerStore, type DirEntry } from '@/stores/code-viewer';
import { cn } from '@/lib/utils';

// ── TreeNode ──

interface TreeNodeProps {
  name: string;
  type: 'file' | 'directory';
  relativePath: string;
  depth: number;
  activeFilePath: string;
  onSelect: (filePath: string) => void;
}

const TreeNode = memo(function TreeNode({
  name,
  type,
  relativePath,
  depth,
  activeFilePath,
  onSelect,
}: TreeNodeProps) {
  const loadDir = useCodeViewerStore((s) => s.loadDir);
  const dirCache = useCodeViewerStore((s) => s.dirCache);
  const [expanded, setExpanded] = useState(false);
  const [children, setChildren] = useState<DirEntry[]>([]);
  const [loading, setLoading] = useState(false);

  const isActive = type === 'file' && relativePath === activeFilePath;

  const handleToggle = useCallback(() => {
    if (type !== 'directory') return;

    if (expanded) {
      setExpanded(false);
      return;
    }

    if (children.length > 0) {
      setExpanded(true);
      return;
    }

    setLoading(true);
    loadDir(relativePath)
      .then((entries) => {
        const sorted = [...entries].sort((a, b) => {
          if (a.type !== b.type) return a.type === 'directory' ? -1 : 1;
          return a.name.localeCompare(b.name);
        });
        setChildren(sorted);
        setExpanded(true);
      })
      .catch((err) => {
        console.error('[FileTree] Failed to load dir:', relativePath, err);
      })
      .finally(() => {
        setLoading(false);
      });
  }, [type, expanded, children.length, loadDir, relativePath]);

  // Auto-expand: fires on mount AND whenever dirCache changes (pre-loaded cache triggers expansion)
  useEffect(() => {
    if (type !== 'directory' || expanded) return;
    if (!activeFilePath.startsWith(relativePath + '/')) return;

    const cached = dirCache[relativePath];
    if (cached && cached.length > 0) {
      // Cache hit — set children and expand directly, no WS needed
      const sorted = [...cached].sort((a, b) => {
        if (a.type !== b.type) return a.type === 'directory' ? -1 : 1;
        return a.name.localeCompare(b.name);
      });
      setChildren(sorted);
      setExpanded(true);
    } else {
      // No cache — load via WS
      handleToggle();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeFilePath, dirCache]);

  const handleClick = useCallback(() => {
    if (type === 'file') {
      onSelect(relativePath);
    } else {
      handleToggle();
    }
  }, [type, onSelect, relativePath, handleToggle]);

  return (
    <div>
      <div
        className={cn(
          'flex items-center gap-1 py-1 pr-2 cursor-pointer text-[13px] transition-colors duration-150',
          isActive
            ? 'bg-[hsl(var(--accent))] text-[hsl(var(--accent-foreground))]'
            : 'hover:bg-[hsl(var(--muted))] text-[hsl(var(--foreground))]/80',
        )}
        style={{ paddingLeft: depth * 16 + 8 }}
        onClick={handleClick}
        title={relativePath}
        {...(isActive && type === 'file' ? { 'data-file-active': 'true' as any } : {})}
      >
        {/* Chevron or spacer */}
        {type === 'directory' ? (
          expanded ? (
            <ChevronDown className="h-3.5 w-3.5 shrink-0 text-muted-foreground" />
          ) : (
            <ChevronRight className="h-3.5 w-3.5 shrink-0 text-muted-foreground" />
          )
        ) : (
          <span className="w-3.5 shrink-0" />
        )}

        {/* Icon */}
        {type === 'directory' ? (
          expanded ? (
            <FolderOpen className="h-3.5 w-3.5 shrink-0 text-muted-foreground" />
          ) : (
            <Folder className="h-3.5 w-3.5 shrink-0 text-muted-foreground" />
          )
        ) : (
          <File className="h-3.5 w-3.5 shrink-0 text-muted-foreground" />
        )}

        {/* Name */}
        <span className="truncate flex-1 min-w-0">{name}</span>

        {/* Loading indicator */}
        {loading && (
          <span className="text-[10px] text-muted-foreground shrink-0">...</span>
        )}
      </div>

      {/* Children */}
      {expanded && children.length > 0 && (
        <div>
          {children.map((child) => (
            <TreeNode
              key={child.name}
              name={child.name}
              type={child.type}
              relativePath={relativePath ? `${relativePath}/${child.name}` : child.name}
              depth={depth + 1}
              activeFilePath={activeFilePath}
              onSelect={onSelect}
            />
          ))}
        </div>
      )}
    </div>
  );
});

// ── FileSearch ──

function FileSearchInput({ onSelect }: { onSelect: (filePath: string) => void }) {
  const [query, setQuery] = useState('');
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const searchFiles = useCodeViewerStore((s) => s.searchFiles);
  const clearFileSearch = useCodeViewerStore((s) => s.clearFileSearch);
  const fileSearchQuery = useCodeViewerStore((s) => s.fileSearchQuery);
  const fileSearchResults = useCodeViewerStore((s) => s.fileSearchResults);
  const fileSearching = useCodeViewerStore((s) => s.fileSearching);
  const sourceOnly = useCodeViewerStore((s) => s.fileSearchSourceOnly);
  const setFileSearchSourceOnly = useCodeViewerStore((s) => s.setFileSearchSourceOnly);

  const handleChange = useCallback((value: string) => {
    setQuery(value);

    if (timerRef.current) clearTimeout(timerRef.current);

    if (!value.trim()) {
      clearFileSearch();
      return;
    }

    // Debounce 500ms
    timerRef.current = setTimeout(() => {
      searchFiles(value);
    }, 500);
  }, [searchFiles, clearFileSearch]);

  const handleClear = useCallback(() => {
    setQuery('');
    clearFileSearch();
    inputRef.current?.focus();
  }, [clearFileSearch]);

  // Cleanup on unmount
  useEffect(() => {
    return () => { if (timerRef.current) clearTimeout(timerRef.current); };
  }, []);

  // Re-search when sourceOnly changes and there's a query
  useEffect(() => {
    if (query.trim()) searchFiles(query);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sourceOnly]);

  const isSearching = fileSearching;
  const hasResults = fileSearchResults.length > 0;
  const showResults = fileSearchQuery && query.trim();

  return (
    <div className="flex flex-col border-b border-[hsl(var(--border))]">
      <div className="flex items-center gap-1.5 px-2 py-1.5">
        <Search className="w-3.5 h-3.5 shrink-0 text-muted-foreground" />
        <input
          ref={inputRef}
          value={query}
          onChange={(e) => handleChange(e.target.value)}
          placeholder="搜索文件..."
          className="flex-1 min-w-0 bg-transparent text-[12px] outline-none placeholder:text-muted-foreground/50"
        />
        {isSearching && <Loader2 className="w-3 h-3 shrink-0 animate-spin text-muted-foreground" />}
        {query && !isSearching && (
          <button onClick={handleClear} className="shrink-0 p-0.5 rounded hover:bg-[hsl(var(--muted))] transition-colors">
            <X className="w-3 h-3 text-muted-foreground" />
          </button>
        )}
        <label className="flex items-center gap-1 shrink-0 cursor-pointer select-none" title="过滤测试、构建等非源码目录">
          <input
            type="checkbox"
            checked={sourceOnly}
            onChange={(e) => setFileSearchSourceOnly(e.target.checked)}
            className="w-3 h-3 accent-[hsl(var(--primary))]"
          />
          <span className="text-[10px] text-muted-foreground whitespace-nowrap">只搜源码</span>
        </label>
      </div>
      {showResults && (
        <ScrollArea className="max-h-[200px]">
          {hasResults ? (
            fileSearchResults.map((result) => (
              <button
                key={result.filePath}
                onClick={() => {
                  onSelect(result.filePath);
                  handleClear();
                }}
                className="w-full text-left flex items-center gap-1.5 px-3 py-1 text-[12px] hover:bg-[hsl(var(--muted))] transition-colors"
                title={result.filePath}
              >
                <File className="w-3 h-3 shrink-0 text-muted-foreground" />
                <span className="truncate flex-1 min-w-0 font-medium">{result.fileName}</span>
                <span className="shrink-0 text-[10px] text-muted-foreground/60 truncate max-w-[100px]">
                  {result.filePath.replace(/[^/]+$/, '')}
                </span>
              </button>
            ))
          ) : !isSearching ? (
            <div className="px-3 py-2 text-[11px] text-muted-foreground text-center">
              无匹配文件
            </div>
          ) : null}
        </ScrollArea>
      )}
    </div>
  );
}

// ── FileTree ──

interface FileTreeProps {
  workspace: string;
  activeFilePath: string;
  onSelectFile: (filePath: string) => void;
  onClose: () => void;
}

export function FileTree({ workspace, activeFilePath, onSelectFile, onClose }: FileTreeProps) {
  const loadDir = useCodeViewerStore((s) => s.loadDir);
  const dirCache = useCodeViewerStore((s) => s.dirCache);
  const [rootEntries, setRootEntries] = useState<DirEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // Scroll active file into view when it changes or dirCache updates (tree expands)
  useEffect(() => {
    if (!activeFilePath) return;
    const timer = setTimeout(() => {
      const el = document.querySelector('[data-file-active="true"]');
      if (el) {
        el.scrollIntoView({ block: 'center', behavior: 'smooth' });
      }
    }, 300);
    return () => clearTimeout(timer);
  }, [activeFilePath, dirCache]);

  // Load root dir on mount or workspace change
  useEffect(() => {
    if (!workspace) return;

    setLoading(true);
    setError(null);

    loadDir('')
      .then((entries) => {
        const sorted = [...entries].sort((a, b) => {
          if (a.type !== b.type) return a.type === 'directory' ? -1 : 1;
          return a.name.localeCompare(b.name);
        });
        setRootEntries(sorted);
      })
      .catch((err) => {
        console.error('[FileTree] Failed to load root dir:', err);
        setError(err instanceof Error ? err.message : String(err));
      })
      .finally(() => {
        setLoading(false);
      });
  }, [workspace, loadDir]);

  const workspaceName = workspace.split(/[/\\]/).pop() || workspace;

  return (
    <div className="flex flex-col h-full border-r border-[hsl(var(--border))]">
      {/* Header */}
      <div className="flex items-center gap-2 px-3 py-2 border-b border-[hsl(var(--border))] shrink-0">
        {(() => {
          const isMac = window.sman?.platform === 'darwin' || (!window.sman && navigator.platform?.includes('Mac'));
          return isMac ? <div className="w-[52px] shrink-0" /> : null;
        })()}
        <button
          className="text-[13px] text-muted-foreground hover:text-[hsl(var(--foreground))] transition-colors"
          onClick={onClose}
        >
          &larr; 返回会话
        </button>
        <span className="text-[13px] text-muted-foreground/60">|</span>
        <span className="text-[13px] font-medium truncate">{workspaceName}</span>
      </div>

      {/* File search */}
      <FileSearchInput onSelect={onSelectFile} />

      {/* Tree content */}
      <ScrollArea className="flex-1 min-h-0">
        <div className="py-1">
          {loading && rootEntries.length === 0 ? (
            <div className="text-center py-8 text-[13px] text-muted-foreground">
              加载中...
            </div>
          ) : error ? (
            <div className="text-center py-8 px-4 text-[13px] text-destructive">
              <p>加载失败</p>
              <p className="text-xs mt-1">{error}</p>
            </div>
          ) : rootEntries.length === 0 ? (
            <div className="text-center py-8 px-4 text-[13px] text-muted-foreground">
              <p>空目录</p>
            </div>
          ) : (
            rootEntries.map((entry) => (
              <TreeNode
                key={entry.name}
                name={entry.name}
                type={entry.type}
                relativePath={entry.name}
                depth={0}
                activeFilePath={activeFilePath}
                onSelect={onSelectFile}
              />
            ))
          )}
        </div>
      </ScrollArea>
    </div>
  );
}
