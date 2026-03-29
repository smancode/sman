import { useState, useEffect, useCallback } from 'react';
import { FolderOpen, ChevronRight, ChevronDown, Loader2, Home } from 'lucide-react';
import { cn } from '@/lib/utils';
import { Button } from '@/components/ui/button';
import { authFetch } from '@/lib/auth';

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
    const parentPath = currentPath.split('/').slice(0, -1).join('/') || '/';
    fetchDirectory(parentPath);
  };

  // Check if we're at root
  const isAtRoot = currentPath === '/' || currentPath === '';

  if (!open) return null;

  return (
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
            <span className="font-medium">选择业务系统目录</span>
          </div>
          <Button variant="ghost" size="sm" onClick={handleCancel}>
            ✕
          </Button>
        </div>

        {/* Path breadcrumb */}
        <div className="flex items-center gap-1 px-4 py-2 bg-muted/30 text-sm border-b truncate">
          <Home className="h-3 w-3 shrink-0" />
          <span className="truncate">{currentPath}</span>
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
              空目录
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
            取消
          </Button>
          <Button size="sm" onClick={handleSelect}>
            选择此目录
          </Button>
        </div>
      </div>
    </div>
  );
}
