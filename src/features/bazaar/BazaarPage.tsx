// src/features/bazaar/BazaarPage.tsx
import { useEffect } from 'react';
import { useBazaarStore } from '@/stores/bazaar';
import { AgentStatusBar } from './AgentStatusBar';
import { TaskPanel } from './TaskPanel';
import { OnlineAgents } from './OnlineAgents';
import { ControlBar } from './ControlBar';
import { OnboardingGuide } from './OnboardingGuide';
import { ArrowLeft, Loader2 } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import { Button } from '@/components/ui/button';

export function BazaarPage() {
  const navigate = useNavigate();
  const { connection, fetchTasks, fetchOnlineAgents, loading } = useBazaarStore();

  useEffect(() => {
    fetchTasks();
    fetchOnlineAgents();
  }, [fetchTasks, fetchOnlineAgents]);

  // 未连接集市时显示配置提示
  if (!connection.connected) {
    return (
      <div className="flex flex-col items-center justify-center h-full gap-4 text-muted-foreground">
        <p>未连接到集市服务器</p>
        <p className="text-sm">请在「设置」中配置集市服务器地址</p>
        <Button variant="outline" onClick={() => navigate('/settings')}>
          前往设置
        </Button>
      </div>
    );
  }

  return (
    <div className="flex flex-col h-full">
      {/* 顶栏 */}
      <div className="flex items-center justify-between px-4 py-2 border-b">
        <div className="flex items-center gap-2">
          <Button variant="ghost" size="sm" onClick={() => navigate('/chat')}>
            <ArrowLeft className="h-4 w-4" />
          </Button>
          <h2 className="text-lg font-semibold">传送门</h2>
          {loading && <Loader2 className="h-4 w-4 animate-spin" />}
        </div>
      </div>

      {/* 主内容区 — 看板模式 */}
      <div className="flex-1 flex overflow-hidden">
        {/* 左侧：任务列表 */}
        <div className="w-1/2 border-r overflow-y-auto p-4">
          <TaskPanel />
        </div>

        {/* 右侧：在线 Agent + 控制栏 */}
        <div className="w-1/2 flex flex-col overflow-hidden">
          <div className="flex-1 overflow-y-auto p-4">
            <OnlineAgents />
          </div>
          <ControlBar />
        </div>
      </div>

      {/* 底部状态栏 */}
      <AgentStatusBar />

      {/* 首次引导 */}
      <OnboardingGuide />
    </div>
  );
}
