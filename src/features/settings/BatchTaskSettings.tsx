import { useEffect, useState } from 'react';
import {
  Plus, Trash2, Play, Pause, Square, RotateCcw,
  Loader2, CheckCircle, XCircle, FileCode, FlaskConical,
  Save, ChevronDown, ChevronUp, Wand2, Timer,
} from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import {
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from '@/components/ui/select';
import { Switch } from '@/components/ui/switch';
import { useBatchStore } from '@/stores/batch';
import { useCronStore } from '@/stores/cron';
import { useWsConnection } from '@/stores/ws-connection';
import { cn } from '@/lib/utils';
import type { BatchTask, BatchTaskStatus } from '@/types/settings';

const STATUS_CONFIG: Record<BatchTaskStatus, { icon: typeof Loader2; text: string; className: string }> = {
  draft: { icon: FileCode, text: '草稿', className: 'text-muted-foreground' },
  generating: { icon: Loader2, text: '生成中', className: 'text-blue-500' },
  generated: { icon: FileCode, text: '已生成', className: 'text-blue-600' },
  testing: { icon: FlaskConical, text: '测试中', className: 'text-purple-500' },
  tested: { icon: CheckCircle, text: '测试通过', className: 'text-green-600' },
  saved: { icon: Save, text: '已保存', className: 'text-green-600' },
  queued: { icon: Loader2, text: '排队中', className: 'text-yellow-600' },
  running: { icon: Play, text: '执行中', className: 'text-blue-500' },
  paused: { icon: Pause, text: '已暂停', className: 'text-yellow-600' },
  completed: { icon: CheckCircle, text: '已完成', className: 'text-green-600' },
  failed: { icon: XCircle, text: '失败', className: 'text-red-600' },
};

function StatusBadge({ status }: { status: BatchTaskStatus }) {
  const config = STATUS_CONFIG[status];
  const Icon = config.icon;
  return (
    <div className={cn('flex items-center gap-1 text-xs', config.className)}>
      <Icon className={cn('h-3 w-3', (status === 'generating' || status === 'testing' || status === 'running') && 'animate-spin')} />
      <span>{config.text}</span>
    </div>
  );
}

function ProgressBar({ task }: { task: BatchTask }) {
  const total = task.totalItems;
  if (total === 0) return null;
  const done = task.successCount + task.failedCount;
  const pct = Math.round((done / total) * 100);

  return (
    <div className="mt-2">
      <div className="flex justify-between text-xs text-muted-foreground mb-1">
        <span>{done}/{total}</span>
        <span>{pct}%</span>
      </div>
      <div className="h-1.5 bg-muted rounded-full overflow-hidden flex">
        <div className="h-full bg-green-500 transition-all" style={{ width: `${(task.successCount / total) * 100}%` }} />
        <div className="h-full bg-red-500 transition-all" style={{ width: `${(task.failedCount / total) * 100}%` }} />
      </div>
    </div>
  );
}

function BatchTaskCard({ task }: { task: BatchTask }) {
  const [expanded, setExpanded] = useState(false);
  const [editInterval, setEditInterval] = useState(String(task.cronIntervalMinutes ?? 60));
  const [editUnit, setEditUnit] = useState<'minutes' | 'hours'>(
    (task.cronIntervalMinutes ?? 60) >= 60 ? 'hours' : 'minutes',
  );
  const {
    deleteTask, generateCode, testCode, saveTask,
    executeTask, pauseTask, resumeTask, cancelTask, retryFailed,
    updateTask,
    generating, testing, executing,
  } = useBatchStore();

  const workspaceName = task.workspace.split(/[/\\]/).pop() || task.workspace;
  const isDraft = task.status === 'draft';
  const isGenerated = task.status === 'generated';
  const isTested = task.status === 'tested';
  const isSaved = task.status === 'saved';
  const isRunning = task.status === 'running';
  const isPaused = task.status === 'paused';
  const isDone = task.status === 'completed' || task.status === 'failed';
  const hasFailed = task.failedCount > 0;
  const isBusy = generating || testing || executing;
  const canCron = isSaved || isDone;

  const handleDelete = async () => {
    if (!confirm('确定要删除这个任务吗？')) return;
    await deleteTask(task.id);
  };

  const handleToggleCron = async () => {
    if (!task.cronEnabled) {
      const intervalMin = editUnit === 'hours' ? parseInt(editInterval) * 60 : parseInt(editInterval);
      if (isNaN(intervalMin) || intervalMin < 1) {
        alert('请输入有效的间隔时间');
        return;
      }
      await updateTask(task.id, { cronEnabled: true, cronIntervalMinutes: intervalMin });
    } else {
      await updateTask(task.id, { cronEnabled: false });
    }
  };

  const handleSaveInterval = async () => {
    const intervalMin = editUnit === 'hours' ? parseInt(editInterval) * 60 : parseInt(editInterval);
    if (isNaN(intervalMin) || intervalMin < 1) {
      alert('请输入有效的间隔时间');
      return;
    }
    await updateTask(task.id, { cronIntervalMinutes: intervalMin });
  };

  return (
    <div className="rounded-lg border bg-card">
      <div
        className="flex items-center justify-between p-3 cursor-pointer"
        onClick={() => setExpanded(!expanded)}
      >
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2">
            <span className="font-medium truncate">{workspaceName}</span>
            <span className="text-muted-foreground">/</span>
            <span className="text-muted-foreground truncate">{task.skillName}</span>
          </div>
          <div className="flex items-center gap-3 mt-1 text-xs text-muted-foreground">
            <StatusBadge status={task.status} />
            {task.totalItems > 0 && (
              <span>{task.successCount} 成功 / {task.failedCount} 失败</span>
            )}
            {task.totalCost > 0 && <span>¥{task.totalCost.toFixed(4)}</span>}
            {task.cronEnabled && (
              <span className="flex items-center gap-1 text-blue-500">
                <Timer className="h-3 w-3" />
                每 {task.cronIntervalMinutes} 分钟
              </span>
            )}
          </div>
          <ProgressBar task={task} />
        </div>

        <div className="flex items-center gap-1 ml-2" onClick={(e) => e.stopPropagation()}>
          {expanded ? (
            <ChevronUp className="h-4 w-4 text-muted-foreground" />
          ) : (
            <ChevronDown className="h-4 w-4 text-muted-foreground" />
          )}
        </div>
      </div>

      {/* Action bar */}
      <div className="border-t px-3 py-2 flex items-center gap-1.5 flex-wrap">
        {isDraft && (
          <Button variant="outline" size="sm" className="h-7 gap-1.5 text-xs" onClick={() => generateCode(task.id)} disabled={isBusy}>
            <Wand2 className="h-3.5 w-3.5" /> 生成代码
          </Button>
        )}

        {isGenerated && (
          <Button variant="outline" size="sm" className="h-7 gap-1.5 text-xs" onClick={() => testCode(task.id)} disabled={isBusy}>
            <FlaskConical className="h-3.5 w-3.5" /> 测试
          </Button>
        )}
        {testing && (
          <Button variant="outline" size="sm" className="h-7 gap-1.5 text-xs" disabled>
            <FlaskConical className="h-3.5 w-3.5 animate-spin" /> 测试中...
          </Button>
        )}

        {isTested && (
          <Button variant="outline" size="sm" className="h-7 gap-1.5 text-xs" onClick={() => saveTask(task.id)} disabled={isBusy}>
            <Save className="h-3.5 w-3.5" /> 保存
          </Button>
        )}

        {isSaved && (
          <Button variant="default" size="sm" className="h-7 gap-1.5 text-xs" onClick={() => executeTask(task.id)} disabled={isBusy}>
            <Play className="h-3.5 w-3.5" /> 执行
          </Button>
        )}
        {isPaused && (
          <Button variant="default" size="sm" className="h-7 gap-1.5 text-xs" onClick={() => executeTask(task.id)} disabled={isBusy}>
            <Play className="h-3.5 w-3.5" /> 继续
          </Button>
        )}

        {isRunning && (
          <>
            <Button variant="outline" size="sm" className="h-7 gap-1.5 text-xs" onClick={() => pauseTask(task.id)}>
              <Pause className="h-3.5 w-3.5" /> 暂停
            </Button>
            <Button variant="outline" size="sm" className="h-7 gap-1.5 text-xs text-destructive hover:text-destructive" onClick={() => cancelTask(task.id)}>
              <Square className="h-3.5 w-3.5" /> 取消
            </Button>
          </>
        )}

        {isDone && (
          <>
            <Button variant="default" size="sm" className="h-7 gap-1.5 text-xs" onClick={() => executeTask(task.id)} disabled={isBusy}>
              <Play className="h-3.5 w-3.5" /> 重新执行
            </Button>
            {hasFailed && (
              <Button variant="outline" size="sm" className="h-7 gap-1.5 text-xs" onClick={() => retryFailed(task.id)} disabled={isBusy}>
                <RotateCcw className="h-3.5 w-3.5" /> 重试失败
              </Button>
            )}
          </>
        )}

        {canCron && (
          <div className="flex items-center gap-2 ml-auto">
            <div className="flex items-center gap-1">
              <Timer className="h-3.5 w-3.5 text-muted-foreground" />
              <Switch checked={task.cronEnabled} onCheckedChange={handleToggleCron} disabled={isBusy} />
            </div>
          </div>
        )}

        <Button
          variant="ghost"
          size="sm"
          className="h-7 gap-1.5 text-xs text-destructive hover:text-destructive"
          onClick={handleDelete}
          disabled={isRunning}
        >
          <Trash2 className="h-3.5 w-3.5" /> 删除
        </Button>
      </div>

      {/* Expanded details */}
      {expanded && (
        <div className="border-t px-3 py-2 text-xs text-muted-foreground space-y-2">
          <div>创建时间：{new Date(task.createdAt).toLocaleString('zh-CN')}</div>
          {task.startedAt && <div>开始执行：{new Date(task.startedAt).toLocaleString('zh-CN')}</div>}
          {task.finishedAt && <div>结束执行：{new Date(task.finishedAt).toLocaleString('zh-CN')}</div>}

          {canCron && (
            <div className="flex items-center gap-2 pt-1">
              <Label className="text-foreground">定时间隔</Label>
              <Input
                type="number"
                min={1}
                max={60}
                value={editInterval}
                onChange={(e) => setEditInterval(e.target.value)}
                className="w-16 h-6 text-xs"
                disabled={task.cronEnabled}
              />
              <Select value={editUnit} onValueChange={(v) => setEditUnit(v as 'minutes' | 'hours')} disabled={task.cronEnabled}>
                <SelectTrigger className="w-16 h-6 text-xs">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="minutes">分钟</SelectItem>
                  <SelectItem value="hours">小时</SelectItem>
                </SelectContent>
              </Select>
              {!task.cronEnabled && (
                <Button variant="outline" size="sm" className="h-6 text-xs" onClick={handleSaveInterval}>
                  保存间隔
                </Button>
              )}
              {task.cronEnabled && <span className="text-green-600">（定时中，关闭后可修改）</span>}
            </div>
          )}

          {task.generatedCode && (
            <div className="mt-1">
              <div className="font-medium text-foreground mb-1">生成代码（前200字符）</div>
              <pre className="bg-muted p-2 rounded text-xs overflow-x-auto max-h-32">
                {task.generatedCode.slice(0, 200)}{task.generatedCode.length > 200 ? '...' : ''}
              </pre>
            </div>
          )}
        </div>
      )}
    </div>
  );
}

// === New Task Form ===

function NewTaskForm({ onCancel }: { onCancel: () => void }) {
  const [workspace, setWorkspace] = useState('');
  const [skillName, setSkillName] = useState('');
  const [mdContent, setMdContent] = useState('');
  const [execTemplate, setExecTemplate] = useState('');
  const [envVars, setEnvVars] = useState('');
  const [concurrency, setConcurrency] = useState('10');
  const [cronInterval, setCronInterval] = useState('60');
  const [cronUnit, setCronUnit] = useState<'minutes' | 'hours'>('hours');

  const { createTask } = useBatchStore();
  const { workspaces, skills, fetchWorkspaces, fetchSkills } = useCronStore();
  const { status: wsStatus } = useWsConnection();

  useEffect(() => {
    if (wsStatus === 'connected') fetchWorkspaces();
  }, [wsStatus, fetchWorkspaces]);

  useEffect(() => {
    if (workspace) {
      fetchSkills(workspace);
      setSkillName('');
    }
  }, [workspace, fetchSkills]);

  const handleCreate = async () => {
    if (!workspace || !skillName || !mdContent.trim()) {
      alert('请填写业务系统、Skill 名称和配置');
      return;
    }

    const envObj: Record<string, string> = {};
    if (envVars.trim()) {
      for (const line of envVars.split('\n')) {
        const eq = line.indexOf('=');
        if (eq > 0) {
          envObj[line.slice(0, eq).trim()] = line.slice(eq + 1).trim();
        }
      }
    }

    const intervalMin = cronUnit === 'hours' ? parseInt(cronInterval) * 60 : parseInt(cronInterval);

    await createTask({
      workspace,
      skillName,
      mdContent,
      execTemplate,
      envVars: Object.keys(envObj).length > 0 ? envObj : undefined,
      concurrency: parseInt(concurrency) || 10,
      cronEnabled: false,
      cronIntervalMinutes: isNaN(intervalMin) ? 60 : intervalMin,
    });
  };

  const handleLoadTemplate = () => {
    setMdContent(`# 数据获取配置

## 数据源
描述数据来源和连接方式

## 数据格式
描述返回的 JSON 数组格式

## 执行模板
/test \${name}
`);
  };

  return (
    <div className="p-4 rounded-lg border bg-muted/50 space-y-4">
      <div className="grid grid-cols-2 gap-4">
        <div className="space-y-2">
          <Label>业务系统</Label>
          <Select value={workspace} onValueChange={setWorkspace}>
            <SelectTrigger><SelectValue placeholder="选择业务系统" /></SelectTrigger>
            <SelectContent>
              {workspaces.map((ws) => (
                <SelectItem key={ws} value={ws}>{ws.split(/[/\\]/).pop()}</SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>

        <div className="space-y-2">
          <Label>Skill</Label>
          <Select value={skillName} onValueChange={setSkillName} disabled={!workspace}>
            <SelectTrigger><SelectValue placeholder="选择 Skill" /></SelectTrigger>
            <SelectContent>
              {skills.map((skill) => (
                <SelectItem key={skill.name} value={skill.name}>{skill.name}</SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
      </div>

      <div className="grid grid-cols-[1fr_auto] gap-4">
        <div className="space-y-2">
          <Label>配置 (batch.md)</Label>
          <Textarea
            value={mdContent}
            onChange={(e) => setMdContent(e.target.value)}
            placeholder="描述如何获取数据..."
            rows={6}
            className="font-mono text-xs"
          />
        </div>
        <div className="flex flex-col gap-2 pt-6">
          <Button variant="outline" size="sm" onClick={handleLoadTemplate}>
            加载模板
          </Button>
        </div>
      </div>

      <div className="space-y-2">
        <Label>执行模板</Label>
        <Input
          value={execTemplate}
          onChange={(e) => setExecTemplate(e.target.value)}
          placeholder="/skill-name ${field_name}"
          className="font-mono text-sm"
        />
        <p className="text-xs text-muted-foreground">
          使用 {'${field_name}'} 引用数据字段
        </p>
      </div>

      <div className="space-y-2">
        <Label>环境变量（可选）</Label>
        <Textarea
          value={envVars}
          onChange={(e) => setEnvVars(e.target.value)}
          placeholder={"DB_HOST=localhost\nDB_PORT=3306\nDB_USER=root"}
          rows={3}
          className="font-mono text-xs"
        />
      </div>

      <div className="grid grid-cols-2 gap-4">
        <div className="space-y-2">
          <Label>并发数</Label>
          <Input
            type="number"
            min={1}
            max={50}
            value={concurrency}
            onChange={(e) => setConcurrency(e.target.value)}
            className="w-24"
          />
        </div>
        <div className="space-y-2">
          <Label>定时间隔</Label>
          <div className="flex items-center gap-2">
            <Input
              type="number"
              min={1}
              max={60}
              value={cronInterval}
              onChange={(e) => setCronInterval(e.target.value)}
              className="w-20"
            />
            <Select value={cronUnit} onValueChange={(v) => setCronUnit(v as 'minutes' | 'hours')}>
              <SelectTrigger className="w-20">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="minutes">分钟</SelectItem>
                <SelectItem value="hours">小时</SelectItem>
              </SelectContent>
            </Select>
          </div>
        </div>
      </div>

      <div className="flex justify-end gap-2">
        <Button variant="outline" onClick={onCancel}>
          取消
        </Button>
        <Button
          onClick={handleCreate}
          disabled={!workspace || !skillName || !mdContent.trim()}
        >
          创建任务
        </Button>
      </div>
    </div>
  );
}

// === Main Component ===

export function BatchTaskSettings() {
  const [showForm, setShowForm] = useState(false);

  const {
    tasks, loading, error, fetchTasks, clearError,
  } = useBatchStore();
  const { status: wsStatus } = useWsConnection();

  useEffect(() => {
    if (wsStatus === 'connected') fetchTasks();
  }, [wsStatus, fetchTasks]);

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h3 className="text-lg font-medium">自定义任务</h3>
          <p className="text-sm text-muted-foreground">
            MD 驱动的批量执行引擎
          </p>
        </div>
        <Button onClick={() => setShowForm(!showForm)}>
          <Plus className="h-4 w-4 mr-2" />
          新建任务
        </Button>
      </div>

      {error && (
        <div className="p-3 rounded-lg bg-destructive/10 text-destructive text-sm flex items-center justify-between">
          <span>{error}</span>
          <Button variant="ghost" size="sm" onClick={clearError}>关闭</Button>
        </div>
      )}

      {showForm && <NewTaskForm onCancel={() => setShowForm(false)} />}

      <div className="space-y-2">
        {loading ? (
          <div className="text-center py-8 text-muted-foreground">加载中...</div>
        ) : tasks.length === 0 ? (
          <div className="text-center py-8 text-muted-foreground">
            暂无自定义任务，点击"新建任务"开始配置
          </div>
        ) : (
          tasks.map((task) => (
            <BatchTaskCard key={task.id} task={task} />
          ))
        )}
      </div>
    </div>
  );
}
