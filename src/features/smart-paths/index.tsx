import { useEffect, useState, useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  ChevronLeft, Plus, Trash2, Play, Loader2, CheckCircle, XCircle,
  FolderOpen, Code, Wand2, Save,
} from 'lucide-react';
import { cn } from '@/lib/utils';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import {
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from '@/components/ui/select';
import { useSmartPathStore } from '@/stores/smart-path';
import { useCronStore } from '@/stores/cron';
import { useWsConnection } from '@/stores/ws-connection';
import type { SmartPath, SmartPathStep, SmartPathAction } from '@/types/settings';

const STATUS_TEXT: Record<string, string> = {
  draft: '草稿',
  ready: '就绪',
  running: '执行中',
  completed: '已完成',
  failed: '失败',
};

function PathCard({
  path,
  isSelected,
  onClick,
}: {
  path: SmartPath;
  isSelected: boolean;
  onClick: () => void;
}) {
  const steps = useMemo(() => {
    try {
      return JSON.parse(path.steps) as SmartPathStep[];
    } catch {
      return [];
    }
  }, [path.steps]);

  return (
    <button
      onClick={onClick}
      className={cn(
        'flex flex-col gap-1 w-full rounded-lg px-3 py-2.5 text-left transition-colors',
        isSelected
          ? 'bg-primary/10 text-primary'
          : 'text-foreground hover:bg-muted',
      )}
    >
      <div className="flex items-center justify-between">
        <span className="font-medium text-sm truncate">{path.name}</span>
        <span className={cn(
          'text-[10px] px-1.5 py-0.5 rounded-full',
          path.status === 'completed' ? 'bg-green-500/10 text-green-600' :
          path.status === 'failed' ? 'bg-red-500/10 text-red-600' :
          path.status === 'running' ? 'bg-blue-500/10 text-blue-600' :
          'bg-muted text-muted-foreground'
        )}>
          {STATUS_TEXT[path.status] || path.status}
        </span>
      </div>
      <div className="text-xs text-muted-foreground">
        {steps.length} 个步骤 · {path.workspace.split(/[/\\]/).pop()}
      </div>
    </button>
  );
}

function ActionEditor({
  action,
  onChange,
  onDelete,
  workspace,
}: {
  action: SmartPathAction;
  onChange: (a: SmartPathAction) => void;
  onDelete: () => void;
  workspace?: string;
}) {
  const [generating, setGenerating] = useState(false);
  const [description, setDescription] = useState('');
  const generatePython = useSmartPathStore((s) => s.generatePython);

  const handleGenerate = async () => {
    if (!description.trim() || !workspace) return;
    setGenerating(true);
    try {
      const code = await generatePython(description, workspace);
      onChange({ ...action, code });
    } finally {
      setGenerating(false);
    }
  };

  return (
    <div className="rounded-md p-3 space-y-2 border">
      <div className="flex items-center gap-2">
        <Select
          value={action.type}
          onValueChange={(v) => onChange({ ...action, type: v as 'skill' | 'python' })}
        >
          <SelectTrigger className="w-32 h-8 text-xs">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="skill"><Wand2 className="h-3 w-3 mr-1" />Skill</SelectItem>
            <SelectItem value="python"><Code className="h-3 w-3 mr-1" />Python</SelectItem>
          </SelectContent>
        </Select>
        <Button variant="ghost" size="icon" className="h-8 w-8 ml-auto" onClick={onDelete}>
          <Trash2 className="h-3.5 w-3.5 text-muted-foreground" />
        </Button>
      </div>

      {action.type === 'skill' ? (
        <div className="space-y-1">
          <Label className="text-xs">Skill ID</Label>
          <Input
            value={action.skillId || ''}
            onChange={(e) => onChange({ ...action, skillId: e.target.value })}
            placeholder="输入 skill 名称"
            className="h-8 text-xs"
          />
        </div>
      ) : (
        <div className="space-y-2">
          {workspace && (
            <div className="space-y-1">
              <Label className="text-xs">需求描述（自然语言）</Label>
              <div className="flex gap-2">
                <Textarea
                  value={description}
                  onChange={(e) => setDescription(e.target.value)}
                  placeholder="描述你想让这段 Python 代码做什么..."
                  className="min-h-[60px] text-xs flex-1"
                />
                <Button
                  variant="outline"
                  size="sm"
                  className="shrink-0 h-auto"
                  disabled={!description.trim() || generating}
                  onClick={handleGenerate}
                >
                  {generating ? <Loader2 className="h-3 w-3 animate-spin" /> : <Wand2 className="h-3 w-3" />}
                  生成
                </Button>
              </div>
            </div>
          )}
          <div className="space-y-1">
            <Label className="text-xs">Python 代码</Label>
            <Textarea
              value={action.code || ''}
              onChange={(e) => onChange({ ...action, code: e.target.value })}
              placeholder="print(json.dumps({&quot;result&quot;: 42}))"
              className="min-h-[80px] text-xs font-mono"
            />
          </div>
        </div>
      )}
    </div>
  );
}

function StepEditor({
  step,
  index,
  onChange,
  onDelete,
  workspace,
}: {
  step: SmartPathStep;
  index: number;
  onChange: (s: SmartPathStep) => void;
  onDelete: () => void;
  workspace?: string;
}) {
  const updateAction = (j: number, action: SmartPathAction) => {
    const newActions = [...step.actions];
    newActions[j] = action;
    onChange({ ...step, actions: newActions });
  };

  const addAction = () => {
    onChange({ ...step, actions: [...step.actions, { type: 'skill' }] });
  };

  const removeAction = (j: number) => {
    const newActions = step.actions.filter((_, idx) => idx !== j);
    onChange({ ...step, actions: newActions });
  };

  return (
    <div className="rounded-lg p-4 space-y-3 border">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2">
          <span className="text-xs font-medium text-muted-foreground">步骤 {index + 1}</span>
          <Select
            value={step.mode}
            onValueChange={(v) => onChange({ ...step, mode: v as 'serial' | 'parallel' })}
          >
            <SelectTrigger className="w-28 h-7 text-xs">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="serial">串行</SelectItem>
              <SelectItem value="parallel">并行</SelectItem>
            </SelectContent>
          </Select>
        </div>
        <Button variant="ghost" size="icon" className="h-7 w-7" onClick={onDelete}>
          <Trash2 className="h-3.5 w-3.5 text-muted-foreground" />
        </Button>
      </div>

      <div className="space-y-2">
        {step.actions.map((action, j) => (
          <ActionEditor
            key={j}
            action={action}
            onChange={(a) => updateAction(j, a)}
            onDelete={() => removeAction(j)}
            workspace={workspace}
          />
        ))}
      </div>

      <Button variant="outline" size="sm" className="w-full h-8 text-xs" onClick={addAction}>
        <Plus className="h-3 w-3 mr-1" /> 添加 Action
      </Button>
    </div>
  );
}

export function SmartPathPage() {
  const navigate = useNavigate();
  const { status: wsStatus } = useWsConnection();

  const paths = useSmartPathStore((s) => s.paths);
  const runs = useSmartPathStore((s) => s.runs);
  const currentPath = useSmartPathStore((s) => s.currentPath);
  const loading = useSmartPathStore((s) => s.loading);
  const running = useSmartPathStore((s) => s.running);
  const error = useSmartPathStore((s) => s.error);

  const fetchPaths = useSmartPathStore((s) => s.fetchPaths);
  const createPath = useSmartPathStore((s) => s.createPath);
  const updatePath = useSmartPathStore((s) => s.updatePath);
  const deletePath = useSmartPathStore((s) => s.deletePath);
  const runPath = useSmartPathStore((s) => s.runPath);
  const fetchRuns = useSmartPathStore((s) => s.fetchRuns);
  const setCurrentPath = useSmartPathStore((s) => s.setCurrentPath);
  const clearError = useSmartPathStore((s) => s.clearError);

  // 复用 cron store 的 workspaces 获取逻辑（从会话建立过的业务系统中选择）
  const workspaces = useCronStore((s) => s.workspaces);
  const fetchWorkspaces = useCronStore((s) => s.fetchWorkspaces);

  const [editingPath, setEditingPath] = useState<SmartPath | null>(null);
  const [editName, setEditName] = useState('');
  const [editSteps, setEditSteps] = useState<SmartPathStep[]>([]);
  const [showCreate, setShowCreate] = useState(false);
  const [newWorkspace, setNewWorkspace] = useState('');

  useEffect(() => {
    if (wsStatus === 'connected') {
      fetchPaths();
      fetchWorkspaces();
    }
  }, [wsStatus, fetchPaths, fetchWorkspaces]);

  useEffect(() => {
    if (currentPath) {
      fetchRuns(currentPath.id);
    }
  }, [currentPath, fetchRuns]);

  const groups = useMemo(() => {
    const map = new Map<string, SmartPath[]>();
    for (const p of paths) {
      const name = p.workspace.split(/[/\\]/).pop() || p.workspace;
      if (!map.has(name)) map.set(name, []);
      map.get(name)!.push(p);
    }
    return Array.from(map.entries()).sort((a, b) => a[0].localeCompare(b[0]));
  }, [paths]);

  const startEdit = (path: SmartPath) => {
    setEditingPath(path);
    setEditName(path.name);
    try {
      setEditSteps(JSON.parse(path.steps));
    } catch {
      setEditSteps([]);
    }
  };

  const saveEdit = async () => {
    if (!editingPath) return;
    await updatePath(editingPath.id, {
      name: editName,
      steps: JSON.stringify(editSteps),
      status: 'ready',
    });
    setEditingPath(null);
  };

  const addStep = () => {
    setEditSteps([...editSteps, { mode: 'serial', actions: [{ type: 'skill' }] }]);
  };

  const updateStep = (i: number, step: SmartPathStep) => {
    const newSteps = [...editSteps];
    newSteps[i] = step;
    setEditSteps(newSteps);
  };

  const removeStep = (i: number) => {
    setEditSteps(editSteps.filter((_, idx) => idx !== i));
  };

  const handleCreate = async () => {
    if (!editName.trim() || !newWorkspace.trim()) return;
    await createPath({
      name: editName,
      workspace: newWorkspace,
      steps: JSON.stringify(editSteps),
    });
    setShowCreate(false);
    setEditName('');
    setNewWorkspace('');
    setEditSteps([]);
  };

  // workspace 选项：显示目录名，值为完整路径
  const workspaceOptions = useMemo(() => {
    return workspaces.map((ws) => ({
      value: ws,
      label: ws.split(/[/\\]/).pop() || ws,
    }));
  }, [workspaces]);

  return (
    <div className="flex h-full">
      {/* Left sidebar */}
      <div className="w-64 shrink-0 flex flex-col">
        <div className="p-3">
          <button
            onClick={() => navigate(-1)}
            className="flex items-center gap-1.5 text-sm text-muted-foreground hover:text-foreground mb-3"
          >
            <ChevronLeft className="h-4 w-4" /> 返回
          </button>
          <Button variant="outline" size="sm" className="w-full" onClick={() => {
            setShowCreate(true);
            setEditingPath(null);
            setEditName('');
            setNewWorkspace('');
            setEditSteps([]);
          }}>
            <Plus className="h-4 w-4 mr-1" /> 新建 Path
          </Button>
        </div>

        <div className="flex-1 overflow-y-auto p-2 space-y-4">
          {groups.length === 0 && !loading && (
            <div className="text-xs text-muted-foreground text-center py-4">暂无 Path</div>
          )}
          {groups.map(([wsName, wsPaths]) => (
            <div key={wsName}>
              <div className="flex items-center gap-1.5 px-2 py-1 text-xs text-muted-foreground">
                <FolderOpen className="h-3 w-3" /> {wsName}
              </div>
              <div className="space-y-1">
                {wsPaths.map((p) => (
                  <PathCard
                    key={p.id}
                    path={p}
                    isSelected={currentPath?.id === p.id}
                    onClick={() => setCurrentPath(p)}
                  />
                ))}
              </div>
            </div>
          ))}
        </div>
      </div>

      {/* Main content */}
      <div className="flex-1 overflow-y-auto p-6">
        {error && (
          <div className="mb-4 p-3 rounded-md bg-red-500/10 text-red-600 text-sm flex items-center justify-between">
            <span>{error}</span>
            <Button variant="ghost" size="sm" onClick={clearError}><XCircle className="h-4 w-4" /></Button>
          </div>
        )}

        {showCreate ? (
          <div className="max-w-2xl mx-auto space-y-4">
            <h2 className="text-lg font-semibold">新建 Path</h2>
            <div className="space-y-2">
              <Label>名称</Label>
              <Input value={editName} onChange={(e) => setEditName(e.target.value)} placeholder="Path 名称" />
            </div>
            <div className="space-y-2">
              <Label>工作目录</Label>
              <Select value={newWorkspace} onValueChange={setNewWorkspace}>
                <SelectTrigger>
                  <SelectValue placeholder="选择业务系统" />
                </SelectTrigger>
                <SelectContent>
                  {workspaceOptions.map((opt) => (
                    <SelectItem key={opt.value} value={opt.value}>
                      <div className="flex items-center gap-2">
                        <FolderOpen className="h-3.5 w-3.5" />
                        <span>{opt.label}</span>
                      </div>
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            <div className="space-y-3">
              <Label>步骤配置</Label>
              {editSteps.map((step, i) => (
                <StepEditor key={i} step={step} index={i} onChange={(s) => updateStep(i, s)} onDelete={() => removeStep(i)} workspace={newWorkspace} />
              ))}
              <Button variant="outline" className="w-full" onClick={addStep}>
                <Plus className="h-4 w-4 mr-1" /> 添加步骤
              </Button>
            </div>
            <div className="flex gap-2">
              <Button variant="outline" onClick={handleCreate} disabled={!editName.trim() || !newWorkspace}><Save className="h-4 w-4 mr-1" /> 创建</Button>
              <Button variant="outline" onClick={() => setShowCreate(false)}>取消</Button>
            </div>
          </div>
        ) : editingPath ? (
          <div className="max-w-2xl mx-auto space-y-4">
            <h2 className="text-lg font-semibold">编辑 Path</h2>
            <div className="space-y-2">
              <Label>名称</Label>
              <Input value={editName} onChange={(e) => setEditName(e.target.value)} />
            </div>
            <div className="space-y-3">
              <Label>步骤配置</Label>
              {editSteps.map((step, i) => (
                <StepEditor key={i} step={step} index={i} onChange={(s) => updateStep(i, s)} onDelete={() => removeStep(i)} workspace={editingPath.workspace} />
              ))}
              <Button variant="outline" className="w-full" onClick={addStep}>
                <Plus className="h-4 w-4 mr-1" /> 添加步骤
              </Button>
            </div>
            <div className="flex gap-2">
              <Button variant="outline" onClick={saveEdit}><Save className="h-4 w-4 mr-1" /> 保存</Button>
              <Button variant="outline" onClick={() => setEditingPath(null)}>取消</Button>
            </div>
          </div>
        ) : currentPath ? (
          <div className="max-w-2xl mx-auto space-y-4">
            <div className="flex items-center justify-between">
              <div>
                <h2 className="text-lg font-semibold">{currentPath.name}</h2>
                <p className="text-sm text-muted-foreground">{currentPath.workspace}</p>
              </div>
              <div className="flex gap-2">
                <Button variant="outline" size="sm" onClick={() => startEdit(currentPath)}>
                  编辑
                </Button>
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => runPath(currentPath.id)}
                  disabled={running || currentPath.status === 'running'}
                >
                  {running || currentPath.status === 'running' ? (
                    <><Loader2 className="h-4 w-4 mr-1 animate-spin" /> 执行中</>
                  ) : (
                    <><Play className="h-4 w-4 mr-1" /> 执行</>
                  )}
                </Button>
                <Button variant="ghost" size="sm" onClick={() => deletePath(currentPath.id)}>
                  <Trash2 className="h-4 w-4 text-muted-foreground" />
                </Button>
              </div>
            </div>

            <div className="space-y-2">
              <Label className="text-sm font-medium">步骤概览</Label>
              {(() => {
                try {
                  const steps = JSON.parse(currentPath.steps) as SmartPathStep[];
                  return (
                    <div className="space-y-2">
                      {steps.map((step, i) => (
                        <div key={i} className="rounded-md p-3 border">
                          <div className="flex items-center gap-2 text-sm">
                            <span className="text-muted-foreground">步骤 {i + 1}</span>
                            <span className={cn(
                              'text-[10px] px-1.5 rounded',
                              step.mode === 'parallel' ? 'bg-blue-500/10 text-blue-600' : 'bg-muted text-muted-foreground'
                            )}>
                              {step.mode === 'parallel' ? '并行' : '串行'}
                            </span>
                            <span className="text-muted-foreground ml-auto">{step.actions.length} 个 action</span>
                          </div>
                          <div className="mt-1 flex flex-wrap gap-1">
                            {step.actions.map((a, j) => (
                              <span key={j} className="text-[10px] px-1.5 py-0.5 rounded border">
                                {a.type === 'skill' ? a.skillId || 'skill' : 'python'}
                              </span>
                            ))}
                          </div>
                        </div>
                      ))}
                    </div>
                  );
                } catch {
                  return <div className="text-sm text-red-500">步骤解析失败</div>;
                }
              })()}
            </div>

            {runs.length > 0 && (
              <div className="space-y-2">
                <Label className="text-sm font-medium">执行历史</Label>
                <div className="space-y-1">
                  {runs.map((run) => (
                    <div key={run.id} className="rounded-md p-2 text-sm flex items-center justify-between border">
                      <div className="flex items-center gap-2">
                        {run.status === 'completed' ? (
                          <CheckCircle className="h-4 w-4 text-green-500" />
                        ) : run.status === 'failed' ? (
                          <XCircle className="h-4 w-4 text-red-500" />
                        ) : (
                          <Loader2 className="h-4 w-4 text-blue-500 animate-spin" />
                        )}
                        <span>{new Date(run.startedAt).toLocaleString()}</span>
                      </div>
                      <span className={cn(
                        'text-xs',
                        run.status === 'completed' ? 'text-green-600' :
                        run.status === 'failed' ? 'text-red-600' : 'text-blue-600'
                      )}>
                        {STATUS_TEXT[run.status] || run.status}
                      </span>
                    </div>
                  ))}
                </div>
              </div>
            )}
          </div>
        ) : (
          <div className="flex items-center justify-center h-full text-muted-foreground">
            选择一个 Path 查看详情，或点击"新建 Path"创建
          </div>
        )}
      </div>
    </div>
  );
}
