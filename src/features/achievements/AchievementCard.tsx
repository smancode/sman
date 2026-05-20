import { useState } from 'react';
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
  const [shining, setShining] = useState(false);

  return (
    <div
      className={cn(
        'group relative border p-4 transition-all duration-300 min-w-0 overflow-hidden',
        'rounded-2xl',
        isUnlocked
          ? cn(catColors.bg, catColors.border, 'border', catColors.shadow, 'backdrop-blur-sm hover:-translate-y-1 hover:shadow-lg')
          : 'border-border/40 bg-card/60 backdrop-blur-sm dark:bg-card/50 dark:opacity-85 hover:shadow-md hover:-translate-y-0.5',
        isHidden && 'opacity-50',
      )}
      onMouseEnter={() => isUnlocked && setShining(true)}
      onMouseLeave={() => setShining(false)}
    >
      {/* Shine sweep effect for unlocked cards */}
      {isUnlocked && (
        <div
          className="absolute inset-0 pointer-events-none z-10 achievement-shine"
          style={{
            transform: shining ? 'translateX(200%)' : 'translateX(-100%)',
            opacity: shining ? 1 : 0,
            transition: shining
              ? 'transform 0.7s cubic-bezier(0.25, 0.46, 0.45, 0.94), opacity 0.1s'
              : 'opacity 0.3s 0.7s',
          }}
        />
      )}

      {/* Glow border effect on hover */}
      {isUnlocked && (
        <div
          className={cn(
            'absolute inset-0 rounded-2xl pointer-events-none z-0 achievement-glow transition-opacity duration-300',
            shining ? 'opacity-100' : 'opacity-0',
          )}
        />
      )}

      {/* Content */}
      <div className="relative z-20">
        {/* Icon + Tier badge */}
        <div className="flex items-start justify-between mb-3">
          <div className="flex items-center gap-2.5">
            <span className={cn('text-2xl', isUnlocked ? '' : 'grayscale opacity-50')}>
              {isHidden ? '?' : icon}
            </span>
            <TierBadge tier={tierKey} icon={TIER_ICONS[tierKey]} size="sm" />
          </div>
          <span className={cn(
            'text-[11px] px-2.5 py-0.5 rounded-full font-medium',
            isUnlocked
              ? 'bg-foreground/8 text-foreground/70 dark:bg-muted/50 dark:text-muted-foreground'
              : 'text-muted-foreground bg-muted/40',
          )}>
            +{points}
          </span>
        </div>

        {/* Name */}
        <h4 className={cn('text-sm font-medium leading-tight mb-1', isUnlocked ? 'text-foreground' : 'text-muted-foreground')}>
          {isHidden ? t('achievement.hidden') : t(nameKey)}
        </h4>
        <p className="text-[11px] text-muted-foreground/70 mb-3">
          {isHidden ? '???' : t(descKey)}
        </p>

        {/* Progress bar */}
        {!isUnlocked && !isHidden && (
          <div className="h-1.5 rounded-full bg-foreground/5 overflow-hidden">
            <div
              className={cn('h-full rounded-full transition-all duration-500', catColors.bar, 'opacity-80')}
              style={{ width: `${progress * 100}%` }}
            />
          </div>
        )}

        {/* Unlocked indicator */}
        {isUnlocked && (
          <div className={cn('text-[10px] font-medium opacity-60', catColors.text)}>
            {new Date(unlockedAt).toLocaleDateString()}
          </div>
        )}
      </div>
    </div>
  );
}
