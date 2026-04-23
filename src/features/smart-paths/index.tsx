import { useEffect, useState, useMemo, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  ChevronLeft, Plus, Trash2, Play, Loader2, CheckCircle, XCircle,
  FolderOpen, Wand2, Save, GripVertical, Route, Pencil,
  ChevronDown,
} from 'lucide-react';
import { cn } from '@/lib/utils';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { Card, CardContent } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import {
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from '@/components/ui/select';
import {
  Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter, DialogDescription,
} from '@/components/ui/dialog';
import {
  Collapsible, CollapsibleContent, CollapsibleTrigger,
} from '@/components/ui/collapsible';
import { useSmartPathStore } from '@/stores/smart-path';
import { useCronStore } from '@/stores/cron';
import { useWsConnection } from '@/stores/ws-connection';
import type { SmartPath, SmartPathRun, SmartPathStep } from '@/types/settings';

const STATUS_CONFIG: Record<string, { label: string; variant: 'default' | 'success' | 'warning' | 'destructive' | 'secondary' | 'outline' }> = {
  draft: { label: '草稿', variant: 'secondary' },
  ready: { label: '就绪', variant: 'outline' },
  running: { label: '执行中', variant: 'default' },
  completed: { label: '已完成', variant: 'success' },
  failed: { label: '失败', variant: 'destructive' },
};

function getStepCount(p: SmartPath): number {
  try { return (JSON.parse(p.steps) as SmartPathStep[]).length; } catch { return 0; }
}

// ── Left sidebar ──

function PathList({ paths, currentPath, onSelect, onNew, onBack }: {
  paths: SmartPath[];
  currentPath: SmartPath | null;
  onSelect: (p: SmartPath) => void;
  onNew: () => void;
  onBack: () => void;
}) {
  const groups = useMemo(() => {
    const map = new Map<string, SmartPath[]>();
    for (const p of paths) {
      const name = p.workspace.split(/[/\\]/).pop() || p.workspace;
      if (!map.has(name)) map.set(name, []);
      map.get(name)!.push(p);
    }
    return Array.from(map.entries()).sort((a, b) => a[0].localeCompare(b[0]));
  }, [paths]);

  return (
    <div className="w-64 shrink-0 flex flex-col h-full border-r">
      <div className="p-3 space-y-2">
        <button onClick={onBack} className="flex items-center gap-1.5 text-sm text-muted-foreground hover:text-foreground transition-colors">
          <ChevronLeft className="h-4 w-4" /> 返回
        </button>
        <Button variant="outline" size="sm" className="w-full" onClick={onNew}>
          <Plus className="h-4 w-4 mr-1" /> 新建路径
        </Button>
      </div>
      <div className="flex-1 overflow-y-auto p-2 space-y-4">
        {groups.length === 0 && <div className="text-xs text-muted-foreground text-center py-8">暂无路径</div>}
        {groups.map(([wsName, wsPaths]) => (
          <div key={wsName}>
            <div className="flex items-center gap-1.5 px-2 py-1 text-xs text-muted-foreground">
              <FolderOpen className="h-3 w-3" /> {wsName}
            </div>
            <div className="space-y-1">
              {wsPaths.map((p) => {
                const sc = STATUS_CONFIG[p.status];
                return (
                  <button key={p.id} onClick={() => onSelect(p)}
                    className={cn('flex flex-col gap-1 w-full rounded-lg px-3 py-2 text-left transition-colors',
                      currentPath?.id === p.id ? 'bg-primary/10 text-primary' : 'text-foreground hover:bg-muted')}>
                    <div className="flex items-center justify-between gap-1">
                      <span className="font-medium text-sm truncate">{p.name}</span>
                      <Badge variant={sc?.variant || 'outline'} className="text-[10px] px-1.5 py-0 shrink-0">{sc?.label || p.status}</Badge>
                    </div>
                    <span className="text-xs text-muted-foreground">{getStepCount(p)} 个步骤</span>
                  </button>
                );
              })}
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}

// ── Step edit card ──

function StepEditCard({ step, index, total, onChange, onDelete, onGenerate, generating, workspace }: {
  step: SmartPathStep; index: number; total: number;
  onChange: (s: SmartPathStep) => void; onDelete: () => void;
  onGenerate: () => void; generating: boolean; workspace: string;
}) {
  const [open, setOpen] = useState(!!step.generatedContent);
  return (
    <>
      <div className="flex justify-center"><div className="w-px h-4 bg-border" /></div>
      <Card className="relative group">
        <CardContent className="p-4 space-y-3">
          <div className="flex items-center gap-2">
            <GripVertical className="h-4 w-4 text-muted-foreground cursor-move shrink-0" />
            <span className="text-sm font-semibold text-muted-foreground shrink-0">{index + 1}</span>
            <Input value={step.name || ''} onChange={(e) => onChange({ ...step, name: e.target.value })}
              placeholder="步骤名称" className="h-6 text-sm border-0 p-0 shadow-none focus-visible:ring-0 font-medium" />
            <div className="flex-1" />
            <Button variant="ghost" size="icon" className="h-7 w-7 opacity-0 group-hover:opacity-100 transition-opacity" onClick={onDelete}>
              <Trash2 className="h-3.5 w-3.5 text-muted-foreground" />
            </Button>
          </div>
          <Textarea value={step.userInput} onChange={(e) => onChange({ ...step, userInput: e.target.value })}
            placeholder="描述这一步要做什么..." className="min-h-[56px] text-sm resize-none" />
          <Button variant="outline" size="sm" className="w-full"
            disabled={!step.userInput.trim() || generating || !workspace} onClick={onGenerate}>
            {generating ? <><Loader2 className="h-3.5 w-3.5 mr-1.5 animate-spin" /> 生成中...</>
              : <><Wand2 className="h-3.5 w-3.5 mr-1.5" /> 生成方案</>}
          </Button>
          {step.generatedContent && (
            <Collapsible open={open} onOpenChange={setOpen}>
              <CollapsibleTrigger className="flex items-center gap-1.5 text-xs text-muted-foreground hover:text-foreground transition-colors w-full">
                <ChevronDown className={cn('h-3 w-3 transition-transform', open && 'rotate-180')} /> 生成的方案
              </CollapsibleTrigger>
              <CollapsibleContent>
                <div className="mt-2 rounded-md bg-muted p-3 text-xs whitespace-pre-wrap max-h-[240px] overflow-y-auto leading-relaxed">
                  {step.generatedContent}
                </div>
              </CollapsibleContent>
            </Collapsible>
          )}
        </CardContent>
      </Card>
    </>
  );
}

// ── Step view card ──

function StepViewCard({ step, index, total }: { step: SmartPathStep; index: number; total: number }) {
  return (
    <div className="flex items-start gap-3">
      <div className="flex flex-col items-center">
        <div className="w-7 h-7 rounded-full bg-primary/10 text-primary flex items-center justify-center text-xs font-semibold shrink-0">{index + 1}</div>
        {index < total - 1 && <div className="w-px flex-1 min-h-[16px] bg-border mt-1" />}
      </div>
      <div className="flex-1 pb-3">
        {step.name && <p className="text-sm font-medium">{step.name}</p>}
        <p className="text-sm leading-relaxed text-muted-foreground">{step.userInput}</p>
        {step.generatedContent && <p className="text-xs text-muted-foreground mt-1 line-clamp-2">{step.generatedContent.slice(0, 100)}...</p>}
      </div>
    </div>
  );
}

// ── Create dialog ──

function CreateDialog({ open, onOpenChange, workspaceOptions, onSubmit }: {
  open: boolean; onOpenChange: (o: boolean) => void;
  workspaceOptions: { value: string; label: string }[];
  onSubmit: (name: string, workspace: string) => void;
}) {
  const [name, setName] = useState('');
  const [workspace, setWorkspace] = useState('');
  useEffect(() => { if (open) { setName(''); setWorkspace(''); } }, [open]);
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader><DialogTitle>新建路径</DialogTitle><DialogDescription>给路径起个名字并选择工作目录</DialogDescription></DialogHeader>
        <div className="space-y-4 py-2">
          <div className="space-y-2"><Label>名称</Label><Input value={name} onChange={(e) => setName(e.target.value)} placeholder="例如：每日数据报告" /></div>
          <div className="space-y-2"><Label>工作目录</Label>
            <Select value={workspace} onValueChange={setWorkspace}>
              <SelectTrigger><SelectValue placeholder="选择业务系统" /></SelectTrigger>
              <SelectContent>{workspaceOptions.map((o) => (<SelectItem key={o.value} value={o.value}><div className="flex items-center gap-2"><FolderOpen className="h-3.5 w-3.5" /><span>{o.label}</span></div></SelectItem>))}</SelectContent>
            </Select>
          </div>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>取消</Button>
          <Button onClick={() => { if (name.trim() && workspace) onSubmit(name.trim(), workspace); }} disabled={!name.trim() || !workspace}>创建</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

// ── Edit mode ──

function PathEditor({ path, onSave, onCancel }: {
  path: SmartPath;
  onSave: (name: string, steps: SmartPathStep[], pathId: string, workspace: string) => Promise<void>;
  onCancel: () => void;
}) {
  const [name, setName] = useState(path.name);
  const [steps, setSteps] = useState<SmartPathStep[]>(() => { try { return JSON.parse(path.steps); } catch { return []; } });
  const [genIdx, setGenIdx] = useState<number | null>(null);
  const [saving, setSaving] = useState(false);
  const generateStep = useSmartPathStore((s) => s.generateStep);

  const updateStep = useCallback((i: number, s: SmartPathStep) => setSteps((prev) => { const n = [...prev]; n[i] = s; return n; }), []);
  const removeStep = useCallback((i: number) => setSteps((prev) => prev.filter((_, idx) => idx !== i)), []);

  const handleGen = useCallback(async (i: number) => {
    const s = steps[i]; if (!s?.userInput.trim()) return;
    setGenIdx(i);
    try { const c = await generateStep(s.userInput, path.workspace, steps.slice(0, i)); updateStep(i, { ...s, generatedContent: c }); } catch {}
    finally { setGenIdx(null); }
  }, [steps, path.workspace, generateStep, updateStep]);

  const handleSave = async () => {
    if (!name.trim() || steps.length === 0) return;
    setSaving(true);
    try { await onSave(name.trim(), steps, path.id, path.workspace); } finally { setSaving(false); }
  };

  return (
    <div className="max-w-2xl mx-auto space-y-6">
      <div className="flex items-center gap-2">
        <Route className="h-5 w-5 text-primary" />
        <Input value={name} onChange={(e) => setName(e.target.value)}
          className="h-8 text-lg font-semibold border-0 p-0 shadow-none focus-visible:ring-0" />
      </div>
      <div className="space-y-0">
        <Label className="text-sm font-medium mb-3 block">步骤</Label>
        {steps.length === 0 && (
          <div className="text-center py-8 text-muted-foreground text-sm border rounded-lg border-dashed">还没有步骤，点击下方按钮添加</div>
        )}
        {steps.map((s, i) => (
          <StepEditCard key={i} step={s} index={i} total={steps.length}
            onChange={(v) => updateStep(i, v)} onDelete={() => removeStep(i)}
            onGenerate={() => handleGen(i)} generating={genIdx === i} workspace={path.workspace} />
        ))}
        <div className="flex justify-center pt-5">
          <Button variant="outline" size="sm" onClick={() => setSteps((p) => [...p, { name: '', userInput: '' }])}>
            <Plus className="h-4 w-4 mr-1.5" /> 添加步骤
          </Button>
        </div>
      </div>
      <div className="flex gap-2 pt-2">
        <Button onClick={handleSave} disabled={!name.trim() || steps.length === 0 || saving}>
          {saving ? <Loader2 className="h-4 w-4 mr-1.5 animate-spin" /> : <Save className="h-4 w-4 mr-1.5" />} 保存路径
        </Button>
        <Button variant="outline" onClick={onCancel}>取消</Button>
      </div>
    </div>
  );
}

// ── View mode ──

function PathDetail({ path, runs, onEdit, onRun, onDelete }: {
  path: SmartPath; runs: SmartPathRun[];
  onEdit: () => void; onRun: () => void; onDelete: () => void;
}) {
  const steps = useMemo<SmartPathStep[]>(() => { try { return JSON.parse(path.steps); } catch { return []; } }, [path.steps]);
  const sc = STATUS_CONFIG[path.status];
  return (
    <div className="max-w-2xl mx-auto space-y-6">
      <div className="flex items-start justify-between gap-3">
        <div className="space-y-1">
          <h2 className="text-lg font-semibold">{path.name}</h2>
          <div className="flex items-center gap-2 text-sm text-muted-foreground">
            <FolderOpen className="h-3.5 w-3.5" /><span>{path.workspace.split(/[/\\]/).pop()}</span>
            <Badge variant={sc?.variant || 'outline'} className="text-[10px]">{sc?.label || path.status}</Badge>
          </div>
        </div>
        <div className="flex gap-1.5 shrink-0">
          <Button variant="outline" size="sm" onClick={onEdit}><Pencil className="h-3.5 w-3.5 mr-1" /> 编辑</Button>
          <Button size="sm" onClick={onRun} disabled={path.status === 'running'}>
            {path.status === 'running' ? <><Loader2 className="h-3.5 w-3.5 mr-1 animate-spin" /> 执行中</> : <><Play className="h-3.5 w-3.5 mr-1" /> 执行</>}
          </Button>
          <Button variant="ghost" size="icon" className="h-8 w-8" onClick={onDelete}><Trash2 className="h-4 w-4 text-muted-foreground" /></Button>
        </div>
      </div>
      <div className="space-y-0">
        <Label className="text-sm font-medium mb-3 block">步骤</Label>
        {steps.length === 0 ? <div className="text-sm text-muted-foreground py-4">暂无步骤</div>
          : <div className="space-y-0">{steps.map((s, i) => <StepViewCard key={i} step={s} index={i} total={steps.length} />)}</div>}
      </div>
      {runs.length > 0 && (
        <div className="space-y-2">
          <Label className="text-sm font-medium">执行历史</Label>
          <div className="space-y-1.5">
            {runs.map((r) => { const rs = STATUS_CONFIG[r.status]; return (
              <div key={r.id} className="flex items-center justify-between rounded-md px-3 py-2 border text-sm">
                <div className="flex items-center gap-2">
                  {r.status === 'completed' ? <CheckCircle className="h-4 w-4 text-green-500" />
                    : r.status === 'failed' ? <XCircle className="h-4 w-4 text-red-500" />
                    : <Loader2 className="h-4 w-4 animate-spin text-blue-500" />}
                  <span className="text-muted-foreground">{new Date(r.startedAt).toLocaleString()}</span>
                </div>
                <Badge variant={rs?.variant || 'outline'} className="text-[10px]">{rs?.label || r.status}</Badge>
              </div>); })}
          </div>
        </div>
      )}
    </div>
  );
}

// ── Empty state ──

function EmptyState({ onNew }: { onNew: () => void }) {
  return (
    <div className="flex items-center justify-center h-full">
      <div className="text-center space-y-3 max-w-xs">
        <div className="mx-auto w-12 h-12 rounded-full bg-muted flex items-center justify-center"><Route className="h-6 w-6 text-muted-foreground" /></div>
        <p className="text-sm text-muted-foreground">选择一个路径查看详情，或创建新路径</p>
        <Button variant="outline" size="sm" onClick={onNew}><Plus className="h-4 w-4 mr-1.5" /> 新建路径</Button>
      </div>
    </div>
  );
}

// ── Main page ──

export function SmartPathPage() {
  const navigate = useNavigate();
  const { status: wsStatus } = useWsConnection();
  const paths = useSmartPathStore((s) => s.paths);
  const runs = useSmartPathStore((s) => s.runs);
  const currentPath = useSmartPathStore((s) => s.currentPath);
  const loading = useSmartPathStore((s) => s.loading);
  const error = useSmartPathStore((s) => s.error);
  const fetchPaths = useSmartPathStore((s) => s.fetchPaths);
  const createPath = useSmartPathStore((s) => s.createPath);
  const updatePath = useSmartPathStore((s) => s.updatePath);
  const deletePath = useSmartPathStore((s) => s.deletePath);
  const runPath = useSmartPathStore((s) => s.runPath);
  const fetchRuns = useSmartPathStore((s) => s.fetchRuns);
  const setCurrentPath = useSmartPathStore((s) => s.setCurrentPath);
  const clearError = useSmartPathStore((s) => s.clearError);
  const workspaces = useCronStore((s) => s.workspaces);
  const fetchWorkspaces = useCronStore((s) => s.fetchWorkspaces);

  const [createOpen, setCreateOpen] = useState(false);
  const [editing, setEditing] = useState(false);

  // 先加载 workspace 列表，再加载 paths
  useEffect(() => {
    if (wsStatus !== 'connected') return;
    fetchWorkspaces().then(() => {
      const ws = useCronStore.getState().workspaces;
      if (ws.length > 0) fetchPaths(ws);
    });
  }, [wsStatus, fetchPaths, fetchWorkspaces]);

  // 切换路径时加载执行历史
  useEffect(() => {
    if (currentPath) fetchRuns(currentPath.id, currentPath.workspace);
  }, [currentPath?.id]);

  // 切换路径时退出编辑模式
  useEffect(() => { setEditing(false); }, [currentPath?.id]);

  const workspaceOptions = useMemo(() => workspaces.map((ws) => ({ value: ws, label: ws.split(/[/\\]/).pop() || ws })), [workspaces]);

  const handleCreate = async (name: string, workspace: string) => {
    setCreateOpen(false);
    const p = await createPath({ name, workspace, steps: '[]' });
    setCurrentPath(p);
    setEditing(true);
  };

  const handleSave = async (name: string, steps: SmartPathStep[], pathId: string, workspace: string) => {
    await updatePath(pathId, workspace, { name, steps: JSON.stringify(steps), status: 'ready' });
    setEditing(false);
  };

  return (
    <div className="flex h-full">
      <PathList paths={paths} currentPath={currentPath}
        onSelect={(p) => setCurrentPath(p)} onNew={() => setCreateOpen(true)} onBack={() => navigate(-1)} />
      <div className="flex-1 overflow-y-auto">
        {error && (
          <div className="mx-6 mt-4 p-3 rounded-md bg-destructive/10 text-destructive text-sm flex items-center justify-between max-w-2xl lg:mx-auto">
            <span>{error}</span>
            <Button variant="ghost" size="sm" onClick={clearError}><XCircle className="h-4 w-4" /></Button>
          </div>
        )}
        {loading && <div className="flex items-center justify-center py-4"><Loader2 className="h-5 w-5 animate-spin text-muted-foreground" /></div>}
        <div className="p-6">
          {editing && currentPath ? (
            <PathEditor path={currentPath} onSave={handleSave} onCancel={() => setEditing(false)} />
          ) : currentPath ? (
            <PathDetail path={currentPath} runs={runs}
              onEdit={() => setEditing(true)}
              onRun={() => runPath(currentPath.id, currentPath.workspace)}
              onDelete={() => { deletePath(currentPath.id, currentPath.workspace); setCurrentPath(null); }} />
          ) : (
            <EmptyState onNew={() => setCreateOpen(true)} />
          )}
        </div>
      </div>
      <CreateDialog open={createOpen} onOpenChange={setCreateOpen} workspaceOptions={workspaceOptions} onSubmit={handleCreate} />
    </div>
  );
}
