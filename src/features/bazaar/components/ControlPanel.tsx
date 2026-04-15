// src/features/bazaar/components/ControlPanel.tsx
// 右侧控制面板 — 深色科技风
// 任务队列 + 声望排行 + 在线 Agent + 任务阶段进度

import { useState } from 'react';
import { useBazaarStore } from '@/stores/bazaar';
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/ui/collapsible';
import { ScrollArea } from '@/components/ui/scroll-area';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { ChevronDown, Trophy, Check, X } from 'lucide-react';
import { cn } from '@/lib/utils';
import { getReputationLevel } from './ReputationUtils';

// ── Shared section header ──

function SectionHeader({ label, count, open }: { label: string; count?: number; open: boolean }) {
  return (
    <CollapsibleTrigger asChild>
      <button
        className="flex items-center justify-between w-full px-3 py-2 text-xs font-medium uppercase tracking-wider rounded transition-colors hover:bg-white/5"
        style={{ color: 'var(--bz-text-dim)' }}
      >
        <div className="flex items-center gap-2">
          {open ? <ChevronDown className="h-3 w-3" /> : <ChevronRightIcon className="h-3 w-3" />}
          {label}
          {count !== undefined && (
            <span className="font-mono text-[10px] px-1.5 py-0.5 rounded" style={{ background: 'var(--bz-bg)', color: 'var(--bz-cyan)' }}>{count}</span>
          )}
        </div>
      </button>
    </CollapsibleTrigger>
  );
}

// ── Task Phase Progress ──

const TASK_PHASES = ['searching', 'offered', 'matched', 'chatting', 'completed'] as const;
const PHASE_LABELS: Record<string, string> = {
  searching: '搜索',
  offered: '邀约',
  matched: '匹配',
  chatting: '执行',
  completed: '结算',
};
const PHASE_COLORS: Record<string, string> = {
  searching: 'var(--bz-purple)',
  offered: 'var(--bz-amber)',
  matched: 'var(--bz-blue)',
  chatting: 'var(--bz-cyan)',
  completed: 'var(--bz-green)',
};

function TaskPhaseBar({ status }: { status: string }) {
  const currentIdx = TASK_PHASES.indexOf(status as any);
  if (currentIdx < 0) return null;

  return (
    <div className="flex items-center gap-0.5">
      {TASK_PHASES.map((phase, i) => {
        const isActive = phase === status;
        const isPast = i < currentIdx;
        const color = PHASE_COLORS[phase];
        return (
          <div key={phase} className="flex-1 flex flex-col items-center gap-0.5">
            <div
              className="w-full h-1 rounded-full transition-all duration-300"
              style={{
                background: isPast || isActive ? color : 'var(--bz-bg)',
                boxShadow: isActive ? `0 0 4px ${color}` : undefined,
              }}
            />
            <span className="text-[8px]" style={{ color: isActive ? color : 'var(--bz-text-dim)' }}>{PHASE_LABELS[phase]}</span>
          </div>
        );
      })}
    </div>
  );
}

// ── Task Queue ──

function TaskQueue() {
  const { tasks, acceptTask, rejectTask } = useBazaarStore();
  const pending = tasks.filter((t) => t.status === 'offered' || t.status === 'searching');
  const [open, setOpen] = useState(true);

  return (
    <Collapsible open={open} onOpenChange={setOpen}>
      <SectionHeader label="任务队列" count={pending.length} open={open} />
      <CollapsibleContent>
        <div className="space-y-2 px-3 pb-2">
          {pending.length === 0 ? (
            <p className="text-[10px] py-1" style={{ color: 'var(--bz-text-dim)' }}>所有战线静默</p>
          ) : (
            pending.map((task) => (
              <div key={task.taskId} className="rounded p-2 space-y-1.5" style={{ background: 'var(--bz-bg)', border: '1px solid var(--bz-border)' }}>
                <div className="text-xs font-medium flex items-center gap-1">
                  <span style={{ color: task.direction === 'outgoing' ? 'var(--bz-cyan)' : 'var(--bz-green)' }}>
                    {task.direction === 'outgoing' ? '→ ' : '← '}
                  </span>
                  <span style={{ color: 'var(--bz-text)' }}>{task.helperName ?? task.requesterName ?? '未知节点'}</span>
                </div>
                <p className="text-[10px] line-clamp-2" style={{ color: 'var(--bz-text-dim)' }}>{task.question}</p>
                <TaskPhaseBar status={task.status} />
                <div className="flex gap-1 pt-0.5">
                  <Button size="sm" className="h-5 text-[10px] px-2" style={{ background: 'var(--bz-green)', color: '#000' }} onClick={() => acceptTask(task.taskId)}>
                    <Check className="h-2.5 w-2.5 mr-0.5" />接入
                  </Button>
                  <Button variant="outline" size="sm" className="h-5 text-[10px] px-2" style={{ borderColor: 'var(--bz-border)', color: 'var(--bz-text-dim)' }} onClick={() => rejectTask(task.taskId)}>
                    <X className="h-2.5 w-2.5 mr-0.5" />拒接
                  </Button>
                </div>
              </div>
            ))
          )}
        </div>
      </CollapsibleContent>
    </Collapsible>
  );
}

// ── Leaderboard ──

function Leaderboard() {
  const { leaderboard, connection } = useBazaarStore();
  const [open, setOpen] = useState(true);

  return (
    <Collapsible open={open} onOpenChange={setOpen}>
      <SectionHeader label="声望排行" open={open as boolean} />
      <CollapsibleContent>
        <div className="px-3 pb-2 space-y-0.5">
          {leaderboard.slice(0, 10).map((entry, i) => {
            const isMe = entry.agentId === connection.agentId;
            const level = getReputationLevel(entry.reputation);
            return (
              <div
                key={entry.agentId}
                className={cn('flex items-center justify-between py-1 px-1.5 rounded text-xs')}
                style={isMe ? { background: 'rgba(6, 182, 212, 0.1)', border: '1px solid rgba(6, 182, 212, 0.2)' } : {}}
              >
                <div className="flex items-center gap-2 min-w-0">
                  <span className="w-4 font-mono text-[10px]" style={{ color: 'var(--bz-text-dim)' }}>{i + 1}.</span>
                  <span className="text-[10px]" style={{ color: level.color }}>{level.icon}</span>
                  <span className={cn('truncate', isMe && 'font-medium')} style={{ color: 'var(--bz-text)' }}>{entry.name}</span>
                </div>
                <span className="font-mono text-[10px] flex items-center gap-0.5" style={{ color: 'var(--bz-amber)' }}>
                  <Trophy className="h-2.5 w-2.5" />{entry.reputation}
                </span>
              </div>
            );
          })}
          {leaderboard.length === 0 && (
            <p className="text-[10px] py-1" style={{ color: 'var(--bz-text-dim)' }}>排行榜数据待加载</p>
          )}
        </div>
      </CollapsibleContent>
    </Collapsible>
  );
}

// ── Online Agents ──

function OnlineAgentList() {
  const { onlineAgents, connection } = useBazaarStore();
  const [open, setOpen] = useState(true);

  return (
    <Collapsible open={open} onOpenChange={setOpen}>
      <SectionHeader label="在线节点" count={onlineAgents.length} open={open} />
      <CollapsibleContent>
        <div className="px-3 pb-2 space-y-0.5">
          {onlineAgents.map((agent) => {
            const isMe = agent.agentId === connection.agentId;
            const statusColor = agent.status === 'idle' ? 'var(--bz-green)' : agent.status === 'busy' ? 'var(--bz-amber)' : 'var(--bz-text-dim)';
            return (
              <div
                key={agent.agentId}
                className={cn('flex items-center justify-between py-1 px-1.5 rounded text-xs')}
                style={isMe ? { background: 'rgba(6, 182, 212, 0.1)' } : {}}
              >
                <div className="flex items-center gap-2 min-w-0">
                  <span className="w-2 h-2 rounded-full flex-shrink-0" style={{ background: statusColor, boxShadow: `0 0 4px ${statusColor}` }} />
                  <span className="truncate" style={{ color: 'var(--bz-text)' }}>{agent.name}</span>
                </div>
                <span className="font-mono text-[10px]" style={{ color: 'var(--bz-text-dim)' }}>{agent.reputation}</span>
              </div>
            );
          })}
          {onlineAgents.length === 0 && (
            <p className="text-[10px] py-1" style={{ color: 'var(--bz-text-dim)' }}>无活跃节点</p>
          )}
        </div>
      </CollapsibleContent>
    </Collapsible>
  );
}

// ── Main ControlPanel ──

export function ControlPanel() {
  const [collapsed, setCollapsed] = useState(false);

  if (collapsed) {
    return (
      <button
        onClick={() => setCollapsed(false)}
        className="w-8 flex-shrink-0 flex items-center justify-center hover:bg-white/5 transition-colors"
        style={{ borderLeft: '1px solid var(--bz-border)', background: 'var(--bz-bg-panel)' }}
      >
        <ChevronLeft className="h-4 w-4" style={{ color: 'var(--bz-text-dim)' }} />
      </button>
    );
  }

  return (
    <div className="w-[260px] flex-shrink-0 flex flex-col" style={{ background: 'var(--bz-bg-panel)', borderLeft: '1px solid var(--bz-border)' }}>
      <div className="flex justify-end px-2 pt-2">
        <button onClick={() => setCollapsed(true)} className="hover:bg-white/5 rounded p-1 transition-colors">
          <ChevronRightIcon className="h-3 w-3" style={{ color: 'var(--bz-text-dim)' }} />
        </button>
      </div>

      <ScrollArea className="flex-1">
        <div className="space-y-1 pb-4">
          <TaskQueue />
          <Leaderboard />
          <OnlineAgentList />
        </div>
      </ScrollArea>
    </div>
  );
}

function ChevronLeft(props: React.SVGProps<SVGSVGElement>) {
  return (
    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" {...props}>
      <path d="m15 18-6-6 6-6" />
    </svg>
  );
}

function ChevronRightIcon(props: React.SVGProps<SVGSVGElement>) {
  return (
    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" {...props}>
      <path d="m9 18 6-6-6-6" />
    </svg>
  );
}
