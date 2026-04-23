// src/features/stardom/StardomDashboard.tsx
// Agent 星图 — Collaboration Atlas
// 深色基底 + 网络沙盘 + 世界事件流 + 资源条 + 进化仓

import { useEffect, useState } from 'react';
import { useStardomStore } from '@/stores/stardom';
import { MyAgentPanel } from './components/MyAgentPanel';
import { ActivityFeed } from './components/ActivityFeed';
import { ControlPanel } from './components/ControlPanel';
import { ResourceBar } from './components/ResourceBar';
import { QuickBar } from './components/QuickBar';
import { NetworkSandbox } from './components/NetworkSandbox';
import { CapabilityTree } from './components/CapabilityTree';
import { TaskNotify } from './TaskNotify';
import { OnboardingGuide } from './OnboardingGuide';
import { Loader2, LayoutGrid, Network, Dna } from 'lucide-react';
import { cn } from '@/lib/utils';
import { Button } from '@/components/ui/button';

type ViewMode = 'immersive' | 'professional';
type SubView = 'main' | 'evolution';

export function StardomDashboard() {
  const { fetchTasks, fetchOnlineAgents, fetchLeaderboard, fetchCapabilities, loading, connection, tasks, onlineAgents } = useStardomStore();
  const [viewMode, setViewMode] = useState<ViewMode>('immersive');
  const [subView, setSubView] = useState<SubView>('main');

  useEffect(() => {
    fetchTasks();
    fetchOnlineAgents();
    fetchLeaderboard();
    fetchCapabilities();
  }, [fetchTasks, fetchOnlineAgents, fetchLeaderboard, fetchCapabilities]);

  return (
    <div className="stardom-theme flex h-full relative">
      <TaskNotify />

      {/* Left: Agent Panel */}
      <MyAgentPanel />

      {/* Center: Main viewport */}
      <div className="flex-1 flex flex-col overflow-hidden min-w-0">
        {/* Top: Resource bar */}
        <ResourceBar />

        {/* View mode toggle */}
        <div className="flex items-center justify-between px-3 py-1" style={{ borderBottom: '1px solid var(--bz-border)' }}>
          <div className="flex items-center gap-3">
            <button
              className={cn('text-xs font-medium px-2 py-0.5 rounded transition-colors', subView === 'main' && 'bg-white/10')}
              style={{ color: subView === 'main' ? 'var(--bz-cyan-glow)' : 'var(--bz-text-dim)' }}
              onClick={() => setSubView('main')}
            >
              协作星图
            </button>
            <button
              className={cn('text-xs font-medium px-2 py-0.5 rounded transition-colors flex items-center gap-1', subView === 'evolution' && 'bg-white/10')}
              style={{ color: subView === 'evolution' ? 'var(--bz-cyan-glow)' : 'var(--bz-text-dim)' }}
              onClick={() => setSubView('evolution')}
            >
              <Dna className="h-3 w-3" /> 进化仓
            </button>
          </div>
          {subView === 'main' && (
            <div className="flex gap-1">
              <Button
                variant="ghost"
                size="sm"
                className={cn('h-6 px-2 text-xs', viewMode === 'immersive' && 'bg-white/10')}
                onClick={() => setViewMode('immersive')}
                style={{ color: viewMode === 'immersive' ? 'var(--bz-cyan-glow)' : 'var(--bz-text-dim)' }}
              >
                <Network className="h-3 w-3 mr-1" />沉浸
              </Button>
              <Button
                variant="ghost"
                size="sm"
                className={cn('h-6 px-2 text-xs', viewMode === 'professional' && 'bg-white/10')}
                onClick={() => setViewMode('professional')}
                style={{ color: viewMode === 'professional' ? 'var(--bz-cyan-glow)' : 'var(--bz-text-dim)' }}
              >
                <LayoutGrid className="h-3 w-3 mr-1" />专业
              </Button>
            </div>
          )}
        </div>

        {/* Loading */}
        {loading && (
          <div className="flex items-center justify-center py-2" style={{ borderBottom: '1px solid var(--bz-border)' }}>
            <Loader2 className="h-4 w-4 animate-spin" style={{ color: 'var(--bz-cyan)' }} />
          </div>
        )}

        {/* Content */}
        {subView === 'evolution' ? (
          <CapabilityTree />
        ) : viewMode === 'immersive' ? (
          <div className="flex-1 flex flex-col overflow-hidden">
            <div className="h-1/2 min-h-[200px] relative">
              <NetworkSandbox />
            </div>
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
