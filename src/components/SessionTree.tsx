import { memo, useEffect, useMemo, useRef, useState } from 'react';
import {
  ChevronDown,
  ChevronRight,
  FolderOpen,
  MessageSquare,
  Plus,
  Trash,
  Copy,
  Server,
} from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import { Button } from '@/components/ui/button';
import { ScrollArea } from '@/components/ui/scroll-area';
import { useChatStore } from '@/stores/chat';
import { useWsConnection } from '@/stores/ws-connection';
import { cn } from '@/lib/utils';
import { DirectorySelectorDialog } from '@/components/DirectorySelectorDialog';
import type { ChatSession } from '@/types/chat';

// Simple local state for expanded systems (no need for a store)
function useExpandedSystems() {
  const [expanded, setExpanded] = useState<Set<string>>(new Set());

  const toggle = (systemId: string, force?: boolean) => {
    setExpanded((prev) => {
      const next = new Set(prev);
      if (force === true) {
        next.add(systemId);
      } else if (force === false) {
        next.delete(systemId);
      } else {
        if (next.has(systemId)) {
          next.delete(systemId);
        } else {
          next.add(systemId);
        }
      }
      return next;
    });
  };

  return { expanded, toggle };
}

function BackendStatusBar() {
  const { status } = useWsConnection();
  const info = useMemo(() => {
    try {
      const servers: Array<{ name: string; url: string; alias?: string }> = JSON.parse(
        localStorage.getItem('sman-servers') || '[]',
      );
      const selected = localStorage.getItem('sman-selected-server') || 'localhost';
      const current = servers.find((s) => s.name === selected);
      return {
        alias: current?.alias || '',
        url: current?.url || '',
        address: current?.name || '',
      };
    } catch {
      return { alias: '', url: '', address: '' };
    }
  }, []);

  const { alias, url, address } = info;
  const isLocal = !url;
  const statusColor = status === 'connected' ? 'bg-green-500' : 'bg-yellow-500';
  const label = isLocal
    ? '本机'
    : alias
      ? `${alias} (${address})`
      : address;

  return (
    <div className="px-3 pb-2 flex items-center gap-1.5">
      <span className={cn('w-1.5 h-1.5 rounded-full shrink-0', statusColor)} />
      <Server className="h-3 w-3 shrink-0 text-muted-foreground" />
      <span className="text-[11px] text-muted-foreground truncate">{label}</span>
    </div>
  );
}

const SessionItem = memo(function SessionItem({
  session,
  isActive,
  onSelect,
  onDelete,
  onDuplicate,
}: {
  session: ChatSession;
  isActive: boolean;
  onSelect: () => void;
  onDelete: () => void;
  onDuplicate: () => void;
}) {
  const isChatbot = session.key.startsWith('chatbot-');
  const [hovered, setHovered] = useState(false);
  const [deleting, setDeleting] = useState(false);

  const handleDelete = async (e: React.MouseEvent) => {
    e.stopPropagation();
    if (deleting) return;
    setDeleting(true);
    try {
      await onDelete();
    } catch (err) {
      console.error('[SessionItem] Failed to delete session:', err);
      setDeleting(false);
    }
  };

  const handleDuplicate = async (e: React.MouseEvent) => {
    e.stopPropagation();
    try {
      await onDuplicate();
    } catch (err) {
      console.error('[SessionItem] Failed to duplicate session:', err);
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
      <span className="truncate flex-1 min-w-0">{session.label || '新会话'}</span>
      <div className={cn('flex items-center gap-0.5 shrink-0', !hovered && !deleting && 'hidden')}>
        {!isChatbot && (
          <button
            className={cn(
              'shrink-0 p-0.5 rounded transition-all',
              hovered ? 'opacity-100' : 'opacity-0 pointer-events-none',
              'text-muted-foreground hover:text-primary',
            )}
            onClick={handleDuplicate}
            title="复制会话"
          >
            <Copy className="h-3.5 w-3.5" />
          </button>
        )}
        <button
          className={cn(
            'shrink-0 p-0.5 rounded transition-all',
            hovered || deleting ? 'opacity-100' : 'opacity-0 pointer-events-none',
            deleting && 'opacity-40 cursor-not-allowed',
            'text-muted-foreground hover:text-destructive',
          )}
          onClick={handleDelete}
          disabled={deleting}
          title="删除会话"
        >
          <Trash className="h-3.5 w-3.5" />
        </button>
      </div>
    </div>
  );
}, (prev, next) => {
  return prev.session.key === next.session.key
    && prev.isActive === next.isActive
    && prev.session.label === next.session.label;
});

const SystemGroup = memo(function SystemGroup({
  system,
  sessions,
  currentSessionId,
  expanded,
  onToggle,
  onSessionSelect,
  onSessionDelete,
  onSessionDuplicate,
}: {
  system: { systemId: string; name: string; workspace: string };
  sessions: ChatSession[];
  currentSessionId: string;
  expanded: boolean;
  onToggle: () => void;
  onSessionSelect: (sessionId: string) => void;
  onSessionDelete: (sessionId: string) => void;
  onSessionDuplicate: (sessionId: string) => void;
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
        title={system.workspace}
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
              isActive={session.key === currentSessionId}
              onSelect={() => onSessionSelect(session.key)}
              onDelete={() => onSessionDelete(session.key)}
              onDuplicate={() => onSessionDuplicate(session.key)}
            />
          ))}
        </div>
      )}
    </div>
  );
}, (prev, next) => {
  return prev.system.systemId === next.system.systemId
    && prev.expanded === next.expanded
    && prev.currentSessionId === next.currentSessionId
    && prev.sessions === next.sessions;
});

export function SessionTree() {
  const navigate = useNavigate();
  const scrollAreaRef = useRef<HTMLDivElement>(null);
  const [showDirSelector, setShowDirSelector] = useState(false);
  const { expanded: expandedSystems, toggle: toggleSystemExpanded } = useExpandedSystems();

  const sessions = useChatStore((s) => s.sessions);
  const currentSessionId = useChatStore((s) => s.currentSessionId);
  const switchSession = useChatStore((s) => s.switchSession);
  const deleteSession = useChatStore((s) => s.deleteSession);
  const loadSessions = useChatStore((s) => s.loadSessions);
  const createSessionWithWorkspace = useChatStore((s) => s.createSessionWithWorkspace);

  // Derive systems from sessions (group by workspace)
  const { systems, sessionsBySystem } = useMemo(() => {
    const map = sessions.reduce<Map<string, { systemId: string; name: string; workspace: string }>>((acc, session) => {
      if (!session.workspace) return acc;
      if (!acc.has(session.workspace)) {
        const name = session.workspace.split(/[/\\]/).pop() || session.workspace;
        acc.set(session.workspace, {
          systemId: session.workspace,
          name,
          workspace: session.workspace,
        });
      }
      return acc;
    }, new Map());
    const systems = Array.from(map.values());
    const sessionsBySystem = sessions.reduce<Record<string, ChatSession[]>>((acc, session) => {
      const sysId = session.workspace || '__default__';
      if (!acc[sysId]) acc[sysId] = [];
      acc[sysId].push(session);
      return acc;
    }, {});
    return { systems, sessionsBySystem };
  }, [sessions]);

  // Stable key for auto-expand effect
  const systemIdsKey = useMemo(() => systems.map((s) => s.systemId).sort().join(','), [systems]);

  // Auto-expand all systems when sessions load (on app start or refresh)
  useEffect(() => {
    if (systems.length > 0) {
      systems.forEach((system) => {
        if (!expandedSystems.has(system.systemId)) {
          toggleSystemExpanded(system.systemId, true);
        }
      });
    }
  }, [systemIdsKey]); // eslint-disable-line react-hooks/exhaustive-deps

  // Auto-expand & auto-scroll to current session
  useEffect(() => {
    if (!currentSessionId) return;

    const session = sessions.find((s) => s.key === currentSessionId);
    if (!session) return;

    if (session.workspace && !expandedSystems.has(session.workspace)) {
      toggleSystemExpanded(session.workspace, true);
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
    toggleSystemExpanded(systemId);
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

  const handleSessionDuplicate = async (sessionId: string) => {
    const session = sessions.find((s) => s.key === sessionId);
    if (!session || !session.workspace) {
      console.error('[SessionTree] Cannot duplicate: session or workspace not found');
      return;
    }
    try {
      // Create new session with the same workspace
      const newSessionId = await createSessionWithWorkspace(session.workspace);
      await loadSessions();
      switchSession(newSessionId);
      navigate('/chat');
    } catch (err) {
      console.error('[SessionTree] Failed to duplicate session:', err);
      alert(`复制会话失败: ${err instanceof Error ? err.message : String(err)}`);
    }
  };

  const handleNewSession = () => {
    setShowDirSelector(true);
  };

  const handleDirectorySelect = async (workspace: string) => {
    setShowDirSelector(false);
    try {
      const sessionId = await createSessionWithWorkspace(workspace);
      await loadSessions();
      switchSession(sessionId);
      navigate('/chat');
    } catch (err) {
      console.error('[SessionTree] Failed to create session:', err);
      alert(`创建会话失败: ${err instanceof Error ? err.message : String(err)}`);
    }
  };

  return (
    <div className="flex flex-col h-full">
      {/* Directory Selector Dialog */}
      <DirectorySelectorDialog
        open={showDirSelector}
        onOpenChange={setShowDirSelector}
        onSelect={handleDirectorySelect}
      />

      {/* New session button */}
      <div className="p-3 pb-2">
        <Button
          variant="outline"
          size="sm"
          className="w-full justify-start gap-2 h-9 text-[13px] font-medium bg-[hsl(var(--card))] hover:bg-[hsl(var(--muted))] border-[hsl(var(--border))]"
          onClick={handleNewSession}
        >
          <Plus className="h-4 w-4" />
          新建会话
        </Button>
      </div>

      {/* Backend status bar */}
      <BackendStatusBar />

      {/* Tree */}
      <div ref={scrollAreaRef} className="flex-1 min-h-0">
        <ScrollArea className="h-full">
          <div className="p-2 pt-1">
            {systems.length === 0 ? (
              <div className="text-center py-8 px-4 text-[13px] text-muted-foreground">
                <p>暂无会话</p>
                <p className="text-xs mt-1">点击上方按钮创建新会话</p>
              </div>
            ) : (
              systems.map((system) => (
                <SystemGroup
                  key={system.systemId}
                  system={system}
                  sessions={sessionsBySystem[system.systemId] || []}
                  currentSessionId={currentSessionId}
                  expanded={expandedSystems.has(system.systemId)}
                  onToggle={() => toggleSystem(system.systemId)}
                  onSessionSelect={handleSessionSelect}
                  onSessionDelete={handleSessionDelete}
                  onSessionDuplicate={handleSessionDuplicate}
                />
              ))
            )}
          </div>
        </ScrollArea>
      </div>
    </div>
  );
}
