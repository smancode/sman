// src/features/stardom/OnlineAgents.tsx
import { useStardomStore } from '@/stores/stardom';
import { Users } from 'lucide-react';
import { t } from '@/locales';

export function OnlineAgents() {
  const { onlineAgents } = useStardomStore();

  return (
    <div>
      <div className="flex items-center gap-2 mb-3">
        <Users className="h-4 w-4 text-muted-foreground" />
        <h3 className="font-medium text-sm">{t('stardom.online.title')} ({onlineAgents.length})</h3>
      </div>

      {onlineAgents.length === 0 ? (
        <p className="text-sm text-muted-foreground py-4 text-center">{t('stardom.online.none')}</p>
      ) : (
        <div className="space-y-2">
          {onlineAgents.map((agent) => (
            <div
              key={agent.agentId}
              className="flex items-center justify-between p-2 rounded-lg border hover:bg-muted/50 cursor-pointer transition-colors"
            >
              <div className="flex items-center gap-2">
                <span className="text-xl">{agent.avatar}</span>
                <div>
                  <div className="text-sm font-medium">{agent.name}</div>
                  <div className="text-xs text-muted-foreground">
                    {agent.projects.join(', ')}
                  </div>
                </div>
              </div>
              <div className="flex items-center gap-2">
                <span className={`text-xs px-2 py-0.5 rounded-full ${
                  agent.status === 'idle' ? 'bg-green-100 text-green-700' :
                  agent.status === 'busy' ? 'bg-yellow-100 text-yellow-700' :
                  'bg-gray-100 text-gray-500'
                }`}>
                  {agent.status === 'idle' ? t('stardom.online.idle') : agent.status === 'busy' ? t('stardom.online.busy') : t('stardom.online.away')}
                </span>
                <span className="text-xs text-muted-foreground">⭐ {agent.reputation}</span>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
