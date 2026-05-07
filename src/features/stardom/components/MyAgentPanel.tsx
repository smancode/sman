// src/features/stardom/components/MyAgentPanel.tsx
// 左侧固定面板 — 深色科技风
// 状态指示灯 pulse 动效 + 声望等级系统

import { useStardomStore } from '@/stores/stardom';
import { useChatStore } from '@/stores/chat';
import { useNavigate } from 'react-router-dom';
import { ScrollArea } from '@/components/ui/scroll-area';
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group';
import { Label } from '@/components/ui/label';
import { Button } from '@/components/ui/button';
import { Wifi, WifiOff, Trophy, Users, ArrowLeft, Loader2 } from 'lucide-react';
import { getReputationLevel, getReputationProgress, getNextLevel } from './ReputationUtils';
import { t } from '@/locales';

function deriveAgentStatus(
  localSending: boolean,
  stardomConnected: boolean,
  stardomStatus?: string,
): { status: string; label: string; cssClass: string } {
  if (localSending) return { status: 'busy', label: t('stardom.myAgent.processing'), cssClass: 'bz-status-busy' };
  if (stardomConnected && stardomStatus === 'collaborating') return { status: 'collaborating', label: t('stardom.myAgent.collaborating'), cssClass: 'bz-status-collaborating' };
  if (stardomConnected && stardomStatus === 'busy') return { status: 'busy', label: t('stardom.online.busy'), cssClass: 'bz-status-busy' };
  return { status: 'idle', label: t('stardom.online.idle'), cssClass: 'bz-status-idle' };
}

export function MyAgentPanel() {
  const navigate = useNavigate();
  const { connection, setMode, leaderboard } = useStardomStore();
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
          <button className="h-7 w-7 p-0 flex-shrink-0 rounded hover:bg-white/10" onClick={() => navigate('/chat')} style={{ color: 'var(--bz-text-dim)' }}>
            <ArrowLeft className="h-4 w-4" />
          </button>
          <img src="/favicon.svg" alt="Agent" className="h-7 w-7 object-contain rounded dark:brightness-0 dark:invert dark:opacity-80" />
          <div className="min-w-0 flex-1">
            <div className="font-medium text-sm truncate" style={{ color: 'var(--bz-text)' }}>{agentName ?? t('stardom.myAgent.localAgent')}</div>
            <div className="flex items-center gap-1 text-xs" style={{ color: connected ? 'var(--bz-green)' : 'var(--bz-amber)' }}>
              {connected ? <><Wifi className="h-3 w-3" /> {t('stardom.myAgent.connectedMap')}</> : <><WifiOff className="h-3 w-3" /> {t('stardom.myAgent.localMode2')}</>}
            </div>
          </div>
        </div>
      </div>

      <div style={{ borderTop: '1px solid var(--bz-border)' }} />

      <ScrollArea className="flex-1">
        <div className="p-3 space-y-4">
          {/* Status indicator with pulse */}
          <div className="space-y-1.5">
            <div className="text-[10px] uppercase tracking-wider" style={{ color: 'var(--bz-text-dim)' }}>{t('stardom.myAgent.nodeStatus')}</div>
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
              {t('stardom.myAgent.depositTitle')} · {repLevel.title}
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
                  {t('stardom.myAgent.needMore').replace('{title}', nextLevel.title).replace('{count}', String(nextLevel.minRep - (reputation ?? 0)))}
                </div>
              </div>
            )}
          </div>

          {/* Collaboration Mode */}
          {connected && (
            <div className="space-y-2">
              <div className="text-[10px] uppercase tracking-wider" style={{ color: 'var(--bz-text-dim)' }}>{t('stardom.myAgent.strategy')}</div>
              <RadioGroup
                value={connection.collabMode ?? 'notify'}
                onValueChange={(v) => setMode(v as 'auto' | 'notify' | 'manual')}
                className="space-y-1"
              >
                {(['auto', 'notify', 'manual'] as const).map((mode) => (
                  <div key={mode} className="flex items-center gap-2">
                    <RadioGroupItem value={mode} id={`mode-${mode}`} className="h-3 w-3" style={{ borderColor: 'var(--bz-border)' }} />
                    <Label htmlFor={`mode-${mode}`} className="text-xs cursor-pointer" style={{ color: 'var(--bz-text)' }}>
                      {mode === 'auto' ? t('stardom.myAgent.autoFull') : mode === 'notify' ? t('stardom.myAgent.semiAuto') : t('stardom.myAgent.manual')}
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
                  <Users className="h-3 w-3" /> {t('stardom.myAgent.collabCount')}
                </span>
                <span className="font-mono" style={{ color: 'var(--bz-cyan)' }}>{helpCount}</span>
              </div>
              {myRank > 0 && (
                <div className="flex items-center justify-between text-xs">
                  <span style={{ color: 'var(--bz-text-dim)' }}>{t('stardom.myAgent.depositRank')}</span>
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
