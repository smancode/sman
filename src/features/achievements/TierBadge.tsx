import { useState, useRef, useEffect } from 'react';
import { Shield, ShieldCheck, Gem, Star, Crown, Sparkles, Flame, Droplet } from 'lucide-react';
import { cn } from '@/lib/utils';
import type { Tier } from '@/types/achievement';
import { TIER_COLORS, TIER_ICONS, TIER_ORDER, TIER_THRESHOLDS } from '@/types/achievement';
import { t } from '@/locales';

const TIER_ICON_MAP: Record<string, React.ComponentType<{ className?: string }>> = {
  shield: Shield,
  'shield-check': ShieldCheck,
  gem: Gem,
  star: Star,
  crown: Crown,
  sparkles: Sparkles,
  flame: Flame,
  droplet: Droplet,
};

interface TierBadgeProps {
  tier: Tier;
  icon: string;
  size?: 'sm' | 'md' | 'lg';
  currentPoints?: number;
  className?: string;
}

export function TierBadge({ tier, icon, size = 'md', currentPoints, className }: TierBadgeProps) {
  const colors = TIER_COLORS[tier];
  const IconComponent = TIER_ICON_MAP[icon] || Shield;
  const [showTooltip, setShowTooltip] = useState(false);
  const [pos, setPos] = useState<{ top: number; left: number } | null>(null);
  const ref = useRef<HTMLDivElement>(null);

  const sizeMap = {
    sm: { container: 'w-6 h-6', icon: 'h-3.5 w-3.5' },
    md: { container: 'w-8 h-8', icon: 'h-4.5 w-4.5' },
    lg: { container: 'w-12 h-12', icon: 'h-6.5 w-6.5' },
  };

  const s = sizeMap[size];
  const showLevelList = size === 'lg' && currentPoints !== undefined;

  useEffect(() => {
    if (!showLevelList) return;
    if (!showTooltip || !ref.current) { setPos(null); return; }

    const rect = ref.current.getBoundingClientRect();
    setPos({ top: rect.bottom + 8, left: rect.left });
  }, [showTooltip, showLevelList]);

  return (
    <>
      <div
        ref={ref}
        className={cn(
          'rounded-full flex items-center justify-center',
          colors.bg, colors.border, 'border dark:border-2',
          s.container,
          showLevelList && 'cursor-pointer',
          size === 'lg' && 'border-2 border-black shadow-[3px_3px_0_0_#1e293b] dark:border-2 dark:shadow-[0_0_14px_rgba(0,255,255,0.2)]',
          className,
        )}
        onMouseEnter={() => showLevelList && setShowTooltip(true)}
        onMouseLeave={() => setShowTooltip(false)}
      >
        <IconComponent className={cn(colors.text, s.icon)} />
      </div>

      {showTooltip && pos && (
        <div
          className="fixed z-50 bg-white dark:bg-black/80 backdrop-blur-xl text-popover-foreground border-2 border-black dark:border-cyan-500/30 rounded-none dark:rounded-2xl shadow-[4px_4px_0_0_#1e293b] dark:shadow-[0_0_20px_rgba(0,255,255,0.1)] px-3.5 py-3 text-[12px] min-w-[180px]"
          style={{ top: pos.top, left: pos.left }}
          onMouseEnter={() => setShowTooltip(true)}
          onMouseLeave={() => setShowTooltip(false)}
        >
          <div className="text-[10px] font-medium text-muted-foreground mb-1.5 uppercase tracking-widest">Level</div>
          {TIER_ORDER.map((tr) => {
            const threshold = TIER_THRESHOLDS[tr];
            const isActive = tr === tier;
            const isUnlocked = currentPoints !== undefined && currentPoints >= threshold;
            const TrIcon = TIER_ICON_MAP[TIER_ICONS[tr]] || Shield;
            const trColors = TIER_COLORS[tr];
            return (
              <div
                key={tr}
                className={cn(
                  'flex items-center justify-between py-0.5 gap-2',
                  isActive && 'font-semibold',
                  !isUnlocked && 'opacity-35',
                )}
              >
                <div className="flex items-center gap-1.5">
                  <TrIcon className={cn('h-3.5 w-3.5 shrink-0', trColors.text)} />
                  <span className={cn(trColors.text, isActive && 'font-semibold')}>
                    {t(`achievement.tier.${tr}`)}
                  </span>
                </div>
                <span className="text-[10px] text-muted-foreground">{threshold}+</span>
              </div>
            );
          })}
        </div>
      )}
    </>
  );
}
