import { t } from '@/locales';
import { FeedbackState } from '@/components/common/FeedbackState';
import { Badge } from '@/components/ui/badge';
import { ScrollArea } from '@/components/ui/scroll-area';
import { useRoomAgents } from '@/queries/use-hub';
import type { Agent } from '@/schemas/hub';
import { cn } from '@/lib/utils';
import { Bot } from 'lucide-react';

const STATUS_MAP: Record<string, { label: string; variant: 'success' | 'warning' | 'secondary' }> = {
  online: { label: 'Online', variant: 'success' },
  busy: { label: 'Busy', variant: 'warning' },
  offline: { label: 'Offline', variant: 'secondary' },
};

interface AgentListProps {
  roomId: string;
}

export function AgentList({ roomId }: AgentListProps) {
  const { data: agents, isLoading } = useRoomAgents(roomId);

  if (isLoading) return <FeedbackState state="loading" title={t('common.loading')} />;

  if (!agents || agents.length === 0) {
    return <FeedbackState state="empty" title={t('hub.agent.empty')} />;
  }

  return (
    <ScrollArea className="h-full">
      <div className="p-4 space-y-2">
        {(agents as Agent[]).map((agent) => {
          const caps = parseCapabilities(agent.capabilities);
          const st = STATUS_MAP[agent.status] ?? STATUS_MAP.offline;
          return (
            <div key={agent.id} className="rounded-lg border bg-card p-3 space-y-2">
              <div className="flex items-center gap-2">
                <Bot className="h-4 w-4 text-muted-foreground shrink-0" />
                <span className="text-sm font-medium truncate">{agent.id}</span>
                <Badge variant={st.variant} className="ml-auto shrink-0 text-[10px] h-4 px-1.5">{st.label}</Badge>
              </div>
              <div className="text-xs text-muted-foreground">{agent.workspace}</div>
              {caps.techStack.length > 0 && (
                <div className="flex flex-wrap gap-1">
                  {caps.techStack.map((tech: string) => (
                    <span key={tech} className="rounded-full bg-muted px-1.5 py-0.5 text-[11px] text-muted-foreground">{tech}</span>
                  ))}
                </div>
              )}
            </div>
          );
        })}
      </div>
    </ScrollArea>
  );
}

function parseCapabilities(json: string): { skills: string[]; techStack: string[]; projectType: string } {
  try {
    return JSON.parse(json);
  } catch {
    return { skills: [], techStack: [], projectType: '' };
  }
}
