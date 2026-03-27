import { useEffect, useState, useCallback } from 'react';
import {
  Plus, Trash2, Play, Pause, Square, RotateCcw,
  Loader2, CheckCircle, XCircle, FileCode, FlaskConical,
  Save, ChevronDown, ChevronUp,
} from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import {
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from '@/components/ui/select';
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
  const {
    deleteTask, executeTask, pauseTask, resumeTask, cancelTask, retryFailed,
    generating, testing, executing,
  } = useBatchStore();

  const workspaceName = task.workspace.split(/[/\\]/).pop() || task.workspace;
  const isExecutable = task.status === 'saved';
  const isRunning = task.status === 'running';
  const isPaused = task.status === 'paused';
  const isCompleted = task.status === 'completed' || task.status === 'failed';
  const hasFailed = task.failedCount > 0;

  const handleDelete = async () => {
    if (!confirm('确定要删除这个批量任务吗？')) return;
    await deleteTask(task.id);
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
          </div>
          <ProgressBar task={task} />
        </div>

        <div className="flex items-center gap-1 ml-2" onClick={(e) => e.stopPropagation()}>
          {(isExecutable || isPaused) && (
            <Button
              variant="ghost"
              size="icon"
              onClick={async () => {
                if (isPaused) {
                  await resumeTask(task.id);
                  await executeTask(task.id);
                } else {
                  await executeTask(task.id);
                }
              }}
              disabled={executing}
              title="执行"
              className="h-8 w-8"
            >
              <Play className="h-4 w-4" />
            </Button>
          )}

          {isRunning && (
            <Button
              variant="ghost"
              size="icon"
              onClick={() => pauseTask(task.id)}
              title="暂停"
              className="h-8 w-8"
            >
              <Pause className="h-4 w-4" />
            </Button>
          )}

          {isRunning && (
            <Button
              variant="ghost"
              size="icon"
              onClick={() => cancelTask(task.id)}
              title="取消"
              className="h-8 w-8 text-destructive hover:text-destructive"
            >
              <Square className="h-4 w-4" />
            </Button>
          )}

          {isCompleted && hasFailed && (
            <Button
              variant="ghost"
              size="icon"
              onClick={() => retryFailed(task.id)}
              disabled={executing}
              title="重试失败项"
              className="h-8 w-8"
            >
              <RotateCcw className="h-4 w-4" />
            </Button>
          )}

          <Button
            variant="ghost"
            size="icon"
            onClick={handleDelete}
            disabled={isRunning}
            className="h-8 w-8 text-destructive hover:text-destructive"
          >
            <Trash2 className="h-4 w-4" />
          </Button>

          {expanded ? (
            <ChevronUp className="h-4 w-4 text-muted-foreground" />
          ) : (
            <ChevronDown className="h-4 w-4 text-muted-foreground" />
          )}
        </div>
      </div>

      {expanded && (
        <div className="border-t px-3 py-2 text-xs text-muted-foreground space-y-1">
          <div>创建时间：{new Date(task.createdAt).toLocaleString('zh-CN')}</div>
          {task.startedAt && <div>开始执行：{new Date(task.startedAt).toLocaleString('zh-CN')}</div>}
          {task.finishedAt && <div>结束执行：{new Date(task.finishedAt).toLocaleString('zh-CN')}</div>}
          {task.generatedCode && (
            <div className="mt-2">
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

function NewTaskForm() {
  const [workspace, setWorkspace] = useState('');
  const [skillName, setSkillName] = useState('');
  const [mdContent, setMdContent] = useState('');
  const [execTemplate, setExecTemplate] = useState('');
  const [envVars, setEnvVars] = useState('');
  const [concurrency, setConcurrency] = useState('10');

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
      alert('请填写业务系统、Skill 名称和 Batch 配置');
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

    await createTask({
      workspace,
      skillName,
      mdContent,
      execTemplate,
      envVars: Object.keys(envObj).length > 0 ? envObj : undefined,
      concurrency: parseInt(concurrency) || 10,
    });
  };

  const handleLoadBatchMd = () => {
    setMdContent(`# Batch 数据获取配置

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
          <Label>Batch 配置 (batch.md)</Label>
          <Textarea
            value={mdContent}
            onChange={(e) => setMdContent(e.target.value)}
            placeholder="描述如何获取数据..."
            rows={6}
            className="font-mono text-xs"
          />
        </div>
        <div className="flex flex-col gap-2 pt-6">
          <Button variant="outline" size="sm" onClick={handleLoadBatchMd}>
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

      <div className="flex justify-end">
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
          <h3 className="text-lg font-medium">批量任务</h3>
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

      {showForm && <NewTaskForm />}

      <div className="space-y-2">
        {loading ? (
          <div className="text-center py-8 text-muted-foreground">加载中...</div>
        ) : tasks.length === 0 ? (
          <div className="text-center py-8 text-muted-foreground">
            暂无批量任务，点击"新建任务"开始配置
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
