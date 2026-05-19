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
  Route,
  Layers,
} from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import { Button } from '@/components/ui/button';
// ScrollArea removed — native overflow-y-auto for better scroll performance with many sessions
import { useChatStore } from '@/stores/chat';
import { useWsConnection } from '@/stores/ws-connection';
import { useGroupStore } from '@/stores/group';
import { cn } from '@/lib/utils';
import { DirectorySelectorDialog } from '@/components/DirectorySelectorDialog';
import { CreateGroupDialog } from '@/components/CreateGroupDialog';
import { CreateTaskDialog } from '@/components/CreateTaskDialog';
import { GroupItem } from '@/components/GroupItem';
import { t, useLocale } from '@/locales';
import type { ChatSession } from '@/types/chat';
import type { Group } from '@/schemas/group';

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
  // status 作为依赖：切换后端后重连时 status 变化，触发重读 localStorage
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
  }, [status]);

  const { alias, url, address } = info;
  const isLocal = !url;
  const statusColor = status === 'connected' ? 'bg-green-500' : 'bg-yellow-500';
  const label = isLocal
    ? t('session.local')
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

  const handleDelete = (e: React.MouseEvent) => {
    e.stopPropagation();
    onDelete();
  };

  const handleDuplicate = (e: React.MouseEvent) => {
    e.stopPropagation();
    onDuplicate();
  };

  return (
    <div
      data-session-id={session.key}
      className={cn(
        'group flex items-center gap-2 pl-3 pr-1 py-2 rounded-lg cursor-pointer text-[13px]',
        isActive
          ? 'bg-[hsl(var(--muted))] text-foreground font-semibold'
          : 'hover:bg-[hsl(var(--muted))] text-foreground/60 hover:text-foreground',
      )}
      onClick={onSelect}
    >
      {(session.label?.startsWith('/')) ? (
        <Route className="h-3.5 w-3.5 shrink-0 text-primary" />
      ) : (
        <MessageSquare className="h-3.5 w-3.5 shrink-0" />
      )}
      <span className="truncate flex-1 min-w-0">{session.label || t('session.newSession')}</span>
      <div className="flex items-center gap-0.5 shrink-0 opacity-0 pointer-events-none group-hover:opacity-100 group-hover:pointer-events-auto [transition:opacity_0.15s_0.3s]">
        {!isChatbot && (
          <button
            className="shrink-0 p-0.5 rounded text-muted-foreground hover:text-primary"
            onClick={handleDuplicate}
            title={t('session.copySession')}
          >
            <Copy className="h-3.5 w-3.5" />
          </button>
        )}
        <button
          className="shrink-0 p-0.5 rounded text-muted-foreground hover:text-destructive"
          onClick={handleDelete}
          title={t('session.delete')}
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
  useLocale();
  const navigate = useNavigate();
  const scrollAreaRef = useRef<HTMLDivElement>(null);
  const [showDirSelector, setShowDirSelector] = useState(false);
  const [showGroupDialog, setShowGroupDialog] = useState(false);
  const [showTaskDialog, setShowTaskDialog] = useState(false);
  const [selectedGroupId, setSelectedGroupId] = useState<string | null>(null);
  const [activeTaskId, setActiveTaskId] = useState<string | null>(null);
  const { expanded: expandedSystems, toggle: toggleSystemExpanded } = useExpandedSystems();

  // Custom state for expanded groups (separate from expandedSystems to avoid conflicts)
  const [expandedGroups, setExpandedGroups] = useState<Set<string>>(new Set());

  const sessions = useChatStore((s) => s.sessions);
  const currentSessionId = useChatStore((s) => s.currentSessionId);
  const switchSession = useChatStore((s) => s.switchSession);
  const deleteSession = useChatStore((s) => s.deleteSession);
  const loadSessions = useChatStore((s) => s.loadSessions);
  const createSessionWithWorkspace = useChatStore((s) => s.createSessionWithWorkspace);

  // Group store
  const groups = useGroupStore((s) => s.groups);
  const tasks = useGroupStore((s) => s.tasks);
  const loadGroups = useGroupStore((s) => s.loadGroups);
  const loadTasks = useGroupStore((s) => s.loadTasks);
  const createGroup = useGroupStore((s) => s.createGroup);
  const deleteGroup = useGroupStore((s) => s.deleteGroup);
  const createTask = useGroupStore((s) => s.createTask);
  const deleteTask = useGroupStore((s) => s.deleteTask);

  // Load groups when WebSocket is connected
  useEffect(() => {
    if (status !== 'connected') return;
    loadGroups();
  }, [status]);

  // Load tasks for a group when it's expanded
  useEffect(() => {
    expandedGroups.forEach((groupId) => {
      if (!tasks[groupId] || tasks[groupId].length === 0) {
        loadTasks(groupId);
      }
    });
  }, [expandedGroups, loadTasks]);

  // Derive local systems and bot groups from sessions
  const { localSystems, botGroups, localSessionsBySystem, botSessionsByGroup } = useMemo(() => {
    const localMap = new Map<string, { systemId: string; name: string; workspace: string }>();
    // Bot groups: query mode keyed by "botLabel|workspace", full mode keyed by "botLabel|"
    const botGroupMap = new Map<string, { groupKey: string; displayName: string }>();

    for (const session of sessions) {
      if (session.source === 'bot') {
        const label = session.botLabel || 'Unknown Bot';
        const ws = session.workspace || '';
        const isFixedWorkspace = session.botMode === 'query' || session.botMode === 'collect';
        // Full mode: group by botLabel only; Query mode: group by botLabel+workspace
        const groupKey = isFixedWorkspace ? `${label}|${ws}` : `${label}|`;
        if (!botGroupMap.has(groupKey)) {
          let displayName = label;
          if (isFixedWorkspace && ws) {
            const wsName = ws.split(/[/\\]/).pop() || ws;
            displayName = `${label} - ${wsName}`;
          }
          botGroupMap.set(groupKey, { groupKey, displayName });
        }
      } else {
        if (!session.workspace) continue;
        if (!localMap.has(session.workspace)) {
          const name = session.workspace.split(/[/\\]/).pop() || session.workspace;
          localMap.set(session.workspace, {
            systemId: session.workspace,
            name,
            workspace: session.workspace,
          });
        }
      }
    }

    const localSystems = Array.from(localMap.values());
    const botGroups = Array.from(botGroupMap.values());

    const localSessionsBySystem = sessions.reduce<Record<string, ChatSession[]>>((acc, session) => {
      if (session.source === 'bot') return acc;
      const sysId = session.workspace || '__default__';
      if (!acc[sysId]) acc[sysId] = [];
      acc[sysId].push(session);
      return acc;
    }, {} as Record<string, ChatSession[]>);

    const botSessionsByGroup = sessions.reduce<Record<string, ChatSession[]>>((acc, session) => {
      if (session.source !== 'bot') return acc;
      const label = session.botLabel || 'Unknown Bot';
      const ws = session.workspace || '';
      const isFixedWorkspace = session.botMode === 'query' || session.botMode === 'collect';
      const groupKey = isFixedWorkspace ? `${label}|${ws}` : `${label}|`;
      if (!acc[groupKey]) acc[groupKey] = [];
      acc[groupKey].push(session);
      return acc;
    }, {} as Record<string, ChatSession[]>);

    return { localSystems, botGroups, localSessionsBySystem, botSessionsByGroup };
  }, [sessions]);

  // Stable key for auto-expand effect
  const systemIdsKey = useMemo(
    () => localSystems.map((s) => s.systemId).sort().join(',') + '|' + botGroups.map((g) => g.groupKey).sort().join(','),
    [localSystems, botGroups],
  );

  // Auto-expand all systems when sessions load (on app start or refresh)
  useEffect(() => {
    localSystems.forEach((system) => {
      if (!expandedSystems.has(system.systemId)) {
        toggleSystemExpanded(system.systemId, true);
      }
    });
    botGroups.forEach((g) => {
      if (!expandedSystems.has(`bot-${g.groupKey}`)) {
        toggleSystemExpanded(`bot-${g.groupKey}`, true);
      }
    });
  }, [systemIdsKey]); // eslint-disable-line react-hooks/exhaustive-deps

  // Auto-expand & auto-scroll to current session
  useEffect(() => {
    if (!currentSessionId) return;

    const session = sessions.find((s) => s.key === currentSessionId);
    if (!session) return;

    if (session.source === 'bot' && session.botLabel) {
      const isFixedWorkspace = session.botMode === 'query' || session.botMode === 'collect';
      const groupKey = isFixedWorkspace ? `${session.botLabel}|${session.workspace || ''}` : `${session.botLabel}|`;
      const botKey = `bot-${groupKey}`;
      if (!expandedSystems.has(botKey)) {
        toggleSystemExpanded(botKey, true);
      }
    } else if (session.workspace && !expandedSystems.has(session.workspace)) {
      toggleSystemExpanded(session.workspace, true);
    }

    requestAnimationFrame(() => {
      const container = scrollAreaRef.current;
      const sessionEl = container?.querySelector(
        `[data-session-id="${CSS.escape(currentSessionId)}"]`,
      );
      if (container && sessionEl) {
        const vpRect = container.getBoundingClientRect();
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

  const handleSessionDelete = (sessionId: string) => {
    // deleteSession already updates local state optimistically in its session.deleted handler
    deleteSession(sessionId).catch((err) => {
      console.error('[SessionTree] Failed to delete session:', err);
      loadSessions(); // sync back on failure
    });
  };

  const handleSessionDuplicate = async (sessionId: string) => {
    const session = sessions.find((s) => s.key === sessionId);
    if (!session || !session.workspace) {
      console.error('[SessionTree] Cannot duplicate: session or workspace not found');
      return;
    }
    try {
      const newSessionId = await createSessionWithWorkspace(session.workspace);
      switchSession(newSessionId);
      navigate('/chat');
      loadSessions();
    } catch (err) {
      console.error('[SessionTree] Failed to duplicate session:', err);
      alert(`${t('session.copyFail')}: ${err instanceof Error ? err.message : String(err)}`);
    }
  };


  const handleNewSession = () => {
    setShowDirSelector(true);
  };

  const handleDirectorySelect = async (workspace: string) => {
    setShowDirSelector(false);
    try {
      const sessionId = await createSessionWithWorkspace(workspace);
      switchSession(sessionId);
      navigate('/chat');
      loadSessions(); // non-blocking background sync
    } catch (err) {
      console.error('[SessionTree] Failed to create session:', err);
      alert(`${t('session.createFail')}: ${err instanceof Error ? err.message : String(err)}`);
    }
  };

  // Group handlers
  const handleNewGroup = () => {
    setShowGroupDialog(true);
  };

  const handleGroupCreated = async (group: { id: string; name: string; workspaceIds: string[] }) => {
    // Optimistically add to local state
    await loadGroups();
  };

  const handleGroupEdit = (group: Group) => {
    // TODO: Implement edit dialog
    alert(t('group.editNotImplemented'));
  };

  const handleGroupDelete = async (groupId: string) => {
    try {
      await deleteGroup(groupId);
      await loadGroups();
    } catch (err) {
      console.error('[SessionTree] Failed to delete group:', err);
      alert(`${t('group.deleteFail')}: ${err instanceof Error ? err.message : String(err)}`);
    }
  };

  const handleNewTask = (groupId: string) => {
    setSelectedGroupId(groupId);
    setShowTaskDialog(true);
  };

  const handleTaskCreated = async (task: {
    id: string;
    groupId: string;
    title: string;
    description: string;
    details: string;
    acceptanceCriteria: string;
  }) => {
    // Reload tasks for the group
    await loadTasks(task.groupId);
    // Navigate to task view
    setActiveTaskId(task.id);
    navigate(`/group-task/${task.id}`);
  };

  const handleTaskSelect = (taskId: string) => {
    setActiveTaskId(taskId);
    navigate(`/group-task/${taskId}`);
  };

  const handleTaskDelete = async (taskId: string) => {
    try {
      await deleteTask(taskId);
      // Reload tasks for all groups
      for (const groupId of Object.keys(tasks)) {
        await loadTasks(groupId);
      }
    } catch (err) {
      console.error('[SessionTree] Failed to delete task:', err);
      alert(`${t('task.deleteFail')}: ${err instanceof Error ? err.message : String(err)}`);
    }
  };

  const toggleGroupExpanded = (groupId: string) => {
    setExpandedGroups((prev) => {
      const next = new Set(prev);
      if (next.has(groupId)) {
        next.delete(groupId);
      } else {
        next.add(groupId);
      }
      return next;
    });
  };

  return (
    <div className="flex flex-col h-full">
      {/* Directory Selector Dialog */}
      <DirectorySelectorDialog
        open={showDirSelector}
        onOpenChange={setShowDirSelector}
        onSelect={handleDirectorySelect}
        recentWorkspaces={localSystems.map((s) => ({ workspace: s.workspace, name: s.name }))}
      />

      {/* Create Group Dialog */}
      <CreateGroupDialog
        open={showGroupDialog}
        onOpenChange={setShowGroupDialog}
        onGroupCreated={handleGroupCreated}
        recentWorkspaces={localSystems.map((s) => ({ workspace: s.workspace, name: s.name }))}
      />

      {/* Create Task Dialog */}
      <CreateTaskDialog
        open={showTaskDialog}
        onOpenChange={setShowTaskDialog}
        onTaskCreated={handleTaskCreated}
        groupId={selectedGroupId || ''}
      />

      {/* New session & group buttons */}
      <div className="px-3 pb-2 pt-1 flex gap-1">
        <Button
          variant="outline"
          size="sm"
          className="flex-1 justify-start gap-2 h-9 text-[13px] font-medium bg-[hsl(var(--card))] hover:bg-[hsl(var(--muted))] border-[hsl(var(--border))]"
          onClick={handleNewSession}
        >
          <Plus className="h-4 w-4" />
          {t('session.new')}
        </Button>
        <Button
          variant="outline"
          size="sm"
          className="flex-1 justify-start gap-2 h-9 text-[13px] font-medium bg-[hsl(var(--card))] hover:bg-[hsl(var(--muted))] border-[hsl(var(--border))]"
          onClick={handleNewGroup}
        >
          <Layers className="h-4 w-4" />
          {t('group.new')}
        </Button>
      </div>



      {/* Backend status bar */}
      <BackendStatusBar />

      {/* Tree — native scroll for performance with many sessions */}
      <div ref={scrollAreaRef} className="flex-1 min-h-0 overflow-y-auto scrollbar-thin">
          <div className="p-2 pt-1">
            {groups.length === 0 && localSystems.length === 0 && botGroups.length === 0 ? (
              <div className="text-center py-8 px-4 text-[13px] text-muted-foreground">
                <p>{t('session.noSessions')}</p>
                <p className="text-xs mt-1">{t('session.createHint')}</p>
              </div>
            ) : (
              <>
                {/* Groups */}
                {groups.length > 0 && (
                  <>
                    {groups.map((group) => (
                      <GroupItem
                        key={group.id}
                        group={group}
                        tasks={tasks[group.id] || []}
                        expanded={expandedGroups.has(group.id)}
                        onToggle={() => toggleGroupExpanded(group.id)}
                        onEdit={handleGroupEdit}
                        onDelete={handleGroupDelete}
                        onNewTask={handleNewTask}
                        onTaskSelect={handleTaskSelect}
                        onTaskDelete={handleTaskDelete}
                        activeTaskId={activeTaskId}
                      />
                    ))}
                  </>
                )}

                {/* Local sessions grouped by workspace */}
                {localSystems.map((system) => (
                  <SystemGroup
                    key={system.systemId}
                    system={system}
                    sessions={localSessionsBySystem[system.systemId] || []}
                    currentSessionId={currentSessionId}
                    expanded={expandedSystems.has(system.systemId)}
                    onToggle={() => toggleSystem(system.systemId)}
                    onSessionSelect={handleSessionSelect}
                    onSessionDelete={handleSessionDelete}
                    onSessionDuplicate={handleSessionDuplicate}
                  />
                ))}

                {/* Bot sessions grouped by bot label */}
                {botGroups.length > 0 && (
                  <>
                    <div className="px-3 py-1.5 text-[11px] font-medium text-muted-foreground/50 uppercase tracking-wider">
                      {t('session.botSessions')}
                    </div>
                    {botGroups.map((g) => (
                      <SystemGroup
                        key={`bot-${g.groupKey}`}
                        system={{ systemId: `bot-${g.groupKey}`, name: `🤖 ${g.displayName}`, workspace: `bot-${g.groupKey}` }}
                        sessions={botSessionsByGroup[g.groupKey] || []}
                        currentSessionId={currentSessionId}
                        expanded={expandedSystems.has(`bot-${g.groupKey}`)}
                        onToggle={() => toggleSystem(`bot-${g.groupKey}`)}
                        onSessionSelect={handleSessionSelect}
                        onSessionDelete={handleSessionDelete}
                        onSessionDuplicate={() => {}}
                      />
                    ))}
                  </>
                )}
              </>
            )}
          </div>
      </div>
    </div>
  );
}
