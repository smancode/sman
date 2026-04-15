// src/features/bazaar/components/ActivityFeed.tsx
// 中间区域 — 活动时间线 + 展开协作详情

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
      <div className="flex-1 flex items-center justify-center text-muted-foreground text-sm">
        等待 Agent 活动...
      </div>
    );
  }

  return (
    <ScrollArea className="flex-1">
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
