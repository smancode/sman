import { useEffect, useState } from 'react';
import {
  Plus, Trash2, Play, Pause, Square, RotateCcw,
  Loader2, CheckCircle, XCircle, FileCode, FlaskConical,
  Save, ChevronDown, ChevronUp, Wand2, Timer, Pencil, X,
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
import { t } from '@/locales';
import type { BatchTask, BatchTaskStatus } from '@/types/settings';

const STATUS_CONFIG: Record<BatchTaskStatus, { icon: typeof Loader2; textKey: string; className: string }> = {
  draft: { icon: FileCode, textKey: 'batch.status.draft', className: 'text-muted-foreground' },
  generating: { icon: Loader2, textKey: 'batch.status.generating', className: 'text-blue-500' },
  generated: { icon: FileCode, textKey: 'batch.status.generated', className: 'text-blue-600' },
  testing: { icon: FlaskConical, textKey: 'batch.status.testing', className: 'text-purple-500' },
  tested: { icon: CheckCircle, textKey: 'batch.status.tested', className: 'text-green-600' },
  saved: { icon: Save, textKey: 'batch.status.saved', className: 'text-green-600' },
  queued: { icon: Loader2, textKey: 'batch.status.queued', className: 'text-yellow-600' },
  running: { icon: Play, textKey: 'batch.status.running', className: 'text-blue-500' },
  paused: { icon: Pause, textKey: 'batch.status.paused', className: 'text-yellow-600' },
  completed: { icon: CheckCircle, textKey: 'batch.status.completed', className: 'text-green-600' },
  failed: { icon: XCircle, textKey: 'batch.status.failed', className: 'text-red-600' },
};

function StatusBadge({ status }: { status: BatchTaskStatus }) {
  const config = STATUS_CONFIG[status];
  const Icon = config.icon;
  return (
    <div className={cn('flex items-center gap-1 text-xs', config.className)}>
      <Icon className={cn('h-3 w-3', (status === 'generating' || status === 'testing' || status === 'running') && 'animate-spin')} />
      <span>{t(config.textKey)}</span>
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
  const [editing, setEditing] = useState(false);
  const [editMdContent, setEditMdContent] = useState(task.mdContent);
  const [editExecTemplate, setEditExecTemplate] = useState(task.execTemplate);
  const [editEnvVars, setEditEnvVars] = useState(task.envVars);
  const [editConcurrency, setEditConcurrency] = useState(String(task.concurrency));
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
  const canEdit = !isBusy && !isRunning && !isPaused;

  const handleDelete = async () => {
    if (!confirm(t('batch.confirmDelete'))) return;
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

  const handleSaveEdit = async () => {
    const intervalMin = editUnit === 'hours' ? parseInt(editInterval) * 60 : parseInt(editInterval);
    await updateTask(task.id, {
      mdContent: editMdContent,
      execTemplate: editExecTemplate,
      envVars: editEnvVars,
      concurrency: parseInt(editConcurrency) || 10,
      cronIntervalMinutes: isNaN(intervalMin) ? 60 : intervalMin,
    });
    setEditing(false);
  };

  const handleStartEdit = () => {
    setEditMdContent(task.mdContent);
    setEditExecTemplate(task.execTemplate);
    setEditEnvVars(task.envVars);
    setEditConcurrency(String(task.concurrency));
    setEditInterval(String(task.cronIntervalMinutes ?? 60));
    setEditUnit((task.cronIntervalMinutes ?? 60) >= 60 ? 'hours' : 'minutes');
    setExpanded(true);
    setEditing(true);
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
              <span>{t('batch.successFail').replace('{success}', String(task.successCount)).replace('{failed}', String(task.failedCount))}</span>
            )}
            {task.totalCost > 0 && <span>¥{task.totalCost.toFixed(4)}</span>}
            {task.cronEnabled && (
              <span className="flex items-center gap-1 text-blue-500">
                <Timer className="h-3 w-3" />
                {t('batch.everyMinutes').replace('{minutes}', String(task.cronIntervalMinutes))}
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
            <Wand2 className="h-3.5 w-3.5" /> {t('batch.generate')}
          </Button>
        )}

        {isGenerated && (
          <Button variant="outline" size="sm" className="h-7 gap-1.5 text-xs" onClick={() => testCode(task.id)} disabled={isBusy}>
            <FlaskConical className="h-3.5 w-3.5" /> {t('batch.test')}
          </Button>
        )}
        {testing && (
          <Button variant="outline" size="sm" className="h-7 gap-1.5 text-xs" disabled>
            <FlaskConical className="h-3.5 w-3.5 animate-spin" /> {t('batch.testing')}
          </Button>
        )}

        {isTested && (
          <Button variant="outline" size="sm" className="h-7 gap-1.5 text-xs" onClick={() => saveTask(task.id)} disabled={isBusy}>
            <Save className="h-3.5 w-3.5" /> {t('batch.save')}
          </Button>
        )}

        {isSaved && (
          <Button variant="default" size="sm" className="h-7 gap-1.5 text-xs" onClick={() => executeTask(task.id)} disabled={isBusy}>
            <Play className="h-3.5 w-3.5" /> {t('batch.execute')}
          </Button>
        )}
        {isPaused && (
          <Button variant="default" size="sm" className="h-7 gap-1.5 text-xs" onClick={() => executeTask(task.id)} disabled={isBusy}>
            <Play className="h-3.5 w-3.5" /> {t('batch.continue')}
          </Button>
        )}

        {isRunning && (
          <>
            <Button variant="outline" size="sm" className="h-7 gap-1.5 text-xs" onClick={() => pauseTask(task.id)}>
              <Pause className="h-3.5 w-3.5" /> {t('batch.pause')}
            </Button>
            <Button variant="outline" size="sm" className="h-7 gap-1.5 text-xs text-destructive hover:text-destructive" onClick={() => cancelTask(task.id)}>
              <Square className="h-3.5 w-3.5" /> {t('batch.cancel')}
            </Button>
          </>
        )}

        {isDone && (
          <>
            <Button variant="default" size="sm" className="h-7 gap-1.5 text-xs" onClick={() => executeTask(task.id)} disabled={isBusy}>
              <Play className="h-3.5 w-3.5" /> {t('batch.reExecute')}
            </Button>
            {hasFailed && (
              <Button variant="outline" size="sm" className="h-7 gap-1.5 text-xs" onClick={() => retryFailed(task.id)} disabled={isBusy}>
                <RotateCcw className="h-3.5 w-3.5" /> {t('batch.retryFailed')}
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

        {canEdit && (
          <Button variant="outline" size="sm" className="h-7 gap-1.5 text-xs" onClick={handleStartEdit}>
            <Pencil className="h-3.5 w-3.5" /> {t('batch.edit')}
          </Button>
        )}

        <Button
          variant="ghost"
          size="sm"
          className="h-7 gap-1.5 text-xs text-destructive hover:text-destructive"
          onClick={handleDelete}
          disabled={isRunning}
        >
          <Trash2 className="h-3.5 w-3.5" /> {t('batch.delete')}
        </Button>
      </div>

      {/* Expanded details */}
      {expanded && !editing && (
        <div className="border-t px-3 py-3 text-xs text-muted-foreground space-y-3">
          {/* Basic info */}
          <div className="grid grid-cols-2 gap-x-6 gap-y-1">
            <div><span className="text-foreground font-medium">{t('batch.businessSystem')}</span>{workspaceName}</div>
            <div><span className="text-foreground font-medium">Skill：</span>{task.skillName}</div>
            <div><span className="text-foreground font-medium">{t('batch.concurrency')}</span>{task.concurrency}</div>
            <div><span className="text-foreground font-medium">{t('batch.createdAt')}</span>{new Date(task.createdAt).toLocaleString('zh-CN')}</div>
            {task.startedAt && <div><span className="text-foreground font-medium">{t('batch.startedAt')}</span>{new Date(task.startedAt).toLocaleString('zh-CN')}</div>}
            {task.finishedAt && <div><span className="text-foreground font-medium">{t('batch.finishedAt')}</span>{new Date(task.finishedAt).toLocaleString('zh-CN')}</div>}
          </div>

          {/* Cron info */}
          <div>
            <span className="text-foreground font-medium">{t('batch.scheduledExec')}</span>
            {task.cronEnabled
              ? <span className="text-green-600">{t('batch.scheduledEnabled').replace('{minutes}', String(task.cronIntervalMinutes))}</span>
              : task.cronIntervalMinutes
                ? <span>{t('batch.scheduledPreset').replace('{minutes}', String(task.cronIntervalMinutes))}</span>
                : <span>{t('batch.scheduledDisabled')}</span>
            }
          </div>

          {/* Config content */}
          {task.mdContent && (
            <div>
              <div className="text-foreground font-medium mb-1">{t('batch.taskDesc')}</div>
              <pre className="bg-muted p-2 rounded text-xs overflow-x-auto max-h-40 whitespace-pre-wrap">{task.mdContent}</pre>
            </div>
          )}

          {task.execTemplate && (
            <div>
              <span className="text-foreground font-medium">{t('batch.execTemplate')}</span>
              <code className="bg-muted px-1.5 py-0.5 rounded">{task.execTemplate}</code>
            </div>
          )}

          {task.envVars && (
            <div>
              <div className="text-foreground font-medium mb-1">{t('batch.envVars')}</div>
              <pre className="bg-muted p-2 rounded text-xs overflow-x-auto max-h-24 whitespace-pre-wrap">{task.envVars}</pre>
            </div>
          )}

          {task.generatedCode && (
            <div>
              <div className="text-foreground font-medium mb-1">{t('batch.genCode')}</div>
              <pre className="bg-muted p-2 rounded text-xs overflow-x-auto max-h-40">
                {task.generatedCode.slice(0, 500)}{task.generatedCode.length > 500 ? '...' : ''}
              </pre>
            </div>
          )}
        </div>
      )}

      {/* Edit mode */}
      {expanded && editing && (
        <div className="border-t px-3 py-3 space-y-3">
          <div className="space-y-2">
            <Label className="text-xs">{t('batch.taskDescLabel')}</Label>
            <Textarea
              value={editMdContent}
              onChange={(e) => setEditMdContent(e.target.value)}
              rows={6}
              className="font-mono text-xs"
            />
          </div>

          <div className="space-y-2">
            <Label className="text-xs">{t('batch.execTemplateLabel')}</Label>
            <Input
              value={editExecTemplate}
              onChange={(e) => setEditExecTemplate(e.target.value)}
              className="font-mono text-sm"
            />
          </div>

          <div className="space-y-2">
            <Label className="text-xs">{t('batch.envVars')}</Label>
            <Textarea
              value={editEnvVars}
              onChange={(e) => setEditEnvVars(e.target.value)}
              rows={3}
              className="font-mono text-xs"
            />
          </div>

          <div className="grid grid-cols-3 gap-4">
            <div className="space-y-2">
              <Label className="text-xs">{t('batch.concurrencyLabel')}</Label>
              <Input
                type="number"
                min={1}
                max={50}
                value={editConcurrency}
                onChange={(e) => setEditConcurrency(e.target.value)}
              />
            </div>
            <div className="space-y-2 col-span-2">
              <Label className="text-xs">{t('batch.cronInterval')}</Label>
              <div className="flex items-center gap-2">
                <Input
                  type="number"
                  min={1}
                  max={60}
                  value={editInterval}
                  onChange={(e) => setEditInterval(e.target.value)}
                  className="w-20"
                />
                <Select value={editUnit} onValueChange={(v) => setEditUnit(v as 'minutes' | 'hours')}>
                  <SelectTrigger className="w-20">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="minutes">{t('batch.minutes')}</SelectItem>
                    <SelectItem value="hours">{t('batch.hours')}</SelectItem>
                  </SelectContent>
                </Select>
              </div>
            </div>
          </div>

          <div className="flex justify-end gap-2">
            <Button variant="outline" size="sm" onClick={() => setEditing(false)}>
              <X className="h-3.5 w-3.5 mr-1" /> {t('batch.cancel')}
            </Button>
            <Button size="sm" onClick={handleSaveEdit}>
              <Save className="h-3.5 w-3.5 mr-1" /> {t('batch.save')}
            </Button>
          </div>
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
    setMdContent(`t('batch.mdTemplateTitle')

t('batch.mdDataSource')

t('batch.mdDataFormat')

t('batch.mdExecTemplate')
/test \${name}
`);
  };

  return (
    <div className="p-4 rounded-lg border bg-muted/50 space-y-4">
      <div className="grid grid-cols-2 gap-4">
        <div className="space-y-2">
          <Label>{t('batch.businessSystemLabel')}</Label>
          <Select value={workspace} onValueChange={setWorkspace}>
            <SelectTrigger><SelectValue placeholder={t('batch.selectBusinessSystem')} /></SelectTrigger>
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
            <SelectTrigger><SelectValue placeholder={t('batch.selectSkill')} /></SelectTrigger>
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
          <Label>{t('batch.taskDescLabel')}</Label>
          <Textarea
            value={mdContent}
            onChange={(e) => setMdContent(e.target.value)}
            placeholder={t('batch.taskDescPlaceholder')}
            rows={6}
            className="font-mono text-xs"
          />
        </div>
        <div className="flex flex-col gap-2 pt-6">
          <Button variant="outline" size="sm" onClick={handleLoadTemplate}>
            {t('batch.loadTemplate')}
          </Button>
        </div>
      </div>

      <div className="space-y-2">
        <Label>{t('batch.execTemplateLabel')}</Label>
        <Input
          value={execTemplate}
          onChange={(e) => setExecTemplate(e.target.value)}
          placeholder="/skill-name ${field_name}"
          className="font-mono text-sm"
        />
        <p className="text-xs text-muted-foreground">
          {t('batch.fieldRef')}
        </p>
      </div>

      <div className="space-y-2">
        <Label>{t('batch.envVarsOptional')}</Label>
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
          <Label>{t('batch.concurrencyLabel')}</Label>
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
          <Label>{t('batch.cronInterval')}</Label>
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
                <SelectItem value="minutes">{t('batch.minutes')}</SelectItem>
                <SelectItem value="hours">{t('batch.hours')}</SelectItem>
              </SelectContent>
            </Select>
          </div>
        </div>
      </div>

      <div className="flex justify-end gap-2">
        <Button variant="outline" onClick={onCancel}>
          {t('batch.cancel')}
        </Button>
        <Button
          onClick={handleCreate}
          disabled={!workspace || !skillName || !mdContent.trim()}
        >
          {t('batch.createTask')}
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
          <h3 className="text-lg font-medium">{t('batch.smartTask')}</h3>
          <p className="text-sm text-muted-foreground">
            {t('batch.smartTaskDesc')}
          </p>
        </div>
        <Button onClick={() => setShowForm(!showForm)}>
          <Plus className="h-4 w-4 mr-2" />
          {t('batch.newTask')}
        </Button>
      </div>

      {error && (
        <div className="p-3 rounded-lg bg-destructive/10 text-destructive text-sm flex items-center justify-between">
          <span>{error}</span>
          <Button variant="ghost" size="sm" onClick={clearError}>{t('common.close')}</Button>
        </div>
      )}

      {showForm && <NewTaskForm onCancel={() => setShowForm(false)} />}

      <div className="space-y-6">
        {loading ? (
          <div className="text-center py-8 text-muted-foreground">{t('common.loading')}</div>
        ) : tasks.length === 0 ? (
          <div className="text-center py-8 text-muted-foreground">
            {t('batch.emptyHint')}
          </div>
        ) : (() => {
          const grouped = new Map<string, BatchTask[]>();
          for (const t of tasks) {
            const name = t.workspace.split(/[/\\]/).pop() || t.workspace;
            if (!grouped.has(name)) grouped.set(name, []);
            grouped.get(name)!.push(t);
          }
          const entries = Array.from(grouped.entries()).sort((a, b) => a[0].localeCompare(b[0]));
          return entries.map(([wsName, wsTasks]) => (
            <div key={wsName} id={`batch-ws-${wsName}`} className="scroll-mt-4">
              <h4 className="text-sm font-semibold text-muted-foreground mb-2">{wsName}</h4>
              <div className="space-y-2">
                {wsTasks.map((task) => (
                  <BatchTaskCard key={task.id} task={task} />
                ))}
              </div>
            </div>
          ));
        })()}
      </div>
    </div>
  );
}
