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
import type { ChatSession } from '@/types/chat';
import { cn } from '@/lib/utils';

interface SessionTreeProps {
  onNewSession: () => void;
}

function SessionItem({
  label,
  isActive,
  onSelect,
  onDelete,
}: {
  label: string;
  isActive: boolean;
  onSelect: () => void;
  onDelete: () => void;
}) {
  const [hovered, setHovered] = useState(false);

  return (
    <div
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
      <span className="truncate flex-1 min-w-0">{label}</span>
      <button
        className={cn(
          'shrink-0 p-0.5 rounded transition-colors',
          (hovered || isActive) ? 'opacity-100' : 'opacity-0 pointer-events-none',
          'text-muted-foreground hover:text-destructive',
        )}
        onClick={(e) => {
          e.stopPropagation();
          onDelete();
        }}
      >
        <Trash2 className="h-3.5 w-3.5" />
      </button>
    </div>
  );
}

export function SessionTree({ onNewSession }: SessionTreeProps) {
  const navigate = useNavigate();
  const scrollRef = useRef<HTMLDivElement>(null);

  const sessions = useChatStore((s) => s.sessions);
  const currentSessionId = useChatStore((s) => s.currentSessionId);
  const sessionLabels = useChatStore((s) => s.sessionLabels);
  const switchSession = useChatStore((s) => s.switchSession);

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

  // Auto-expand current session's system
  useEffect(() => {
    const current = sessions.find((s) => s.key === currentSessionId);
    if (current?.systemId && !expandedSystems.has(current.systemId)) {
      toggleSystemExpanded(current.systemId, true);
    }
  }, [currentSessionId, sessions, expandedSystems, toggleSystemExpanded]);

  const handleSelect = (sessionId: string) => {
    switchSession(sessionId);
    navigate('/chat');
  };

  return (
    <div className="flex flex-col h-full">
      {/* New session button */}
      <div className="p-3 border-b border-[hsl(var(--sidebar-border))]">
        <Button
          variant="outline"
          size="sm"
          className="w-full justify-start gap-2 h-9 text-[13px] font-medium"
          onClick={onNewSession}
        >
          <Plus className="h-4 w-4" />
          新建会话
        </Button>
      </div>

      {/* Tree */}
      <div ref={scrollRef} className="flex-1 min-h-0">
        <ScrollArea className="h-full">
          <div className="p-2 pt-1">
            {systems.length === 0 ? (
              <div className="text-center py-8 text-[13px] text-muted-foreground">
                <p>暂无业务系统</p>
                <p className="text-xs mt-1">请在设置中添加</p>
              </div>
            ) : (
              systems.map((system) => {
                const sysSessions = sessionsBySystem[system.systemId] || [];
                const isExpanded = expandedSystems.has(system.systemId);

                return (
                  <div key={system.systemId} className="mb-0.5">
                    {/* System header */}
                    <div
                      className={cn(
                        'flex items-center gap-1.5 px-2 py-2 rounded-lg cursor-pointer transition-all duration-200',
                        isExpanded ? 'text-foreground' : 'text-foreground/60 hover:text-foreground/80',
                        'hover:bg-[hsl(var(--muted))]/50',
                      )}
                      onClick={() => toggleSystemExpanded(system.systemId, !isExpanded)}
                    >
                      <div className="flex items-center justify-center w-4 h-4">
                        {isExpanded ? (
                          <ChevronDown className="h-3.5 w-3.5 text-muted-foreground" />
                        ) : (
                          <ChevronRight className="h-3.5 w-3.5 text-muted-foreground" />
                        )}
                      </div>
                      <FolderOpen className={cn('h-4 w-4 shrink-0', isExpanded ? 'text-foreground/70' : 'text-muted-foreground')} />
                      <span className="text-[13px] font-medium truncate flex-1">{system.name}</span>
                      <span className="text-[11px] text-muted-foreground/60 tabular-nums">{sysSessions.length}</span>
                    </div>

                    {/* Sessions */}
                    {isExpanded && sysSessions.length > 0 && (
                      <div className="ml-6 mt-0.5 space-y-0.5">
                        {sysSessions.map((session) => (
                          <SessionItem
                            key={session.key}
                            label={sessionLabels[session.key] || session.label || '新会话'}
                            isActive={session.key === currentSessionId}
                            onSelect={() => handleSelect(session.key)}
                            onDelete={() => {
                              // Session deletion is handled via backend — for now just switch
                              handleSelect(sessions[0]?.key || '');
                            }}
                          />
                        ))}
                      </div>
                    )}
                  </div>
                );
              })
            )}
          </div>
        </ScrollArea>
      </div>
    </div>
  );
}
