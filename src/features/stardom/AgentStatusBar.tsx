// src/features/stardom/AgentStatusBar.tsx
import { useStardomStore } from '@/stores/stardom';
import { Circle, Zap } from 'lucide-react';
import { t } from '@/locales';

export function AgentStatusBar() {
  const { connection } = useStardomStore();

  if (!connection.connected) {
    return (
      <div className="flex items-center justify-between px-4 py-2 border-t bg-muted/30 text-sm text-muted-foreground">
        <div className="flex items-center gap-4">
          <div className="flex items-center gap-1.5">
            <Circle className="h-2.5 w-2.5 fill-current text-gray-400" />
            <span>{t("stardom.disconnected")}</span>
          </div>
        </div>
        <div className="flex items-center gap-1.5">
          <Zap className="h-3.5 w-3.5 text-muted-foreground/50" />
          <span className="text-muted-foreground/50">{t("stardom.notConnected")}</span>
        </div>
      </div>
    );
  }

  const statusColor = connection.agentStatus === 'idle' ? 'text-green-500' :
    connection.agentStatus === 'busy' ? 'text-yellow-500' : 'text-gray-400';

  return (
    <div className="flex items-center justify-between px-4 py-2 border-t bg-muted/30 text-sm text-muted-foreground">
      <div className="flex items-center gap-4">
        <div className="flex items-center gap-1.5">
          <Circle className={`h-2.5 w-2.5 fill-current ${statusColor}`} />
          <span>
            {connection.agentName ?? 'Agent'}: {connection.agentStatus ?? t("stardom.unknown")}
          </span>
        </div>
        <span>{t("stardom.reputation")} {connection.reputation ?? 0}</span>
        <span>{t("stardom.slots")} {connection.activeSlots}/{connection.maxSlots}</span>
      </div>
      <div className="flex items-center gap-1.5">
        <Zap className="h-3.5 w-3.5" />
        <span>{t("stardom.connected")}</span>
      </div>
    </div>
  );
}
