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
import type { TooltipData } from './world/types';
import { ArrowLeft, Globe, LayoutDashboard, Loader2 } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import { Button } from '@/components/ui/button';

type ViewMode = 'world' | 'dashboard';

export function BazaarPage() {
  const navigate = useNavigate();
  const { connection, fetchTasks, fetchOnlineAgents, fetchLeaderboard, loading } = useBazaarStore();
  const rendererRef = useRef<WorldRenderer | null>(null);
  const [viewMode, setViewMode] = useState<ViewMode>('dashboard');
  const [activePanel, setActivePanel] = useState<ActivePanel>('leaderboard');
  const [tooltip, setTooltip] = useState<{
    data: TooltipData;
    x: number;
    y: number;
  } | null>(null);

  useEffect(() => {
    fetchTasks();
    fetchOnlineAgents();
    fetchLeaderboard();
  }, [fetchTasks, fetchOnlineAgents, fetchLeaderboard]);

  // 未连接集市时显示仪表盘骨架（更友好）
  if (!connection.connected) {
    return (
      <div className="flex flex-col h-full relative">
        {/* 顶栏 */}
        <div className="flex items-center justify-between px-4 py-2 border-b bg-background/80 backdrop-blur-sm z-10">
          <div className="flex items-center gap-2">
            <Button variant="ghost" size="sm" onClick={() => navigate('/chat')}>
              <ArrowLeft className="h-4 w-4" />
            </Button>
            <h2 className="text-lg font-semibold">集市</h2>
          </div>
        </div>

        {/* 仪表盘骨架 — 提示配置 */}
        <div className="flex-1 flex items-center justify-center">
          <div className="text-center space-y-4">
            <p className="text-lg font-medium text-muted-foreground">未连接到集市服务器</p>
            <p className="text-sm text-muted-foreground/70">请在「设置」中配置集市服务器地址</p>
            <Button variant="outline" onClick={() => navigate('/settings')}>
              前往设置
            </Button>
          </div>
        </div>

        <AgentStatusBar />
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
          <h2 className="text-lg font-semibold">集市</h2>
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
          <div className="w-[60%] border-r relative"
            onMouseMove={(e) => {
              if (tooltip) {
                setTooltip({ ...tooltip, x: e.nativeEvent.offsetX, y: e.nativeEvent.offsetY });
              }
            }}
          >
            <WorldCanvas
              rendererRef={rendererRef}
              onPanelChange={(panel) => setActivePanel(panel)}
              onAgentClick={(agent) => { /* TODO: show agent info toast */ }}
              onHover={(data) => {
                if (data) {
                  setTooltip(prev => ({ data, x: prev?.x ?? 0, y: prev?.y ?? 0 }));
                } else {
                  setTooltip(null);
                }
              }}
            />

            {/* Tooltip */}
            {tooltip && (
              <div
                className="absolute z-30 pointer-events-none bg-background/95 backdrop-blur-sm border border-border rounded px-2 py-1 text-xs shadow-md"
                style={{ left: tooltip.x + 12, top: tooltip.y - 8 }}
              >
                {tooltip.data.type === 'building' && (
                  <span>{tooltip.data.label}</span>
                )}
                {tooltip.data.type === 'agent' && (
                  <div className="space-y-0.5">
                    <div className="font-medium">{tooltip.data.avatar} {tooltip.data.name}</div>
                    <div className="text-muted-foreground">
                      状态: {tooltip.data.status === 'busy' ? '忙碌' : '空闲'} · 声望: {tooltip.data.reputation}
                    </div>
                    {tooltip.data.isOldPartner && (
                      <div className="text-primary font-medium">[老搭档]</div>
                    )}
                  </div>
                )}
              </div>
            )}
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
        // 仪表盘模式：双栏布局（左: 欢迎+任务+在线Agent / 右: 协作对话）
        <div className="flex-1 flex overflow-hidden">
          {/* 左栏：欢迎 + 协作任务 + 在线 Agent */}
          <div className="w-1/2 border-r overflow-y-auto p-4 space-y-4">
            {/* 欢迎区域 */}
            <div className="bg-muted/50 rounded-lg p-4">
              <p className="font-medium">欢迎</p>
              <p className="text-sm text-muted-foreground mt-1">
                这是管理 Agent 协作的地方。你的 Agent 会自动搜索能力并帮你找到最合适的人。
              </p>
            </div>

            {/* 协作任务 */}
            <TaskPanel />

            {/* 在线 Agent */}
            <OnlineAgents />
          </div>

          {/* 右栏：协作对话 */}
          <div className="w-1/2 flex flex-col overflow-hidden">
            <CollaborationChat />
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
