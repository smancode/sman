import { useEffect, useState } from 'react';
import { X } from 'lucide-react';
import { cn } from '@/lib/utils';
import type { AchievementUnlockEvent } from '@/stores/achievement';
import { TIER_COLORS, TIER_ICONS, type Tier } from '@/types/achievement';
import { TierBadge } from './TierBadge';

interface AchievementToastProps {
  unlock: AchievementUnlockEvent;
  onDismiss: () => void;
}

export function AchievementToast({ unlock, onDismiss }: AchievementToastProps) {
  const [isVisible, setIsVisible] = useState(false);
  const { achievement, totalPoints } = unlock;
  const colors = TIER_COLORS[achievement.tier as Tier];

  useEffect(() => {
    requestAnimationFrame(() => setIsVisible(true));
    const timer = setTimeout(() => {
      setIsVisible(false);
      setTimeout(onDismiss, 300);
    }, 4000);
    return () => clearTimeout(timer);
  }, [onDismiss]);

  return (
    <div
      className={cn(
        'fixed top-6 right-6 z-50 transition-all duration-300',
        isVisible ? 'translate-x-0 opacity-100' : 'translate-x-8 opacity-0',
      )}
    >
      <div className={cn(
        'flex items-center gap-3 px-4 py-3 rounded-xl border shadow-lg',
        'bg-card backdrop-blur-sm',
        colors.border,
      )}>
        <span className="text-2xl">{achievement.icon}</span>
        <TierBadge tier={achievement.tier as Tier} icon={TIER_ICONS[achievement.tier as Tier]} size="sm" />
        <div className="flex flex-col">
          <span className="text-sm font-semibold">{achievement.nameKey}</span>
          <div className="flex items-center gap-2">
            <span className={cn('text-xs font-medium', colors.text)}>+{achievement.points} 分</span>
            <span className="text-[10px] text-muted-foreground">总计 {totalPoints} 分</span>
          </div>
        </div>
        <button
          onClick={() => { setIsVisible(false); setTimeout(onDismiss, 300); }}
          className="ml-2 text-muted-foreground hover:text-foreground transition-colors"
        >
          <X className="h-3.5 w-3.5" />
        </button>
      </div>
    </div>
  );
}
