import { memo, useCallback, useEffect, useState } from 'react';
import {
  ChevronDown,
  ChevronRight,
  File,
  Folder,
  FolderOpen,
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
        // Directories first, then files, alphabetical within each group
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

  // Auto-expand folders that contain the active file
  useEffect(() => {
    if (type === 'directory' && !expanded && activeFilePath.startsWith(relativePath + '/')) {
      handleToggle();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeFilePath]);

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

// ── FileTree ──

interface FileTreeProps {
  workspace: string;
  activeFilePath: string;
  onSelectFile: (filePath: string) => void;
  onClose: () => void;
}

export function FileTree({ workspace, activeFilePath, onSelectFile, onClose }: FileTreeProps) {
  const loadDir = useCodeViewerStore((s) => s.loadDir);
  const [rootEntries, setRootEntries] = useState<DirEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

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
        <button
          className="text-[13px] text-muted-foreground hover:text-[hsl(var(--foreground))] transition-colors"
          onClick={onClose}
        >
          &larr; 返回会话
        </button>
        <span className="text-[13px] text-muted-foreground/60">|</span>
        <span className="text-[13px] font-medium truncate">{workspaceName}</span>
      </div>

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
