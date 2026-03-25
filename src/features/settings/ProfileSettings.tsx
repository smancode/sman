import { useState, useEffect, useCallback } from 'react';
import { Briefcase, Plus, Trash2, FolderOpen, Edit2, X, Check, ChevronRight, Home, Loader2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { useBusinessSystemsStore } from '@/stores/business-systems';
import { cn } from '@/lib/utils';
import type { BusinessSystem, CreateBusinessSystemInput } from '@/types/business-system';

// Directory browser component for web mode
interface DirectoryEntry {
  name: string;
  path: string;
  isDirectory: boolean;
}

function DirectorySelector({
  open,
  onSelect,
  onCancel,
}: {
  open: boolean;
  onSelect: (path: string) => void;
  onCancel: () => void;
}) {
  const [currentPath, setCurrentPath] = useState('');
  const [entries, setEntries] = useState<DirectoryEntry[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Fetch home directory on mount
  useEffect(() => {
    if (open && !currentPath) {
      fetch('/api/directory/home')
        .then(res => res.json())
        .then(data => {
          if (data.home) {
            setCurrentPath(data.home);
          }
        })
        .catch(() => {
          // Fallback to /Users on macOS/Linux or C:\Users on Windows
          const platform = navigator.platform.toLowerCase();
          if (platform.includes('win')) {
            setCurrentPath('C:\\Users');
          } else {
            setCurrentPath('/Users');
          }
        });
    }
  }, [open, currentPath]);

  // Fetch directory contents when path changes
  useEffect(() => {
    if (!open || !currentPath) return;
    setLoading(true);
    setError(null);
    fetch(`/api/directory/read?path=${encodeURIComponent(currentPath)}`)
      .then(res => res.json())
      .then(data => {
        if (data.error) {
          throw new Error(data.message || data.error);
        }
        setEntries(data.entries || []);
      })
      .catch(err => {
        setError(err.message);
        setEntries([]);
      })
      .finally(() => setLoading(false));
  }, [open, currentPath]);

  const handleEntryClick = (entry: DirectoryEntry) => {
    if (entry.isDirectory) {
      setCurrentPath(entry.path);
    }
  };

  const navigateUp = () => {
    const sep = currentPath.includes('\\') ? '\\' : '/';
    const parts = currentPath.split(sep).filter(Boolean);
    if (parts.length > 1) {
      parts.pop();
      setCurrentPath(sep + parts.join(sep));
    } else if (parts.length === 1 && currentPath.includes(':')) {
      // Windows drive root
      setCurrentPath(parts[0] + ':\\');
    } else {
      setCurrentPath(sep);
    }
  };

  const isAtRoot = currentPath === '/' || currentPath === '' ||
    (currentPath.match(/^[A-Z]:\\?$/i) && !currentPath.includes('\\'));

  if (!open) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center">
      <div className="absolute inset-0 bg-black/50" onClick={onCancel} />
      <div className="relative bg-card rounded-lg shadow-lg border w-[500px] max-h-[70vh] flex flex-col">
        {/* Header */}
        <div className="flex items-center justify-between px-4 py-3 border-b">
          <div className="flex items-center gap-2">
            <FolderOpen className="h-5 w-5 text-muted-foreground" />
            <span className="font-medium">选择业务系统目录</span>
          </div>
          <Button variant="ghost" size="sm" onClick={onCancel}>
            ✕
          </Button>
        </div>

        {/* Path display */}
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
              {!isAtRoot && (
                <button
                  onClick={navigateUp}
                  className="w-full flex items-center gap-2 px-3 py-2 rounded-lg text-sm hover:bg-muted/50 text-muted-foreground text-left"
                >
                  <ChevronRight className="h-4 w-4 rotate-180" />
                  <span>..</span>
                </button>
              )}
              {entries.map((entry) => (
                <button
                  key={entry.path}
                  onClick={() => handleEntryClick(entry)}
                  className={cn(
                    'w-full flex items-center gap-2 px-3 py-2 rounded-lg text-sm hover:bg-muted/50 text-left',
                    entry.isDirectory ? 'text-foreground' : 'text-muted-foreground opacity-50'
                  )}
                  disabled={!entry.isDirectory}
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
          <Button variant="outline" size="sm" onClick={onCancel}>
            取消
          </Button>
          <Button size="sm" onClick={() => onSelect(currentPath)}>
            选择此目录
          </Button>
        </div>
      </div>
    </div>
  );
}

function ProfileForm({
  initial,
  onSave,
  onCancel,
}: {
  initial?: BusinessSystem;
  onSave: (input: CreateBusinessSystemInput) => void;
  onCancel: () => void;
}) {
  const [form, setForm] = useState<CreateBusinessSystemInput>({
    systemId: initial?.systemId || '',
    name: initial?.name || '',
    workspace: initial?.workspace || '',
    description: initial?.description || '',
    skills: initial?.skills || [],
    autoTriggers: initial?.autoTriggers || { onInit: [], onConversationStart: [] },
    claudeMdTemplate: initial?.claudeMdTemplate,
  });
  const [showDirSelector, setShowDirSelector] = useState(false);

  const handleSelectDir = async () => {
    // Try Electron native dialog first
    if (window.sman?.selectDirectory) {
      try {
        const dir = await window.sman.selectDirectory();
        if (dir) {
          const name = dir.split(/[/\\]/).pop() || dir;
          setForm((f) => ({
            ...f,
            workspace: dir,
            name: f.name || name,
            systemId: f.systemId || name.toLowerCase().replace(/[^a-z0-9]/g, '-'),
          }));
        }
        return;
      } catch (err) {
        console.warn('Electron dialog failed, falling back to web selector:', err);
      }
    }
    // Fallback to web directory selector
    setShowDirSelector(true);
  };

  const handleDirSelect = (dir: string) => {
    const name = dir.split(/[/\\]/).pop() || dir;
    setForm((f) => ({
      ...f,
      workspace: dir,
      name: f.name || name,
      systemId: f.systemId || name.toLowerCase().replace(/[^a-z0-9]/g, '-'),
    }));
    setShowDirSelector(false);
  };

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!form.systemId || !form.name || !form.workspace) return;
    onSave(form);
  };

  const isEdit = !!initial;

  return (
    <>
      <form onSubmit={handleSubmit} className="space-y-4 p-4 rounded-lg border bg-muted/30">
        <div className="grid grid-cols-2 gap-4">
          <div className="space-y-2">
            <Label>ID</Label>
            <Input
              value={form.systemId}
              onChange={(e) => setForm((f) => ({ ...f, systemId: e.target.value }))}
              placeholder="my-project"
              disabled={isEdit}
            />
          </div>
          <div className="space-y-2">
            <Label>名称</Label>
            <Input
              value={form.name}
              onChange={(e) => setForm((f) => ({ ...f, name: e.target.value }))}
              placeholder="我的项目"
            />
          </div>
        </div>

        <div className="space-y-2">
          <Label>工作目录</Label>
          <div className="flex gap-2">
            <Input
              value={form.workspace}
              onChange={(e) => setForm((f) => ({ ...f, workspace: e.target.value }))}
              placeholder="/path/to/project"
              className="flex-1"
            />
            <Button type="button" variant="outline" size="icon" onClick={handleSelectDir}>
              <FolderOpen className="h-4 w-4" />
            </Button>
          </div>
        </div>

        <div className="space-y-2">
          <Label>描述</Label>
          <Input
            value={form.description}
            onChange={(e) => setForm((f) => ({ ...f, description: e.target.value }))}
            placeholder="项目描述..."
          />
        </div>

        <div className="flex justify-end gap-2 pt-2">
          <Button type="button" variant="ghost" size="sm" onClick={onCancel}>
            <X className="h-4 w-4 mr-1" />
            取消
          </Button>
          <Button type="submit" size="sm">
            <Check className="h-4 w-4 mr-1" />
            {isEdit ? '保存' : '创建'}
          </Button>
        </div>
      </form>

      <DirectorySelector
        open={showDirSelector}
        onSelect={handleDirSelect}
        onCancel={() => setShowDirSelector(false)}
      />
    </>
  );
}

export function ProfileSettings() {
  const systems = useBusinessSystemsStore((s) => s.systems);
  const loading = useBusinessSystemsStore((s) => s.loading);
  const createSystem = useBusinessSystemsStore((s) => s.createSystem);
  const deleteSystem = useBusinessSystemsStore((s) => s.deleteSystem);

  const [showForm, setShowForm] = useState(false);
  const [editing, setEditing] = useState<BusinessSystem | null>(null);

  const handleCreate = async (input: CreateBusinessSystemInput) => {
    try {
      await createSystem(input);
      setShowForm(false);
    } catch (err) {
      console.error('Failed to create system:', err);
      alert(`创建失败: ${err instanceof Error ? err.message : String(err)}`);
    }
  };

  const handleDelete = async (systemId: string) => {
    if (!confirm('确定删除此业务系统？')) return;
    try {
      await deleteSystem(systemId);
    } catch (err) {
      console.error('Failed to delete system:', err);
      alert(`删除失败: ${err instanceof Error ? err.message : String(err)}`);
    }
  };

  return (
    <Card>
      <CardHeader>
        <div className="flex items-center justify-between">
          <div>
            <CardTitle className="flex items-center gap-2">
              <Briefcase className="h-5 w-5" />
              业务系统
            </CardTitle>
            <CardDescription>管理业务系统（Profile），每个系统对应一个工作目录</CardDescription>
          </div>
          <Button
            variant="outline"
            size="sm"
            onClick={() => setShowForm(!showForm)}
            disabled={loading}
          >
            <Plus className="h-4 w-4 mr-1" />
            新建
          </Button>
        </div>
      </CardHeader>
      <CardContent className="space-y-4">
        {showForm && (
          <ProfileForm
            onSave={handleCreate}
            onCancel={() => setShowForm(false)}
          />
        )}

        {systems.length === 0 && !showForm && (
          <div className="text-center py-8 text-muted-foreground">
            <Briefcase className="h-12 w-12 mx-auto mb-3 opacity-30" />
            <p>暂无业务系统</p>
            <p className="text-sm mt-1">点击上方"新建"创建第一个业务系统</p>
          </div>
        )}

        <div className="space-y-2">
          {systems.map((system) => (
            <div
              key={system.systemId}
              className="flex items-center justify-between p-3 rounded-lg border bg-card hover:bg-muted/50 transition-colors"
            >
              <div className="flex-1 min-w-0">
                <div className="flex items-center gap-2">
                  <span className="font-medium">{system.name}</span>
                  <span className="text-xs text-muted-foreground bg-muted px-1.5 py-0.5 rounded">
                    {system.systemId}
                  </span>
                </div>
                <p className="text-sm text-muted-foreground truncate mt-0.5">
                  {system.workspace}
                </p>
              </div>
              <div className="flex items-center gap-1 ml-2">
                <Button
                  variant="ghost"
                  size="icon"
                  className="h-8 w-8 text-muted-foreground hover:text-foreground"
                  onClick={() => setEditing(system)}
                >
                  <Edit2 className="h-4 w-4" />
                </Button>
                <Button
                  variant="ghost"
                  size="icon"
                  className="h-8 w-8 text-muted-foreground hover:text-destructive"
                  onClick={() => handleDelete(system.systemId)}
                >
                  <Trash2 className="h-4 w-4" />
                </Button>
              </div>
            </div>
          ))}
        </div>

        {/* Edit Dialog */}
        {editing && (
          <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50">
            <div className="bg-card rounded-lg shadow-lg w-full max-w-lg mx-4">
              <div className="p-4 border-b flex items-center justify-between">
                <h3 className="font-semibold">编辑业务系统</h3>
                <Button variant="ghost" size="icon" onClick={() => setEditing(null)}>
                  <X className="h-4 w-4" />
                </Button>
              </div>
              <div className="p-4">
                <ProfileForm
                  initial={editing}
                  onSave={async (input) => {
                    // TODO: implement update
                    setEditing(null);
                  }}
                  onCancel={() => setEditing(null)}
                />
              </div>
            </div>
          </div>
        )}
      </CardContent>
    </Card>
  );
}
