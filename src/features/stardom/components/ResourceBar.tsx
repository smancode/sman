// src/features/stardom/components/ResourceBar.tsx
// 顶部资源条 — 像战略游戏资源栏
// 活跃任务 / 声望 / 协作信用 / 并行容量

import { useStardomStore } from '@/stores/stardom';
import { useChatStore } from '@/stores/chat';
import { Cpu, Trophy, Zap, Users, Radio } from 'lucide-react';
import { getReputationLevel } from './ReputationUtils';

function ResourceItem({ icon: Icon, label, value, max, color, glow }: {
  icon: React.ElementType;
  label: string;
  value: number | string;
  max?: number;
  color: string;
  glow?: string;
}) {
  return (
    <div className="flex items-center gap-2 px-3 py-1.5 rounded" style={{ background: 'var(--bz-bg-card)' }}>
      <Icon className="h-3.5 w-3.5 flex-shrink-0" style={{ color, filter: glow ? `drop-shadow(0 0 4px ${glow})` : undefined }} />
      <div className="flex flex-col">
        <span className="text-[10px] leading-tight" style={{ color: 'var(--bz-text-dim)' }}>{label}</span>
        <div className="flex items-baseline gap-1">
          <span className="text-sm font-mono font-bold" style={{ color }}>{value}</span>
          {max !== undefined && (
            <span className="text-[10px] font-mono" style={{ color: 'var(--bz-text-dim)' }}>/ {max}</span>
          )}
        </div>
      </div>
      {max !== undefined && typeof value === 'number' && (
        <div className="w-12 h-1.5 rounded-full overflow-hidden ml-1" style={{ background: 'var(--bz-bg)' }}>
          <div
            className="h-full rounded-full transition-all duration-500"
            style={{ width: `${Math.min(100, (value / max) * 100)}%`, background: color, boxShadow: `0 0 6px ${color}` }}
          />
        </div>
      )}
    </div>
  );
}

export function ResourceBar() {
  const { connection, tasks, onlineAgents } = useStardomStore();
  const sending = useChatStore((s) => s.sending);

  const activeTasks = tasks.filter(t => ['searching', 'offered', 'matched', 'chatting'].includes(t.status)).length;
  const maxSlots = connection.maxSlots ?? 3;
  const reputation = connection.reputation ?? 0;
  const level = getReputationLevel(reputation);

  return (
    <div className="flex items-center gap-2 px-3 py-1.5 overflow-x-auto" style={{ borderBottom: '1px solid var(--bz-border)', background: 'var(--bz-bg-panel)' }}>
      {/* Agent status indicator */}
      <div className="flex items-center gap-2 pr-3 mr-1" style={{ borderRight: '1px solid var(--bz-border)' }}>
        <div className="w-2 h-2 rounded-full flex-shrink-0" style={{
          background: sending ? 'var(--bz-amber)' : connection.connected ? 'var(--bz-green)' : 'var(--bz-text-dim)',
          boxShadow: sending
            ? '0 0 8px var(--bz-amber-glow)'
            : connection.connected
              ? '0 0 8px var(--bz-green-glow)'
              : 'none',
          animation: sending ? 'bz-pulse-yellow 1.5s ease-in-out infinite' : connection.connected ? 'bz-pulse-green 2s ease-in-out infinite' : 'none',
        }} />
        <span className="text-xs font-medium">{sending ? '处理中' : connection.connected ? '已连接' : '本地模式'}</span>
      </div>

      <ResourceItem icon={Cpu} label="行动流" value={activeTasks} max={maxSlots} color="var(--bz-cyan)" glow="var(--bz-cyan)" />
      <ResourceItem icon={Trophy} label="贡献沉积" value={reputation} color="var(--bz-amber)" glow="var(--bz-amber)" />
      <ResourceItem icon={Zap} label={level.title} value={`Lv.${level.level}`} color="var(--bz-purple)" />
      <ResourceItem icon={Users} label="活跃节点" value={onlineAgents.length} color="var(--bz-green)" />
      <ResourceItem icon={Radio} label="星域信号" value={connection.connected ? '强' : '无'} color={connection.connected ? 'var(--bz-green)' : 'var(--bz-red)'} />
    </div>
  );
}
