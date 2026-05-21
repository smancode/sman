import { AchievementStore, type AchievementProgress, type StreakData } from './achievement-store.js';
import { ACHIEVEMENT_DEFINITIONS, TIER_SCORES, getDefinitionsByMetric, calculateLevel, calculateLevelProgress, type Tier, type AchievementDef } from './achievement-definitions.js';
import { onAchievementEvent, removeAllAchievementListeners, type AchievementEvent } from './achievement-events.js';

// Metric weights for weighted score calculation
const METRIC_WEIGHTS: Record<string, number> = {
  total_sessions: 3,         // 有消息的会话，3分/个
  total_messages: 0.5,       // 0.5分/条
  total_tokens: 0.000005,    // 0.000005分/token (= 1分/20万token)
  total_cron_runs: 2,        // 2分/次
  bot_sessions_total: 2,     // 2分/个
  bot_messages_total: 1,     // 1分/条
  bot_count_total: 5,        // 5分/个Bot
  current_streak: 2,         // 2分/天(取当前连续天数)
};
import { createLogger, type Logger } from './utils/logger.js';

export interface AchievementView {
  id: string;
  category: string;
  tier: Tier;
  nameKey: string;
  descKey: string;
  icon: string;
  hidden: boolean;
  currentValue: number;
  threshold: number;
  unlockedAt: string | null;
  points: number;
}

export interface AchievementSummary {
  achievements: AchievementView[];
  stats: Record<string, string>;
  streak: { current: number; longest: number };
  totalPoints: number;
  level: Tier;
  levelProgress: ReturnType<typeof calculateLevelProgress>;
  totalUnlocked: number;
  totalAchievements: number;
}

export class AchievementEngine {
  private store: AchievementStore;
  private log: Logger;
  private broadcastFn: ((msg: string) => void) | null = null;
  private speedWindow: Map<string, number[]> = new Map();
  private sessionIdMessageCount: Map<string, number> = new Map();
  private sessionStore: any = null;
  private reconcileTimer: ReturnType<typeof setInterval> | null = null;
  private leaderboardTimer: ReturnType<typeof setInterval> | null = null;
  private getHubUrl: (() => string | null) | null = null;
  private getClientId: (() => string) | null = null;
  private buildEncryptedRequest: ((data: Record<string, unknown>) => any) | null = null;

  constructor(store: AchievementStore) {
    this.store = store;
    this.log = createLogger('AchievementEngine');
  }

  setBroadcast(fn: (msg: string) => void): void {
    this.broadcastFn = fn;
  }

  setSessionStore(store: any): void {
    this.sessionStore = store;
  }

  setHubDeps(getHubUrl: () => string | null, getClientId: () => string, buildEncryptedRequest: (data: Record<string, unknown>) => any): void {
    this.getHubUrl = getHubUrl;
    this.getClientId = getClientId;
    this.buildEncryptedRequest = buildEncryptedRequest;
  }

  start(): void {
    const initialized = this.store.getStat('_initialized');
    if (!initialized) {
      this.log.info('First run — backfilling stats from existing data...');
      this.recalcStatsFromDB();
      this.store.setStat('_initialized', 'true');
    }

    // Clean up orphaned progress records for removed achievements
    this.cleanOrphanedProgress();

    onAchievementEvent((event) => this.handleEvent(event));

    this.checkDayActive();

    // Reconcile with DB every 30 minutes to catch missed events
    this.reconcileTimer = setInterval(() => {
      this.log.info('Running periodic DB reconciliation...');
      this.reconcileWithDB();
    }, 30 * 60 * 1000);

    // Upload to leaderboard every hour
    this.leaderboardTimer = setInterval(() => {
      this.uploadToLeaderboard();
    }, 60 * 60 * 1000);

    this.log.info(`Achievement engine started. Level: ${calculateLevel(this.getTotalPoints())}`);
  }

  private checkDayActive(): void {
    const today = this.getToday();
    const lastActive = this.store.getStat('last_active_date');
    if (lastActive !== today) {
      this.store.setStat('last_active_date', today);
      const result = this.store.updateStreak(today);
      this.store.setStat('current_streak', String(result.current));
      this.store.setStat('longest_streak', String(result.longest));
      const activeDays = parseInt(this.store.getStat('total_active_days') || '0', 10) + 1;
      this.store.setStat('total_active_days', String(activeDays));
      this.checkMetrics(['current_streak', 'longest_streak', 'total_active_days']);
    }
  }

  handleEvent(event: AchievementEvent): void {
    try {
      const { type, data } = event;
      const metricsToUpdate: Set<string> = new Set();

      switch (type) {
        case 'session_created':
          this.incrementStat('total_sessions', metricsToUpdate);
          if (data.workspace) {
            this.trackWorkspace(data.workspace, metricsToUpdate);
          }
          break;

        case 'message_sent': {
          this.incrementStat('total_messages', metricsToUpdate);
          this.checkDayActive();
          // Track per-session message count
          if (data.sessionId) {
            const count = (this.sessionIdMessageCount.get(data.sessionId) || 0) + 1;
            this.sessionIdMessageCount.set(data.sessionId, count);
            if (count === 50 || count === 200) {
              this.store.setStat('session_messages', String(count));
              metricsToUpdate.add('session_messages');
            }
          }
          // Check hour for hidden achievements
          const hour = new Date().getHours();
          if (hour >= 0 && hour < 5) {
            this.incrementStat('hour_sent', metricsToUpdate);
          }
          if (hour >= 5 && hour < 7) {
            this.incrementStat('early_bird', metricsToUpdate);
          }
          // Speed demon check
          if (data.sessionId) {
            const now = Date.now();
            const timestamps = this.speedWindow.get(data.sessionId) || [];
            timestamps.push(now);
            const recent = timestamps.filter(t => now - t < 60000);
            this.speedWindow.set(data.sessionId, recent);
            if (recent.length >= 5) {
              this.store.setStat('messages_per_minute', '5');
              metricsToUpdate.add('messages_per_minute');
            }
          }
          // Weekend check
          const day = new Date().getDay();
          if (day === 0 || day === 6) {
            const wc = parseInt(this.store.getStat('weekend_count') || '0', 10) + 1;
            this.store.setStat('weekend_count', String(wc));
            metricsToUpdate.add('weekend_count');
          }
          // New year check
          const month = new Date().getMonth();
          const dayOfMonth = new Date().getDate();
          if (month === 0 && dayOfMonth === 1) {
            this.store.setStat('new_year', '1');
            metricsToUpdate.add('new_year');
          }
          // Comeback check
          const lastActive = this.store.getStat('last_active_date_before');
          if (lastActive) {
            const diff = Math.floor((new Date().getTime() - new Date(lastActive).getTime()) / (1000 * 60 * 60 * 24));
            if (diff >= 7) {
              this.store.setStat('comeback', '1');
              metricsToUpdate.add('comeback');
            }
          }
          break;
        }

        case 'cron_executed':
          this.incrementStat('total_cron_runs', metricsToUpdate);
          break;

        case 'token_used':
          if (data.tokens && data.tokens > 0) {
            const current = parseInt(this.store.getStat('total_tokens') || '0', 10);
            this.store.setStat('total_tokens', String(current + data.tokens));
            metricsToUpdate.add('total_tokens');
          }
          break;

        case 'workspace_added':
          if (data.workspace) {
            this.trackWorkspace(data.workspace, metricsToUpdate);
          }
          break;

        case 'stardom_collab':
          this.incrementStat('total_collabs', metricsToUpdate);
          break;

        case 'error_occurred':
          this.incrementStat('total_errors', metricsToUpdate);
          break;

        case 'bot_session_created':
          if (data.platform) {
            this.incrementStat(`bot_sessions_${data.platform}`, metricsToUpdate);
            this.incrementStat('bot_sessions_total', metricsToUpdate);
          }
          break;

        case 'bot_message_sent':
          if (data.platform) {
            this.incrementStat(`bot_messages_${data.platform}`, metricsToUpdate);
            this.incrementStat('bot_messages_total', metricsToUpdate);
          }
          break;
      }

      this.checkMetrics([...metricsToUpdate]);
    } catch (err) {
      this.log.error('Error handling achievement event:', { error: String(err) });
    }
  }

  private incrementStat(key: string, updated: Set<string>): void {
    this.store.incrementStat(key);
    updated.add(key);
  }

  private trackWorkspace(workspace: string, updated: Set<string>): void {
    const seen = this.store.getStat('_seen_workspaces');
    const workspaces = seen ? JSON.parse(seen) as string[] : [];
    if (!workspaces.includes(workspace)) {
      workspaces.push(workspace);
      this.store.setStat('_seen_workspaces', JSON.stringify(workspaces));
      this.store.setStat('total_workspaces', String(workspaces.length));
      updated.add('total_workspaces');
    }
  }

  private checkMetrics(metrics: string[]): void {
    for (const metric of metrics) {
      const defs = getDefinitionsByMetric(metric);
      const currentValue = this.getMetricValue(metric);
      for (const def of defs) {
        if (def.hidden && currentValue < def.condition.threshold) continue;
        this.updateAchievementProgress(def, currentValue);
      }
    }
  }

  private getMetricValue(metric: string): number {
    // Streak metrics come from streak table
    if (metric === 'current_streak' || metric === 'longest_streak') {
      const streak = this.store.getStreak();
      return metric === 'current_streak' ? streak.currentStreak : streak.longestStreak;
    }
    return parseInt(this.store.getStat(metric) || '0', 10);
  }

  private updateAchievementProgress(def: AchievementDef, currentValue: number): void {
    const existing = this.store.getProgress(def.id);
    if (existing?.unlockedAt) return; // Already unlocked

    this.store.setProgress(def.id, currentValue);

    if (currentValue >= def.condition.threshold) {
      this.store.unlock(def.id);
      this.log.info(`Achievement unlocked: ${def.id} (${def.tier})`);
      this.broadcastUnlock(def);
    }
  }

  private broadcastUnlock(def: AchievementDef): void {
    if (!this.broadcastFn) return;
    this.broadcastFn(JSON.stringify({
      type: 'achievement.unlock',
      achievement: {
        id: def.id,
        nameKey: def.nameKey,
        descKey: def.descKey,
        icon: def.icon,
        tier: def.tier,
        category: def.category,
        points: TIER_SCORES[def.tier],
        hidden: def.hidden,
      },
      totalPoints: this.getTotalPoints(),
      level: calculateLevel(this.getTotalPoints()),
    }));
  }

  private broadcastProgress(): void {
    if (!this.broadcastFn) return;
    this.broadcastFn(JSON.stringify({
      type: 'achievement.progress',
      summary: this.getSummary(),
    }));
  }

  recalcStatsFromDB(): void {
    const db = this.store.getDatabase();
    try {
      // Sessions with at least one user message (including soft-deleted)
      const sessionCount = (db.prepare("SELECT COUNT(*) as c FROM sessions s WHERE EXISTS (SELECT 1 FROM messages m WHERE m.session_id = s.id AND m.role = 'user')").get() as { c: number }).c;
      this.store.setStat('total_sessions', String(sessionCount));

      // User messages
      const msgCount = (db.prepare("SELECT COUNT(*) as c FROM messages WHERE role = 'user'").get() as { c: number }).c;
      this.store.setStat('total_messages', String(msgCount));

      // Workspaces (including from soft-deleted sessions)
      const wsCount = (db.prepare('SELECT COUNT(DISTINCT workspace) as c FROM sessions').get() as { c: number }).c;
      this.store.setStat('total_workspaces', String(wsCount));

      // Tokens
      const inputTokens = (db.prepare('SELECT COALESCE(SUM(input_tokens), 0) as c FROM sessions').get() as { c: number }).c;
      const outputTokens = (db.prepare('SELECT COALESCE(SUM(output_tokens), 0) as c FROM sessions').get() as { c: number }).c;
      this.store.setStat('total_tokens', String(inputTokens + outputTokens));

      // Cron runs
      const cronRuns = (db.prepare("SELECT COUNT(*) as c FROM cron_runs WHERE status = 'success'").get() as { c: number }).c;
      this.store.setStat('total_cron_runs', String(cronRuns));

      // Bot stats — extract platform from user_key prefix
      const botSessionRow = db.prepare("SELECT COUNT(DISTINCT session_id) as c FROM chatbot_sessions").get() as { c: number };
      this.store.setStat('bot_sessions_total', String(botSessionRow.c));

      for (const platform of ['wecom', 'feishu', 'weixin']) {
        const row = db.prepare(
          "SELECT COUNT(DISTINCT cs.session_id) as c FROM chatbot_sessions cs WHERE SUBSTR(cs.user_key, 1, INSTR(cs.user_key, ':') - 1) = ?"
        ).get(platform) as { c: number };
        this.store.setStat(`bot_sessions_${platform}`, String(row.c));
      }

      const botMsgRow = db.prepare(
        "SELECT COUNT(*) as c FROM messages m JOIN chatbot_sessions cs ON cs.session_id = m.session_id WHERE m.role = 'user'"
      ).get() as { c: number };
      this.store.setStat('bot_messages_total', String(botMsgRow.c));

      for (const platform of ['wecom', 'feishu', 'weixin']) {
        const row = db.prepare(
          "SELECT COUNT(*) as c FROM messages m JOIN chatbot_sessions cs ON cs.session_id = m.session_id WHERE m.role = 'user' AND SUBSTR(cs.user_key, 1, INSTR(cs.user_key, ':') - 1) = ?"
        ).get(platform) as { c: number };
        this.store.setStat(`bot_messages_${platform}`, String(row.c));
      }

      // Bot count (distinct user_keys per platform)
      const botCountTotal = (db.prepare("SELECT COUNT(DISTINCT user_key) as c FROM chatbot_sessions").get() as { c: number }).c;
      this.store.setStat('bot_count_total', String(botCountTotal));

      // Platforms used
      const platformCount = (db.prepare("SELECT COUNT(DISTINCT SUBSTR(user_key, 1, INSTR(user_key, ':') - 1)) as c FROM chatbot_sessions").get() as { c: number }).c;
      this.store.setStat('bot_platforms_used', String(platformCount));

      // Check and unlock all eligible achievements
      const allMetrics = new Set<string>();
      for (const def of ACHIEVEMENT_DEFINITIONS) {
        allMetrics.add(def.condition.metric);
      }
      this.checkMetrics([...allMetrics]);

      this.log.info(`Backfill complete. Sessions: ${sessionCount}, Messages: ${msgCount}, Workspaces: ${wsCount}, Tokens: ${inputTokens + outputTokens}`);
    } catch (err) {
      this.log.error('Error during stats backfill:', { error: String(err) });
    }
  }

  private reconcileWithDB(): void {
    const db = this.store.getDatabase();
    const metricsToUpdate: Set<string> = new Set();
    try {
      const reconciliations: [string, string][] = [
        ['total_sessions', "SELECT COUNT(*) as c FROM sessions s WHERE EXISTS (SELECT 1 FROM messages m WHERE m.session_id = s.id AND m.role = 'user')"],
        ['total_messages', "SELECT COUNT(*) as c FROM messages WHERE role = 'user'"],
        ['total_cron_runs', "SELECT COUNT(*) as c FROM cron_runs WHERE status = 'success'"],
      ];

      for (const [metric, sql] of reconciliations) {
        const row = db.prepare(sql).get() as { c: number };
        const current = parseInt(this.store.getStat(metric) || '0', 10);
        if (row.c > current) {
          this.log.info(`Reconciliation: ${metric} ${current} → ${row.c}`);
          this.store.setStat(metric, String(row.c));
          metricsToUpdate.add(metric);
        }
      }

      // Tokens
      const inputTokens = (db.prepare('SELECT COALESCE(SUM(input_tokens), 0) as c FROM sessions').get() as { c: number }).c;
      const outputTokens = (db.prepare('SELECT COALESCE(SUM(output_tokens), 0) as c FROM sessions').get() as { c: number }).c;
      const dbTokens = inputTokens + outputTokens;
      const statTokens = parseInt(this.store.getStat('total_tokens') || '0', 10);
      if (dbTokens > statTokens) {
        this.log.info(`Reconciliation: total_tokens ${statTokens} → ${dbTokens}`);
        this.store.setStat('total_tokens', String(dbTokens));
        metricsToUpdate.add('total_tokens');
      }

      // Workspaces
      const wsCount = (db.prepare('SELECT COUNT(DISTINCT workspace) as c FROM sessions').get() as { c: number }).c;
      const statWs = parseInt(this.store.getStat('total_workspaces') || '0', 10);
      if (wsCount > statWs) {
        this.store.setStat('total_workspaces', String(wsCount));
        metricsToUpdate.add('total_workspaces');
      }

      if (metricsToUpdate.size > 0) {
        this.checkMetrics([...metricsToUpdate]);
        this.log.info(`Reconciliation complete, updated ${metricsToUpdate.size} metrics`);
      }
    } catch (err) {
      this.log.error('Error during DB reconciliation:', { error: String(err) });
    }
  }

  private getTotalPoints(): number {
    let total = 0;
    for (const [metric, weight] of Object.entries(METRIC_WEIGHTS)) {
      const value = parseInt(this.store.getStat(metric) || '0', 10);
      total += value * weight;
    }
    return Math.round(total);
  }

  private getToday(): string {
    return new Date().toISOString().split('T')[0];
  }

  getSummary(): AchievementSummary {
    const achievements: AchievementView[] = ACHIEVEMENT_DEFINITIONS.map(def => {
      const progress = this.store.getProgress(def.id);
      return {
        id: def.id,
        category: def.category,
        tier: def.tier,
        nameKey: def.nameKey,
        descKey: def.descKey,
        icon: def.icon,
        hidden: def.hidden,
        currentValue: progress?.currentValue ?? 0,
        threshold: def.condition.threshold,
        unlockedAt: progress?.unlockedAt ?? null,
        points: TIER_SCORES[def.tier],
      };
    });

    const totalPoints = this.getTotalPoints();
    const totalUnlocked = achievements.filter(a => a.unlockedAt).length;
    const streak = this.store.getStreak();

    return {
      achievements,
      stats: this.store.getAllStats(),
      streak: { current: streak.currentStreak, longest: streak.longestStreak },
      totalPoints,
      level: calculateLevel(totalPoints),
      levelProgress: calculateLevelProgress(totalPoints),
      totalUnlocked,
      totalAchievements: achievements.length,
    };
  }

  getAll(): AchievementView[] {
    return ACHIEVEMENT_DEFINITIONS.map(def => {
      const progress = this.store.getProgress(def.id);
      return {
        id: def.id,
        category: def.category,
        tier: def.tier,
        nameKey: def.nameKey,
        descKey: def.descKey,
        icon: def.icon,
        hidden: def.hidden,
        currentValue: progress?.currentValue ?? 0,
        threshold: def.condition.threshold,
        unlockedAt: progress?.unlockedAt ?? null,
        points: TIER_SCORES[def.tier],
      };
    });
  }

  getStats(): Record<string, string> {
    return this.store.getAllStats();
  }

  getStreak(): StreakData {
    return this.store.getStreak();
  }

  async uploadToLeaderboard(): Promise<void> {
    if (!this.getHubUrl || !this.getClientId || !this.buildEncryptedRequest) return;

    const hubUrl = this.getHubUrl();
    if (!hubUrl) return;

    // Reconcile before upload
    this.reconcileWithDB();

    const totalPoints = this.getTotalPoints();
    const level = calculateLevel(totalPoints);
    const clientId = this.getClientId();

    // Count tier distribution
    const tierCounts: Record<string, number> = {};
    for (const def of ACHIEVEMENT_DEFINITIONS) {
      const progress = this.store.getProgress(def.id);
      if (progress?.unlockedAt) {
        tierCounts[def.tier] = (tierCounts[def.tier] || 0) + 1;
      }
    }

    const summary = this.getSummary();

    // Build dimension scores from raw metrics
    const dimensionScores: Record<string, number> = {};
    for (const metric of Object.keys(METRIC_WEIGHTS)) {
      dimensionScores[metric] = parseInt(this.store.getStat(metric) || '0', 10);
    }

    try {
      const payload = {
        agentId: clientId,
        agentName: clientId,
        totalPoints,
        totalUnlocked: summary.totalUnlocked,
        level,
        tierCounts: JSON.stringify(tierCounts),
        dimensionScores: JSON.stringify(dimensionScores),
      };
      const encrypted = this.buildEncryptedRequest(payload);
      const controller = new AbortController();
      const tid = setTimeout(() => controller.abort(), 10_000);

      fetch(`${hubUrl}/api/achievement-report`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(encrypted),
        signal: controller.signal,
      }).then(res => {
        clearTimeout(tid);
        if (res.ok) {
          this.log.info(`Leaderboard uploaded: ${totalPoints} pts, level ${level}`);
        } else {
          this.log.warn(`Leaderboard upload failed: ${res.status}`);
        }
      }).catch(err => {
        clearTimeout(tid);
        this.log.warn(`Leaderboard upload error: ${err}`);
      });
    } catch (err) {
      this.log.error(`Leaderboard upload error: ${err}`);
    }
  }

  async fetchLeaderboard(dimension?: string): Promise<{ entries: { rank: number; agentName: string; totalPoints: number; totalUnlocked: number; level: string; dimensionValue?: number }[]; dimension: string } | null> {
    if (!this.getHubUrl) return null;
    const hubUrl = this.getHubUrl();
    if (!hubUrl) return null;

    try {
      const controller = new AbortController();
      const tid = setTimeout(() => controller.abort(), 10_000);
      const url = dimension && dimension !== 'total'
        ? `${hubUrl}/api/achievement-leaderboard?dimension=${encodeURIComponent(dimension)}`
        : `${hubUrl}/api/achievement-leaderboard`;
      const res = await fetch(url, {
        signal: controller.signal,
      });
      clearTimeout(tid);
      if (!res.ok) return null;
      return await res.json() as any;
    } catch {
      return null;
    }
  }

  private cleanOrphanedProgress(): void {
    const validIds = new Set(ACHIEVEMENT_DEFINITIONS.map(d => d.id));
    const db = this.store.getDatabase();
    const rows = db.prepare('SELECT achievement_id FROM achievement_progress').all() as { achievement_id: string }[];
    const orphans = rows.filter(r => !validIds.has(r.achievement_id));
    if (orphans.length > 0) {
      const del = db.prepare('DELETE FROM achievement_progress WHERE achievement_id = ?');
      for (const o of orphans) {
        del.run(o.achievement_id);
        this.log.info(`Removed orphaned achievement: ${o.achievement_id}`);
      }
    }
  }

  getClientIdStr(): string {
    return this.getClientId ? this.getClientId() : '';
  }

  close(): void {
    if (this.reconcileTimer) {
      clearInterval(this.reconcileTimer);
      this.reconcileTimer = null;
    }
    if (this.leaderboardTimer) {
      clearInterval(this.leaderboardTimer);
      this.leaderboardTimer = null;
    }
    removeAllAchievementListeners();
  }
}
