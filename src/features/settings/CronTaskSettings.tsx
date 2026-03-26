import { useEffect, useState } from 'react';
import { Plus, Trash2, Play, Pencil, CheckCircle, XCircle, Clock, Loader2, Timer } from 'lucide-react';
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
        </div>
        <div className="flex items-center gap-2 mt-1 text-xs text-muted-foreground">
          <Clock className="h-3 w-3" />
          <span>每 {task.intervalMinutes} 分钟</span>
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
  const [intervalValue, setIntervalValue] = useState('30');
  const [intervalUnit, setIntervalUnit] = useState<'minutes' | 'hours'>('minutes');

  const isEditing = editingTask !== null;

  const {
    workspaces,
    skills,
    tasks,
    loading,
    error,
    fetchWorkspaces,
    fetchSkills,
    fetchTasks,
    createTask,
    updateTask,
    deleteTask,
    executeNow,
    clearError,
  } = useCronStore();

  useEffect(() => {
    fetchWorkspaces();
    fetchTasks();
  }, [fetchWorkspaces, fetchTasks]);

  useEffect(() => {
    if (selectedWorkspace) {
      fetchSkills(selectedWorkspace);
      setSelectedSkill('');
    }
  }, [selectedWorkspace, fetchSkills]);

  const handleSave = async () => {
    if (!selectedWorkspace || !selectedSkill) return;

    const intervalMinutes =
      intervalUnit === 'hours' ? parseInt(intervalValue) * 60 : parseInt(intervalValue);

    if (isNaN(intervalMinutes) || intervalMinutes < 1) {
      alert('请输入有效的间隔时间');
      return;
    }

    try {
      if (isEditing) {
        await updateTask(editingTask.id, { workspace: selectedWorkspace, skillName: selectedSkill, intervalMinutes });
        setEditingTask(null);
      } else {
        await createTask(selectedWorkspace, selectedSkill, intervalMinutes);
        setShowForm(false);
      }
      setSelectedWorkspace('');
      setSelectedSkill('');
      setIntervalValue('30');
      setIntervalUnit('minutes');
    } catch (err) {
      console.error('Failed to save task:', err);
    }
  };

  const handleEdit = (task: CronTask) => {
    setEditingTask(task);
    setSelectedWorkspace(task.workspace);
    setSelectedSkill(task.skillName);
    // 根据 intervalMinutes 推算值和单位
    if (task.intervalMinutes >= 60 && task.intervalMinutes % 60 === 0) {
      setIntervalValue(String(task.intervalMinutes / 60));
      setIntervalUnit('hours');
    } else {
      setIntervalValue(String(task.intervalMinutes));
      setIntervalUnit('minutes');
    }
  };

  const handleCancel = () => {
    setShowForm(false);
    setEditingTask(null);
    setSelectedWorkspace('');
    setSelectedSkill('');
    setIntervalValue('30');
    setIntervalUnit('minutes');
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
      // 轮询刷新直到任务不再是 running 状态
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

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h3 className="text-lg font-medium">定时任务</h3>
          <p className="text-sm text-muted-foreground">
            配置定时执行 Skill 的任务
          </p>
        </div>
        <Button onClick={() => { setEditingTask(null); setShowForm(!showForm); }}>
          <Plus className="h-4 w-4 mr-2" />
          新建任务
        </Button>
      </div>

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
                onValueChange={setSelectedSkill}
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
            <Label>执行间隔</Label>
            <div className="flex items-center gap-2">
              <Input
                type="number"
                min={1}
                max={60}
                value={intervalValue}
                onChange={(e) => setIntervalValue(e.target.value)}
                className="w-20"
              />
              <Select
                value={intervalUnit}
                onValueChange={(v) => setIntervalUnit(v as 'minutes' | 'hours')}
              >
                <SelectTrigger className="w-24">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="minutes">分钟</SelectItem>
                  <SelectItem value="hours">小时</SelectItem>
                </SelectContent>
              </Select>
            </div>
          </div>

          <div className="flex justify-end gap-2">
            <Button variant="outline" onClick={handleCancel}>
              取消
            </Button>
            <Button
              onClick={handleSave}
              disabled={!selectedWorkspace || !selectedSkill}
            >
              {isEditing ? '保存' : '创建'}
            </Button>
          </div>
        </div>
      )}

      <div className="space-y-2">
        {loading ? (
          <div className="text-center py-8 text-muted-foreground">加载中...</div>
        ) : tasks.length === 0 ? (
          <div className="text-center py-8 text-muted-foreground">
            暂无定时任务，点击"新建任务"开始配置
          </div>
        ) : (
          tasks.map((task) => (
            <TaskItem
              key={task.id}
              task={task}
              onDelete={() => handleDelete(task.id)}
              onToggle={() => handleToggle(task)}
              onExecute={() => handleExecute(task.id)}
              onEdit={() => handleEdit(task)}
            />
          ))
        )}
      </div>
    </div>
  );
}
