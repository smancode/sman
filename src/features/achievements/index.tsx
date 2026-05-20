import { useEffect, useState, useMemo } from 'react';
import { cn } from '@/lib/utils';
import { useAchievementStore } from '@/stores/achievement';
import { TIER_COLORS, TIER_ORDER, TIER_ICONS, CATEGORY_LABELS, type Category, type Tier } from '@/types/achievement';
import { TIER_THRESHOLDS } from '@/types/achievement';
import { TierBadge } from './TierBadge';
import { AchievementCard } from './AchievementCard';
import { AchievementToast } from './AchievementToast';
import { LeaderboardTab } from './LeaderboardTab';
import { t, useLocale } from '@/locales';

type FilterTab = 'all' | Category | 'unlocked' | 'locked' | 'leaderboard';

const TABS: { key: FilterTab; labelKey: string }[] = [
  { key: 'all', labelKey: 'achievement.tabAll' },
  { key: 'unlocked', labelKey: 'achievement.tabUnlocked' },
  { key: 'locked', labelKey: 'achievement.tabLocked' },
  { key: 'conversation', labelKey: 'achievement.catConversation' },
  { key: 'bot', labelKey: 'achievement.catBot' },
  { key: 'advanced', labelKey: 'achievement.catAdvanced' },
  { key: 'exploration', labelKey: 'achievement.catExploration' },
  { key: 'collaboration', labelKey: 'achievement.catCollaboration' },
  { key: 'leaderboard', labelKey: 'achievement.leaderboard' },
];

const TAB_BREAKS = new Set(['conversation', 'leaderboard']);

export function AchievementsPage() {
  useLocale();
  const { summary, fetchSummary, fetchLeaderboard, isLoading, recentUnlocks, clearRecentUnlocks } = useAchievementStore();
  const [activeTab, setActiveTab] = useState<FilterTab>('all');

  useEffect(() => {
    fetchSummary();
  }, [fetchSummary]);

  const handleTabChange = (tab: FilterTab) => {
    setActiveTab(tab);
    if (tab === 'leaderboard') {
      fetchLeaderboard();
    }
  };

  const filtered = useMemo(() => {
    if (!summary) return [];
    return summary.achievements.filter((a) => {
      if (activeTab === 'all') return true;
      if (activeTab === 'unlocked') return !!a.unlockedAt;
      if (activeTab === 'locked') return !a.unlockedAt;
      return a.category === activeTab;
    });
  }, [summary, activeTab]);

  if (isLoading && !summary) {
    return (
      <div className="flex items-center justify-center h-full">
        <div className="text-muted-foreground text-sm">{t('achievement.loading')}</div>
      </div>
    );
  }

  if (!summary) return null;

  const levelTier = summary.level as Tier;
  const levelColors = TIER_COLORS[levelTier];
  const lp = summary.levelProgress;

  return (
    <div className="h-full">
      <div className="max-w-5xl mx-auto px-6 py-8 w-full">
        {/* Toast notifications */}
        {recentUnlocks.map((unlock, i) => (
          <AchievementToast
            key={`${unlock.achievement.id}-${i}`}
            unlock={unlock}
            onDismiss={clearRecentUnlocks}
          />
        ))}

        {/* Header */}
        <div className="mb-8">
          <div className="flex items-center gap-4 mb-4">
            <TierBadge tier={levelTier} icon={TIER_ICONS[levelTier]} size="lg" currentPoints={summary.totalPoints} />
            <div>
              <div className="flex items-center gap-2">
                <h1 className={cn(
                  'text-2xl font-bold',
                  'dark:text-foreground',
                )}>
                  {t('achievement.title')}
                </h1>
                <span className={cn(
                  'text-sm font-semibold px-2 py-0.5 border-2 dark:border-0',
                  levelColors.text, levelColors.bg, levelColors.border,
                  'dark:rounded',
                  'rounded dark:rounded',
                )}>
                  Lv.{TIER_ORDER.indexOf(levelTier) + 1} {t(`achievement.tier.${levelTier}`)}
                </span>
              </div>
              <div className="flex items-center gap-3 text-sm text-muted-foreground mt-1">
                <span className={cn(
                  'px-2 py-0.5 text-[12px] font-bold border-2 border-black dark:border-0 dark:font-medium dark:rounded bg-yellow-200 dark:bg-muted/50 dark:text-muted-foreground',
                )}>
                  {t('achievement.points', { count: String(summary.totalPoints) })}
                </span>
                <span>{t('achievement.unlockedCount', { count: String(summary.totalUnlocked), total: String(summary.totalAchievements) })}</span>
              </div>
            </div>
          </div>

          {/* Level progress bar */}
          {lp && lp.progress < 1 && (
            <div className="space-y-1.5 max-w-md">
              <div className="flex justify-between text-[11px] text-muted-foreground">
                <span className="font-bold dark:font-medium">{t(`achievement.tier.${levelTier}`)}</span>
                <span>{t('achievement.nextLevel', { points: String(Math.round(lp.nextMin - lp.currentMin - lp.progress * (lp.nextMin - lp.currentMin))) })}</span>
              </div>
              <div className={cn(
                'h-3 dark:h-2 border-2 border-black dark:border-0 dark:rounded-full bg-muted overflow-hidden',
                'dark:bg-muted',
              )}>
                <div
                  className={cn(
                    'h-full transition-all duration-700',
                    levelColors.bar,
                    'dark:rounded-full',
                  )}
                  style={{ width: `${lp.progress * 100}%` }}
                />
              </div>
            </div>
          )}

          {/* Streak */}
          {summary.streak.current > 0 && (
            <div className={cn(
              'mt-3 text-[12px] inline-block px-2 py-0.5 border-2 border-black dark:border-0 dark:rounded',
              'font-bold dark:font-medium dark:text-muted-foreground',
              'bg-orange-200 dark:bg-transparent',
            )}>
              {t('achievement.streak', { current: String(summary.streak.current), longest: String(summary.streak.longest) })}
            </div>
          )}
        </div>

        {/* Filter tabs */}
        <div className="mb-6 space-y-1.5">
          <div className="flex flex-wrap gap-1.5">
            {TABS.filter((_, i) => i < 3).map((tab) => (
              <button
                key={tab.key}
                onClick={() => handleTabChange(tab.key)}
                className={cn(
                  'px-3 py-1.5 text-[12px] font-bold dark:font-medium transition-all duration-150',
                  'dark:rounded-lg',
                  activeTab === tab.key
                    ? 'bg-foreground text-background border-2 border-black shadow-[2px_2px_0_0_#1e293b] dark:shadow-none dark:bg-foreground/10 dark:text-foreground dark:border-0'
                    : 'border-2 border-transparent dark:border-0 bg-white dark:bg-transparent text-foreground hover:border-black dark:hover:text-foreground dark:hover:bg-muted/50 hover:shadow-[2px_2px_0_0_#1e293b] dark:hover:shadow-none',
                )}
              >
                {t(tab.labelKey)}
              </button>
            ))}
          </div>
          <div className="flex flex-wrap gap-1.5">
            {TABS.filter((_, i) => i >= 3 && i < TABS.length - 1).map((tab) => (
              <button
                key={tab.key}
                onClick={() => handleTabChange(tab.key)}
                className={cn(
                  'px-3 py-1.5 text-[12px] font-bold dark:font-medium transition-all duration-150',
                  'dark:rounded-lg',
                  activeTab === tab.key
                    ? 'bg-foreground text-background border-2 border-black shadow-[2px_2px_0_0_#1e293b] dark:shadow-none dark:bg-foreground/10 dark:text-foreground dark:border-0'
                    : 'border-2 border-transparent dark:border-0 bg-white dark:bg-transparent text-foreground hover:border-black dark:hover:text-foreground dark:hover:bg-muted/50 hover:shadow-[2px_2px_0_0_#1e293b] dark:hover:shadow-none',
                )}
              >
                {t(tab.labelKey)}
              </button>
            ))}
          </div>
          <div className="flex flex-wrap gap-1.5">
            {TABS.filter((_, i) => i === TABS.length - 1).map((tab) => (
              <button
                key={tab.key}
                onClick={() => handleTabChange(tab.key)}
                className={cn(
                  'px-3 py-1.5 text-[12px] font-bold dark:font-medium transition-all duration-150',
                  'dark:rounded-lg',
                  activeTab === tab.key
                    ? 'bg-foreground text-background border-2 border-black shadow-[2px_2px_0_0_#1e293b] dark:shadow-none dark:bg-foreground/10 dark:text-foreground dark:border-0'
                    : 'border-2 border-transparent dark:border-0 bg-white dark:bg-transparent text-foreground hover:border-black dark:hover:text-foreground dark:hover:bg-muted/50 hover:shadow-[2px_2px_0_0_#1e293b] dark:hover:shadow-none',
                )}
              >
                {t(tab.labelKey)}
              </button>
            ))}
          </div>
        </div>

        {/* Content */}
        {activeTab === 'leaderboard' ? (
          <LeaderboardTab />
        ) : (
          <>
            <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-4 gap-3">
              {filtered.map((a) => (
                <AchievementCard key={a.id} achievement={a} />
              ))}
            </div>
            {filtered.length === 0 && (
              <div className="text-center py-16 border-2 border-black dark:border-0 dark:rounded-lg bg-white dark:bg-transparent">
                <span className="text-muted-foreground text-sm font-bold dark:font-medium">{t('achievement.noResults')}</span>
              </div>
            )}
          </>
        )}
      </div>
    </div>
  );
}
