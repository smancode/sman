// src/features/bazaar/components/ActivityCard.tsx
// 活动流卡片 — 世界事件表述
// 把底层行为翻译成战略指挥中心的战报语言

import type { ActivityEntry } from '@/types/bazaar';
import { cn } from '@/lib/utils';

// 世界事件图标 — 战略指挥风格
const TYPE_ICONS: Record<string, string> = {
  status_change: '◉',
  task_event: '⚡',
  capability_search: '◈',
  collab_start: '⬡',
  collab_complete: '★',
  reputation_change: '◆',
  system: '▣',
};

const TYPE_COLORS: Record<string, string> = {
  status_change: 'var(--bz-text-dim)',
  task_event: 'var(--bz-cyan)',
  capability_search: 'var(--bz-purple)',
  collab_start: 'var(--bz-green)',
  collab_complete: 'var(--bz-amber)',
  reputation_change: 'var(--bz-amber)',
  system: 'var(--bz-red)',
};

function timeAgo(timestamp: number): string {
  const diff = Date.now() - timestamp;
  const seconds = Math.floor(diff / 1000);
  if (seconds < 60) return '刚刚';
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes}m`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h`;
  return `${Math.floor(hours / 24)}d`;
}

// 世界事件转译：底层行为 → 战略语言
function translateToWorldEvent(entry: ActivityEntry): string {
  const meta = entry.metadata ?? {};
  const direction = meta.direction as string;
  const status = meta.status as string;

  switch (entry.type) {
    case 'task_event': {
      if (direction === 'outgoing') {
        if (status === 'searching') return `分身网络已发出协作召集信号: ${entry.description}`;
        if (status === 'matched') return `协作链路已建立，执行分身已部署至战线`;
        if (status === 'chatting') return `协作战线进入实时联动状态`;
        return entry.description;
      }
      if (status === 'offered') return `邻近能力节点发来协同支援请求`;
      if (status === 'matched') return `已与协作节点完成握手，链路激活`;
      return entry.description;
    }
    case 'collab_start':
      return `执行分身已进入新的协作现场`;
    case 'collab_complete':
      return `本轮协作行动完成，协作信用与影响力已结算`;
    case 'reputation_change':
      return entry.description.replace('声望', '网络影响力');
    case 'capability_search':
      return `正在扫描分身网络中的能力节点`;
    case 'status_change':
      return entry.description;
    default:
      return entry.description;
  }
}

interface ActivityCardProps {
  entry: ActivityEntry;
  isExpanded?: boolean;
  onClick?: () => void;
}

export function ActivityCard({ entry, isExpanded, onClick }: ActivityCardProps) {
  const clickable = entry.type === 'collab_start' || entry.type === 'task_event';
  const worldDesc = translateToWorldEvent(entry);
  const color = TYPE_COLORS[entry.type] ?? 'var(--bz-text-dim)';

  return (
    <div
      className={cn(
        'flex gap-3 px-4 py-2.5 transition-all duration-200 bz-card-enter',
        clickable && 'cursor-pointer',
        isExpanded && 'bg-white/5',
      )}
      style={{ borderBottom: '1px solid var(--bz-border)' }}
      onClick={clickable ? onClick : undefined}
      onMouseEnter={(e) => { if (clickable) e.currentTarget.style.background = 'var(--bz-bg-hover)'; }}
      onMouseLeave={(e) => { if (clickable) e.currentTarget.style.background = isExpanded ? 'rgba(255,255,255,0.03)' : 'transparent'; }}
    >
      <span className="text-sm mt-0.5 flex-shrink-0" style={{ color, textShadow: `0 0 6px ${color}` }}>
        {TYPE_ICONS[entry.type] ?? '●'}
      </span>

      <div className="flex-1 min-w-0">
        <p className="text-sm leading-relaxed" style={{ color: 'var(--bz-text)' }}>{worldDesc}</p>
        {entry.agentName && (
          <span className="text-[10px]" style={{ color: 'var(--bz-text-dim)' }}>{entry.agentAvatar ?? ''} {entry.agentName}</span>
        )}
      </div>

      <span className="text-[10px] font-mono flex-shrink-0 mt-0.5" style={{ color: 'var(--bz-text-dim)' }}>
        {timeAgo(entry.timestamp)}
      </span>
    </div>
  );
}
