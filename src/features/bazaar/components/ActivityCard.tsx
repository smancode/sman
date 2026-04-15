// src/features/bazaar/components/ActivityCard.tsx
// 活动流中的单条卡片

import type { ActivityEntry } from '@/types/bazaar';
import { cn } from '@/lib/utils';

const TYPE_ICONS: Record<string, string> = {
  status_change: '●',
  task_event: '📋',
  capability_search: '🔍',
  collab_start: '🤝',
  collab_complete: '✅',
  reputation_change: '⭐',
  system: '🔔',
};

const TYPE_COLORS: Record<string, string> = {
  status_change: 'text-muted-foreground',
  task_event: 'text-blue-400',
  capability_search: 'text-purple-400',
  collab_start: 'text-green-400',
  collab_complete: 'text-emerald-400',
  reputation_change: 'text-yellow-400',
  system: 'text-orange-400',
};

function timeAgo(timestamp: number): string {
  const diff = Date.now() - timestamp;
  const seconds = Math.floor(diff / 1000);
  if (seconds < 60) return '刚刚';
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes}m 前`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h 前`;
  return `${Math.floor(hours / 24)}d 前`;
}

interface ActivityCardProps {
  entry: ActivityEntry;
  isExpanded?: boolean;
  onClick?: () => void;
}

export function ActivityCard({ entry, isExpanded, onClick }: ActivityCardProps) {
  const clickable = entry.type === 'collab_start' || entry.type === 'task_event';

  return (
    <div
      className={cn(
        'flex gap-3 px-4 py-2.5 border-b border-border/50 transition-colors',
        clickable && 'cursor-pointer hover:bg-muted/50',
        isExpanded && 'bg-muted/30',
      )}
      onClick={clickable ? onClick : undefined}
    >
      <span className={cn('text-sm mt-0.5 flex-shrink-0', TYPE_COLORS[entry.type])}>
        {TYPE_ICONS[entry.type]}
      </span>

      <div className="flex-1 min-w-0">
        <p className="text-sm leading-relaxed">{entry.description}</p>
        {entry.agentName && (
          <span className="text-xs text-muted-foreground">{entry.agentAvatar ?? ''} {entry.agentName}</span>
        )}
      </div>

      <span className="text-xs text-muted-foreground flex-shrink-0 mt-0.5">
        {timeAgo(entry.timestamp)}
      </span>
    </div>
  );
}
