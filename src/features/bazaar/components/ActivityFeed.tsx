// src/features/bazaar/components/ActivityFeed.tsx
// 活动时间线 — 深色科技风

import { useState } from 'react';
import { useBazaarStore } from '@/stores/bazaar';
import { ScrollArea } from '@/components/ui/scroll-area';
import { ActivityCard } from './ActivityCard';
import { CollaborationDetail } from './CollaborationDetail';

export function ActivityFeed() {
  const { activityLog } = useBazaarStore();
  const [expandedTaskId, setExpandedTaskId] = useState<string | null>(null);

  const handleCardClick = (entry: typeof activityLog[number]) => {
    const taskId = entry.metadata?.taskId as string | undefined;
    if (!taskId) return;
    setExpandedTaskId(prev => prev === taskId ? null : taskId);
  };

  if (activityLog.length === 0) {
    return (
      <div className="flex-1 flex flex-col items-center justify-center gap-2" style={{ color: 'var(--bz-text-dim)' }}>
        <div className="text-3xl opacity-30">◎</div>
        <p className="text-sm">分身网络待命中，等待世界事件...</p>
        <p className="text-xs opacity-60">Agent 活动将实时显示在此</p>
      </div>
    );
  }

  return (
    <ScrollArea className="h-full">
      <div>
        {activityLog.map((entry) => {
          const taskId = entry.metadata?.taskId as string | undefined;
          const isExpanded = expandedTaskId != null && taskId === expandedTaskId;
          return (
            <div key={entry.id}>
              <ActivityCard
                entry={entry}
                isExpanded={isExpanded}
                onClick={() => handleCardClick(entry)}
              />
              {isExpanded && taskId && (
                <CollaborationDetail
                  taskId={taskId}
                  onClose={() => setExpandedTaskId(null)}
                />
              )}
            </div>
          );
        })}
      </div>
    </ScrollArea>
  );
}
