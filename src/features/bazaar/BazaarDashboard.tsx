// src/features/bazaar/BazaarDashboard.tsx
// Agent 集市 Dashboard — 三栏布局
// 左: MyAgent | 中: ActivityFeed | 右: ControlPanel

import { useEffect } from 'react';
import { useBazaarStore } from '@/stores/bazaar';
import { MyAgentPanel } from './components/MyAgentPanel';
import { ActivityFeed } from './components/ActivityFeed';
import { ControlPanel } from './components/ControlPanel';
import { TaskNotify } from './TaskNotify';
import { OnboardingGuide } from './OnboardingGuide';
import { Loader2 } from 'lucide-react';

export function BazaarDashboard() {
  const { fetchTasks, fetchOnlineAgents, fetchLeaderboard, loading } = useBazaarStore();

  useEffect(() => {
    fetchTasks();
    fetchOnlineAgents();
    fetchLeaderboard();
  }, [fetchTasks, fetchOnlineAgents, fetchLeaderboard]);

  return (
    <div className="flex h-full relative">
      {/* Collaboration request notifications overlay */}
      <TaskNotify />

      {/* Left: My Agent fixed panel */}
      <MyAgentPanel />

      {/* Center: Activity feed */}
      <div className="flex-1 flex flex-col overflow-hidden">
        {loading && (
          <div className="flex items-center justify-center py-2 border-b border-border/50">
            <Loader2 className="h-4 w-4 animate-spin text-muted-foreground" />
          </div>
        )}
        <ActivityFeed />
      </div>

      {/* Right: Control panel */}
      <ControlPanel />

      {/* First-time onboarding */}
      <OnboardingGuide />
    </div>
  );
}
