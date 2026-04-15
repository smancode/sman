// src/features/bazaar/components/MyAgentPanel.tsx
// 左侧固定面板 — 用户自己的 Agent 身份和状态

import { useBazaarStore } from '@/stores/bazaar';
import { ScrollArea } from '@/components/ui/scroll-area';
import { Separator } from '@/components/ui/separator';
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group';
import { Label } from '@/components/ui/label';
import { Wifi, WifiOff, Trophy, Users, ArrowUp, ArrowDown, Minus } from 'lucide-react';

const STATUS_COLORS: Record<string, string> = {
  idle: 'bg-green-500',
  busy: 'bg-yellow-500',
  collaborating: 'bg-blue-500',
  afk: 'bg-gray-500',
  offline: 'bg-gray-400',
};

const STATUS_LABELS: Record<string, string> = {
  idle: '空闲',
  busy: '忙碌',
  collaborating: '协作中',
  afk: '离开',
  offline: '离线',
};

export function MyAgentPanel() {
  const { connection, setMode, leaderboard } = useBazaarStore();
  const { connected, agentName, reputation, agentStatus } = connection;
  const agentAvatar = connection.agentId ? '🧙' : '👤';
  const myRank = leaderboard.findIndex((e) => e.agentId === connection.agentId) + 1;
  const helpCount = leaderboard.find((e) => e.agentId === connection.agentId)?.helpCount ?? 0;

  return (
    <div className="w-[200px] flex-shrink-0 border-r bg-background/80 backdrop-blur-sm flex flex-col">
      {/* Header: avatar + name */}
      <div className="p-3 space-y-2">
        <div className="flex items-center gap-2">
          <span className="text-2xl">{agentAvatar}</span>
          <div className="min-w-0 flex-1">
            <div className="font-medium text-sm truncate">{agentName ?? '未连接'}</div>
            <div className="flex items-center gap-1 text-xs text-muted-foreground">
              {connected ? (
                <><Wifi className="h-3 w-3 text-green-500" /> 已连接</>
              ) : (
                <><WifiOff className="h-3 w-3" /> 未连接</>
              )}
            </div>
          </div>
        </div>
      </div>

      <Separator />

      <ScrollArea className="flex-1">
        <div className="p-3 space-y-4">
          {/* Reputation */}
          <div className="space-y-1">
            <div className="flex items-center gap-1 text-xs text-muted-foreground">
              <Trophy className="h-3 w-3" style={{ color: '#E8C460' }} />
              声望
            </div>
            <div className="flex items-baseline gap-1">
              <span className="text-lg font-mono font-bold" style={{ color: '#E8C460' }}>
                {reputation ?? 0}
              </span>
              {myRank > 0 && (
                <span className="text-xs text-muted-foreground">#{myRank}</span>
              )}
            </div>
          </div>

          {/* Status */}
          <div className="space-y-1">
            <div className="text-xs text-muted-foreground">状态</div>
            <div className="flex items-center gap-1.5">
              <span className={`inline-block w-2 h-2 rounded-full ${STATUS_COLORS[agentStatus ?? 'offline']}`} />
              <span className="text-sm">{STATUS_LABELS[agentStatus ?? 'offline']}</span>
            </div>
          </div>

          {/* Collaboration Mode */}
          <div className="space-y-2">
            <div className="text-xs text-muted-foreground">协作模式</div>
            <RadioGroup
              value={connection.collabMode ?? 'notify'}
              onValueChange={(v) => setMode(v as 'auto' | 'notify' | 'manual')}
              className="space-y-1"
            >
              <div className="flex items-center gap-2">
                <RadioGroupItem value="auto" id="mode-auto" className="h-3 w-3" />
                <Label htmlFor="mode-auto" className="text-xs cursor-pointer">全自动</Label>
              </div>
              <div className="flex items-center gap-2">
                <RadioGroupItem value="notify" id="mode-notify" className="h-3 w-3" />
                <Label htmlFor="mode-notify" className="text-xs cursor-pointer">半自动 30s</Label>
              </div>
              <div className="flex items-center gap-2">
                <RadioGroupItem value="manual" id="mode-manual" className="h-3 w-3" />
                <Label htmlFor="mode-manual" className="text-xs cursor-pointer">手动</Label>
              </div>
            </RadioGroup>
          </div>

          <Separator />

          {/* Stats */}
          <div className="space-y-1.5">
            <div className="flex items-center justify-between text-xs">
              <span className="text-muted-foreground flex items-center gap-1">
                <Users className="h-3 w-3" /> 协作次数
              </span>
              <span className="font-mono">{helpCount}</span>
            </div>
            {myRank > 0 && (
              <div className="flex items-center justify-between text-xs">
                <span className="text-muted-foreground">排名</span>
                <span className="font-mono">#{myRank}</span>
              </div>
            )}
          </div>
        </div>
      </ScrollArea>
    </div>
  );
}
