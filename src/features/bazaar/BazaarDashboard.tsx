// src/features/bazaar/BazaarDashboard.tsx
// Agent 集市 — 硬核科技风指挥中心
// 深色基底 + 网络沙盘 + 世界事件流 + 资源条

import { useEffect, useState } from 'react';
import { useBazaarStore } from '@/stores/bazaar';
import { MyAgentPanel } from './components/MyAgentPanel';
import { ActivityFeed } from './components/ActivityFeed';
import { ControlPanel } from './components/ControlPanel';
import { ResourceBar } from './components/ResourceBar';
import { QuickBar } from './components/QuickBar';
import { NetworkSandbox } from './components/NetworkSandbox';
import { TaskNotify } from './TaskNotify';
import { OnboardingGuide } from './OnboardingGuide';
import { Loader2, LayoutGrid, Network } from 'lucide-react';
import { cn } from '@/lib/utils';
import { Button } from '@/components/ui/button';

type ViewMode = 'immersive' | 'professional';

export function BazaarDashboard() {
  const { fetchTasks, fetchOnlineAgents, fetchLeaderboard, loading, connection, tasks, onlineAgents } = useBazaarStore();
  const [viewMode, setViewMode] = useState<ViewMode>('immersive');

  useEffect(() => {
    fetchTasks();
    fetchOnlineAgents();
    fetchLeaderboard();
  }, [fetchTasks, fetchOnlineAgents, fetchLeaderboard]);

  return (
    <div className="bazaar-dark flex h-full relative" style={{ background: 'var(--bz-bg)', color: 'var(--bz-text)' }}>
      <TaskNotify />

      {/* Left: Agent Panel */}
      <MyAgentPanel />

      {/* Center: Main viewport */}
      <div className="flex-1 flex flex-col overflow-hidden min-w-0">
        {/* Top: Resource bar */}
        <ResourceBar />

        {/* View mode toggle */}
        <div className="flex items-center justify-between px-3 py-1" style={{ borderBottom: '1px solid var(--bz-border)' }}>
          <div className="text-xs font-medium" style={{ color: 'var(--bz-text-dim)' }}>
            {viewMode === 'immersive' ? '沉浸指挥模式' : '专业分析模式'}
          </div>
          <div className="flex gap-1">
            <Button
              variant="ghost"
              size="sm"
              className={cn(
                'h-6 px-2 text-xs',
                viewMode === 'immersive' && 'bg-white/10',
              )}
              onClick={() => setViewMode('immersive')}
              style={{ color: viewMode === 'immersive' ? 'var(--bz-cyan-glow)' : 'var(--bz-text-dim)' }}
            >
              <Network className="h-3 w-3 mr-1" />沉浸
            </Button>
            <Button
              variant="ghost"
              size="sm"
              className={cn(
                'h-6 px-2 text-xs',
                viewMode === 'professional' && 'bg-white/10',
              )}
              onClick={() => setViewMode('professional')}
              style={{ color: viewMode === 'professional' ? 'var(--bz-cyan-glow)' : 'var(--bz-text-dim)' }}
            >
              <LayoutGrid className="h-3 w-3 mr-1" />专业
            </Button>
          </div>
        </div>

        {/* Loading */}
        {loading && (
          <div className="flex items-center justify-center py-2" style={{ borderBottom: '1px solid var(--bz-border)' }}>
            <Loader2 className="h-4 w-4 animate-spin" style={{ color: 'var(--bz-cyan)' }} />
          </div>
        )}

        {/* Main content area */}
        {viewMode === 'immersive' ? (
          <div className="flex-1 flex flex-col overflow-hidden">
            {/* Network sandbox (top half) */}
            <div className="h-1/2 min-h-[200px] relative">
              <NetworkSandbox />
            </div>
            {/* Activity feed (bottom half) */}
            <div className="h-1/2 border-t" style={{ borderColor: 'var(--bz-border)' }}>
              <ActivityFeed />
            </div>
          </div>
        ) : (
          <ActivityFeed />
        )}

        {/* Bottom: Quick bar */}
        <QuickBar />
      </div>

      {/* Right: Control panel */}
      <ControlPanel />
      <OnboardingGuide />
    </div>
  );
}
