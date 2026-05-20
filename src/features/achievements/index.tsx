import { useEffect } from 'react';
import { useAchievementStore } from '@/stores/achievement';

export function AchievementsPage() {
  const { summary, fetchSummary, isLoading } = useAchievementStore();

  useEffect(() => {
    fetchSummary();
  }, [fetchSummary]);

  if (isLoading && !summary) {
    return (
      <div className="flex items-center justify-center h-full">
        <div className="text-muted-foreground">Loading...</div>
      </div>
    );
  }

  if (!summary) {
    return (
      <div className="flex items-center justify-center h-full">
        <div className="text-muted-foreground">No achievement data</div>
      </div>
    );
  }

  return (
    <div className="h-full overflow-y-auto p-6">
      <div className="max-w-5xl mx-auto">
        <h1 className="text-2xl font-bold mb-6">Achievements</h1>
        <p className="text-muted-foreground mb-4">
          Level: {summary.level} | Points: {summary.totalPoints} | Unlocked: {summary.totalUnlocked}/{summary.totalAchievements}
        </p>
        <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-4 gap-4">
          {summary.achievements.map((a) => (
            <div
              key={a.id}
              className="border rounded-lg p-4"
              style={{ opacity: a.unlockedAt ? 1 : 0.5 }}
            >
              <div className="text-2xl mb-2">{a.hidden ? '?' : a.icon}</div>
              <div className="text-sm font-medium">{a.nameKey}</div>
              <div className="text-xs text-muted-foreground">{a.descKey}</div>
              <div className="text-xs mt-1">
                {a.unlockedAt ? '✓ Unlocked' : `${a.currentValue}/${a.threshold}`}
              </div>
              <div className="text-xs text-muted-foreground mt-1">{a.tier} · {a.points} pts</div>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
