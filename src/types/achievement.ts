export type Tier = 'bronze' | 'silver' | 'gold' | 'platinum' | 'diamond' | 'star' | 'king' | 'legend' | 'epic' | 'eternal';
export type Category = 'conversation' | 'advanced' | 'exploration' | 'collaboration' | 'bot' | 'hidden';

export const TIER_SCORES: Record<Tier, number> = {
  bronze: 1, silver: 3, gold: 5, platinum: 8, diamond: 12,
  star: 16, king: 20, legend: 25, epic: 30, eternal: 50,
};

export const TIER_ICONS: Record<Tier, string> = {
  bronze: 'shield',
  silver: 'shield',
  gold: 'shield',
  platinum: 'shield-check',
  diamond: 'gem',
  star: 'star',
  king: 'crown',
  legend: 'sparkles',
  epic: 'flame',
  eternal: 'droplet',
};

export const TIER_ORDER: Tier[] = ['bronze', 'silver', 'gold', 'platinum', 'diamond', 'star', 'king', 'legend', 'epic', 'eternal'];

export const TIER_COLORS: Record<Tier, { text: string; bg: string; border: string; glow: string; bar: string }> = {
  bronze:   { text: 'text-amber-600 dark:text-amber-400',   bg: 'bg-amber-50 dark:bg-amber-950/20',   border: 'border-amber-200 dark:border-amber-800/30',   glow: 'shadow-amber-200/50 dark:shadow-amber-800/30',   bar: 'bg-amber-500' },
  silver:   { text: 'text-slate-500 dark:text-slate-300',   bg: 'bg-slate-50 dark:bg-slate-800/20',   border: 'border-slate-200 dark:border-slate-700/30',   glow: 'shadow-slate-200/50 dark:shadow-slate-700/30',   bar: 'bg-slate-400' },
  gold:     { text: 'text-yellow-600 dark:text-yellow-400', bg: 'bg-yellow-50 dark:bg-yellow-950/20', border: 'border-yellow-200 dark:border-yellow-800/30', glow: 'shadow-yellow-200/50 dark:shadow-yellow-800/30', bar: 'bg-yellow-500' },
  platinum: { text: 'text-cyan-600 dark:text-cyan-400',     bg: 'bg-cyan-50 dark:bg-cyan-950/20',     border: 'border-cyan-200 dark:border-cyan-800/30',     glow: 'shadow-cyan-200/50 dark:shadow-cyan-800/30',     bar: 'bg-cyan-500' },
  diamond:  { text: 'text-teal-600 dark:text-teal-400',     bg: 'bg-teal-50 dark:bg-teal-950/20',     border: 'border-teal-200 dark:border-teal-800/30',     glow: 'shadow-teal-200/50 dark:shadow-teal-800/30',     bar: 'bg-teal-500' },
  star:     { text: 'text-pink-600 dark:text-pink-400',     bg: 'bg-pink-50 dark:bg-pink-950/20',     border: 'border-pink-200 dark:border-pink-800/30',     glow: 'shadow-pink-200/50 dark:shadow-pink-800/30',     bar: 'bg-pink-500' },
  king:     { text: 'text-red-600 dark:text-red-400',       bg: 'bg-red-50 dark:bg-red-950/20',       border: 'border-red-200 dark:border-red-800/30',       glow: 'shadow-red-200/50 dark:shadow-red-800/30',       bar: 'bg-red-500' },
  legend:   { text: 'text-violet-600 dark:text-violet-400', bg: 'bg-violet-50 dark:bg-violet-950/20', border: 'border-violet-200 dark:border-violet-800/30', glow: 'shadow-violet-200/50 dark:shadow-violet-800/30', bar: 'bg-violet-500' },
  epic:     { text: 'text-orange-600 dark:text-orange-400', bg: 'bg-orange-50 dark:bg-orange-950/20', border: 'border-orange-200 dark:border-orange-800/30', glow: 'shadow-orange-200/50 dark:shadow-orange-800/30', bar: 'bg-orange-500' },
  eternal:  { text: 'text-emerald-600 dark:text-emerald-400', bg: 'bg-emerald-50 dark:bg-emerald-950/20', border: 'border-emerald-200 dark:border-emerald-800/30', glow: 'shadow-emerald-200/50 dark:shadow-emerald-800/30', bar: 'bg-emerald-500' },
};

export const TIER_THRESHOLDS: Record<Tier, number> = {
  bronze: 0, silver: 100, gold: 300, platinum: 600, diamond: 1200,
  star: 2000, king: 3200, legend: 4800, epic: 7000, eternal: 10000,
};

export const CATEGORY_LABELS: Record<Category, string> = {
  conversation: '对话',
  advanced: '进阶',
  exploration: '探索',
  collaboration: '协作',
  bot: 'Bot',
  hidden: '隐藏',
};

export const CATEGORY_COLORS: Record<Category, { text: string; bg: string; border: string; bar: string; shadow: string }> = {
  conversation:  { text: 'text-blue-600 dark:text-blue-400',     bg: 'bg-blue-50/80 dark:bg-blue-950/20',     border: 'border-blue-200/60 dark:border-blue-600/30',     bar: 'bg-blue-500',     shadow: 'shadow-sm shadow-blue-200/40 dark:shadow-none' },
  advanced:      { text: 'text-violet-600 dark:text-violet-400', bg: 'bg-violet-50/80 dark:bg-violet-950/20', border: 'border-violet-200/60 dark:border-violet-600/30', bar: 'bg-violet-500',   shadow: 'shadow-sm shadow-violet-200/40 dark:shadow-none' },
  exploration:   { text: 'text-emerald-600 dark:text-emerald-400', bg: 'bg-emerald-50/80 dark:bg-emerald-950/20', border: 'border-emerald-200/60 dark:border-emerald-600/30', bar: 'bg-emerald-500', shadow: 'shadow-sm shadow-emerald-200/40 dark:shadow-none' },
  collaboration: { text: 'text-amber-600 dark:text-amber-400',   bg: 'bg-amber-50/80 dark:bg-amber-950/20',   border: 'border-amber-200/60 dark:border-amber-600/30',   bar: 'bg-amber-500',    shadow: 'shadow-sm shadow-amber-200/40 dark:shadow-none' },
  bot:           { text: 'text-pink-600 dark:text-pink-400',     bg: 'bg-pink-50/80 dark:bg-pink-950/20',     border: 'border-pink-200/60 dark:border-pink-600/30',     bar: 'bg-pink-500',    shadow: 'shadow-sm shadow-pink-200/40 dark:shadow-none' },
  hidden:        { text: 'text-slate-600 dark:text-slate-400',   bg: 'bg-slate-50/80 dark:bg-slate-800/20',   border: 'border-slate-200/60 dark:border-slate-600/30',   bar: 'bg-slate-500',    shadow: 'shadow-sm shadow-slate-200/40 dark:shadow-none' },
};
