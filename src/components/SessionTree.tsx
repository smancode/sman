import { useState } from 'react';
import {
  ChevronDown,
  ChevronRight,
  FolderOpen,
  MessageSquare,
  Plus,
  Trash2,
} from 'lucide-react';
import { Button } from '@/components/ui/button';
import { ScrollArea } from '@/components/ui/scroll-area';
import {
  ContextMenu,
  ContextMenuContent,
  ContextMenuItem,
  ContextMenuTrigger,
} from '@/components/ui/context-menu';
import { useBusinessSystemsStore } from '@/stores/business-systems';
import type { BusinessSystem, SystemSession } from '@/types/business-system';
import { cn } from '@/lib/utils';

interface SessionTreeProps {
  onNewSession: () => void;
}

function SessionItem({
  session,
  isActive,
  onSelect,
  onDelete,
}: {
  session: SystemSession;
  isActive: boolean;
  onSelect: () => void;
  onDelete: () => void;
}) {
  return (
    <ContextMenu>
      <ContextMenuTrigger>
        <div
          className={cn(
            'flex items-center gap-2 px-3 py-1.5 rounded-md cursor-pointer text-sm',
            'hover:bg-muted/50 transition-colors',
            isActive && 'bg-primary/10 text-primary font-medium'
          )}
          onClick={onSelect}
        >
          <MessageSquare className="h-4 w-4 shrink-0 text-muted-foreground" />
          <span className="truncate">{session.label}</span>
        </div>
      </ContextMenuTrigger>
      <ContextMenuContent>
        <ContextMenuItem
          className="text-destructive focus:text-destructive"
          onClick={onDelete}
        >
          <Trash2 className="h-4 w-4 mr-2" />
          删除会话
        </ContextMenuItem>
      </ContextMenuContent>
    </ContextMenu>
  );
}

function SystemGroup({
  system,
  sessions,
  currentSessionId,
  expanded,
  onToggle,
  onSessionSelect,
  onSessionDelete,
}: {
  system: BusinessSystem;
  sessions: SystemSession[];
  currentSessionId: string | null;
  expanded: boolean;
  onToggle: () => void;
  onSessionSelect: (sessionId: string) => void;
  onSessionDelete: (sessionId: string) => void;
}) {
  const sessionCount = sessions.length;

  return (
    <div className="mb-1">
      {/* 系统标题行 */}
      <div
        className="flex items-center gap-1 px-2 py-1.5 rounded-md cursor-pointer hover:bg-muted/50 transition-colors"
        onClick={onToggle}
      >
        {expanded ? (
          <ChevronDown className="h-4 w-4 shrink-0 text-muted-foreground" />
        ) : (
          <ChevronRight className="h-4 w-4 shrink-0 text-muted-foreground" />
        )}
        <FolderOpen className="h-4 w-4 shrink-0 text-muted-foreground" />
        <span className="text-sm font-medium truncate flex-1">{system.name}</span>
        <span className="text-xs text-muted-foreground">({sessionCount})</span>
      </div>

      {/* 会话列表 */}
      {expanded && sessionCount > 0 && (
        <div className="ml-4 mt-1 space-y-0.5">
          {sessions.map((session) => (
            <SessionItem
              key={session.id}
              session={session}
              isActive={session.id === currentSessionId}
              onSelect={() => onSessionSelect(session.id)}
              onDelete={() => onSessionDelete(session.id)}
            />
          ))}
        </div>
      )}
    </div>
  );
}

export function SessionTree({ onNewSession }: SessionTreeProps) {
  const {
    systems,
    currentSessionId,
    getSessionsBySystem,
    switchSession,
    deleteSession,
  } = useBusinessSystemsStore();

  // 记录展开状态的系统 ID
  const [expandedSystems, setExpandedSystems] = useState<Set<string>>(new Set());

  const toggleSystem = (systemId: string) => {
    setExpandedSystems((prev) => {
      const next = new Set(prev);
      if (next.has(systemId)) {
        next.delete(systemId);
      } else {
        next.add(systemId);
      }
      return next;
    });
  };

  return (
    <div className="flex flex-col h-full">
      {/* 新建会话按钮 */}
      <div className="p-2 border-b">
        <Button
          variant="outline"
          size="sm"
          className="w-full"
          onClick={onNewSession}
        >
          <Plus className="h-4 w-4 mr-2" />
          新建会话
        </Button>
      </div>

      {/* 会话树 */}
      <ScrollArea className="flex-1">
        <div className="p-2">
          {systems.length === 0 ? (
            <div className="text-center py-8 text-sm text-muted-foreground">
              <p>没有可用的业务系统</p>
            </div>
          ) : (
            systems.map((system) => (
              <SystemGroup
                key={system.id}
                system={system}
                sessions={getSessionsBySystem(system.id)}
                currentSessionId={currentSessionId}
                expanded={expandedSystems.has(system.id)}
                onToggle={() => toggleSystem(system.id)}
                onSessionSelect={switchSession}
                onSessionDelete={deleteSession}
              />
            ))
          )}
        </div>
      </ScrollArea>
    </div>
  );
}
