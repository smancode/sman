import { cn } from '@/lib/utils';
import type { AchievementView } from '@/stores/achievement';
import { TIER_COLORS, TIER_SCORES, TIER_ICONS, CATEGORY_COLORS, type Tier, type Category } from '@/types/achievement';
import { TierBadge } from './TierBadge';
import { t } from '@/locales';

interface AchievementCardProps {
  achievement: AchievementView;
}

export function AchievementCard({ achievement }: AchievementCardProps) {
  const { id, icon, nameKey, descKey, tier, hidden, currentValue, threshold, unlockedAt, points, category } = achievement;
  const tierKey = tier as Tier;
  const tierColors = TIER_COLORS[tierKey];
  const catColors = CATEGORY_COLORS[(category || 'hidden') as Category];
  const isUnlocked = !!unlockedAt;
  const isHidden = hidden && !isUnlocked;
  const progress = Math.min(currentValue / threshold, 1);
  const remaining = Math.max(threshold - currentValue, 0);

  return (
    <div
      className={cn(
        'group relative rounded-xl border p-4 transition-all duration-200 min-w-0 overflow-hidden',
        'hover:shadow-md hover:-translate-y-0.5',
        isUnlocked
          ? cn(catColors.bg, catColors.border, 'shadow-sm')
          : 'border-border/60 bg-card',
        isHidden && 'opacity-50',
      )}
    >
      {/* Shine sweep effect for unlocked cards */}
      {isUnlocked && (
        <div
          className="absolute inset-0 pointer-events-none opacity-0 group-hover:animate-[shine-sweep_0.6s_ease-in-out_0.1s_forwards]"
          style={{
            background: 'linear-gradient(105deg, transparent 40%, rgba(255,255,255,0.25) 45%, rgba(255,255,255,0.4) 50%, rgba(255,255,255,0.25) 55%, transparent 60%)',
          }}
        />
      )}
      {/* Icon + Tier badge */}
      <div className="flex items-start justify-between mb-3">
        <div className="flex items-center gap-2.5">
          <span className={cn('text-2xl', isUnlocked ? '' : 'grayscale opacity-50')}>
            {isHidden ? '?' : icon}
          </span>
          <TierBadge tier={tierKey} icon={TIER_ICONS[tierKey]} size="sm" />
        </div>
        <span className={cn(
          'text-[11px] font-medium px-2 py-0.5 rounded-full',
          isUnlocked ? cn(catColors.text, catColors.bg, catColors.border, 'border') : 'text-muted-foreground bg-muted/50',
        )}>
          +{points}
        </span>
      </div>

      {/* Name */}
      <h4 className={cn('text-sm font-semibold leading-tight mb-1', isUnlocked ? 'text-foreground' : 'text-muted-foreground')}>
        {isHidden ? t('achievement.hidden') : t(nameKey)}
      </h4>
      <p className="text-[11px] text-muted-foreground mb-3">
        {isHidden ? '???' : t(descKey)}
      </p>

      {/* Progress bar */}
      {!isUnlocked && !isHidden && (
        <div className="h-1.5 rounded-full bg-muted overflow-hidden">
          <div
            className={cn('h-full rounded-full transition-all duration-500', catColors.bar)}
            style={{ width: `${progress * 100}%` }}
          />
        </div>
      )}

      {/* Unlocked indicator */}
      {isUnlocked && (
        <div className={cn('text-[10px] font-medium', catColors.text)}>
          {new Date(unlockedAt).toLocaleDateString()}
        </div>
      )}
    </div>
  );
}
