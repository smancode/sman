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
    setPos({ top: rect.bottom + 6, left: rect.left });
  }, [showTooltip, showLevelList]);

  return (
    <>
      <div
        ref={ref}
        className={cn(
          'rounded-full flex items-center justify-center',
          colors.bg, colors.border, 'border',
          s.container,
          showLevelList && 'cursor-pointer',
          className,
        )}
        onMouseEnter={() => showLevelList && setShowTooltip(true)}
        onMouseLeave={() => setShowTooltip(false)}
      >
        <IconComponent className={cn(colors.text, s.icon)} />
      </div>

      {showTooltip && pos && (
        <div
          className="fixed z-50 bg-popover text-popover-foreground border rounded-lg shadow-lg px-3 py-2 text-[12px] min-w-[160px]"
          style={{ top: pos.top, left: pos.left }}
          onMouseEnter={() => setShowTooltip(true)}
          onMouseLeave={() => setShowTooltip(false)}
        >
          {TIER_ORDER.map((tr) => {
            const threshold = TIER_THRESHOLDS[tr];
            const isActive = tr === tier;
            const isUnlocked = currentPoints !== undefined && currentPoints >= threshold;
            const TrIcon = TIER_ICON_MAP[TIER_ICONS[tr]] || Shield;
            return (
              <div
                key={tr}
                className={cn(
                  'flex items-center justify-between py-0.5 gap-2',
                  isActive && 'font-bold',
                  !isUnlocked && 'opacity-40',
                )}
              >
                <div className="flex items-center gap-1.5">
                  <TrIcon className={cn('h-3.5 w-3.5 shrink-0', TIER_COLORS[tr].text)} />
                  <span className={cn(TIER_COLORS[tr].text, isActive && 'font-bold')}>
                    {t(`achievement.tier.${tr}`)}
                  </span>
                </div>
                <span className="text-muted-foreground">{threshold}+</span>
              </div>
            );
          })}
        </div>
      )}
    </>
  );
}
