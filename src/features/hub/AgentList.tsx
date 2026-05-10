import { t } from '@/locales';
import { useRoomAgents } from '@/queries/use-hub';
import type { Agent } from '@/schemas/hub';

const STATUS_COLORS: Record<string, string> = {
  online: 'bg-green-500',
  busy: 'bg-yellow-500',
  offline: 'bg-gray-400',
};

interface AgentListProps {
  roomId: string;
}

export function AgentList({ roomId }: AgentListProps) {
  const { data: agents, isLoading } = useRoomAgents(roomId);

  if (isLoading) return <div className="p-4 text-muted-foreground">{t('common.loading')}</div>;

  if (!agents || agents.length === 0) {
    return <div className="p-4 text-muted-foreground">{t('hub.agent.empty')}</div>;
  }

  return (
    <div className="p-4 space-y-2">
      {(agents as Agent[]).map((agent) => {
        const caps = parseCapabilities(agent.capabilities);
        return (
          <div key={agent.id} className="rounded-lg border bg-card p-3">
            <div className="flex items-center gap-2">
              <span className={`h-2 w-2 rounded-full ${STATUS_COLORS[agent.status] || 'bg-gray-400'}`} />
              <span className="text-sm font-medium">{agent.id}</span>
              <span className="text-xs text-muted-foreground ml-auto">{agent.status}</span>
            </div>
            <div className="mt-1 text-xs text-muted-foreground">{agent.workspace}</div>
            {caps.techStack.length > 0 && (
              <div className="mt-2 flex flex-wrap gap-1">
                {caps.techStack.map((tech: string) => (
                  <span key={tech} className="rounded-full bg-muted px-2 py-0.5 text-xs">{tech}</span>
                ))}
              </div>
            )}
            <div className="mt-1 text-xs text-muted-foreground">
              {t('hub.agent.registered')}: {new Date(agent.registered_at).toLocaleString()}
            </div>
          </div>
        );
      })}
    </div>
  );
}

function parseCapabilities(json: string): { skills: string[]; techStack: string[]; projectType: string } {
  try {
    return JSON.parse(json);
  } catch {
    return { skills: [], techStack: [], projectType: '' };
  }
}
