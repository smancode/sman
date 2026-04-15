// src/features/bazaar/components/ControlPanel.tsx
// 右侧可折叠控制面板 — 任务队列 + 声望排行 + 在线 Agent

import { useState } from 'react';
import { useBazaarStore } from '@/stores/bazaar';
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/ui/collapsible';
import { ScrollArea } from '@/components/ui/scroll-area';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { ChevronDown, ChevronRight, Trophy, Check, X } from 'lucide-react';
import { cn } from '@/lib/utils';

// ── Shared section header ──

function SectionHeader({ label, count, open }: { label: string; count?: number; open: boolean }) {
  return (
    <CollapsibleTrigger asChild>
      <button className="flex items-center justify-between w-full px-3 py-2 text-sm font-medium hover:bg-muted/50 rounded">
        <div className="flex items-center gap-2">
          {open ? <ChevronDown className="h-3 w-3" /> : <ChevronRight className="h-3 w-3" />}
          {label}
          {count !== undefined && (
            <Badge variant="secondary" className="h-4 px-1.5 text-xs">{count}</Badge>
          )}
        </div>
      </button>
    </CollapsibleTrigger>
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
            <p className="text-xs text-muted-foreground py-1">暂无待处理任务</p>
          ) : (
            pending.map((task) => (
              <div key={task.taskId} className="rounded border border-border/50 p-2 space-y-1">
                <div className="text-xs font-medium">
                  {task.direction === 'outgoing' ? '→ ' : '← '}
                  {task.helperName ?? task.requesterName ?? '未知'}
                </div>
                <p className="text-xs text-muted-foreground line-clamp-2">{task.question}</p>
                <div className="flex gap-1">
                  <Button size="sm" className="h-6 text-xs px-2" onClick={() => acceptTask(task.taskId)}>
                    <Check className="h-3 w-3 mr-0.5" />接受
                  </Button>
                  <Button variant="outline" size="sm" className="h-6 text-xs px-2" onClick={() => rejectTask(task.taskId)}>
                    <X className="h-3 w-3 mr-0.5" />拒绝
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
      <SectionHeader label="声望排行" open={open} />
      <CollapsibleContent>
        <div className="px-3 pb-2 space-y-0.5">
          {leaderboard.slice(0, 10).map((entry, i) => {
            const isMe = entry.agentId === connection.agentId;
            return (
              <div
                key={entry.agentId}
                className={cn(
                  'flex items-center justify-between py-1 px-1.5 rounded text-xs',
                  isMe && 'bg-primary/10 border border-primary/20',
                )}
              >
                <div className="flex items-center gap-2 min-w-0">
                  <span className="w-4 text-muted-foreground font-mono">{i + 1}.</span>
                  <span>{entry.avatar}</span>
                  <span className={cn('truncate', isMe && 'font-medium')}>{entry.name}</span>
                </div>
                <span className="font-mono flex items-center gap-0.5" style={{ color: '#E8C460' }}>
                  <Trophy className="h-3 w-3" />{entry.reputation}
                </span>
              </div>
            );
          })}
          {leaderboard.length === 0 && (
            <p className="text-xs text-muted-foreground py-1">暂无排行数据</p>
          )}
        </div>
      </CollapsibleContent>
    </Collapsible>
  );
}

// ── Online Agents ──

const STATUS_COLORS: Record<string, string> = {
  idle: 'bg-green-500',
  busy: 'bg-yellow-500',
  afk: 'bg-gray-500',
  offline: 'bg-gray-400',
};

function OnlineAgentList() {
  const { onlineAgents, connection } = useBazaarStore();
  const [open, setOpen] = useState(true);

  return (
    <Collapsible open={open} onOpenChange={setOpen}>
      <SectionHeader label="在线 Agent" count={onlineAgents.length} open={open} />
      <CollapsibleContent>
        <div className="px-3 pb-2 space-y-0.5">
          {onlineAgents.map((agent) => {
            const isMe = agent.agentId === connection.agentId;
            return (
              <div
                key={agent.agentId}
                className={cn(
                  'flex items-center justify-between py-1 px-1.5 rounded text-xs',
                  isMe && 'bg-primary/10',
                )}
              >
                <div className="flex items-center gap-2 min-w-0">
                  <span className={`w-2 h-2 rounded-full flex-shrink-0 ${STATUS_COLORS[agent.status] ?? 'bg-gray-400'}`} />
                  <span>{agent.avatar}</span>
                  <span className="truncate">{agent.name}</span>
                </div>
                <span className="text-muted-foreground font-mono">{agent.reputation}</span>
              </div>
            );
          })}
          {onlineAgents.length === 0 && (
            <p className="text-xs text-muted-foreground py-1">暂无在线 Agent</p>
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
        className="w-8 border-l bg-background/80 flex items-center justify-center hover:bg-muted/50"
      >
        <ChevronLeft className="h-4 w-4 text-muted-foreground" />
      </button>
    );
  }

  return (
    <div className="w-[280px] flex-shrink-0 border-l bg-background/80 backdrop-blur-sm flex flex-col">
      <div className="flex justify-end px-2 pt-2">
        <button onClick={() => setCollapsed(true)} className="text-muted-foreground hover:text-foreground">
          <ChevronRight className="h-4 w-4" />
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

// Simple chevron icons
function ChevronLeft(props: React.SVGProps<SVGSVGElement>) {
  return (
    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" {...props}>
      <path d="m15 18-6-6 6-6" />
    </svg>
  );
}
