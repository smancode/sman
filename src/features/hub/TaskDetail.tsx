import { useState, useMemo } from 'react';
import { t } from '@/locales';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Badge } from '@/components/ui/badge';
import { ScrollArea } from '@/components/ui/scroll-area';
import { Separator } from '@/components/ui/separator';
import { useTaskDetail, useConfirmTask, useRejectTask, useDispatchTask, useApproveReport, useRejectReport, useStopTask } from '@/queries/use-hub';
import type { Task, EvaluationReport, TaskAssignment, Agent, Subtask } from '@/schemas/hub';
import { ChevronLeft, Check, X, Send, FileText, StopCircle } from 'lucide-react';
import { cn } from '@/lib/utils';

interface TaskDetailProps {
  taskId: string;
  agents: Agent[];
  onBack: () => void;
}

export function TaskDetail({ taskId, agents, onBack }: TaskDetailProps) {
  const { data: detail, isLoading } = useTaskDetail(taskId);
  const confirmTask = useConfirmTask();
  const rejectTask = useRejectTask();
  const dispatchTask = useDispatchTask();
  const stopTask = useStopTask();

  if (isLoading) return <div className="p-4 text-sm text-muted-foreground">{t('common.loading')}</div>;
  if (!detail) return <div className="p-4 text-sm text-muted-foreground">{t('hub.task.notFound')}</div>;

  const { task, events, evaluations, assignments } = detail;
  const subtasks = parseSubtasks(task.subtasks);
  const canConfirm = task.status === 'evaluating' && evaluations.length > 0;
  const canDispatch = task.status === 'confirmed';
  const canReject = task.status === 'evaluating';
  const canStop = task.status === 'dispatched' || task.status === 'running';

  return (
    <div className="flex h-full flex-col">
      {/* Header */}
      <div className="flex items-center gap-2 border-b px-4 py-2.5">
        <button onClick={onBack} className="rounded p-1 text-muted-foreground hover:text-foreground">
          <ChevronLeft className="h-4 w-4" />
        </button>
        <div className="flex-1 min-w-0">
          <h3 className="text-sm font-medium truncate">{task.title}</h3>
          <div className="flex items-center gap-2 mt-0.5">
            <StatusBadge status={task.status} />
            {task.git_branch && (
              <span className="text-[11px] font-mono text-muted-foreground">{task.git_branch}</span>
            )}
          </div>
        </div>
        <div className="flex items-center gap-1.5 shrink-0">
          {canStop && (
            <Button variant="outline" size="sm" className="h-7 text-xs text-orange-600 hover:text-orange-700" onClick={() => stopTask.mutate({ taskId })}>
              <StopCircle className="h-3.5 w-3.5 mr-1" />
              {t('hub.task.stop')}
            </Button>
          )}
          {canConfirm && (
            <Button size="sm" className="h-7 text-xs" onClick={() => confirmTask.mutate({ taskId })}>
              <Check className="h-3.5 w-3.5 mr-1" />
              {t('hub.task.confirm')}
            </Button>
          )}
          {canReject && (
            <Button variant="outline" size="sm" className="h-7 text-xs" onClick={() => rejectTask.mutate({ taskId })}>
              <X className="h-3.5 w-3.5 mr-1" />
              {t('hub.task.reject')}
            </Button>
          )}
        </div>
      </div>

      <ScrollArea className="flex-1">
        <div className="p-4 space-y-4">
          {/* Description & Acceptance Criteria */}
          {task.description && (
            <div>
              <h4 className="text-xs font-medium text-muted-foreground mb-1">{t('hub.task.description')}</h4>
              <p className="text-sm">{task.description}</p>
            </div>
          )}
          {task.acceptance_criteria && (
            <div>
              <h4 className="text-xs font-medium text-muted-foreground mb-1">{t('hub.task.acceptance')}</h4>
              <p className="text-sm whitespace-pre-wrap">{task.acceptance_criteria}</p>
            </div>
          )}

          {/* Subtasks */}
          {subtasks.length > 0 && (
            <div>
              <h4 className="text-xs font-medium text-muted-foreground mb-1">{t('hub.task.subtasks')}</h4>
              <div className="space-y-1">
                {subtasks.map(st => (
                  <SubtaskRow
                    key={st.id}
                    subtask={st}
                    evaluations={evaluations}
                    assignments={assignments}
                  />
                ))}
              </div>
            </div>
          )}

          {/* Evaluation Reports */}
          {evaluations.length > 0 && (
            <>
              <Separator />
              <div>
                <h4 className="text-xs font-medium text-muted-foreground mb-2">
                  {t('hub.task.evaluationReports')} ({evaluations.length})
                </h4>
                <div className="space-y-2">
                  {evaluations.map(report => (
                    <EvaluationCard
                      key={report.id}
                      report={report}
                      subtasks={subtasks}
                      agents={agents}
                    />
                  ))}
                </div>
              </div>
            </>
          )}

          {/* Dispatch UI */}
          {canDispatch && (
            <>
              <Separator />
              <DispatchPanel
                task={task}
                evaluations={evaluations}
                agents={agents}
                subtasks={subtasks}
                onDispatch={(assignments) => dispatchTask.mutate({ taskId, assignments })}
              />
            </>
          )}

          {/* Assignments */}
          {assignments.length > 0 && (
            <>
              <Separator />
              <div>
                <h4 className="text-xs font-medium text-muted-foreground mb-2">
                  {t('hub.task.assignments')} ({assignments.length})
                </h4>
                <div className="space-y-1.5">
                  {assignments.map(asgn => (
                    <div key={asgn.id} className="flex items-center gap-2 text-xs rounded-md border p-2">
                      <Badge variant="secondary" className="text-[10px]">{asgn.agent_id.slice(0, 12)}</Badge>
                      <span className="flex-1 truncate text-muted-foreground">{asgn.workspace}</span>
                      <AssignmentStatusBadge status={asgn.status} />
                    </div>
                  ))}
                </div>
              </div>
            </>
          )}

          {/* Events */}
          {events.length > 0 && (
            <>
              <Separator />
              <div>
                <h4 className="text-xs font-medium text-muted-foreground mb-2">
                  {t('hub.task.events')}
                </h4>
                <div className="space-y-1">
                  {events.map(ev => (
                    <div key={ev.id} className="flex items-center gap-2 text-[11px] text-muted-foreground">
                      <span className="font-mono">{formatTime(ev.created_at)}</span>
                      <Badge variant="outline" className="text-[9px] h-4">{ev.event}</Badge>
                      {ev.actor && <span>{ev.actor.slice(0, 12)}</span>}
                    </div>
                  ))}
                </div>
              </div>
            </>
          )}

          {/* Result / Error */}
          {task.result && (
            <>
              <Separator />
              <div>
                <h4 className="text-xs font-medium text-muted-foreground mb-1">{t('hub.task.result')}</h4>
                <p className="text-sm whitespace-pre-wrap">{task.result}</p>
              </div>
            </>
          )}
          {task.error && (
            <div>
              <h4 className="text-xs font-medium text-muted-foreground mb-1">{t('hub.task.error')}</h4>
              <p className="text-sm text-destructive whitespace-pre-wrap">{task.error}</p>
            </div>
          )}
        </div>
      </ScrollArea>
    </div>
  );
}

function StatusBadge({ status }: { status: string }) {
  const map: Record<string, { label: string; cls: string }> = {
    evaluating: { label: t('hub.task.evaluating'), cls: 'bg-purple-100 text-purple-700 dark:bg-purple-900/30 dark:text-purple-400' },
    confirmed: { label: t('hub.task.confirmed'), cls: 'bg-amber-100 text-amber-700 dark:bg-amber-900/30 dark:text-amber-400' },
    rejected: { label: t('hub.task.rejected'), cls: 'bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-400' },
    dispatched: { label: t('hub.task.dispatched'), cls: 'bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-400' },
    running: { label: t('hub.task.running'), cls: 'bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-400' },
    completed: { label: t('hub.task.completed'), cls: 'bg-gray-100 text-gray-600 dark:bg-gray-800/40 dark:text-gray-400' },
    failed: { label: t('hub.task.failed'), cls: 'bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-400' },
    cancelled: { label: t('hub.task.cancelled'), cls: 'bg-gray-100 text-gray-600 dark:bg-gray-800/40 dark:text-gray-400' },
    stopping: { label: t('hub.task.stopping'), cls: 'bg-orange-100 text-orange-700 dark:bg-orange-900/30 dark:text-orange-400' },
  };
  const info = map[status] || { label: status, cls: 'bg-gray-100 text-gray-600' };
  return <span className={cn('inline-block rounded px-1.5 py-0.5 text-[11px] font-medium', info.cls)}>{info.label}</span>;
}

function AssignmentStatusBadge({ status }: { status: string }) {
  const map: Record<string, { label: string; cls: string }> = {
    assigned: { label: t('hub.task.assigned'), cls: 'bg-blue-100 text-blue-700 dark:bg-blue-900/30' },
    running: { label: t('hub.task.running'), cls: 'bg-green-100 text-green-700 dark:bg-green-900/30' },
    completed: { label: t('hub.task.completed'), cls: 'bg-gray-100 text-gray-600' },
    failed: { label: t('hub.task.failed'), cls: 'bg-red-100 text-red-700 dark:bg-red-900/30' },
  };
  const info = map[status] || { label: status, cls: 'bg-gray-100 text-gray-600' };
  return <span className={cn('rounded px-1 py-0.5 text-[10px]', info.cls)}>{info.label}</span>;
}

function SubtaskRow({ subtask, evaluations, assignments }: {
  subtask: Subtask;
  evaluations: EvaluationReport[];
  assignments: TaskAssignment[];
}) {
  const claimers = evaluations.filter(e => {
    try { return JSON.parse(e.claimed_subtasks).includes(subtask.id); } catch { return false; }
  });
  const assignee = assignments.find(a => {
    try { return JSON.parse(a.subtask_ids).includes(subtask.id); } catch { return false; }
  });

  return (
    <div className="flex items-center gap-2 text-xs rounded border p-1.5">
      <Badge variant="outline" className="text-[9px] h-4 shrink-0">{subtask.id}</Badge>
      <span className="flex-1 truncate">{subtask.name}</span>
      {claimers.length > 0 && (
        <span className="text-[10px] text-muted-foreground">
          {claimers.length} {t('hub.task.claimers')}
        </span>
      )}
      {assignee && (
        <Badge variant="secondary" className="text-[9px] h-4">
          {assignee.agent_id.slice(0, 8)}
        </Badge>
      )}
    </div>
  );
}

function EvaluationCard({ report, subtasks, agents }: {
  report: EvaluationReport;
  subtasks: Subtask[];
  agents: Agent[];
}) {
  const approveReport = useApproveReport();
  const rejectReport = useRejectReport();
  const [expanded, setExpanded] = useState(false);

  const agent = agents.find(a => a.id === report.agent_id);
  const claimedIds: string[] = (() => {
    try { return JSON.parse(report.claimed_subtasks); } catch { return []; }
  })();
  const complexityMap: Record<string, string> = {
    simple: 'bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-400',
    medium: 'bg-yellow-100 text-yellow-700 dark:bg-yellow-900/30 dark:text-yellow-400',
    complex: 'bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-400',
  };

  return (
    <div className="rounded-md border p-2.5 space-y-1.5">
      <div className="flex items-center gap-2">
        <Badge variant="secondary" className="text-[10px]">{report.agent_id.slice(0, 12)}</Badge>
        {agent && <span className="text-[11px] text-muted-foreground truncate">{agent.workspace}</span>}
        {report.complexity && (
          <span className={cn('ml-auto rounded px-1 py-0.5 text-[10px]', complexityMap[report.complexity] || 'bg-gray-100 text-gray-600')}>
            {report.complexity}
          </span>
        )}
        <ReportStatusBadge status={report.status} />
      </div>

      <div className="flex flex-wrap gap-1">
        {claimedIds.map(id => {
          const st = subtasks.find(s => s.id === id);
          return (
            <Badge key={id} variant="outline" className="text-[9px] h-4">
              {st ? st.name : id}
            </Badge>
          );
        })}
      </div>

      {report.approach && (
        <button
          onClick={() => setExpanded(!expanded)}
          className="text-xs text-muted-foreground hover:text-foreground text-left"
        >
          <FileText className="h-3 w-3 inline mr-1" />
          {expanded ? t('hub.task.hideApproach') : t('hub.task.showApproach')}
        </button>
      )}
      {expanded && report.approach && (
        <p className="text-xs text-muted-foreground whitespace-pre-wrap">{report.approach}</p>
      )}

      {report.status === 'pending' && (
        <div className="flex gap-1.5 pt-1">
          <Button variant="outline" size="sm" className="h-6 text-[11px] px-2" onClick={() => approveReport.mutate({ reportId: report.id })}>
            <Check className="h-2.5 w-2.5 mr-0.5" />
            {t('hub.task.approve')}
          </Button>
          <Button variant="ghost" size="sm" className="h-6 text-[11px] px-2" onClick={() => rejectReport.mutate({ reportId: report.id })}>
            <X className="h-2.5 w-2.5 mr-0.5" />
            {t('hub.task.rejectReport')}
          </Button>
        </div>
      )}
    </div>
  );
}

function ReportStatusBadge({ status }: { status: string }) {
  const map: Record<string, { label: string; cls: string }> = {
    pending: { label: t('hub.task.pending'), cls: 'bg-yellow-100 text-yellow-700 dark:bg-yellow-900/30' },
    approved: { label: t('hub.task.approved'), cls: 'bg-green-100 text-green-700 dark:bg-green-900/30' },
    rejected: { label: t('hub.task.rejected'), cls: 'bg-red-100 text-red-700 dark:bg-red-900/30' },
  };
  const info = map[status] || { label: status, cls: 'bg-gray-100 text-gray-600' };
  return <span className={cn('rounded px-1 py-0.5 text-[10px]', info.cls)}>{info.label}</span>;
}

function DispatchPanel({ task, evaluations, agents, subtasks, onDispatch }: {
  task: Task;
  evaluations: EvaluationReport[];
  agents: Agent[];
  subtasks: Subtask[];
  onDispatch: (assignments: Array<{ agentId: string; workspace: string; subtaskIds: string[]; instructions?: string }>) => void;
}) {
  const approvedReports = evaluations.filter(e => e.status === 'approved');
  const [assignments, setAssignments] = useState<Record<string, string>>({});
  const [instructions, setInstructions] = useState<Record<string, string>>({});

  // Build coverage: which agent can do which subtask
  const agentCapabilities = useMemo(() => {
    const caps: Record<string, string[]> = {};
    for (const report of approvedReports) {
      try {
        caps[report.agent_id] = JSON.parse(report.claimed_subtasks);
      } catch { caps[report.agent_id] = []; }
    }
    return caps;
  }, [approvedReports]);

  const handleDispatch = () => {
    // Group subtask IDs by assigned agent
    const agentMap: Record<string, { subtaskIds: string[]; workspace: string }> = {};
    for (const [subtaskId, agentId] of Object.entries(assignments)) {
      if (!agentId) continue;
      if (!agentMap[agentId]) {
        const agent = agents.find(a => a.id === agentId);
        agentMap[agentId] = { subtaskIds: [], workspace: agent?.workspace || '' };
      }
      agentMap[agentId].subtaskIds.push(subtaskId);
    }

    const result = Object.entries(agentMap).map(([agentId, data]) => ({
      agentId,
      workspace: data.workspace,
      subtaskIds: data.subtaskIds,
      instructions: instructions[agentId] || undefined,
    }));

    if (result.length > 0) onDispatch(result);
  };

  const allAssigned = subtasks.length === 0 || subtasks.every(st => assignments[st.id]);

  return (
    <div className="space-y-3">
      <h4 className="text-xs font-medium text-muted-foreground">{t('hub.task.dispatchTitle')}</h4>

      {subtasks.map(st => {
        const capableAgents = approvedReports
          .filter(r => { try { return JSON.parse(r.claimed_subtasks).includes(st.id); } catch { return false; } })
          .map(r => r.agent_id);

        return (
          <div key={st.id} className="flex items-center gap-2 text-xs">
            <Badge variant="outline" className="text-[9px] h-4 w-16 justify-center shrink-0">{st.id}</Badge>
            <span className="w-32 truncate">{st.name}</span>
            <select
              value={assignments[st.id] || ''}
              onChange={(e) => setAssignments({ ...assignments, [st.id]: e.target.value })}
              className="h-6 flex-1 rounded border bg-background px-1.5 text-xs"
            >
              <option value="">{t('hub.task.selectAgent')}</option>
              {capableAgents.map(agentId => (
                <option key={agentId} value={agentId}>{agentId.slice(0, 12)}</option>
              ))}
            </select>
          </div>
        );
      })}

      {agents.length > 0 && (
        <div className="space-y-1.5">
          {agents.map(agent => (
            <div key={agent.id} className="space-y-1">
              <span className="text-[11px] text-muted-foreground">{agent.id.slice(0, 12)}</span>
              <Input
                value={instructions[agent.id] || ''}
                onChange={(e) => setInstructions({ ...instructions, [agent.id]: e.target.value })}
                placeholder={t('hub.task.instructionsPlaceholder')}
                className="h-6 text-xs"
              />
            </div>
          ))}
        </div>
      )}

      <Button size="sm" onClick={handleDispatch} disabled={!allAssigned}>
        <Send className="h-3 w-3 mr-1" />
        {t('hub.task.dispatch')}
      </Button>
    </div>
  );
}

function parseSubtasks(json: string): Subtask[] {
  try {
    const arr = JSON.parse(json);
    return Array.isArray(arr) ? arr : [];
  } catch {
    return [];
  }
}

function formatTime(iso: string): string {
  try {
    return new Date(iso).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' });
  } catch {
    return iso;
  }
}
