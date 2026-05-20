import { cn } from '@/lib/utils';
import type { AchievementView } from '@/stores/achievement';
import { TIER_COLORS, TIER_SCORES, TIER_ICONS, CATEGORY_LABELS, type Tier, type Category } from '@/types/achievement';
import { TierBadge } from './TierBadge';
import { t } from '@/locales';

interface AchievementCardProps {
  achievement: AchievementView;
}

export function AchievementCard({ achievement }: AchievementCardProps) {
  const { id, icon, nameKey, descKey, tier, hidden, currentValue, threshold, unlockedAt, points, category } = achievement;
  const tierKey = tier as Tier;
  const colors = TIER_COLORS[tierKey];
  const isUnlocked = !!unlockedAt;
  const isHidden = hidden && !isUnlocked;
  const progress = Math.min(currentValue / threshold, 1);
  const remaining = Math.max(threshold - currentValue, 0);

  return (
    <div
      className={cn(
        'group relative rounded-xl border p-4 transition-all duration-200 min-w-0',
        'hover:shadow-md hover:-translate-y-0.5',
        isUnlocked
          ? cn(colors.bg, colors.border, 'shadow-sm')
          : 'border-border/60 bg-card',
        isHidden && 'opacity-50',
      )}
    >
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
          isUnlocked ? cn(colors.text, colors.bg, colors.border, 'border') : 'text-muted-foreground bg-muted/50',
        )}>
          +{points}
        </span>
      </div>

      {/* Name + Category */}
      <h4 className={cn('text-sm font-semibold leading-tight mb-1', isUnlocked ? 'text-foreground' : 'text-muted-foreground')}>
        {isHidden ? t('achievement.hidden') : t(nameKey)}
      </h4>
      <p className="text-[11px] text-muted-foreground mb-3">
        {isHidden ? '???' : t(descKey)}
      </p>

      {/* Progress bar */}
      {!isUnlocked && !isHidden && (
        <div className="space-y-1.5">
          <div className="h-1.5 rounded-full bg-muted overflow-hidden">
            <div
              className={cn('h-full rounded-full transition-all duration-500', colors.text.replace('text-', 'bg-'))}
              style={{ width: `${progress * 100}%` }}
            />
          </div>
          <p className="text-[10px] text-muted-foreground">
            {remaining > 0 ? t('achievement.remaining', { count: String(remaining) }) : t('achievement.ready')}
          </p>
        </div>
      )}

      {/* Unlocked indicator */}
      {isUnlocked && (
        <div className={cn('text-[10px] font-medium', colors.text)}>
          {new Date(unlockedAt).toLocaleDateString()}
        </div>
      )}

      {/* Category tag */}
      <div className="absolute top-3 right-3 opacity-0 group-hover:opacity-100 transition-opacity">
        <span className="text-[10px] text-muted-foreground">
          {CATEGORY_LABELS[category as Category] || category}
        </span>
      </div>
    </div>
  );
}
