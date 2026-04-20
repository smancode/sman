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
import { StepCardsEditor } from './StepCardsEditor';
import { JsonEditor } from './JsonEditor';
import type { SmartPath, SmartPathStep, SmartPathAction } from '@/types/settings';

const STATUS_TEXT: Record<string, string> = {
  draft: '草稿',
  ready: '就绪',
  running: '执行中',
  completed: '已完成',
  failed: '失败',
};

type EditorMode = 'visual' | 'json';

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
  previousSteps,
}: {
  action: SmartPathAction;
  onChange: (a: SmartPathAction) => void;
  onDelete: () => void;
  workspace?: string;
  previousSteps?: SmartPathAction[];
}) {
  const [generating, setGenerating] = useState(false);
  const generateStep = useSmartPathStore((s) => s.generateStep);

  const handleGenerate = async () => {
    if (!action.userInput?.trim() || !workspace) return;
    setGenerating(true);
    try {
      const content = await generateStep(action.userInput, workspace, previousSteps || []);
      onChange({ ...action, generatedContent: content });
    } catch (err) {
      console.error('Generation failed:', err);
    } finally {
      setGenerating(false);
    }
  };

  return (
    <div className="rounded-md p-3 space-y-3 border">
      <div className="flex items-center gap-2">
        <Label className="text-xs">行动 {previousSteps ? previousSteps.length + 1 : 1}</Label>
        <Button variant="ghost" size="icon" className="h-8 w-8 ml-auto" onClick={onDelete}>
          <Trash2 className="h-3.5 w-3.5 text-muted-foreground" />
        </Button>
      </div>

      <div className="space-y-2">
        <Label className="text-xs">你想做什么</Label>
        <Textarea
          value={action.userInput || ''}
          onChange={(e) => onChange({ ...action, userInput: e.target.value })}
          placeholder="描述你想让这一步做什么..."
          className="min-h-[60px] text-xs"
        />
        <Button
          variant="outline"
          size="sm"
          className="w-full"
          disabled={!action.userInput?.trim() || generating || !workspace}
          onClick={handleGenerate}
        >
          {generating ? (
            <>
              <Loader2 className="h-3 w-3 mr-1 animate-spin" />
              生成中...
            </>
          ) : (
            <>
              <Wand2 className="h-3 w-3 mr-1" />
              生成实现方案
            </>
          )}
        </Button>
      </div>

      {action.generatedContent && (
        <div className="space-y-1">
          <Label className="text-xs">生成的方案</Label>
          <div className="rounded-md bg-muted p-2 text-xs whitespace-pre-wrap max-h-[300px] overflow-y-auto">
            {action.generatedContent}
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
    onChange({ ...step, actions: [...step.actions, { userInput: '' }] });
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
            previousSteps={step.actions.slice(0, j)}
          />
        ))}
      </div>

      <Button variant="outline" size="sm" className="w-full h-8 text-xs" onClick={addAction}>
        <Plus className="h-3 w-3 mr-1" /> 添加行动
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
  const saveFile = useSmartPathStore((s) => s.saveFile);

  // 复用 cron store 的 workspaces 获取逻辑（从会话建立过的业务系统中选择）
  const workspaces = useCronStore((s) => s.workspaces);
  const fetchWorkspaces = useCronStore((s) => s.fetchWorkspaces);

  const [editingPath, setEditingPath] = useState<SmartPath | null>(null);
  const [editName, setEditName] = useState('');
  const [editSteps, setEditSteps] = useState<SmartPathStep[]>([]);
  const [editorMode, setEditorMode] = useState<EditorMode>('visual');
  const [showCreate, setShowCreate] = useState(false);
  const [showStepEditor, setShowStepEditor] = useState(false);
  const [newWorkspace, setNewWorkspace] = useState('');
  const [saveFilePath, setSaveFilePath] = useState('');

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
    setEditorMode('visual');
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
    setEditSteps([...editSteps, { mode: 'serial', actions: [{ userInput: '', description: '' }] }]);
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
    setShowStepEditor(false);
    setEditName('');
    setNewWorkspace('');
    setEditSteps([]);
  };

  const startConfig = () => {
    if (!editName.trim() || !newWorkspace.trim()) return;
    setShowStepEditor(true);
    setEditSteps([{ mode: 'serial', actions: [{ userInput: '' }] }]);
  };

  const handleSaveFile = async () => {
    if (!currentPath || !saveFilePath.trim()) return;
    await saveFile(currentPath.id, saveFilePath);
    setSaveFilePath('');
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
            setShowStepEditor(false);
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

            {showStepEditor && (
            <div className="space-y-3">
              <div className="flex items-center justify-between">
                <Label>步骤配置</Label>
                <div className="flex gap-2">
                  <Button
                    variant={editorMode === 'visual' ? 'default' : 'outline'}
                    size="sm"
                    onClick={() => setEditorMode('visual')}
                  >
                    可视化编辑
                  </Button>
                  <Button
                    variant={editorMode === 'json' ? 'default' : 'outline'}
                    size="sm"
                    onClick={() => setEditorMode('json')}
                  >
                    JSON 编辑
                  </Button>
                </div>
              </div>

              {editorMode === 'visual' ? (
                <div className="space-y-2">
                  {editSteps.map((step, i) => (
                    <StepEditor key={i} step={step} index={i} onChange={(s) => updateStep(i, s)} onDelete={() => removeStep(i)} workspace={newWorkspace} />
                  ))}
                  <Button variant="outline" className="w-full" onClick={addStep}>
                    <Plus className="h-4 w-4 mr-1" /> 添加步骤
                  </Button>
                </div>
              ) : (
                <div className="border rounded-md h-96">
                  <JsonEditor steps={editSteps} onChange={setEditSteps} />
                </div>
              )}
            </div>
            )}
            <div className="flex gap-2">
              {!showStepEditor ? (
                <Button variant="default" onClick={startConfig} disabled={!editName.trim() || !newWorkspace}>
                  下一步：配置步骤
                </Button>
              ) : (
                <Button variant="outline" onClick={handleCreate} disabled={editSteps.length === 0}>
                  <Save className="h-4 w-4 mr-1" /> 保存 Path
                </Button>
              )}
              <Button variant="outline" onClick={() => {
                setShowCreate(false);
                setShowStepEditor(false);
              }}>取消</Button>
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
              <div className="flex items-center justify-between">
                <Label>步骤配置</Label>
                <div className="flex gap-2">
                  <Button
                    variant={editorMode === 'visual' ? 'default' : 'outline'}
                    size="sm"
                    onClick={() => setEditorMode('visual')}
                  >
                    可视化编辑
                  </Button>
                  <Button
                    variant={editorMode === 'json' ? 'default' : 'outline'}
                    size="sm"
                    onClick={() => setEditorMode('json')}
                  >
                    JSON 编辑
                  </Button>
                </div>
              </div>

              {editorMode === 'visual' ? (
                <div className="space-y-2">
                  {editSteps.map((step, i) => (
                    <StepEditor key={i} step={step} index={i} onChange={(s) => updateStep(i, s)} onDelete={() => removeStep(i)} workspace={editingPath.workspace} />
                  ))}
                  <Button variant="outline" className="w-full" onClick={addStep}>
                    <Plus className="h-4 w-4 mr-1" /> 添加步骤
                  </Button>
                </div>
              ) : (
                <div className="border rounded-md h-96">
                  <JsonEditor steps={editSteps} onChange={setEditSteps} />
                </div>
              )}
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
                                {a.userInput?.slice(0, 20) || '无描述'}...
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

            <div className="space-y-2">
              <Label className="text-sm font-medium">保存到文件</Label>
              <div className="flex gap-2">
                <Input
                  value={saveFilePath}
                  onChange={(e) => setSaveFilePath(e.target.value)}
                  placeholder="例如: /path/to/smart-path.md"
                  className="flex-1"
                />
                <Button
                  variant="outline"
                  onClick={handleSaveFile}
                  disabled={!saveFilePath.trim()}
                >
                  <Save className="h-4 w-4 mr-1" />
                  保存
                </Button>
              </div>
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
