import { cn } from '@/lib/utils';
import { useAchievementStore } from '@/stores/achievement';
import { TIER_COLORS, type Tier } from '@/types/achievement';
import { t, useLocale } from '@/locales';
import { Medal } from 'lucide-react';

type LeaderboardEntry = {
  rank: number;
  agentName: string;
  totalPoints: number;
  totalUnlocked: number;
  level: string;
};

export function LeaderboardTab() {
  useLocale();
  const { leaderboard, leaderboardOnline, leaderboardClientId } = useAchievementStore();

  if (!leaderboardOnline && leaderboard.length === 0) {
    return (
      <div className="text-center py-16 text-muted-foreground/60 text-sm">
        {t('achievement.leaderboard.offline')}
      </div>
    );
  }

  if (leaderboard.length === 0) {
    return (
      <div className="text-center py-16 text-muted-foreground/60 text-sm">
        {t('achievement.leaderboard.empty')}
      </div>
    );
  }

  const selfSuffix = leaderboardClientId || '';
  const selfIndex = selfSuffix
    ? leaderboard.findIndex((e) => e.agentName === selfSuffix)
    : -1;

  // Build display list: top 5 + ... + self neighborhood
  const displayEntries: (LeaderboardEntry | 'ellipsis')[] = [];

  if (selfIndex <= 4) {
    const show = leaderboard.slice(0, Math.min(10, leaderboard.length));
    displayEntries.push(...show);
  } else {
    displayEntries.push(...leaderboard.slice(0, 5));
    displayEntries.push('ellipsis');
    const start = Math.max(5, selfIndex - 2);
    const end = Math.min(leaderboard.length, selfIndex + 3);
    displayEntries.push(...leaderboard.slice(start, end));
  }

  return (
    <div>
      {/* Header row */}
      <div className={cn(
        'flex items-center gap-3 px-4 py-2 text-[10px] font-bold dark:font-medium uppercase tracking-wider',
        'border-b-2 border-black dark:border-cyan-500/20 mb-2',
        'bg-foreground text-background dark:bg-transparent dark:text-muted-foreground/60',
      )}>
        <div className="w-8 shrink-0 text-center">{t('achievement.leaderboard.rankCol')}</div>
        <div className="w-16 shrink-0">{t('achievement.leaderboard.tierCol')}</div>
        <div className="flex-1 min-w-0">{t('achievement.leaderboard.nameCol')}</div>
        <div className="shrink-0 w-16 text-right">{t('achievement.leaderboard.pointsCol')}</div>
        <div className="shrink-0 w-16 text-right">{t('achievement.leaderboard.unlockedCol')}</div>
      </div>

      {/* Entries */}
      <div className="space-y-1">
        {displayEntries.map((entry, i) => {
          if (entry === 'ellipsis') {
            return (
              <div key={`ellipsis-${i}`} className="flex items-center justify-center py-1">
                <span className="text-muted-foreground/40 text-[12px]">· · ·</span>
              </div>
            );
          }

          const tier = entry.level as Tier;
          const tierColors = TIER_COLORS[tier];
          const isTop3 = entry.rank <= 3;
          const isSelf = entry.agentName === selfSuffix;
          return (
            <div
              key={entry.rank}
              className={cn(
                'flex items-center gap-3 px-4 py-2.5 transition-all duration-200',
                'dark:rounded-xl',
                isTop3 && 'border-2 border-black dark:border-0 dark:bg-foreground/[0.02]',
                isTop3 && entry.rank === 1 && 'bg-yellow-100 dark:bg-foreground/[0.02] shadow-[2px_2px_0_0_#1e293b] dark:shadow-none',
                isTop3 && entry.rank === 2 && 'bg-slate-100 dark:bg-foreground/[0.02] shadow-[2px_2px_0_0_#1e293b] dark:shadow-none',
                isTop3 && entry.rank === 3 && 'bg-amber-50 dark:bg-foreground/[0.02] shadow-[2px_2px_0_0_#1e293b] dark:shadow-none',
                isSelf && !isTop3 && 'border-2 border-black dark:border-0 dark:ring-cyan-400/40 dark:bg-cyan-400/5 bg-white shadow-[2px_2px_0_0_#1e293b] dark:shadow-none',
                !isTop3 && !isSelf && 'hover:bg-muted/30 dark:hover:bg-white/[0.02]',
              )}
            >
              {/* Rank */}
              <div className="w-8 shrink-0 flex items-center justify-center">
                {isTop3 ? (
                  <Medal
                    className={cn(
                      'h-4.5 w-4.5',
                      entry.rank === 1 && 'text-yellow-500',
                      entry.rank === 2 && 'text-gray-400',
                      entry.rank === 3 && 'text-amber-700',
                    )}
                  />
                ) : (
                  <span className="text-[13px] text-muted-foreground/50 font-mono font-medium">{entry.rank}</span>
                )}
              </div>

              {/* Level */}
              <div className="w-16 shrink-0">
                <span className={cn(
                  'text-[11px] font-bold dark:font-medium px-1.5 py-0.5',
                  'border border-black dark:border-0 dark:rounded-full',
                  tierColors.bg, tierColors.text,
                )}>
                  {t(`achievement.tier.${tier}`)}
                </span>
              </div>

              {/* Name */}
              <div className="flex-1 min-w-0">
                <span className={cn(
                  'text-[13px] truncate block',
                  isSelf ? 'font-medium' : '',
                )}>
                  {entry.agentName}
                </span>
              </div>

              {/* Points */}
              <div className="text-[12px] text-muted-foreground shrink-0 w-16 text-right">
                {entry.totalPoints}
              </div>

              {/* Unlocked count */}
              <div className="text-[12px] text-muted-foreground shrink-0 w-16 text-right">
                {entry.totalUnlocked}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}
