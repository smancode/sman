import type { AchievementEngine } from './achievement-engine.js';

export function handleAchievementMessage(
  msg: Record<string, any>,
  ws: any,
  engine: AchievementEngine,
): boolean {
  switch (msg.type) {
    case 'achievement.list': {
      const summary = engine.getSummary();
      ws.send(JSON.stringify({
        type: 'achievement.data',
        ...summary,
      }));
      return true;
    }
    case 'achievement.stats': {
      const stats = engine.getStats();
      ws.send(JSON.stringify({ type: 'achievement.stats', stats }));
      return true;
    }
    case 'achievement.leaderboard': {
      ws.send(JSON.stringify({
        type: 'achievement.leaderboard',
        entries: [],
        isOnline: false,
      }));
      return true;
    }
    default:
      return false;
  }
}
