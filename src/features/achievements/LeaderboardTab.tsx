import { cn } from '@/lib/utils';
import { useAchievementStore } from '@/stores/achievement';
import { TIER_COLORS, type Tier } from '@/types/achievement';
import { t, useLocale } from '@/locales';
import { Medal } from 'lucide-react';

export function LeaderboardTab() {
  useLocale();
  const { leaderboard, leaderboardOnline } = useAchievementStore();

  if (!leaderboardOnline && leaderboard.length === 0) {
    return (
      <div className="text-center py-16 text-muted-foreground text-sm">
        {t('achievement.leaderboard.offline')}
      </div>
    );
  }

  if (leaderboard.length === 0) {
    return (
      <div className="text-center py-16 text-muted-foreground text-sm">
        {t('achievement.leaderboard.empty')}
      </div>
    );
  }

  return (
    <div className="space-y-1.5">
      {leaderboard.map((entry) => {
        const tier = entry.level as Tier;
        const tierColors = TIER_COLORS[tier];
        const isTop3 = entry.rank <= 3;
        return (
          <div
            key={entry.rank}
            className={cn(
              'flex items-center gap-3 px-3 py-2.5 rounded-lg transition-colors',
              isTop3 && 'bg-foreground/[0.03]',
            )}
          >
            {/* Rank */}
            <div className="w-8 shrink-0 flex items-center justify-center">
              {isTop3 ? (
                <Medal
                  className={cn(
                    'h-5 w-5',
                    entry.rank === 1 && 'text-yellow-500',
                    entry.rank === 2 && 'text-gray-400',
                    entry.rank === 3 && 'text-amber-700',
                  )}
                />
              ) : (
                <span className="text-[12px] text-muted-foreground font-mono">{entry.rank}</span>
              )}
            </div>

            {/* Level */}
            <span className={cn('text-[11px] font-semibold px-1.5 py-0.5 rounded', tierColors.bg, tierColors.text)}>
              {t(`achievement.tier.${tier}`)}
            </span>

            {/* Name */}
            <div className="flex-1 min-w-0">
              <span className="text-[13px] font-medium truncate block">{entry.agentName}</span>
            </div>

            {/* Points */}
            <div className="text-[12px] text-muted-foreground shrink-0">
              {t('achievement.leaderboard.points', { points: String(entry.totalPoints) })}
            </div>

            {/* Unlocked count */}
            <div className="text-[11px] text-muted-foreground shrink-0 w-16 text-right">
              {entry.totalUnlocked} {t('achievement.unlockedCount', { count: '', total: '' }).trim()}
            </div>
          </div>
        );
      })}
    </div>
  );
}
