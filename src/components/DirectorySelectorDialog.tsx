import { useState, useEffect, useCallback, useRef } from 'react';
import { createPortal } from 'react-dom';
import { FolderOpen, ChevronRight, Loader2, Pencil } from 'lucide-react';
import { cn } from '@/lib/utils';
import { Button } from '@/components/ui/button';
import { authFetch } from '@/lib/auth';
import { t } from '@/locales';

interface DirectoryEntry {
  name: string;
  path: string;
  isDirectory: boolean;
}

interface DirectorySelectorDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onSelect: (dirPath: string) => void;
  initialPath?: string;
}

/**
 * Get parent directory path (cross-platform).
 * Handles both POSIX (/a/b/c) and Windows (C:\Users\xxx) paths.
 */
function getParentPath(path: string): string | null {
  if (!path) return null;
  // Windows drive root: C:\ or C:/
  if (/^[A-Za-z]:\\?$/.test(path)) return null;
  // POSIX root
  if (path === '/') return null;
  // Find the last separator (support both / and \)
  const lastSlash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
  if (lastSlash <= 0) return null;
  // Windows: C:\Users -> C:\ (keep trailing backslash for drive root)
  const parent = path.substring(0, lastSlash);
  if (/^[A-Za-z]:$/.test(parent)) return parent + '\\';
  return parent || '/';
}

export function DirectorySelectorDialog({
  open,
  onOpenChange,
  onSelect,
  initialPath = '/Users',
}: DirectorySelectorDialogProps) {
  const [currentPath, setCurrentPath] = useState(initialPath);
  const [entries, setEntries] = useState<DirectoryEntry[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Address bar editing state
  const [isEditing, setIsEditing] = useState(false);
  const [editValue, setEditValue] = useState('');
  const inputRef = useRef<HTMLInputElement>(null);

  // Fetch directory contents
  const fetchDirectory = useCallback(async (dirPath: string) => {
    setLoading(true);
    setError(null);
    try {
      const response = await authFetch(`/api/directory/read?path=${encodeURIComponent(dirPath)}`);
      if (!response.ok) {
        const data = await response.json();
        throw new Error(data.error || 'Failed to read directory');
      }
      const data = await response.json();
      setEntries(data.entries);
      setCurrentPath(dirPath);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Unknown error');
      setEntries([]);
    } finally {
      setLoading(false);
    }
  }, []);

  // Fetch initial directory when opened
  useEffect(() => {
    if (open) {
      setIsEditing(false);
      // Try to get home directory first
      authFetch('/api/directory/home')
        .then(res => res.json())
        .then(data => {
          const home = data.home || initialPath;
          fetchDirectory(home);
        })
        .catch(() => fetchDirectory(initialPath));
    }
  }, [open, initialPath, fetchDirectory]);

  // Auto-focus input when entering edit mode
  useEffect(() => {
    if (isEditing && inputRef.current) {
      inputRef.current.focus();
      inputRef.current.select();
    }
  }, [isEditing]);

  // Handle directory click
  const handleEntryClick = async (entry: DirectoryEntry) => {
    if (entry.isDirectory) {
      fetchDirectory(entry.path);
    }
  };

  // Handle select button
  const handleSelect = () => {
    onSelect(currentPath);
    onOpenChange(false);
  };

  // Handle cancel
  const handleCancel = () => {
    onOpenChange(false);
  };

  // Navigate to parent directory
  const navigateUp = () => {
    const parentPath = getParentPath(currentPath);
    if (parentPath !== null) {
      fetchDirectory(parentPath);
    }
  };

  // Start editing the address bar
  const startEditing = () => {
    setEditValue(currentPath);
    setIsEditing(true);
  };

  // Commit the edited path (Enter key or blur)
  const commitEdit = () => {
    setIsEditing(false);
    const trimmed = editValue.trim();
    if (trimmed && trimmed !== currentPath) {
      fetchDirectory(trimmed);
    }
  };

  // Cancel editing (Escape key)
  const cancelEdit = () => {
    setIsEditing(false);
  };

  // Check if we're at root (POSIX or Windows drive root)
  const isAtRoot = currentPath === '/' || currentPath === '' || /^[A-Za-z]:\\?$/.test(currentPath);

  if (!open) return null;

  return createPortal(
    <div className="fixed inset-0 z-50 flex items-center justify-center">
      {/* Backdrop */}
      <div
        className="absolute inset-0 bg-black/50"
        onClick={handleCancel}
      />

      {/* Dialog */}
      <div className="relative bg-background rounded-lg shadow-lg border w-[500px] max-h-[70vh] flex flex-col">
        {/* Header */}
        <div className="flex items-center justify-between px-4 py-3 border-b">
          <div className="flex items-center gap-2">
            <FolderOpen className="h-5 w-5 text-muted-foreground" />
            <span className="font-medium">{t('dir.selectDir')}</span>
          </div>
          <Button variant="ghost" size="sm" onClick={handleCancel}>
            ✕
          </Button>
        </div>

        {/* Address bar */}
        <div className="flex items-center gap-1.5 px-4 py-1.5 bg-muted/30 border-b">
          <FolderOpen className="h-3.5 w-3.5 shrink-0 text-muted-foreground" />
          {isEditing ? (
            <input
              ref={inputRef}
              type="text"
              value={editValue}
              onChange={(e) => setEditValue(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter') commitEdit();
                if (e.key === 'Escape') cancelEdit();
              }}
              onBlur={commitEdit}
              className="flex-1 bg-background border rounded px-2 py-1 text-sm font-mono outline-none focus:ring-1 focus:ring-ring"
              spellCheck={false}
            />
          ) : (
            <>
              <span
                className="flex-1 truncate text-sm font-mono cursor-default"
                onDoubleClick={startEditing}
                title={currentPath}
              >
                {currentPath}
              </span>
              <button
                onClick={startEditing}
                className="shrink-0 p-1 rounded hover:bg-muted/80 text-muted-foreground"
                title={t('dir.manualInput')}
              >
                <Pencil className="h-3 w-3" />
              </button>
            </>
          )}
        </div>

        {/* Directory listing */}
        <div className="flex-1 overflow-auto p-2 min-h-[300px]">
          {loading ? (
            <div className="flex items-center justify-center h-full">
              <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
            </div>
          ) : error ? (
            <div className="flex items-center justify-center h-full text-destructive text-sm">
              {error}
            </div>
          ) : entries.length === 0 ? (
            <div className="flex items-center justify-center h-full text-muted-foreground text-sm">
              {t('dir.emptyDir')}
            </div>
          ) : (
            <div className="space-y-0.5">
              {/* Navigate up button */}
              {!isAtRoot && (
                <button
                  onClick={navigateUp}
                  className={cn(
                    'w-full flex items-center gap-2 px-3 py-2 rounded-lg text-sm',
                    'hover:bg-muted/50 text-muted-foreground',
                    'text-left'
                  )}
                >
                  <ChevronRight className="h-4 w-4 rotate-180" />
                  <span>..</span>
                </button>
              )}

              {/* Directory entries */}
              {entries.map((entry) => (
                <button
                  key={entry.path}
                  onClick={() => handleEntryClick(entry)}
                  className={cn(
                    'w-full flex items-center gap-2 px-3 py-2 rounded-lg text-sm',
                    'hover:bg-muted/50',
                    entry.isDirectory ? 'text-foreground' : 'text-muted-foreground',
                    'text-left'
                  )}
                >
                  {entry.isDirectory ? (
                    <ChevronRight className="h-4 w-4 shrink-0" />
                  ) : (
                    <span className="h-4 w-4 shrink-0" />
                  )}
                  <span className="truncate">{entry.name}</span>
                </button>
              ))}
            </div>
          )}
        </div>

        {/* Footer */}
        <div className="flex items-center justify-end gap-2 px-4 py-3 border-t bg-muted/30">
          <Button variant="outline" size="sm" onClick={handleCancel}>
            {t('dir.cancel')}
          </Button>
          <Button size="sm" onClick={handleSelect}>
            {t('dir.confirm')}
          </Button>
        </div>
      </div>
    </div>,
    document.body,
  );
}
