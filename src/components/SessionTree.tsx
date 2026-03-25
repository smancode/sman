import { useEffect, useRef, useState } from 'react';
import {
  ChevronDown,
  ChevronRight,
  FolderOpen,
  MessageSquare,
  Plus,
  Trash2,
} from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import { Button } from '@/components/ui/button';
import { ScrollArea } from '@/components/ui/scroll-area';
import { useChatStore } from '@/stores/chat';
import { useBusinessSystemsStore } from '@/stores/business-systems';
import { cn } from '@/lib/utils';
import type { ChatSession } from '@/types/chat';

interface SessionTreeProps {
  onNewSession: () => void;
}

function SessionItem({
  session,
  sessionLabel,
  isActive,
  onSelect,
  onDelete,
}: {
  session: ChatSession;
  sessionLabel?: string;
  isActive: boolean;
  onSelect: () => void;
  onDelete: () => void;
}) {
  const [hovered, setHovered] = useState(false);
  const [deleting, setDeleting] = useState(false);

  const handleDelete = async (e: React.MouseEvent) => {
    e.stopPropagation();
    if (deleting) return;
    setDeleting(true);
    try {
      onDelete();
    } catch (err) {
      console.error('[SessionItem] Failed to delete session:', err);
      setDeleting(false);
    }
  };

  return (
    <div
      data-session-id={session.key}
      className={cn(
        'flex items-center gap-2 pl-3 pr-1 py-2 rounded-lg cursor-pointer text-[13px] transition-all duration-200',
        isActive
          ? 'bg-[hsl(var(--muted))] text-foreground font-semibold'
          : 'hover:bg-[hsl(var(--muted))] text-foreground/60 hover:text-foreground',
      )}
      onClick={onSelect}
      onMouseEnter={() => setHovered(true)}
      onMouseLeave={() => setHovered(false)}
    >
      <MessageSquare className="h-3.5 w-3.5 shrink-0" />
      <span className="truncate flex-1 min-w-0">{sessionLabel || '新会话'}</span>
      <button
        className={cn(
          'shrink-0 p-0.5 rounded transition-colors',
          (hovered || isActive) ? 'opacity-100' : 'opacity-0 pointer-events-none',
          deleting
            ? 'opacity-40 cursor-not-allowed text-muted-foreground'
            : 'text-muted-foreground hover:text-destructive',
        )}
        onClick={handleDelete}
        disabled={deleting}
      >
        <Trash2 className="h-3.5 w-3.5" />
      </button>
    </div>
  );
}

function SystemGroup({
  system,
  sessions,
  sessionLabels,
  currentSessionId,
  expanded,
  onToggle,
  onSessionSelect,
  onSessionDelete,
}: {
  system: { systemId: string; name: string };
  sessions: ChatSession[];
  sessionLabels: Record<string, string>;
  currentSessionId: string;
  expanded: boolean;
  onToggle: () => void;
  onSessionSelect: (sessionId: string) => void;
  onSessionDelete: (sessionId: string) => void;
}) {
  const sessionCount = sessions.length;
  const hasActiveSession = sessions.some((s) => s.key === currentSessionId);

  return (
    <div className="mb-0.5">
      {/* System header */}
      <div
        className={cn(
          'flex items-center gap-1.5 px-2 py-2 rounded-lg cursor-pointer transition-all duration-200',
          expanded || hasActiveSession
            ? 'text-foreground'
            : 'text-foreground/60 hover:text-foreground/80',
          'hover:bg-[hsl(var(--muted))]/50',
        )}
        onClick={onToggle}
      >
        <div className="flex items-center justify-center w-4 h-4">
          {expanded ? (
            <ChevronDown className="h-3.5 w-3.5 text-muted-foreground" />
          ) : (
            <ChevronRight className="h-3.5 w-3.5 text-muted-foreground" />
          )}
        </div>
        <FolderOpen
          className={cn(
            'h-4 w-4 shrink-0',
            expanded || hasActiveSession ? 'text-foreground/70' : 'text-muted-foreground',
          )}
        />
        <span className="text-[13px] font-medium truncate flex-1">{system.name}</span>
        <span className="text-[11px] text-muted-foreground/60 tabular-nums">{sessionCount}</span>
      </div>

      {/* Sessions */}
      {expanded && sessionCount > 0 && (
        <div className="ml-6 mt-0.5 space-y-0.5">
          {sessions.map((session) => (
            <SessionItem
              key={session.key}
              session={session}
              sessionLabel={sessionLabels[session.key]}
              isActive={session.key === currentSessionId}
              onSelect={() => onSessionSelect(session.key)}
              onDelete={() => onSessionDelete(session.key)}
            />
          ))}
        </div>
      )}
    </div>
  );
}

export function SessionTree({ onNewSession }: SessionTreeProps) {
  const navigate = useNavigate();
  const scrollAreaRef = useRef<HTMLDivElement>(null);

  const sessions = useChatStore((s) => s.sessions);
  const currentSessionId = useChatStore((s) => s.currentSessionId);
  const sessionLabels = useChatStore((s) => s.sessionLabels);
  const switchSession = useChatStore((s) => s.switchSession);
  const deleteSession = useChatStore((s) => s.deleteSession);
  const loadSessions = useChatStore((s) => s.loadSessions);

  const systems = useBusinessSystemsStore((s) => s.systems);
  const expandedSystems = useBusinessSystemsStore((s) => s.expandedSystems);
  const toggleSystemExpanded = useBusinessSystemsStore((s) => s.toggleSystemExpanded);

  // Group sessions by systemId
  const sessionsBySystem = sessions.reduce<Record<string, ChatSession[]>>((acc, session) => {
    const sysId = session.systemId || '__default__';
    if (!acc[sysId]) acc[sysId] = [];
    acc[sysId].push(session);
    return acc;
  }, {});

  // Auto-expand & auto-scroll to current session
  useEffect(() => {
    if (!currentSessionId) return;

    const session = sessions.find((s) => s.key === currentSessionId);
    if (!session) return;

    if (session.systemId && !expandedSystems.has(session.systemId)) {
      toggleSystemExpanded(session.systemId, true);
    }

    requestAnimationFrame(() => {
      const viewport = scrollAreaRef.current?.querySelector(
        '[data-radix-scroll-area-viewport]',
      );
      const sessionEl = scrollAreaRef.current?.querySelector(
        `[data-session-id="${CSS.escape(currentSessionId)}"]`,
      );
      if (viewport && sessionEl) {
        const vpRect = viewport.getBoundingClientRect();
        const elRect = sessionEl.getBoundingClientRect();
        if (elRect.bottom > vpRect.bottom || elRect.top < vpRect.top) {
          sessionEl.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
        }
      }
    });
  }, [currentSessionId, sessions, expandedSystems, toggleSystemExpanded]);

  const toggleSystem = (systemId: string) => {
    const isExpanded = expandedSystems.has(systemId);
    toggleSystemExpanded(systemId, !isExpanded);
  };

  const handleSessionSelect = (sessionId: string) => {
    switchSession(sessionId);
    navigate('/chat');
  };

  const handleSessionDelete = async (sessionId: string) => {
    try {
      await deleteSession(sessionId);
      await loadSessions();
    } catch (err) {
      console.error('[SessionTree] Failed to delete session:', err);
    }
  };

  return (
    <div className="flex flex-col h-full">
      {/* New session button */}
      <div className="p-3 border-b border-[hsl(var(--sidebar-border))]">
        <Button
          variant="outline"
          size="sm"
          className="w-full justify-start gap-2 h-9 text-[13px] font-medium bg-[hsl(var(--card))] hover:bg-[hsl(var(--muted))] border-[hsl(var(--border))]"
          onClick={onNewSession}
        >
          <Plus className="h-4 w-4" />
          新建会话
        </Button>
      </div>

      {/* Tree */}
      <div ref={scrollAreaRef} className="flex-1 min-h-0">
        <ScrollArea className="h-full">
          <div className="p-2 pt-1">
            {systems.length === 0 ? (
              <div className="text-center py-8 px-4 text-[13px] text-muted-foreground">
                <p>没有可用的业务系统</p>
                <p className="text-xs mt-1">请先在设置中创建</p>
              </div>
            ) : (
              systems.map((system) => (
                <SystemGroup
                  key={system.systemId}
                  system={system}
                  sessions={sessionsBySystem[system.systemId] || []}
                  sessionLabels={sessionLabels}
                  currentSessionId={currentSessionId}
                  expanded={expandedSystems.has(system.systemId)}
                  onToggle={() => toggleSystem(system.systemId)}
                  onSessionSelect={handleSessionSelect}
                  onSessionDelete={handleSessionDelete}
                />
              ))
            )}
          </div>
        </ScrollArea>
      </div>
    </div>
  );
}
