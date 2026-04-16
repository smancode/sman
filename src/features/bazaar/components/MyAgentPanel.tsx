// src/features/bazaar/components/MyAgentPanel.tsx
// 左侧固定面板 — 深色科技风
// 状态指示灯 pulse 动效 + 声望等级系统

import { useBazaarStore } from '@/stores/bazaar';
import { useChatStore } from '@/stores/chat';
import { useNavigate } from 'react-router-dom';
import { ScrollArea } from '@/components/ui/scroll-area';
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group';
import { Label } from '@/components/ui/label';
import { Button } from '@/components/ui/button';
import { Wifi, WifiOff, Trophy, Users, Loader2 } from 'lucide-react';
import { getReputationLevel, getReputationProgress, getNextLevel } from './ReputationUtils';

function deriveAgentStatus(
  localSending: boolean,
  bazaarConnected: boolean,
  bazaarStatus?: string,
): { status: string; label: string; cssClass: string } {
  if (localSending) return { status: 'busy', label: '处理中', cssClass: 'bz-status-busy' };
  if (bazaarConnected && bazaarStatus === 'collaborating') return { status: 'collaborating', label: '协作中', cssClass: 'bz-status-collaborating' };
  if (bazaarConnected && bazaarStatus === 'busy') return { status: 'busy', label: '忙碌', cssClass: 'bz-status-busy' };
  return { status: 'idle', label: '空闲', cssClass: 'bz-status-idle' };
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

  const repLevel = getReputationLevel(reputation ?? 0);
  const repProgress = getReputationProgress(reputation ?? 0);
  const nextLevel = getNextLevel(reputation ?? 0);

  return (
    <div className="w-[200px] flex-shrink-0 flex flex-col" style={{ background: 'var(--bz-bg-panel)', borderRight: '1px solid var(--bz-border)' }}>
      {/* Header */}
      <div className="p-3 space-y-2">
        <div className="flex items-center gap-2">
          <button className="h-7 w-7 p-0 flex-shrink-0 rounded hover:opacity-80 transition-opacity" onClick={() => navigate('/chat')}>
            <img src="/favicon.svg" alt="Sman" className="h-7 w-7 object-contain" />
          </button>
          <span className="text-2xl">{agentAvatar}</span>
          <div className="min-w-0 flex-1">
            <div className="font-medium text-sm truncate" style={{ color: 'var(--bz-text)' }}>{agentName ?? '本地 Agent'}</div>
            <div className="flex items-center gap-1 text-xs" style={{ color: connected ? 'var(--bz-green)' : 'var(--bz-amber)' }}>
              {connected ? <><Wifi className="h-3 w-3" /> 已连接星图</> : <><WifiOff className="h-3 w-3" /> 本地模式</>}
            </div>
          </div>
        </div>
      </div>

      <div style={{ borderTop: '1px solid var(--bz-border)' }} />

      <ScrollArea className="flex-1">
        <div className="p-3 space-y-4">
          {/* Status indicator with pulse */}
          <div className="space-y-1.5">
            <div className="text-[10px] uppercase tracking-wider" style={{ color: 'var(--bz-text-dim)' }}>节点状态</div>
            <div className="flex items-center gap-2">
              {sending ? (
                <Loader2 className="h-3.5 w-3.5 animate-spin" style={{ color: 'var(--bz-amber)' }} />
              ) : (
                <span className={`inline-block w-2.5 h-2.5 rounded-full ${derived.cssClass}`} />
              )}
              <span className="text-sm font-medium">{derived.label}</span>
            </div>
          </div>

          {/* Reputation + Level */}
          <div className="space-y-1.5">
            <div className="flex items-center gap-1 text-[10px] uppercase tracking-wider" style={{ color: 'var(--bz-text-dim)' }}>
              <Trophy className="h-3 w-3" style={{ color: 'var(--bz-amber)' }} />
              贡献沉积 · {repLevel.title}
            </div>
            <div className="flex items-baseline gap-2">
              <span className="text-lg font-mono font-bold" style={{ color: repLevel.color, textShadow: `0 0 8px ${repLevel.glow}` }}>
                {repLevel.icon} {reputation ?? 0}
              </span>
              {myRank > 0 && (
                <span className="text-xs font-mono" style={{ color: 'var(--bz-text-dim)' }}>#{myRank}</span>
              )}
            </div>
            {/* Level progress bar */}
            {nextLevel && (
              <div className="space-y-0.5">
                <div className="w-full h-1 rounded-full overflow-hidden" style={{ background: 'var(--bz-bg)' }}>
                  <div
                    className="h-full rounded-full transition-all duration-700"
                    style={{ width: `${repProgress}%`, background: repLevel.color, boxShadow: `0 0 6px ${repLevel.glow}` }}
                  />
                </div>
                <div className="text-[9px]" style={{ color: 'var(--bz-text-dim)' }}>
                  距{nextLevel.title}还需 {nextLevel.minRep - (reputation ?? 0)}
                </div>
              </div>
            )}
          </div>

          {/* Collaboration Mode */}
          {connected && (
            <div className="space-y-2">
              <div className="text-[10px] uppercase tracking-wider" style={{ color: 'var(--bz-text-dim)' }}>响应策略</div>
              <RadioGroup
                value={connection.collabMode ?? 'notify'}
                onValueChange={(v) => setMode(v as 'auto' | 'notify' | 'manual')}
                className="space-y-1"
              >
                {(['auto', 'notify', 'manual'] as const).map((mode) => (
                  <div key={mode} className="flex items-center gap-2">
                    <RadioGroupItem value={mode} id={`mode-${mode}`} className="h-3 w-3" style={{ borderColor: 'var(--bz-border)' }} />
                    <Label htmlFor={`mode-${mode}`} className="text-xs cursor-pointer" style={{ color: 'var(--bz-text)' }}>
                      {mode === 'auto' ? '全自动' : mode === 'notify' ? '半自动 30s' : '手动'}
                    </Label>
                  </div>
                ))}
              </RadioGroup>
            </div>
          )}

          {/* Stats */}
          {connected && (
            <div className="space-y-1.5">
              <div className="flex items-center justify-between text-xs">
                <span style={{ color: 'var(--bz-text-dim)' }} className="flex items-center gap-1">
                  <Users className="h-3 w-3" /> 协作次数
                </span>
                <span className="font-mono" style={{ color: 'var(--bz-cyan)' }}>{helpCount}</span>
              </div>
              {myRank > 0 && (
                <div className="flex items-center justify-between text-xs">
                  <span style={{ color: 'var(--bz-text-dim)' }}>沉积排名</span>
                  <span className="font-mono" style={{ color: 'var(--bz-cyan)' }}>#{myRank}</span>
                </div>
              )}
            </div>
          )}
        </div>
      </ScrollArea>
    </div>
  );
}
