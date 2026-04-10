// src/features/bazaar/AgentStatusBar.tsx
import { useBazaarStore } from '@/stores/bazaar';
import { Circle, Zap } from 'lucide-react';

export function AgentStatusBar() {
  const { connection } = useBazaarStore();
  const statusColor = connection.agentStatus === 'idle' ? 'text-green-500' :
    connection.agentStatus === 'busy' ? 'text-yellow-500' : 'text-gray-400';

  return (
    <div className="flex items-center justify-between px-4 py-2 border-t bg-muted/30 text-sm text-muted-foreground">
      <div className="flex items-center gap-4">
        <div className="flex items-center gap-1.5">
          <Circle className={`h-2.5 w-2.5 fill-current ${statusColor}`} />
          <span>
            {connection.agentName ?? 'Agent'}: {connection.agentStatus ?? '未知'}
          </span>
        </div>
        <span>声望 {connection.reputation ?? 0}</span>
        <span>槽位 {connection.activeSlots}/{connection.maxSlots}</span>
      </div>
      <div className="flex items-center gap-1.5">
        <Zap className="h-3.5 w-3.5" />
        <span>集市已连接</span>
      </div>
    </div>
  );
}
