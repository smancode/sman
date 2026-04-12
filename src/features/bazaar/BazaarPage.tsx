// src/features/bazaar/BazaarPage.tsx
import { useEffect, useRef, useState } from 'react';
import { useBazaarStore } from '@/stores/bazaar';
import { AgentStatusBar } from './AgentStatusBar';
import { TaskPanel } from './TaskPanel';
import { OnlineAgents } from './OnlineAgents';
import { LeaderboardPanel } from './LeaderboardPanel';
import { ControlBar } from './ControlBar';
import { CollaborationChat } from './CollaborationChat';
import { TaskNotify } from './TaskNotify';
import { OnboardingGuide } from './OnboardingGuide';
import { WorldCanvas } from './world/WorldCanvas';
import { WorldRenderer } from './world/WorldRenderer';
import type { ActivePanel } from './world/types';
import { ArrowLeft, Globe, LayoutDashboard, Loader2 } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import { Button } from '@/components/ui/button';

type ViewMode = 'world' | 'dashboard';

export function BazaarPage() {
  const navigate = useNavigate();
  const { connection, fetchTasks, fetchOnlineAgents, fetchLeaderboard, loading } = useBazaarStore();
  const rendererRef = useRef<WorldRenderer | null>(null);
  const [viewMode, setViewMode] = useState<ViewMode>('world');
  const [activePanel, setActivePanel] = useState<ActivePanel>('leaderboard');

  useEffect(() => {
    fetchTasks();
    fetchOnlineAgents();
    fetchLeaderboard();
  }, [fetchTasks, fetchOnlineAgents, fetchLeaderboard]);

  // 未连接集市时显示配置提示（但仍显示像素世界预览）
  if (!connection.connected) {
    return (
      <div className="flex flex-col h-full relative">
        {/* 像素世界预览（即使未连接也显示） */}
        <div className="flex-1 relative">
          <WorldCanvas rendererRef={rendererRef} onPanelChange={(panel) => setActivePanel(panel)} />
          <div className="absolute inset-0 flex flex-col items-center justify-center bg-black/60 gap-4 text-white">
            <p className="text-lg font-medium">未连接到集市服务器</p>
            <p className="text-sm text-white/70">请在「设置」中配置集市服务器地址</p>
            <Button variant="outline" className="text-white border-white/30 hover:bg-white/10" onClick={() => navigate('/settings')}>
              前往设置
            </Button>
          </div>
        </div>
        <AgentStatusBar />
        <OnboardingGuide />
      </div>
    );
  }

  return (
    <div className="flex flex-col h-full relative">
      {/* 顶栏 */}
      <div className="flex items-center justify-between px-4 py-2 border-b bg-background/80 backdrop-blur-sm z-10">
        <div className="flex items-center gap-2">
          <Button variant="ghost" size="sm" onClick={() => navigate('/chat')}>
            <ArrowLeft className="h-4 w-4" />
          </Button>
          <h2 className="text-lg font-semibold">传送门</h2>
          {loading && <Loader2 className="h-4 w-4 animate-spin" />}
        </div>

        {/* 视图模式切换 */}
        <div className="flex items-center gap-1 bg-muted rounded-lg p-0.5">
          <Button
            variant={viewMode === 'world' ? 'default' : 'ghost'}
            size="sm"
            className="h-7 px-3 text-xs gap-1"
            onClick={() => setViewMode('world')}
          >
            <Globe className="h-3.5 w-3.5" />
            世界
          </Button>
          <Button
            variant={viewMode === 'dashboard' ? 'default' : 'ghost'}
            size="sm"
            className="h-7 px-3 text-xs gap-1"
            onClick={() => setViewMode('dashboard')}
          >
            <LayoutDashboard className="h-3.5 w-3.5" />
            仪表盘
          </Button>
        </div>
      </div>

      {/* 协作请求通知浮层 */}
      <TaskNotify />

      {/* 主内容区 */}
      {viewMode === 'world' ? (
        // 世界模式：Canvas 60% + 信息面板 40%
        <div className="flex-1 flex overflow-hidden">
          {/* 左：像素世界 */}
          <div className="w-[60%] border-r">
            <WorldCanvas
              rendererRef={rendererRef}
              onPanelChange={(panel) => setActivePanel(panel)}
              onAgentClick={(agent) => { /* TODO: show agent info toast */ }}
            />
          </div>

          {/* 右：信息面板 */}
          <div className="w-[40%] flex flex-col overflow-hidden">
            <div className="flex-1 overflow-y-auto p-4 space-y-4">
              {activePanel === 'leaderboard' && <LeaderboardPanel />}
              {activePanel === 'tasks' && <TaskPanel />}
              {activePanel === 'chat' && <CollaborationChat />}
              {activePanel === 'agents' && <OnlineAgents />}
            </div>
            <ControlBar />
          </div>
        </div>
      ) : (
        // 仪表盘模式：原有三栏布局
        <div className="flex-1 flex overflow-hidden">
          <div className="w-1/3 border-r overflow-y-auto p-4">
            <TaskPanel />
          </div>
          <div className="w-1/3 border-r overflow-hidden">
            <CollaborationChat />
          </div>
          <div className="w-1/3 flex flex-col overflow-hidden">
            <div className="flex-1 overflow-y-auto p-4 space-y-4">
              <LeaderboardPanel />
              <OnlineAgents />
            </div>
            <ControlBar />
          </div>
        </div>
      )}

      {/* 底部状态栏 */}
      <AgentStatusBar />

      {/* 首次引导 */}
      <OnboardingGuide />
    </div>
  );
}
