import { useState } from 'react';
import { t } from '@/locales';
import { FeedbackState } from '@/components/common/FeedbackState';
import { Badge } from '@/components/ui/badge';
import { ScrollArea } from '@/components/ui/scroll-area';
import { useRoomAgents } from '@/queries/use-hub';
import type { Agent } from '@/schemas/hub';
import { cn } from '@/lib/utils';
import { Bot, ChevronDown, ChevronUp } from 'lucide-react';

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
  const [expandedId, setExpandedId] = useState<string | null>(null);

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
          const isExpanded = expandedId === agent.id;
          const displayName = agent.client_id || agent.id;

          return (
            <div
              key={agent.id}
              onClick={() => setExpandedId(isExpanded ? null : agent.id)}
              className="rounded-lg border bg-card p-3 space-y-1.5 cursor-pointer hover:shadow-sm transition-shadow"
            >
              <div className="flex items-center gap-2">
                <Bot className="h-4 w-4 text-muted-foreground shrink-0" />
                <span className="text-sm font-medium truncate">{displayName}</span>
                <Badge variant={st.variant} className="ml-auto shrink-0 text-[10px] h-4 px-1.5">{st.label}</Badge>
                {isExpanded
                  ? <ChevronUp className="h-3 w-3 text-muted-foreground shrink-0" />
                  : <ChevronDown className="h-3 w-3 text-muted-foreground shrink-0" />}
              </div>

              <div className="text-xs text-muted-foreground truncate">{agent.workspace}</div>

              {caps.summary && (
                <p className="text-xs text-muted-foreground line-clamp-1">{caps.summary}</p>
              )}

              {caps.techStack.length > 0 && (
                <div className="flex flex-wrap gap-1">
                  {caps.techStack.slice(0, 5).map((tech: string) => (
                    <span key={tech} className="rounded-full bg-muted px-1.5 py-0.5 text-[11px] text-muted-foreground">{tech}</span>
                  ))}
                  {caps.techStack.length > 5 && (
                    <span className="text-[11px] text-muted-foreground">+{caps.techStack.length - 5}</span>
                  )}
                </div>
              )}

              {isExpanded && (
                <div className="border-t pt-2 mt-1 space-y-1.5">
                  {caps.description && (
                    <div>
                      <span className="text-[11px] font-medium text-muted-foreground">{t('hub.agent.description')}</span>
                      <p className="text-xs text-muted-foreground mt-0.5">{caps.description}</p>
                    </div>
                  )}
                  {caps.skills.length > 0 && (
                    <div>
                      <span className="text-[11px] font-medium text-muted-foreground">{t('hub.agent.skills')}</span>
                      <div className="flex flex-wrap gap-1 mt-0.5">
                        {caps.skills.map((s: string) => (
                          <span key={s} className="rounded bg-primary/10 px-1 py-0.5 text-[10px]">{s}</span>
                        ))}
                      </div>
                    </div>
                  )}
                  {caps.projectType && (
                    <div>
                      <span className="text-[11px] font-medium text-muted-foreground">{t('hub.agent.projectType')}</span>
                      <span className="text-xs ml-1">{caps.projectType}</span>
                    </div>
                  )}
                </div>
              )}
            </div>
          );
        })}
      </div>
    </ScrollArea>
  );
}

function parseCapabilities(json: string): { skills: string[]; techStack: string[]; projectType: string; summary: string; description: string } {
  try {
    const parsed = JSON.parse(json);
    return {
      skills: Array.isArray(parsed.skills) ? parsed.skills : [],
      techStack: Array.isArray(parsed.techStack) ? parsed.techStack : [],
      projectType: typeof parsed.projectType === 'string' ? parsed.projectType : '',
      summary: typeof parsed.summary === 'string' ? parsed.summary : '',
      description: typeof parsed.description === 'string' ? parsed.description : '',
    };
  } catch {
    return { skills: [], techStack: [], projectType: '', summary: '', description: '' };
  }
}
