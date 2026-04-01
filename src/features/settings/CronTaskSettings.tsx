import { useEffect, useState } from 'react';
import { Plus, Trash2, Play, Pencil, CheckCircle, XCircle, Clock, Loader2, RefreshCw } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { Switch } from '@/components/ui/switch';
import { useCronStore } from '@/stores/cron';
import { useWsConnection } from '@/stores/ws-connection';
import { cn } from '@/lib/utils';
import type { CronTask, CronRun } from '@/types/settings';

function formatTime(isoString: string): string {
  return new Date(isoString).toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  });
}

function RunStatusBadge({ run }: { run?: CronRun }) {
  if (!run) return <span className="text-muted-foreground text-xs">无记录</span>;

  const statusConfig = {
    running: { icon: Loader2, text: '执行中', className: 'text-yellow-600' },
    success: { icon: CheckCircle, text: '成功', className: 'text-green-600' },
    failed: { icon: XCircle, text: '失败', className: 'text-red-600' },
  };

  const config = statusConfig[run.status];
  const Icon = config.icon;

  return (
    <div className={cn('flex items-center gap-1 text-xs', config.className)}>
      <Icon className={cn('h-3 w-3', run.status === 'running' && 'animate-spin')} />
      <span>{config.text}</span>
      <span className="text-muted-foreground ml-1">{formatTime(run.startedAt)}</span>
      {run.errorMessage && (
        <span className="text-red-500 ml-1 truncate max-w-[150px]" title={run.errorMessage}>
          ({run.errorMessage})
        </span>
      )}
    </div>
  );
}

interface TaskItemProps {
  task: CronTask;
  onDelete: () => void;
  onToggle: () => void;
  onExecute: () => void;
  onEdit: () => void;
}

function TaskItem({ task, onDelete, onToggle, onExecute, onEdit }: TaskItemProps) {
  const workspaceName = task.workspace.split(/[/\\]/).pop() || task.workspace;

  return (
    <div className="flex items-center justify-between p-3 rounded-lg border bg-card">
      <div className="flex-1 min-w-0">
        <div className="flex items-center gap-2">
          <span className="font-medium truncate">{workspaceName}</span>
          <span className="text-muted-foreground">/</span>
          <span className="text-muted-foreground truncate">{task.skillName}</span>
          {task.source === 'scan' && (
            <span className="text-xs bg-blue-100 text-blue-700 px-1.5 py-0.5 rounded">自动配置</span>
          )}
        </div>
        <div className="flex items-center gap-2 mt-1 text-xs text-muted-foreground">
          <Clock className="h-3 w-3" />
          <code className="text-xs bg-muted px-1 rounded">{task.cronExpression}</code>
          {task.enabled && task.nextRunAt && (
            <span className="ml-1">
              · 下次执行：<span className="text-foreground">{formatTime(task.nextRunAt)}</span>
            </span>
          )}
        </div>
        <div className="mt-1">
          <RunStatusBadge run={task.latestRun} />
        </div>
      </div>

      <div className="flex items-center gap-2">
        <Button
          variant="ghost"
          size="icon"
          onClick={onExecute}
          title="立即执行"
          className="h-8 w-8"
        >
          <Play className="h-4 w-4" />
        </Button>

        <Switch checked={task.enabled} onCheckedChange={onToggle} />

        <Button
          variant="ghost"
          size="icon"
          onClick={onEdit}
          title="编辑"
          className="h-8 w-8"
        >
          <Pencil className="h-4 w-4" />
        </Button>

        <Button
          variant="ghost"
          size="icon"
          onClick={onDelete}
          className="h-8 w-8 text-destructive hover:text-destructive"
        >
          <Trash2 className="h-4 w-4" />
        </Button>
      </div>
    </div>
  );
}

export function CronTaskSettings() {
  const [showForm, setShowForm] = useState(false);
  const [editingTask, setEditingTask] = useState<CronTask | null>(null);
  const [selectedWorkspace, setSelectedWorkspace] = useState('');
  const [selectedSkill, setSelectedSkill] = useState('');
  const [cronExpression, setCronExpression] = useState('*/30 * * * *');
  const [scanResult, setScanResult] = useState<string | null>(null);

  const isEditing = editingTask !== null;

  const {
    workspaces,
    skills,
    tasks,
    loading,
    scanning,
    error,
    fetchWorkspaces,
    fetchSkills,
    fetchTasks,
    createTask,
    updateTask,
    deleteTask,
    executeNow,
    scanCronTasks,
    clearError,
  } = useCronStore();

  const { status: wsStatus } = useWsConnection();

  useEffect(() => {
    if (wsStatus === 'connected') {
      fetchWorkspaces();
      fetchTasks();
    }
  }, [wsStatus, fetchWorkspaces, fetchTasks]);

  useEffect(() => {
    if (selectedWorkspace) {
      fetchSkills(selectedWorkspace);
      setSelectedSkill('');
    }
  }, [selectedWorkspace, fetchSkills]);

  const handleSave = async () => {
    if (!selectedWorkspace || !selectedSkill || !cronExpression) return;

    try {
      if (isEditing) {
        await updateTask(editingTask.id, { workspace: selectedWorkspace, skillName: selectedSkill, cronExpression });
        setEditingTask(null);
      } else {
        await createTask(selectedWorkspace, selectedSkill, cronExpression);
        setShowForm(false);
      }
      setSelectedWorkspace('');
      setSelectedSkill('');
      setCronExpression('*/30 * * * *');
    } catch (err) {
      console.error('Failed to save task:', err);
    }
  };

  const handleEdit = (task: CronTask) => {
    setEditingTask(task);
    setSelectedWorkspace(task.workspace);
    setSelectedSkill(task.skillName);
    setCronExpression(task.cronExpression);
  };

  const handleCancel = () => {
    setShowForm(false);
    setEditingTask(null);
    setSelectedWorkspace('');
    setSelectedSkill('');
    setCronExpression('*/30 * * * *');
  };

  const handleToggle = async (task: CronTask) => {
    try {
      await updateTask(task.id, { enabled: !task.enabled });
    } catch (err) {
      console.error('Failed to toggle task:', err);
    }
  };

  const handleDelete = async (taskId: string) => {
    if (!confirm('确定要删除这个定时任务吗？')) return;

    try {
      await deleteTask(taskId);
    } catch (err) {
      console.error('Failed to delete task:', err);
    }
  };

  const handleExecute = async (taskId: string) => {
    try {
      await executeNow(taskId);
      await fetchTasks();
      const poll = setInterval(async () => {
        await fetchTasks();
        const task = useCronStore.getState().tasks.find(t => t.id === taskId);
        if (!task?.latestRun || task.latestRun.status !== 'running') {
          clearInterval(poll);
        }
      }, 5000);
    } catch (err) {
      console.error('Failed to execute task:', err);
    }
  };

  const handleScan = async () => {
    try {
      const result = await scanCronTasks();
      const parts: string[] = [];
      if (result.created > 0) parts.push(`新建 ${result.created}`);
      if (result.updated > 0) parts.push(`更新 ${result.updated}`);
      if (result.disabled > 0) parts.push(`禁用 ${result.disabled}`);
      setScanResult(parts.length > 0 ? parts.join('，') : '无变更');
      setTimeout(() => setScanResult(null), 5000);
    } catch (err) {
      console.error('Failed to scan:', err);
    }
  };

  // 选中 skill 后自动填充 cronExpression（如果 skill 有自带表达式）
  const handleSkillSelect = (skillName: string) => {
    setSelectedSkill(skillName);
    const skill = skills.find(s => s.name === skillName);
    if (skill?.cronExpression) {
      setCronExpression(skill.cronExpression);
    }
  };

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h3 className="text-lg font-medium">定时任务</h3>
          <p className="text-sm text-muted-foreground">
            配置定时执行 Skill 的任务（标准 cron 表达式）
          </p>
        </div>
        <div className="flex items-center gap-2">
          <Button
            variant="outline"
            onClick={handleScan}
            disabled={scanning}
          >
            <RefreshCw className={cn('h-4 w-4 mr-2', scanning && 'animate-spin')} />
            {scanning ? '扫描中...' : '自动拉取'}
          </Button>
          <Button onClick={() => { setEditingTask(null); setShowForm(!showForm); }}>
            <Plus className="h-4 w-4 mr-2" />
            新建任务
          </Button>
        </div>
      </div>

      {scanResult && (
        <div className="p-3 rounded-lg bg-green-500/10 text-green-600 text-sm">
          扫描完成：{scanResult}
        </div>
      )}

      {error && (
        <div className="p-3 rounded-lg bg-destructive/10 text-destructive text-sm flex items-center justify-between">
          <span>{error}</span>
          <Button variant="ghost" size="sm" onClick={clearError}>
            关闭
          </Button>
        </div>
      )}

      {(showForm || isEditing) && (
        <div className="p-4 rounded-lg border bg-muted/50 space-y-4">
          <div className="grid grid-cols-2 gap-4">
            <div className="space-y-2">
              <Label>业务系统</Label>
              <Select value={selectedWorkspace} onValueChange={setSelectedWorkspace}>
                <SelectTrigger>
                  <SelectValue placeholder="选择业务系统" />
                </SelectTrigger>
                <SelectContent>
                  {workspaces.map((ws) => (
                    <SelectItem key={ws} value={ws}>
                      {ws.split(/[/\\]/).pop()}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>

            <div className="space-y-2">
              <Label>Skill</Label>
              <Select
                value={selectedSkill}
                onValueChange={handleSkillSelect}
                disabled={!selectedWorkspace}
              >
                <SelectTrigger>
                  <SelectValue placeholder="选择 Skill" />
                </SelectTrigger>
                <SelectContent>
                  {skills.map((skill) => (
                    <SelectItem key={skill.name} value={skill.name}>
                      {skill.name}
                      {!skill.hasCrontab && (
                        <span className="text-muted-foreground ml-1">(无 crontab.md)</span>
                      )}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
          </div>

          <div className="space-y-2">
            <Label>Cron 表达式</Label>
            <Input
              type="text"
              placeholder="*/30 * * * *（分 时 日 月 周）"
              value={cronExpression}
              onChange={(e) => setCronExpression(e.target.value)}
              className="font-mono"
            />
            <p className="text-xs text-muted-foreground">
              格式：分 时 日 月 周（例：0 9 * * 1-5 = 工作日每天 9 点）
            </p>
          </div>

          <div className="flex justify-end gap-2">
            <Button variant="outline" onClick={handleCancel}>
              取消
            </Button>
            <Button
              onClick={handleSave}
              disabled={!selectedWorkspace || !selectedSkill || !cronExpression}
            >
              {isEditing ? '保存' : '创建'}
            </Button>
          </div>
        </div>
      )}

      <div className="space-y-2">
        {loading && (
          <div className="text-center py-8 text-muted-foreground">加载中...</div>
        )}
        {!loading && tasks.length === 0 && (
          <div className="text-center py-8 text-muted-foreground">
            暂无定时任务，点击「自动拉取」扫描或「新建任务」手动创建
          </div>
        )}
        {!loading && [...tasks].sort((a, b) => {
          // 按 workspace 分组，同一 workspace 内 scan 排前
          const wsComp = a.workspace.localeCompare(b.workspace);
          if (wsComp !== 0) return wsComp;
          if (a.source !== b.source) return a.source === 'scan' ? -1 : 1;
          return 0;
        }).map((task) => (
          <TaskItem
            key={task.id}
            task={task}
            onDelete={() => handleDelete(task.id)}
            onToggle={() => handleToggle(task)}
            onExecute={() => handleExecute(task.id)}
            onEdit={() => handleEdit(task)}
          />
        ))}
      </div>
    </div>
  );
}
