import { cn } from '@/lib/utils';
import { useAchievementStore } from '@/stores/achievement';
import { TIER_COLORS, type Tier } from '@/types/achievement';
import { t, useLocale } from '@/locales';
import { Medal } from 'lucide-react';

export function LeaderboardTab() {
  useLocale();
  const { leaderboard, leaderboardOnline, leaderboardClientId } = useAchievementStore();

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

  // Find self — agentName ends with clientId.slice(-6)
  const selfSuffix = leaderboardClientId ? leaderboardClientId.slice(-6) : '';
  const selfIndex = selfSuffix
    ? leaderboard.findIndex((e) => e.agentName.endsWith(`@${selfSuffix}`))
    : -1;

  // Build display list: top 5 + ... + self neighborhood
  const displayEntries: (LeaderboardEntry | 'ellipsis')[] = [];

  if (selfIndex <= 4) {
    // Self is in top 5, just show top 10 or all
    const show = leaderboard.slice(0, Math.min(10, leaderboard.length));
    displayEntries.push(...show);
  } else {
    // Show top 5
    displayEntries.push(...leaderboard.slice(0, 5));
    displayEntries.push('ellipsis');
    // Self + 2 before + 2 after
    const start = Math.max(5, selfIndex - 2);
    const end = Math.min(leaderboard.length, selfIndex + 3);
    displayEntries.push(...leaderboard.slice(start, end));
  }

  return (
    <div className="space-y-1.5">
      {displayEntries.map((entry, i) => {
        if (entry === 'ellipsis') {
          return (
            <div key={`ellipsis-${i}`} className="flex items-center justify-center py-1">
              <span className="text-muted-foreground text-[12px]">· · ·</span>
            </div>
          );
        }

        const tier = entry.level as Tier;
        const tierColors = TIER_COLORS[tier];
        const isTop3 = entry.rank <= 3;
        const isSelf = entry.agentName.endsWith(`@${selfSuffix}`);
        return (
          <div
            key={entry.rank}
            className={cn(
              'flex items-center gap-3 px-3 py-2.5 rounded-lg transition-colors',
              isTop3 && 'bg-foreground/[0.03]',
              isSelf && 'ring-1 ring-foreground/20 bg-foreground/[0.05]',
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
              <span className={cn('text-[13px] font-medium truncate block', isSelf && 'text-foreground')}>
                {entry.agentName}
              </span>
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

type LeaderboardEntry = {
  rank: number;
  agentName: string;
  totalPoints: number;
  totalUnlocked: number;
  level: string;
};
