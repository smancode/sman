import { useEffect, useState, useMemo } from 'react';
import { cn } from '@/lib/utils';
import { useAchievementStore } from '@/stores/achievement';
import { TIER_COLORS, TIER_ORDER, TIER_ICONS, CATEGORY_LABELS, type Category, type Tier } from '@/types/achievement';
import { TIER_THRESHOLDS } from '@/types/achievement';
import { TierBadge } from './TierBadge';
import { AchievementCard } from './AchievementCard';
import { AchievementToast } from './AchievementToast';
import { LeaderboardTab, LeaderboardDimensions } from './LeaderboardTab';
import { t, useLocale } from '@/locales';

type FilterTab = 'all' | Category | 'unlocked' | 'locked' | 'leaderboard';

// Metric display config for score breakdown
const SCORE_METRICS: { key: string; labelKey: string; unit: string; weight: number }[] = [
  { key: 'total_sessions', labelKey: 'achievement.scoreDetail.sessions', unit: '个', weight: 3 },
  { key: 'total_messages', labelKey: 'achievement.scoreDetail.messages', unit: '条', weight: 0.5 },
  { key: 'total_tokens', labelKey: 'achievement.scoreDetail.tokens', unit: '', weight: 0.000005 },
  { key: 'total_cron_runs', labelKey: 'achievement.scoreDetail.cron', unit: '次', weight: 2 },
  { key: 'total_smartpath_runs', labelKey: 'achievement.scoreDetail.path', unit: '次', weight: 5 },
  { key: 'total_skills_used', labelKey: 'achievement.scoreDetail.skill', unit: '次', weight: 2 },
  { key: 'total_code_views', labelKey: 'achievement.scoreDetail.codeview', unit: '次', weight: 0.3 },
  { key: 'total_git_ops', labelKey: 'achievement.scoreDetail.git', unit: '次', weight: 0.5 },
  { key: 'bot_sessions_total', labelKey: 'achievement.scoreDetail.botSessions', unit: '个', weight: 2 },
  { key: 'bot_messages_total', labelKey: 'achievement.scoreDetail.botMessages', unit: '条', weight: 1 },
  { key: 'bot_count_total', labelKey: 'achievement.scoreDetail.botCount', unit: '个', weight: 5 },
  { key: 'current_streak', labelKey: 'achievement.scoreDetail.streak', unit: '天', weight: 2 },
];

const TABS: { key: FilterTab; labelKey: string }[] = [
  { key: 'all', labelKey: 'achievement.tabAll' },
  { key: 'unlocked', labelKey: 'achievement.tabUnlocked' },
  { key: 'locked', labelKey: 'achievement.tabLocked' },
  { key: 'conversation', labelKey: 'achievement.catConversation' },
  { key: 'bot', labelKey: 'achievement.catBot' },
  { key: 'advanced', labelKey: 'achievement.catAdvanced' },
  { key: 'exploration', labelKey: 'achievement.catExploration' },
  { key: 'collaboration', labelKey: 'achievement.catCollaboration' },
];

export function AchievementsPage() {
  useLocale();
  const { summary, fetchSummary, fetchLeaderboard, isLoading, recentUnlocks, clearRecentUnlocks } = useAchievementStore();
  const [activeTab, setActiveTab] = useState<FilterTab>('all');
  const [showScoreDetail, setShowScoreDetail] = useState(false);

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
              <div className="flex items-center gap-2.5">
                <h1 className="text-2xl font-bold dark:font-semibold tracking-tight">{t('achievement.title')}</h1>
                <span className={cn(
                  'text-[12px] font-bold dark:font-medium px-2 py-0.5',
                  'border-2 border-black dark:border-white/20 dark:rounded-full',
                  levelColors.bg, levelColors.text,
                )}>
                  Lv.{TIER_ORDER.indexOf(levelTier) + 1} {t(`achievement.tier.${levelTier}`)}
                </span>
              </div>
              <div className="flex items-center gap-3 text-sm text-muted-foreground mt-1">
                <span
                  className="relative cursor-default font-bold dark:font-medium"
                  onMouseEnter={() => setShowScoreDetail(true)}
                  onMouseLeave={() => setShowScoreDetail(false)}
                >
                  {t('achievement.points', { count: String(summary.totalPoints) })}
                  {showScoreDetail && (
                    <div className="absolute top-full left-0 mt-2 z-50 bg-white dark:bg-black/80 backdrop-blur-xl border-2 border-black dark:border-cyan-500/30 rounded-none dark:rounded-2xl shadow-[4px_4px_0_0_#1e293b] dark:shadow-[0_0_20px_rgba(0,255,255,0.1)] px-4 py-3 text-[12px] min-w-[240px]">
                      <div className="text-[10px] font-bold text-foreground dark:text-muted-foreground mb-2 uppercase tracking-widest">{t('achievement.scoreDetail.title')}</div>
                      {SCORE_METRICS.map((m) => {
                        const raw = parseInt(summary.stats[m.key] || '0', 10);
                        if (raw === 0) return null;
                        const score = Math.round(raw * m.weight * 100) / 100;
                        const display = m.key === 'total_tokens'
                          ? `${(raw / 10000).toFixed(1)}万`
                          : raw;
                        return (
                          <div key={m.key} className="flex items-center justify-between py-0.5 gap-4">
                            <span className="text-muted-foreground">{t(m.labelKey)}</span>
                            <span className="text-foreground font-medium tabular-nums">{display}{m.unit} → <span className="text-foreground/70">{score}分</span></span>
                          </div>
                        );
                      })}
                    </div>
                  )}
                </span>
                <span className="text-muted-foreground/30">·</span>
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
              <div className="h-3 dark:h-1.5 border-2 border-black dark:border-0 dark:rounded-full bg-foreground/5 overflow-hidden">
                <div
                  className={cn('h-full transition-all duration-700', levelColors.bar, 'dark:rounded-full dark:opacity-80')}
                  style={{ width: `${lp.progress * 100}%` }}
                />
              </div>
            </div>
          )}

          {/* Streak */}
          {summary.streak.current > 0 && (
            <div className="mt-3 text-[12px] inline-block px-2 py-0.5 border-2 border-black dark:border-white/20 dark:rounded-full font-bold dark:font-medium bg-orange-200 dark:bg-transparent dark:text-muted-foreground">
              {t('achievement.streak', { current: String(summary.streak.current), longest: String(summary.streak.longest) })}
            </div>
          )}
        </div>

        {/* Primary tabs: 全部成就 / 排行榜 */}
        <div className="mb-2">
          <div className="flex gap-1.5">
            <button
              onClick={() => setActiveTab('all')}
              className={cn(
                'px-3.5 py-1.5 text-[12px] font-bold dark:font-medium transition-all duration-200',
                'rounded-none dark:rounded-lg',
                activeTab !== 'leaderboard'
                  ? 'bg-foreground text-background border-2 border-black shadow-[2px_2px_0_0_#1e293b] dark:bg-cyan-400 dark:text-black dark:border-0 dark:shadow-[0_0_12px_rgba(0,255,255,0.3)]'
                  : 'border-2 border-transparent text-muted-foreground bg-white dark:bg-transparent dark:border-0 hover:border-black dark:hover:bg-white/5 dark:hover:text-cyan-300 hover:shadow-[2px_2px_0_0_#1e293b] dark:hover:shadow-none',
              )}
            >
              {t('achievement.tabAchievements')}
            </button>
            <button
              onClick={() => handleTabChange('leaderboard')}
              className={cn(
                'px-3.5 py-1.5 text-[12px] font-bold dark:font-medium transition-all duration-200',
                'rounded-none dark:rounded-lg',
                activeTab === 'leaderboard'
                  ? 'bg-yellow-300 text-foreground border-2 border-black shadow-[2px_2px_0_0_#1e293b] dark:bg-fuchsia-400 dark:text-black dark:border-0 dark:shadow-[0_0_12px_rgba(255,0,255,0.3)]'
                  : 'border-2 border-transparent text-muted-foreground bg-white dark:bg-transparent dark:border-0 hover:border-black dark:hover:bg-yellow-100 dark:hover:bg-white/5 dark:hover:text-fuchsia-300 hover:shadow-[2px_2px_0_0_#1e293b] dark:hover:shadow-none',
              )}
            >
              {t('achievement.leaderboard')}
            </button>
          </div>
        </div>

        {/* Secondary tabs: 成就筛选 或 排行榜维度 */}
        <div className="mb-6">
          {activeTab === 'leaderboard' ? (
            <LeaderboardDimensions />
          ) : (
            <div className="flex flex-wrap gap-1.5">
              {TABS.map((tab) => (
                <button
                  key={tab.key}
                  onClick={() => handleTabChange(tab.key)}
                  className={cn(
                    'px-3.5 py-1.5 text-[12px] font-bold dark:font-medium transition-all duration-200',
                    'rounded-none dark:rounded-lg',
                    activeTab === tab.key
                      ? 'bg-muted text-foreground border-2 border-black shadow-[2px_2px_0_0_#1e293b] dark:bg-white/10 dark:text-cyan-300 dark:border-cyan-500/30 dark:shadow-none'
                      : 'border-2 border-transparent text-muted-foreground bg-white dark:bg-transparent dark:border-0 hover:border-black dark:hover:bg-white/5 dark:hover:text-cyan-300 hover:shadow-[2px_2px_0_0_#1e293b] dark:hover:shadow-none',
                  )}
                >
                  {t(tab.labelKey)}
                </button>
              ))}
            </div>
          )}
        </div>

        {/* Content */}
        <div className="min-h-[400px]">
          {activeTab === 'leaderboard' ? (
            <LeaderboardTab />
          ) : filtered.length > 0 ? (
            <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-4 gap-3">
              {filtered.map((a) => (
                <AchievementCard key={a.id} achievement={a} />
              ))}
            </div>
          ) : (
            <div className="text-center py-16 text-muted-foreground/60 text-sm">
              {t('achievement.noResults')}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
