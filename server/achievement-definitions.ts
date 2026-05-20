import type { Database } from 'better-sqlite3';

export type Tier = 'bronze' | 'silver' | 'gold' | 'platinum' | 'diamond' | 'star' | 'king' | 'legend' | 'epic' | 'eternal';
export type Category = 'conversation' | 'advanced' | 'exploration' | 'collaboration' | 'bot' | 'hidden';

export interface AchievementDef {
  id: string;
  category: Category;
  tier: Tier;
  nameKey: string;
  descKey: string;
  icon: string;
  hidden: boolean;
  condition: {
    metric: string;
    threshold: number;
  };
}

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

export const TIER_NAMES: Record<Tier, string> = {
  bronze: '青铜', silver: '白银', gold: '黄金', platinum: '铂金',
  diamond: '钻石', star: '星耀', king: '王者', legend: '传说',
  epic: '史诗', eternal: '永恒',
};

// Level thresholds: tier name → minimum points
export const LEVEL_THRESHOLDS: { tier: Tier; minPoints: number }[] = [
  { tier: 'bronze', minPoints: 0 },
  { tier: 'silver', minPoints: 20 },
  { tier: 'gold', minPoints: 50 },
  { tier: 'platinum', minPoints: 100 },
  { tier: 'diamond', minPoints: 200 },
  { tier: 'star', minPoints: 380 },
  { tier: 'king', minPoints: 560 },
  { tier: 'legend', minPoints: 760 },
  { tier: 'epic', minPoints: 960 },
  { tier: 'eternal', minPoints: 1200 },
];

export function calculateLevel(points: number): Tier {
  let result: Tier = 'bronze';
  for (const { tier, minPoints } of LEVEL_THRESHOLDS) {
    if (points >= minPoints) {
      result = tier;
    }
  }
  return result;
}

export function calculateLevelProgress(points: number): { current: Tier; next: Tier | null; currentMin: number; nextMin: number; progress: number } {
  const current = calculateLevel(points);
  const idx = LEVEL_THRESHOLDS.findIndex(l => l.tier === current);
  const next = idx < LEVEL_THRESHOLDS.length - 1 ? LEVEL_THRESHOLDS[idx + 1].tier : null;
  const currentMin = LEVEL_THRESHOLDS[idx].minPoints;
  const nextMin = next ? LEVEL_THRESHOLDS[idx + 1].minPoints : currentMin;
  const progress = next ? (points - currentMin) / (nextMin - currentMin) : 1;
  return { current, next, currentMin, nextMin, progress: Math.min(1, progress) };
}

// Achievement definitions
const defs: AchievementDef[] = [
  // ── Conversation: Sessions ──
  { id: 'session_1', category: 'conversation', tier: 'bronze', nameKey: 'achievement.session_1', descKey: 'achievement.session_1.desc', icon: '💬', hidden: false, condition: { metric: 'total_sessions', threshold: 1 } },
  { id: 'session_10', category: 'conversation', tier: 'silver', nameKey: 'achievement.session_10', descKey: 'achievement.session_10.desc', icon: '💬', hidden: false, condition: { metric: 'total_sessions', threshold: 10 } },
  { id: 'session_50', category: 'conversation', tier: 'gold', nameKey: 'achievement.session_50', descKey: 'achievement.session_50.desc', icon: '💬', hidden: false, condition: { metric: 'total_sessions', threshold: 50 } },
  { id: 'session_200', category: 'conversation', tier: 'platinum', nameKey: 'achievement.session_200', descKey: 'achievement.session_200.desc', icon: '💬', hidden: false, condition: { metric: 'total_sessions', threshold: 200 } },
  { id: 'session_500', category: 'conversation', tier: 'diamond', nameKey: 'achievement.session_500', descKey: 'achievement.session_500.desc', icon: '💬', hidden: false, condition: { metric: 'total_sessions', threshold: 500 } },
  { id: 'session_1k', category: 'conversation', tier: 'star', nameKey: 'achievement.session_1k', descKey: 'achievement.session_1k.desc', icon: '💬', hidden: false, condition: { metric: 'total_sessions', threshold: 1000 } },
  { id: 'session_2k', category: 'conversation', tier: 'king', nameKey: 'achievement.session_2k', descKey: 'achievement.session_2k.desc', icon: '💬', hidden: false, condition: { metric: 'total_sessions', threshold: 2000 } },
  { id: 'session_5k', category: 'conversation', tier: 'legend', nameKey: 'achievement.session_5k', descKey: 'achievement.session_5k.desc', icon: '💬', hidden: false, condition: { metric: 'total_sessions', threshold: 5000 } },
  { id: 'session_10k', category: 'conversation', tier: 'epic', nameKey: 'achievement.session_10k', descKey: 'achievement.session_10k.desc', icon: '💬', hidden: false, condition: { metric: 'total_sessions', threshold: 10000 } },
  { id: 'session_99999', category: 'conversation', tier: 'eternal', nameKey: 'achievement.session_99999', descKey: 'achievement.session_99999.desc', icon: '💬', hidden: false, condition: { metric: 'total_sessions', threshold: 99999 } },

  // ── Conversation: Messages ──
  { id: 'msg_10', category: 'conversation', tier: 'bronze', nameKey: 'achievement.msg_10', descKey: 'achievement.msg_10.desc', icon: '📝', hidden: false, condition: { metric: 'total_messages', threshold: 10 } },
  { id: 'msg_100', category: 'conversation', tier: 'silver', nameKey: 'achievement.msg_100', descKey: 'achievement.msg_100.desc', icon: '📝', hidden: false, condition: { metric: 'total_messages', threshold: 100 } },
  { id: 'msg_500', category: 'conversation', tier: 'gold', nameKey: 'achievement.msg_500', descKey: 'achievement.msg_500.desc', icon: '📝', hidden: false, condition: { metric: 'total_messages', threshold: 500 } },
  { id: 'msg_2k', category: 'conversation', tier: 'platinum', nameKey: 'achievement.msg_2k', descKey: 'achievement.msg_2k.desc', icon: '📝', hidden: false, condition: { metric: 'total_messages', threshold: 2000 } },
  { id: 'msg_5k', category: 'conversation', tier: 'diamond', nameKey: 'achievement.msg_5k', descKey: 'achievement.msg_5k.desc', icon: '📝', hidden: false, condition: { metric: 'total_messages', threshold: 5000 } },
  { id: 'msg_10k', category: 'conversation', tier: 'star', nameKey: 'achievement.msg_10k', descKey: 'achievement.msg_10k.desc', icon: '📝', hidden: false, condition: { metric: 'total_messages', threshold: 10000 } },
  { id: 'msg_50k', category: 'conversation', tier: 'king', nameKey: 'achievement.msg_50k', descKey: 'achievement.msg_50k.desc', icon: '📝', hidden: false, condition: { metric: 'total_messages', threshold: 50000 } },
  { id: 'msg_100k', category: 'conversation', tier: 'legend', nameKey: 'achievement.msg_100k', descKey: 'achievement.msg_100k.desc', icon: '📝', hidden: false, condition: { metric: 'total_messages', threshold: 100000 } },
  { id: 'msg_500k', category: 'conversation', tier: 'epic', nameKey: 'achievement.msg_500k', descKey: 'achievement.msg_500k.desc', icon: '📝', hidden: false, condition: { metric: 'total_messages', threshold: 500000 } },
  { id: 'msg_1m', category: 'conversation', tier: 'eternal', nameKey: 'achievement.msg_1m', descKey: 'achievement.msg_1m.desc', icon: '📝', hidden: false, condition: { metric: 'total_messages', threshold: 1000000 } },

  // ── Conversation: Streaks ──
  { id: 'streak_3', category: 'conversation', tier: 'bronze', nameKey: 'achievement.streak_3', descKey: 'achievement.streak_3.desc', icon: '🔥', hidden: false, condition: { metric: 'current_streak', threshold: 3 } },
  { id: 'streak_7', category: 'conversation', tier: 'silver', nameKey: 'achievement.streak_7', descKey: 'achievement.streak_7.desc', icon: '🔥', hidden: false, condition: { metric: 'current_streak', threshold: 7 } },
  { id: 'streak_14', category: 'conversation', tier: 'gold', nameKey: 'achievement.streak_14', descKey: 'achievement.streak_14.desc', icon: '🔥', hidden: false, condition: { metric: 'current_streak', threshold: 14 } },
  { id: 'streak_30', category: 'conversation', tier: 'platinum', nameKey: 'achievement.streak_30', descKey: 'achievement.streak_30.desc', icon: '🔥', hidden: false, condition: { metric: 'current_streak', threshold: 30 } },
  { id: 'streak_60', category: 'conversation', tier: 'diamond', nameKey: 'achievement.streak_60', descKey: 'achievement.streak_60.desc', icon: '🔥', hidden: false, condition: { metric: 'current_streak', threshold: 60 } },
  { id: 'streak_100', category: 'conversation', tier: 'star', nameKey: 'achievement.streak_100', descKey: 'achievement.streak_100.desc', icon: '🔥', hidden: false, condition: { metric: 'current_streak', threshold: 100 } },
  { id: 'streak_180', category: 'conversation', tier: 'king', nameKey: 'achievement.streak_180', descKey: 'achievement.streak_180.desc', icon: '🔥', hidden: false, condition: { metric: 'current_streak', threshold: 180 } },
  { id: 'streak_365', category: 'conversation', tier: 'legend', nameKey: 'achievement.streak_365', descKey: 'achievement.streak_365.desc', icon: '🔥', hidden: false, condition: { metric: 'current_streak', threshold: 365 } },
  { id: 'streak_500', category: 'conversation', tier: 'epic', nameKey: 'achievement.streak_500', descKey: 'achievement.streak_500.desc', icon: '🔥', hidden: false, condition: { metric: 'current_streak', threshold: 500 } },
  { id: 'streak_999', category: 'conversation', tier: 'eternal', nameKey: 'achievement.streak_999', descKey: 'achievement.streak_999.desc', icon: '🔥', hidden: false, condition: { metric: 'current_streak', threshold: 999 } },

  // ── Advanced: Cron ──
  { id: 'cron_1', category: 'advanced', tier: 'bronze', nameKey: 'achievement.cron_1', descKey: 'achievement.cron_1.desc', icon: '⏰', hidden: false, condition: { metric: 'total_cron_runs', threshold: 1 } },
  { id: 'cron_10', category: 'advanced', tier: 'silver', nameKey: 'achievement.cron_10', descKey: 'achievement.cron_10.desc', icon: '⏰', hidden: false, condition: { metric: 'total_cron_runs', threshold: 10 } },
  { id: 'cron_50', category: 'advanced', tier: 'gold', nameKey: 'achievement.cron_50', descKey: 'achievement.cron_50.desc', icon: '⏰', hidden: false, condition: { metric: 'total_cron_runs', threshold: 50 } },
  { id: 'cron_200', category: 'advanced', tier: 'platinum', nameKey: 'achievement.cron_200', descKey: 'achievement.cron_200.desc', icon: '⏰', hidden: false, condition: { metric: 'total_cron_runs', threshold: 200 } },
  { id: 'cron_500', category: 'advanced', tier: 'diamond', nameKey: 'achievement.cron_500', descKey: 'achievement.cron_500.desc', icon: '⏰', hidden: false, condition: { metric: 'total_cron_runs', threshold: 500 } },
  { id: 'cron_1k', category: 'advanced', tier: 'star', nameKey: 'achievement.cron_1k', descKey: 'achievement.cron_1k.desc', icon: '⏰', hidden: false, condition: { metric: 'total_cron_runs', threshold: 1000 } },

  // ── Advanced: Batch ──
  { id: 'batch_1', category: 'advanced', tier: 'bronze', nameKey: 'achievement.batch_1', descKey: 'achievement.batch_1.desc', icon: '📦', hidden: false, condition: { metric: 'total_batch_items', threshold: 1 } },
  { id: 'batch_50', category: 'advanced', tier: 'silver', nameKey: 'achievement.batch_50', descKey: 'achievement.batch_50.desc', icon: '📦', hidden: false, condition: { metric: 'total_batch_items', threshold: 50 } },
  { id: 'batch_200', category: 'advanced', tier: 'gold', nameKey: 'achievement.batch_200', descKey: 'achievement.batch_200.desc', icon: '📦', hidden: false, condition: { metric: 'total_batch_items', threshold: 200 } },
  { id: 'batch_1k', category: 'advanced', tier: 'platinum', nameKey: 'achievement.batch_1k', descKey: 'achievement.batch_1k.desc', icon: '📦', hidden: false, condition: { metric: 'total_batch_items', threshold: 1000 } },
  { id: 'batch_5k', category: 'advanced', tier: 'diamond', nameKey: 'achievement.batch_5k', descKey: 'achievement.batch_5k.desc', icon: '📦', hidden: false, condition: { metric: 'total_batch_items', threshold: 5000 } },
  { id: 'batch_20k', category: 'advanced', tier: 'star', nameKey: 'achievement.batch_20k', descKey: 'achievement.batch_20k.desc', icon: '📦', hidden: false, condition: { metric: 'total_batch_items', threshold: 20000 } },

  // ── Advanced: Smart Path ──
  { id: 'path_1', category: 'advanced', tier: 'bronze', nameKey: 'achievement.path_1', descKey: 'achievement.path_1.desc', icon: '🧭', hidden: false, condition: { metric: 'total_smartpath_runs', threshold: 1 } },
  { id: 'path_10', category: 'advanced', tier: 'silver', nameKey: 'achievement.path_10', descKey: 'achievement.path_10.desc', icon: '🧭', hidden: false, condition: { metric: 'total_smartpath_runs', threshold: 10 } },
  { id: 'path_50', category: 'advanced', tier: 'gold', nameKey: 'achievement.path_50', descKey: 'achievement.path_50.desc', icon: '🧭', hidden: false, condition: { metric: 'total_smartpath_runs', threshold: 50 } },
  { id: 'path_100', category: 'advanced', tier: 'platinum', nameKey: 'achievement.path_100', descKey: 'achievement.path_100.desc', icon: '🧭', hidden: false, condition: { metric: 'total_smartpath_runs', threshold: 100 } },
  { id: 'path_500', category: 'advanced', tier: 'diamond', nameKey: 'achievement.path_500', descKey: 'achievement.path_500.desc', icon: '🧭', hidden: false, condition: { metric: 'total_smartpath_runs', threshold: 500 } },
  { id: 'path_1k', category: 'advanced', tier: 'star', nameKey: 'achievement.path_1k', descKey: 'achievement.path_1k.desc', icon: '🧭', hidden: false, condition: { metric: 'total_smartpath_runs', threshold: 1000 } },

  // ── Advanced: Skills ──
  { id: 'skill_1', category: 'advanced', tier: 'bronze', nameKey: 'achievement.skill_1', descKey: 'achievement.skill_1.desc', icon: '🛠️', hidden: false, condition: { metric: 'total_skills_used', threshold: 1 } },
  { id: 'skill_5', category: 'advanced', tier: 'silver', nameKey: 'achievement.skill_5', descKey: 'achievement.skill_5.desc', icon: '🛠️', hidden: false, condition: { metric: 'total_skills_used', threshold: 5 } },
  { id: 'skill_20', category: 'advanced', tier: 'gold', nameKey: 'achievement.skill_20', descKey: 'achievement.skill_20.desc', icon: '🛠️', hidden: false, condition: { metric: 'total_skills_used', threshold: 20 } },
  { id: 'skill_50', category: 'advanced', tier: 'platinum', nameKey: 'achievement.skill_50', descKey: 'achievement.skill_50.desc', icon: '🛠️', hidden: false, condition: { metric: 'total_skills_used', threshold: 50 } },
  { id: 'skill_100', category: 'advanced', tier: 'diamond', nameKey: 'achievement.skill_100', descKey: 'achievement.skill_100.desc', icon: '🛠️', hidden: false, condition: { metric: 'total_skills_used', threshold: 100 } },
  { id: 'skill_500', category: 'advanced', tier: 'star', nameKey: 'achievement.skill_500', descKey: 'achievement.skill_500.desc', icon: '🛠️', hidden: false, condition: { metric: 'total_skills_used', threshold: 500 } },

  // ── Exploration: Workspaces ──
  { id: 'ws_2', category: 'exploration', tier: 'bronze', nameKey: 'achievement.ws_2', descKey: 'achievement.ws_2.desc', icon: '📁', hidden: false, condition: { metric: 'total_workspaces', threshold: 2 } },
  { id: 'ws_5', category: 'exploration', tier: 'silver', nameKey: 'achievement.ws_5', descKey: 'achievement.ws_5.desc', icon: '📁', hidden: false, condition: { metric: 'total_workspaces', threshold: 5 } },
  { id: 'ws_10', category: 'exploration', tier: 'gold', nameKey: 'achievement.ws_10', descKey: 'achievement.ws_10.desc', icon: '📁', hidden: false, condition: { metric: 'total_workspaces', threshold: 10 } },
  { id: 'ws_20', category: 'exploration', tier: 'platinum', nameKey: 'achievement.ws_20', descKey: 'achievement.ws_20.desc', icon: '📁', hidden: false, condition: { metric: 'total_workspaces', threshold: 20 } },
  { id: 'ws_50', category: 'exploration', tier: 'diamond', nameKey: 'achievement.ws_50', descKey: 'achievement.ws_50.desc', icon: '📁', hidden: false, condition: { metric: 'total_workspaces', threshold: 50 } },
  { id: 'ws_100', category: 'exploration', tier: 'star', nameKey: 'achievement.ws_100', descKey: 'achievement.ws_100.desc', icon: '📁', hidden: false, condition: { metric: 'total_workspaces', threshold: 100 } },

  // ── Exploration: Tokens ──
  { id: 'token_1w', category: 'exploration', tier: 'bronze', nameKey: 'achievement.token_1w', descKey: 'achievement.token_1w.desc', icon: '🪙', hidden: false, condition: { metric: 'total_tokens', threshold: 10000 } },
  { id: 'token_10w', category: 'exploration', tier: 'silver', nameKey: 'achievement.token_10w', descKey: 'achievement.token_10w.desc', icon: '🪙', hidden: false, condition: { metric: 'total_tokens', threshold: 100000 } },
  { id: 'token_50w', category: 'exploration', tier: 'gold', nameKey: 'achievement.token_50w', descKey: 'achievement.token_50w.desc', icon: '🪙', hidden: false, condition: { metric: 'total_tokens', threshold: 500000 } },
  { id: 'token_100w', category: 'exploration', tier: 'platinum', nameKey: 'achievement.token_100w', descKey: 'achievement.token_100w.desc', icon: '🪙', hidden: false, condition: { metric: 'total_tokens', threshold: 1000000 } },
  { id: 'token_500w', category: 'exploration', tier: 'diamond', nameKey: 'achievement.token_500w', descKey: 'achievement.token_500w.desc', icon: '🪙', hidden: false, condition: { metric: 'total_tokens', threshold: 5000000 } },
  { id: 'token_1000w', category: 'exploration', tier: 'star', nameKey: 'achievement.token_1000w', descKey: 'achievement.token_1000w.desc', icon: '🪙', hidden: false, condition: { metric: 'total_tokens', threshold: 10000000 } },
  { id: 'token_5000w', category: 'exploration', tier: 'king', nameKey: 'achievement.token_5000w', descKey: 'achievement.token_5000w.desc', icon: '🪙', hidden: false, condition: { metric: 'total_tokens', threshold: 50000000 } },
  { id: 'token_1yi', category: 'exploration', tier: 'legend', nameKey: 'achievement.token_1yi', descKey: 'achievement.token_1yi.desc', icon: '🪙', hidden: false, condition: { metric: 'total_tokens', threshold: 100000000 } },

  // ── Exploration: Code View & Git ──
  { id: 'codeview_10', category: 'exploration', tier: 'bronze', nameKey: 'achievement.codeview_10', descKey: 'achievement.codeview_10.desc', icon: '👁️', hidden: false, condition: { metric: 'total_code_views', threshold: 10 } },
  { id: 'codeview_50', category: 'exploration', tier: 'silver', nameKey: 'achievement.codeview_50', descKey: 'achievement.codeview_50.desc', icon: '👁️', hidden: false, condition: { metric: 'total_code_views', threshold: 50 } },
  { id: 'codeview_200', category: 'exploration', tier: 'gold', nameKey: 'achievement.codeview_200', descKey: 'achievement.codeview_200.desc', icon: '👁️', hidden: false, condition: { metric: 'total_code_views', threshold: 200 } },
  { id: 'codeview_1k', category: 'exploration', tier: 'platinum', nameKey: 'achievement.codeview_1k', descKey: 'achievement.codeview_1k.desc', icon: '👁️', hidden: false, condition: { metric: 'total_code_views', threshold: 1000 } },
  { id: 'codeview_5k', category: 'exploration', tier: 'diamond', nameKey: 'achievement.codeview_5k', descKey: 'achievement.codeview_5k.desc', icon: '👁️', hidden: false, condition: { metric: 'total_code_views', threshold: 5000 } },

  { id: 'git_10', category: 'exploration', tier: 'bronze', nameKey: 'achievement.git_10', descKey: 'achievement.git_10.desc', icon: '🔀', hidden: false, condition: { metric: 'total_git_ops', threshold: 10 } },
  { id: 'git_50', category: 'exploration', tier: 'silver', nameKey: 'achievement.git_50', descKey: 'achievement.git_50.desc', icon: '🔀', hidden: false, condition: { metric: 'total_git_ops', threshold: 50 } },
  { id: 'git_200', category: 'exploration', tier: 'gold', nameKey: 'achievement.git_200', descKey: 'achievement.git_200.desc', icon: '🔀', hidden: false, condition: { metric: 'total_git_ops', threshold: 200 } },
  { id: 'git_1k', category: 'exploration', tier: 'platinum', nameKey: 'achievement.git_1k', descKey: 'achievement.git_1k.desc', icon: '🔀', hidden: false, condition: { metric: 'total_git_ops', threshold: 1000 } },
  { id: 'git_5k', category: 'exploration', tier: 'diamond', nameKey: 'achievement.git_5k', descKey: 'achievement.git_5k.desc', icon: '🔀', hidden: false, condition: { metric: 'total_git_ops', threshold: 5000 } },

  // ── Collaboration ──
  { id: 'collab_1', category: 'collaboration', tier: 'bronze', nameKey: 'achievement.collab_1', descKey: 'achievement.collab_1.desc', icon: '✨', hidden: false, condition: { metric: 'total_collabs', threshold: 1 } },
  { id: 'collab_10', category: 'collaboration', tier: 'silver', nameKey: 'achievement.collab_10', descKey: 'achievement.collab_10.desc', icon: '✨', hidden: false, condition: { metric: 'total_collabs', threshold: 10 } },
  { id: 'collab_50', category: 'collaboration', tier: 'gold', nameKey: 'achievement.collab_50', descKey: 'achievement.collab_50.desc', icon: '✨', hidden: false, condition: { metric: 'total_collabs', threshold: 50 } },
  { id: 'collab_200', category: 'collaboration', tier: 'platinum', nameKey: 'achievement.collab_200', descKey: 'achievement.collab_200.desc', icon: '✨', hidden: false, condition: { metric: 'total_collabs', threshold: 200 } },
  { id: 'collab_1k', category: 'collaboration', tier: 'diamond', nameKey: 'achievement.collab_1k', descKey: 'achievement.collab_1k.desc', icon: '✨', hidden: false, condition: { metric: 'total_collabs', threshold: 1000 } },

  { id: 'reputation_5', category: 'collaboration', tier: 'bronze', nameKey: 'achievement.reputation_5', descKey: 'achievement.reputation_5.desc', icon: '⭐', hidden: false, condition: { metric: 'total_reputation', threshold: 5 } },
  { id: 'reputation_10', category: 'collaboration', tier: 'silver', nameKey: 'achievement.reputation_10', descKey: 'achievement.reputation_10.desc', icon: '⭐', hidden: false, condition: { metric: 'total_reputation', threshold: 10 } },
  { id: 'reputation_25', category: 'collaboration', tier: 'gold', nameKey: 'achievement.reputation_25', descKey: 'achievement.reputation_25.desc', icon: '⭐', hidden: false, condition: { metric: 'total_reputation', threshold: 25 } },
  { id: 'reputation_50', category: 'collaboration', tier: 'platinum', nameKey: 'achievement.reputation_50', descKey: 'achievement.reputation_50.desc', icon: '⭐', hidden: false, condition: { metric: 'total_reputation', threshold: 50 } },
  { id: 'reputation_100', category: 'collaboration', tier: 'diamond', nameKey: 'achievement.reputation_100', descKey: 'achievement.reputation_100.desc', icon: '⭐', hidden: false, condition: { metric: 'total_reputation', threshold: 100 } },
  { id: 'reputation_200', category: 'collaboration', tier: 'star', nameKey: 'achievement.reputation_200', descKey: 'achievement.reputation_200.desc', icon: '⭐', hidden: false, condition: { metric: 'total_reputation', threshold: 200 } },
  { id: 'reputation_500', category: 'collaboration', tier: 'king', nameKey: 'achievement.reputation_500', descKey: 'achievement.reputation_500.desc', icon: '⭐', hidden: false, condition: { metric: 'total_reputation', threshold: 500 } },
  { id: 'reputation_1k', category: 'collaboration', tier: 'legend', nameKey: 'achievement.reputation_1k', descKey: 'achievement.reputation_1k.desc', icon: '⭐', hidden: false, condition: { metric: 'total_reputation', threshold: 1000 } },

  // ── Bot: per-platform (wecom/feishu/weixin) + total ──
  { id: 'bot_count_total_1', category: 'bot', tier: 'bronze', nameKey: 'achievement.bot_count_total_1', descKey: 'achievement.bot_count_total_1.desc', icon: '🤖', hidden: false, condition: { metric: 'bot_count_total', threshold: 1 } },
  { id: 'bot_count_total_5', category: 'bot', tier: 'silver', nameKey: 'achievement.bot_count_total_5', descKey: 'achievement.bot_count_total_5.desc', icon: '🤖', hidden: false, condition: { metric: 'bot_count_total', threshold: 5 } },
  { id: 'bot_count_total_10', category: 'bot', tier: 'gold', nameKey: 'achievement.bot_count_total_10', descKey: 'achievement.bot_count_total_10.desc', icon: '🤖', hidden: false, condition: { metric: 'bot_count_total', threshold: 10 } },
  { id: 'bot_count_total_20', category: 'bot', tier: 'platinum', nameKey: 'achievement.bot_count_total_20', descKey: 'achievement.bot_count_total_20.desc', icon: '🤖', hidden: false, condition: { metric: 'bot_count_total', threshold: 20 } },
  { id: 'bot_count_total_50', category: 'bot', tier: 'diamond', nameKey: 'achievement.bot_count_total_50', descKey: 'achievement.bot_count_total_50.desc', icon: '🤖', hidden: false, condition: { metric: 'bot_count_total', threshold: 50 } },
  { id: 'bot_count_total_100', category: 'bot', tier: 'star', nameKey: 'achievement.bot_count_total_100', descKey: 'achievement.bot_count_total_100.desc', icon: '🤖', hidden: false, condition: { metric: 'bot_count_total', threshold: 100 } },

  { id: 'bot_sessions_total_1', category: 'bot', tier: 'bronze', nameKey: 'achievement.bot_sessions_total_1', descKey: 'achievement.bot_sessions_total_1.desc', icon: '💬', hidden: false, condition: { metric: 'bot_sessions_total', threshold: 1 } },
  { id: 'bot_sessions_total_10', category: 'bot', tier: 'silver', nameKey: 'achievement.bot_sessions_total_10', descKey: 'achievement.bot_sessions_total_10.desc', icon: '💬', hidden: false, condition: { metric: 'bot_sessions_total', threshold: 10 } },
  { id: 'bot_sessions_total_50', category: 'bot', tier: 'gold', nameKey: 'achievement.bot_sessions_total_50', descKey: 'achievement.bot_sessions_total_50.desc', icon: '💬', hidden: false, condition: { metric: 'bot_sessions_total', threshold: 50 } },
  { id: 'bot_sessions_total_200', category: 'bot', tier: 'platinum', nameKey: 'achievement.bot_sessions_total_200', descKey: 'achievement.bot_sessions_total_200.desc', icon: '💬', hidden: false, condition: { metric: 'bot_sessions_total', threshold: 200 } },
  { id: 'bot_sessions_total_1k', category: 'bot', tier: 'diamond', nameKey: 'achievement.bot_sessions_total_1k', descKey: 'achievement.bot_sessions_total_1k.desc', icon: '💬', hidden: false, condition: { metric: 'bot_sessions_total', threshold: 1000 } },
  { id: 'bot_sessions_total_5k', category: 'bot', tier: 'king', nameKey: 'achievement.bot_sessions_total_5k', descKey: 'achievement.bot_sessions_total_5k.desc', icon: '💬', hidden: false, condition: { metric: 'bot_sessions_total', threshold: 5000 } },

  { id: 'bot_messages_total_10', category: 'bot', tier: 'bronze', nameKey: 'achievement.bot_messages_total_10', descKey: 'achievement.bot_messages_total_10.desc', icon: '📨', hidden: false, condition: { metric: 'bot_messages_total', threshold: 10 } },
  { id: 'bot_messages_total_100', category: 'bot', tier: 'silver', nameKey: 'achievement.bot_messages_total_100', descKey: 'achievement.bot_messages_total_100.desc', icon: '📨', hidden: false, condition: { metric: 'bot_messages_total', threshold: 100 } },
  { id: 'bot_messages_total_500', category: 'bot', tier: 'gold', nameKey: 'achievement.bot_messages_total_500', descKey: 'achievement.bot_messages_total_500.desc', icon: '📨', hidden: false, condition: { metric: 'bot_messages_total', threshold: 500 } },
  { id: 'bot_messages_total_2k', category: 'bot', tier: 'platinum', nameKey: 'achievement.bot_messages_total_2k', descKey: 'achievement.bot_messages_total_2k.desc', icon: '📨', hidden: false, condition: { metric: 'bot_messages_total', threshold: 2000 } },
  { id: 'bot_messages_total_10k', category: 'bot', tier: 'diamond', nameKey: 'achievement.bot_messages_total_10k', descKey: 'achievement.bot_messages_total_10k.desc', icon: '📨', hidden: false, condition: { metric: 'bot_messages_total', threshold: 10000 } },
  { id: 'bot_messages_total_50k', category: 'bot', tier: 'king', nameKey: 'achievement.bot_messages_total_50k', descKey: 'achievement.bot_messages_total_50k.desc', icon: '📨', hidden: false, condition: { metric: 'bot_messages_total', threshold: 50000 } },

  { id: 'bot_platform_2', category: 'bot', tier: 'silver', nameKey: 'achievement.bot_platform_2', descKey: 'achievement.bot_platform_2.desc', icon: '🌐', hidden: false, condition: { metric: 'bot_platforms_used', threshold: 2 } },
  { id: 'bot_platform_3', category: 'bot', tier: 'gold', nameKey: 'achievement.bot_platform_3', descKey: 'achievement.bot_platform_3.desc', icon: '🌐', hidden: false, condition: { metric: 'bot_platforms_used', threshold: 3 } },

  // ── Hidden / Easter Eggs ──
  { id: 'midnight_warrior', category: 'hidden', tier: 'silver', nameKey: 'achievement.midnight_warrior', descKey: 'achievement.midnight_warrior.desc', icon: '🌙', hidden: true, condition: { metric: 'hour_sent', threshold: 1 } },
  { id: 'early_bird', category: 'hidden', tier: 'silver', nameKey: 'achievement.early_bird', descKey: 'achievement.early_bird.desc', icon: '🌅', hidden: true, condition: { metric: 'early_bird', threshold: 1 } },
  { id: 'marathon_50', category: 'hidden', tier: 'gold', nameKey: 'achievement.marathon_50', descKey: 'achievement.marathon_50.desc', icon: '🏃', hidden: true, condition: { metric: 'session_messages', threshold: 50 } },
  { id: 'marathon_200', category: 'hidden', tier: 'king', nameKey: 'achievement.marathon_200', descKey: 'achievement.marathon_200.desc', icon: '🏃', hidden: true, condition: { metric: 'session_messages', threshold: 200 } },
  { id: 'speed_demon', category: 'hidden', tier: 'epic', nameKey: 'achievement.speed_demon', descKey: 'achievement.speed_demon.desc', icon: '⚡', hidden: true, condition: { metric: 'messages_per_minute', threshold: 5 } },
  { id: 'weekend_warrior', category: 'hidden', tier: 'gold', nameKey: 'achievement.weekend_warrior', descKey: 'achievement.weekend_warrior.desc', icon: '🎯', hidden: true, condition: { metric: 'weekend_count', threshold: 4 } },
  { id: 'first_error', category: 'hidden', tier: 'bronze', nameKey: 'achievement.first_error', descKey: 'achievement.first_error.desc', icon: '🐛', hidden: true, condition: { metric: 'total_errors', threshold: 1 } },
  { id: 'error_100', category: 'hidden', tier: 'silver', nameKey: 'achievement.error_100', descKey: 'achievement.error_100.desc', icon: '🐛', hidden: true, condition: { metric: 'total_errors', threshold: 100 } },
  { id: 'comeback', category: 'hidden', tier: 'gold', nameKey: 'achievement.comeback', descKey: 'achievement.comeback.desc', icon: '🔄', hidden: true, condition: { metric: 'comeback', threshold: 1 } },
  { id: 'new_year', category: 'hidden', tier: 'gold', nameKey: 'achievement.new_year', descKey: 'achievement.new_year.desc', icon: '🎆', hidden: true, condition: { metric: 'new_year', threshold: 1 } },
];

export const ACHIEVEMENT_DEFINITIONS: AchievementDef[] = defs;

// Build metric index for O(1) lookup
const metricIndex = new Map<string, AchievementDef[]>();
for (const def of defs) {
  const arr = metricIndex.get(def.condition.metric) || [];
  arr.push(def);
  metricIndex.set(def.condition.metric, arr);
}

export function getDefinitionsByMetric(metric: string): AchievementDef[] {
  return metricIndex.get(metric) || [];
}
