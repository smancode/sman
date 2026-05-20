import { useEffect, useState, useMemo } from 'react';
import { cn } from '@/lib/utils';
import { useAchievementStore } from '@/stores/achievement';
import { TIER_COLORS, TIER_ORDER, TIER_ICONS, CATEGORY_LABELS, type Category, type Tier } from '@/types/achievement';
import { TierBadge } from './TierBadge';
import { AchievementCard } from './AchievementCard';
import { AchievementToast } from './AchievementToast';
import { t, useLocale } from '@/locales';

type FilterTab = 'all' | Category | 'unlocked' | 'locked';

const TABS: { key: FilterTab; label: string }[] = [
  { key: 'all', label: '全部' },
  { key: 'unlocked', label: '已解锁' },
  { key: 'locked', label: '未解锁' },
  { key: 'conversation', label: '对话' },
  { key: 'advanced', label: '进阶' },
  { key: 'exploration', label: '探索' },
  { key: 'collaboration', label: '协作' },
  { key: 'bot', label: 'Bot' },
];

export function AchievementsPage() {
  useLocale();
  const { summary, fetchSummary, isLoading, recentUnlocks, clearRecentUnlocks } = useAchievementStore();
  const [activeTab, setActiveTab] = useState<FilterTab>('all');

  useEffect(() => {
    fetchSummary();
  }, [fetchSummary]);

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
    <div className="h-full overflow-y-auto">
      <div className="max-w-5xl mx-auto px-8 py-10">
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
            <TierBadge tier={levelTier} icon={TIER_ICONS[levelTier]} size="lg" />
            <div>
              <div className="flex items-center gap-2">
                <h1 className="text-2xl font-bold">{t('achievement.title')}</h1>
                <span className={cn('text-sm font-semibold', levelColors.text)}>
                  Lv.{TIER_ORDER.indexOf(levelTier) + 1} {t(`achievement.tier.${levelTier}`)}
                </span>
              </div>
              <div className="flex items-center gap-3 text-sm text-muted-foreground mt-1">
                <span>{t('achievement.points', { count: String(summary.totalPoints) })}</span>
                <span>·</span>
                <span>{t('achievement.unlockedCount', { count: String(summary.totalUnlocked), total: String(summary.totalAchievements) })}</span>
              </div>
            </div>
          </div>

          {/* Level progress bar */}
          {lp && lp.progress < 1 && (
            <div className="space-y-1.5 max-w-md">
              <div className="flex justify-between text-[11px] text-muted-foreground">
                <span>{t(`achievement.tier.${levelTier}`)}</span>
                <span>{t('achievement.nextLevel', { points: String(Math.round(lp.nextMin - lp.currentMin - lp.progress * (lp.nextMin - lp.currentMin))) })}</span>
              </div>
              <div className="h-2 rounded-full bg-muted overflow-hidden">
                <div
                  className={cn('h-full rounded-full transition-all duration-700', levelColors.text.replace('text-', 'bg-'))}
                  style={{ width: `${lp.progress * 100}%` }}
                />
              </div>
            </div>
          )}

          {/* Streak */}
          {summary.streak.current > 0 && (
            <div className="mt-3 text-[12px] text-muted-foreground">
              {t('achievement.streak', { current: String(summary.streak.current), longest: String(summary.streak.longest) })}
            </div>
          )}
        </div>

        {/* Filter tabs */}
        <div className="flex flex-wrap gap-1.5 mb-6">
          {TABS.map((tab) => (
            <button
              key={tab.key}
              onClick={() => setActiveTab(tab.key)}
              className={cn(
                'px-3 py-1.5 rounded-lg text-[12px] font-medium transition-all duration-150',
                activeTab === tab.key
                  ? 'bg-foreground/10 text-foreground'
                  : 'text-muted-foreground hover:text-foreground hover:bg-muted/50',
              )}
            >
              {tab.label}
            </button>
          ))}
        </div>

        {/* Achievement grid */}
        <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-4 gap-3">
          {filtered.map((a) => (
            <AchievementCard key={a.id} achievement={a} />
          ))}
        </div>

        {filtered.length === 0 && (
          <div className="text-center py-16 text-muted-foreground text-sm">
            {t('achievement.noResults')}
          </div>
        )}
      </div>
    </div>
  );
}
