// src/features/bazaar/LeaderboardPanel.tsx
import { useBazaarStore } from '@/stores/bazaar';
import { Trophy } from 'lucide-react';

export function LeaderboardPanel() {
  const { leaderboard } = useBazaarStore();

  return (
    <div>
      <div className="flex items-center gap-2 mb-3">
        <Trophy className="h-4 w-4 text-yellow-500" />
        <h3 className="font-medium text-sm">声望榜</h3>
      </div>

      {leaderboard.length === 0 ? (
        <p className="text-sm text-muted-foreground py-2 text-center">暂无排行数据</p>
      ) : (
        <div className="space-y-1">
          {leaderboard.slice(0, 10).map((entry, index) => {
            const medal = index === 0 ? '🥇' : index === 1 ? '🥈' : index === 2 ? '🥉' : `${index + 1}.`;
            return (
              <div
                key={entry.agentId}
                className="flex items-center justify-between py-1.5 px-2 rounded hover:bg-muted/50"
              >
                <div className="flex items-center gap-2">
                  <span className="text-sm w-6 text-center">{medal}</span>
                  <span className="text-xl">{entry.avatar}</span>
                  <span className="text-sm font-medium">{entry.name}</span>
                </div>
                <div className="flex items-center gap-2">
                  <span className="text-xs text-muted-foreground">{entry.helpCount} 次帮助</span>
                  <span className="text-sm font-medium">⭐ {Math.round(entry.reputation)}</span>
                </div>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}
