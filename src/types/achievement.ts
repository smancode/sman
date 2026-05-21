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
  eternal: 'infinity',
};

export const TIER_ORDER: Tier[] = ['bronze', 'silver', 'gold', 'platinum', 'diamond', 'star', 'king', 'legend', 'epic', 'eternal'];

export const TIER_COLORS: Record<Tier, { text: string; bg: string; border: string; glow: string; bar: string }> = {
  bronze:   { text: 'text-orange-800 dark:text-orange-400',   bg: 'bg-orange-100 dark:bg-orange-950/20',   border: 'border-orange-300 dark:border-orange-800/30',   glow: 'shadow-orange-200/50 dark:shadow-orange-800/30',   bar: 'bg-orange-700' },
  silver:   { text: 'text-zinc-400 dark:text-zinc-200',   bg: 'bg-zinc-50 dark:bg-zinc-800/20',   border: 'border-zinc-200 dark:border-zinc-500/30',   glow: 'shadow-zinc-200/50 dark:shadow-zinc-500/30',   bar: 'bg-zinc-300' },
  gold:     { text: 'text-yellow-600 dark:text-yellow-400', bg: 'bg-yellow-50 dark:bg-yellow-950/20', border: 'border-yellow-200 dark:border-yellow-800/30', glow: 'shadow-yellow-200/50 dark:shadow-yellow-800/30', bar: 'bg-yellow-500' },
  platinum: { text: 'text-amber-700 dark:text-amber-300',     bg: 'bg-amber-100 dark:bg-amber-900/20',     border: 'border-amber-300 dark:border-amber-700/30',     glow: 'shadow-amber-200/50 dark:shadow-amber-700/30',     bar: 'bg-amber-400' },
  diamond:  { text: 'text-teal-600 dark:text-teal-400',     bg: 'bg-teal-50 dark:bg-teal-950/20',     border: 'border-teal-200 dark:border-teal-800/30',     glow: 'shadow-teal-200/50 dark:shadow-teal-800/30',     bar: 'bg-teal-500' },
  star:     { text: 'text-pink-600 dark:text-pink-400',     bg: 'bg-pink-50 dark:bg-pink-950/20',     border: 'border-pink-200 dark:border-pink-800/30',     glow: 'shadow-pink-200/50 dark:shadow-pink-800/30',     bar: 'bg-pink-500' },
  king:     { text: 'text-red-600 dark:text-red-400',       bg: 'bg-red-50 dark:bg-red-950/20',       border: 'border-red-200 dark:border-red-800/30',       glow: 'shadow-red-200/50 dark:shadow-red-800/30',       bar: 'bg-red-500' },
  legend:   { text: 'text-violet-600 dark:text-violet-400', bg: 'bg-violet-50 dark:bg-violet-950/20', border: 'border-violet-200 dark:border-violet-800/30', glow: 'shadow-violet-200/50 dark:shadow-violet-800/30', bar: 'bg-violet-500' },
  epic:     { text: 'text-orange-600 dark:text-orange-400', bg: 'bg-orange-50 dark:bg-orange-950/20', border: 'border-orange-200 dark:border-orange-800/30', glow: 'shadow-orange-200/50 dark:shadow-orange-800/30', bar: 'bg-orange-500' },
  eternal:  { text: 'text-emerald-600 dark:text-emerald-400', bg: 'bg-emerald-50 dark:bg-emerald-950/20', border: 'border-emerald-200 dark:border-emerald-800/30', glow: 'shadow-emerald-200/50 dark:shadow-emerald-800/30', bar: 'bg-emerald-500' },
};

export const TIER_THRESHOLDS: Record<Tier, number> = {
  bronze: 0, silver: 100, gold: 300, platinum: 600, diamond: 1200,
  star: 2500, king: 4000, legend: 6000, epic: 9000, eternal: 15000,
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
  conversation:  { text: 'text-blue-700 dark:text-cyan-300',     bg: 'bg-blue-200 dark:bg-cyan-950/30',     border: 'border-black dark:border-cyan-400/50',     bar: 'bg-blue-500',     shadow: 'shadow-[3px_3px_0_0_#1e293b] dark:shadow-[0_0_16px_rgba(0,255,255,0.15)]' },
  advanced:      { text: 'text-violet-700 dark:text-fuchsia-300', bg: 'bg-violet-200 dark:bg-fuchsia-950/30', border: 'border-black dark:border-fuchsia-400/50', bar: 'bg-violet-500',   shadow: 'shadow-[3px_3px_0_0_#1e293b] dark:shadow-[0_0_16px_rgba(255,0,255,0.15)]' },
  exploration:   { text: 'text-emerald-700 dark:text-lime-300', bg: 'bg-emerald-200 dark:bg-lime-950/30', border: 'border-black dark:border-lime-400/50', bar: 'bg-emerald-500', shadow: 'shadow-[3px_3px_0_0_#1e293b] dark:shadow-[0_0_16px_rgba(132,255,0,0.15)]' },
  collaboration: { text: 'text-amber-700 dark:text-amber-300',   bg: 'bg-amber-200 dark:bg-amber-950/30',   border: 'border-black dark:border-amber-400/50',   bar: 'bg-amber-500',    shadow: 'shadow-[3px_3px_0_0_#1e293b] dark:shadow-[0_0_16px_rgba(255,200,0,0.15)]' },
  bot:           { text: 'text-pink-700 dark:text-rose-300',     bg: 'bg-pink-200 dark:bg-rose-950/30',     border: 'border-black dark:border-rose-400/50',     bar: 'bg-pink-500',    shadow: 'shadow-[3px_3px_0_0_#1e293b] dark:shadow-[0_0_16px_rgba(255,50,100,0.15)]' },
  hidden:        { text: 'text-slate-700 dark:text-slate-300',   bg: 'bg-slate-200 dark:bg-slate-800/40',   border: 'border-black dark:border-slate-500/50',   bar: 'bg-slate-500',    shadow: 'shadow-[3px_3px_0_0_#1e293b] dark:shadow-[0_0_16px_rgba(150,150,180,0.12)]' },
};
