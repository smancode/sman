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
      // Upload first, then fetch
      const clientId = engine.getClientIdStr();
      engine.uploadToLeaderboard().then(() => engine.fetchLeaderboard()).then(result => {
        if (result) {
          ws.send(JSON.stringify({
            type: 'achievement.leaderboard',
            entries: result.entries,
            isOnline: true,
            clientId,
          }));
        } else {
          ws.send(JSON.stringify({
            type: 'achievement.leaderboard',
            entries: [],
            isOnline: false,
            clientId,
          }));
        }
      });
      return true;
    }
    default:
      return false;
  }
}
