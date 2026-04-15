// src/features/bazaar/components/MyAgentPanel.tsx
// 左侧固定面板 — 用户自己的 Agent 身份和状态
// 状态优先级：本地 Claude sending > 集市协作 > 集市连接

import { useBazaarStore } from '@/stores/bazaar';
import { useChatStore } from '@/stores/chat';
import { useNavigate } from 'react-router-dom';
import { ScrollArea } from '@/components/ui/scroll-area';
import { Separator } from '@/components/ui/separator';
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group';
import { Label } from '@/components/ui/label';
import { Button } from '@/components/ui/button';
import { Wifi, WifiOff, Trophy, Users, ArrowLeft, Loader2 } from 'lucide-react';

function deriveAgentStatus(
  localSending: boolean,
  bazaarConnected: boolean,
  bazaarStatus?: string,
): { status: string; label: string; color: string } {
  // 优先级1: 本地 Claude 正在处理
  if (localSending) {
    return { status: 'busy', label: '处理中', color: 'bg-yellow-500' };
  }
  // 优先级2: 集市协作状态
  if (bazaarConnected && bazaarStatus === 'collaborating') {
    return { status: 'collaborating', label: '协作中', color: 'bg-blue-500' };
  }
  if (bazaarConnected && bazaarStatus === 'busy') {
    return { status: 'busy', label: '忙碌', color: 'bg-yellow-500' };
  }
  // 优先级3: 空闲
  return { status: 'idle', label: '空闲', color: 'bg-green-500' };
}

export function MyAgentPanel() {
  const navigate = useNavigate();
  const { connection, setMode, leaderboard } = useBazaarStore();
  const sending = useChatStore((s) => s.sending);
  const { connected, agentName, reputation, agentStatus } = connection;
  const agentAvatar = connection.agentId ? '🧙' : '🤖';
  const myRank = leaderboard.findIndex((e) => e.agentId === connection.agentId) + 1;
  const helpCount = leaderboard.find((e) => e.agentId === connection.agentId)?.helpCount ?? 0;

  const derived = deriveAgentStatus(sending, connected, agentStatus);

  return (
    <div className="w-[200px] flex-shrink-0 border-r bg-background/80 backdrop-blur-sm flex flex-col">
      {/* Header: back + avatar + name */}
      <div className="p-3 space-y-2">
        <div className="flex items-center gap-2">
          <Button variant="ghost" size="sm" className="h-7 w-7 p-0 flex-shrink-0" onClick={() => navigate('/chat')}>
            <ArrowLeft className="h-4 w-4" />
          </Button>
          <span className="text-2xl">{agentAvatar}</span>
          <div className="min-w-0 flex-1">
            <div className="font-medium text-sm truncate">{agentName ?? '本地 Agent'}</div>
            <div className="flex items-center gap-1 text-xs text-muted-foreground">
              {connected ? (
                <><Wifi className="h-3 w-3 text-green-500" /> 已连接集市</>
              ) : (
                <><WifiOff className="h-3 w-3" /> 离线模式</>
              )}
            </div>
          </div>
        </div>
      </div>

      <Separator />

      <ScrollArea className="flex-1">
        <div className="p-3 space-y-4">
          {/* Status — 本地状态优先 */}
          <div className="space-y-1">
            <div className="text-xs text-muted-foreground">状态</div>
            <div className="flex items-center gap-1.5">
              {sending ? (
                <Loader2 className="h-3 w-3 animate-spin text-yellow-500" />
              ) : (
                <span className={`inline-block w-2 h-2 rounded-full ${derived.color}`} />
              )}
              <span className="text-sm">{derived.label}</span>
            </div>
          </div>

          {/* Reputation — 集市才有 */}
          {connected && (
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
          )}

          {/* Collaboration Mode — 集市才有 */}
          {connected && (
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
          )}

          {connected && <Separator />}

          {/* Stats */}
          {connected && (
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
          )}
        </div>
      </ScrollArea>
    </div>
  );
}
