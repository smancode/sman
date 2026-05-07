// src/features/stardom/components/ActivityCard.tsx
// 活动流卡片 — 世界事件表述
// 把底层行为翻译成战略指挥中心的战报语言

import type { ActivityEntry } from '@/types/stardom';
import { cn } from '@/lib/utils';
import { t } from '@/locales';

// 世界事件图标 — Atlas 星图风格
const TYPE_ICONS: Record<string, string> = {
  status_change: '◉',
  task_event: '⬡',
  capability_search: '◈',
  collab_start: '⟐',
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
  if (seconds < 60) return t('stardom.activity.justNow');
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes}m`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h`;
  return `${Math.floor(hours / 24)}d`;
}

// 世界事件转译：底层行为 → Atlas 星图语言
function translateToWorldEvent(entry: ActivityEntry): string {
  const meta = entry.metadata ?? {};
  const direction = meta.direction as string;
  const status = meta.status as string;

  switch (entry.type) {
    case 'task_event': {
      if (direction === 'outgoing') {
        if (status === 'searching') return `${t('stardom.activity.searchStart')}: ${entry.description}`;
        if (status === 'matched') return t('stardom.activity.linkEstablished');
        if (status === 'chatting') return t('stardom.activity.realtimeSync');
        return entry.description;
      }
      if (status === 'offered') return t('stardom.activity.collabRequest');
      if (status === 'matched') return t('stardom.activity.handshakeDone');
      return entry.description;
    }
    case 'collab_start':
      return t('stardom.activity.newPath');
    case 'collab_complete':
      return t('stardom.activity.completed');
    case 'reputation_change':
      return entry.description;
    case 'capability_search':
      return t('stardom.activity.scanning');
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
