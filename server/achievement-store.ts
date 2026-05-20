import betterSqlite3 from 'better-sqlite3';
import type { Database } from 'better-sqlite3';
import { createLogger, type Logger } from './utils/logger.js';

// @ts-expect-error - better-sqlite3 ESM interop
const DatabaseConstructor = betterSqlite3 as unknown as typeof betterSqlite3.default;

export interface AchievementProgress {
  achievementId: string;
  currentValue: number;
  unlockedAt: string | null;
  notifiedAt: string | null;
}

export interface StreakData {
  currentStreak: number;
  longestStreak: number;
  lastActiveDate: string | null;
}

export interface AchievementBoardEntry {
  agentId: string;
  agentName: string;
  totalUnlocked: number;
  totalPoints: number;
  tierCounts: string;
  dimensionScores: string;
  lastSynced: string;
}

export class AchievementStore {
  private db: Database;
  private log: Logger;

  constructor(dbPath: string) {
    this.db = new DatabaseConstructor(dbPath);
    this.log = createLogger('AchievementStore');
    this.init();
  }

  private init(): void {
    this.db.exec(`
      CREATE TABLE IF NOT EXISTS achievement_progress (
        achievement_id TEXT PRIMARY KEY,
        current_value INTEGER DEFAULT 0,
        unlocked_at TEXT,
        notified_at TEXT
      );

      CREATE TABLE IF NOT EXISTS achievement_stats (
        key TEXT PRIMARY KEY,
        value TEXT,
        updated_at TEXT
      );

      CREATE TABLE IF NOT EXISTS achievement_streaks (
        id INTEGER PRIMARY KEY DEFAULT 1 CHECK (id = 1),
        current_streak INTEGER DEFAULT 0,
        longest_streak INTEGER DEFAULT 0,
        last_active_date TEXT
      );

      CREATE TABLE IF NOT EXISTS achievement_board (
        agent_id TEXT PRIMARY KEY,
        agent_name TEXT,
        total_unlocked INTEGER DEFAULT 0,
        total_points INTEGER DEFAULT 0,
        tier_counts TEXT,
        dimension_scores TEXT,
        last_synced TEXT
      );
    `);

    this.db.exec(`
      INSERT OR IGNORE INTO achievement_streaks (id, current_streak, longest_streak)
      VALUES (1, 0, 0);
    `);

    this.db.pragma('journal_mode = WAL');
    this.db.pragma('foreign_keys = ON');
  }

  getProgress(achievementId: string): AchievementProgress | undefined {
    return this.db.prepare(
      'SELECT achievement_id as achievementId, current_value as currentValue, unlocked_at as unlockedAt, notified_at as notifiedAt FROM achievement_progress WHERE achievement_id = ?'
    ).get(achievementId) as AchievementProgress | undefined;
  }

  setProgress(achievementId: string, value: number): void {
    this.db.prepare(
      'INSERT INTO achievement_progress (achievement_id, current_value, unlocked_at) VALUES (?, ?, NULL) ON CONFLICT(achievement_id) DO UPDATE SET current_value = ?, unlocked_at = unlocked_at'
    ).run(achievementId, value, value);
  }

  unlock(achievementId: string): void {
    const now = new Date().toISOString();
    this.db.prepare(
      'INSERT INTO achievement_progress (achievement_id, current_value, unlocked_at) VALUES (?, 0, ?) ON CONFLICT(achievement_id) DO UPDATE SET unlocked_at = ?'
    ).run(achievementId, now, now);
  }

  markNotified(achievementId: string): void {
    const now = new Date().toISOString();
    this.db.prepare(
      'UPDATE achievement_progress SET notified_at = ? WHERE achievement_id = ?'
    ).run(now, achievementId);
  }

  getAllProgress(): AchievementProgress[] {
    return this.db.prepare(
      'SELECT achievement_id as achievementId, current_value as currentValue, unlocked_at as unlockedAt, notified_at as notifiedAt FROM achievement_progress'
    ).all() as AchievementProgress[];
  }

  getStat(key: string): string | undefined {
    const row = this.db.prepare('SELECT value FROM achievement_stats WHERE key = ?').get(key) as { value: string } | undefined;
    return row?.value;
  }

  setStat(key: string, value: string): void {
    const now = new Date().toISOString();
    this.db.prepare(
      'INSERT INTO achievement_stats (key, value, updated_at) VALUES (?, ?, ?) ON CONFLICT(key) DO UPDATE SET value = ?, updated_at = ?'
    ).run(key, value, now, value, now);
  }

  incrementStat(key: string, delta: number = 1): void {
    const current = parseInt(this.getStat(key) || '0', 10);
    this.setStat(key, String(current + delta));
  }

  getAllStats(): Record<string, string> {
    const rows = this.db.prepare('SELECT key, value FROM achievement_stats').all() as { key: string; value: string }[];
    const result: Record<string, string> = {};
    for (const row of rows) {
      result[row.key] = row.value;
    }
    return result;
  }

  getStreak(): StreakData {
    return this.db.prepare(
      'SELECT current_streak as currentStreak, longest_streak as longestStreak, last_active_date as lastActiveDate FROM achievement_streaks WHERE id = 1'
    ).get() as StreakData;
  }

  updateStreak(today: string): { current: number; longest: number } {
    const streak = this.getStreak();
    const lastDate = streak.lastActiveDate;

    if (lastDate === today) {
      return { current: streak.currentStreak, longest: streak.longestStreak };
    }

    let newCurrent: number;
    if (lastDate) {
      const last = new Date(lastDate);
      const todayDate = new Date(today);
      const diffDays = Math.floor((todayDate.getTime() - last.getTime()) / (1000 * 60 * 60 * 24));
      newCurrent = diffDays === 1 ? streak.currentStreak + 1 : 1;
    } else {
      newCurrent = 1;
    }

    const newLongest = Math.max(streak.longestStreak, newCurrent);
    this.db.prepare(
      'UPDATE achievement_streaks SET current_streak = ?, longest_streak = ?, last_active_date = ? WHERE id = 1'
    ).run(newCurrent, newLongest, today);

    return { current: newCurrent, longest: newLongest };
  }

  getBoard(): AchievementBoardEntry[] {
    return this.db.prepare(
      'SELECT agent_id as agentId, agent_name as agentName, total_unlocked as totalUnlocked, total_points as totalPoints, tier_counts as tierCounts, dimension_scores as dimensionScores, last_synced as lastSynced FROM achievement_board ORDER BY total_points DESC'
    ).all() as AchievementBoardEntry[];
  }

  upsertBoardEntry(entry: AchievementBoardEntry): void {
    this.db.prepare(
      `INSERT INTO achievement_board (agent_id, agent_name, total_unlocked, total_points, tier_counts, dimension_scores, last_synced)
       VALUES (?, ?, ?, ?, ?, ?, ?)
       ON CONFLICT(agent_id) DO UPDATE SET
         agent_name = excluded.agent_name,
         total_unlocked = excluded.total_unlocked,
         total_points = excluded.total_points,
         tier_counts = excluded.tier_counts,
         dimension_scores = excluded.dimension_scores,
         last_synced = excluded.last_synced`
    ).run(entry.agentId, entry.agentName, entry.totalUnlocked, entry.totalPoints, entry.tierCounts, entry.dimensionScores, entry.lastSynced);
  }

  getDatabase(): Database {
    return this.db;
  }

  close(): void {
    this.db.close();
  }
}
