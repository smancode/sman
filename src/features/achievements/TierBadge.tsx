import { Shield, ShieldCheck, Gem, Star, Crown, Sparkles, Flame, Infinity } from 'lucide-react';
import { cn } from '@/lib/utils';
import type { Tier } from '@/types/achievement';
import { TIER_COLORS, TIER_ICONS } from '@/types/achievement';

const TIER_ICON_MAP: Record<string, React.ComponentType<{ className?: string }>> = {
  shield: Shield,
  'shield-check': ShieldCheck,
  gem: Gem,
  star: Star,
  crown: Crown,
  sparkles: Sparkles,
  flame: Flame,
  droplet: Infinity,
  infinity: Infinity,
};

interface TierBadgeProps {
  tier: Tier;
  icon: string;
  size?: 'sm' | 'md' | 'lg';
  className?: string;
}

export function TierBadge({ tier, icon, size = 'md', className }: TierBadgeProps) {
  const colors = TIER_COLORS[tier];
  const IconComponent = TIER_ICON_MAP[icon] || Shield;

  const sizeMap = {
    sm: { container: 'w-6 h-6', icon: 'h-3.5 w-3.5' },
    md: { container: 'w-8 h-8', icon: 'h-4.5 w-4.5' },
    lg: { container: 'w-12 h-12', icon: 'h-6.5 w-6.5' },
  };

  const s = sizeMap[size];

  return (
    <div
      className={cn(
        'rounded-full flex items-center justify-center',
        colors.bg, colors.border, 'border dark:border-2',
        s.container,
        size === 'lg' && 'border-2 border-black shadow-[3px_3px_0_0_#1e293b] dark:border-2 dark:shadow-[0_0_14px_rgba(0,255,255,0.2)]',
        className,
      )}
    >
      <IconComponent className={cn(colors.text, s.icon)} />
    </div>
  );
}
