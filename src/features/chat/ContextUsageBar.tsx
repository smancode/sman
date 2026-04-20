import { useMemo, useState, useEffect, useCallback, useRef } from 'react';
import { useChatStore } from '@/stores/chat';
import { useSettingsStore } from '@/stores/settings';
import {
  Tooltip,
  TooltipContent,
  TooltipTrigger,
} from '@/components/ui/tooltip';
import { GitBranch } from 'lucide-react';

// ── Global git branch refresh trigger ──
// Any component can call `triggerGitBranchRefresh()` to request a re-fetch.
let refreshCallback: (() => void) | null = null;

export function triggerGitBranchRefresh() {
  refreshCallback?.();
}

function useGitBranch() {
  const currentSessionId = useChatStore((s) => s.currentSessionId);
  const sessions = useChatStore((s) => s.sessions);

  const [gitBranch, setGitBranch] = useState<string | null>(null);
  const workspaceRef = useRef<string | undefined>(undefined);

  const workspace = useMemo(() => {
    const session = sessions.find((s) => s.key === currentSessionId);
    return session?.workspace;
  }, [sessions, currentSessionId]);

  // Keep ref in sync so the global callback always uses latest workspace
  useEffect(() => {
    workspaceRef.current = workspace;
  }, [workspace]);

  const fetchBranch = useCallback(() => {
    const ws = workspaceRef.current;
    if (!ws || !window.sman?.getGitBranch) {
      setGitBranch(null);
      return;
    }
    window.sman.getGitBranch(ws).then((branch) => {
      setGitBranch(branch);
    });
  }, []);

  // Fetch on workspace change (new session / session switch)
  useEffect(() => {
    fetchBranch();
  }, [workspace, fetchBranch]);

  // Refresh on window focus
  useEffect(() => {
    const onFocus = () => fetchBranch();
    window.addEventListener('focus', onFocus);
    return () => window.removeEventListener('focus', onFocus);
  }, [fetchBranch]);

  // Register global refresh callback
  useEffect(() => {
    refreshCallback = fetchBranch;
    return () => {
      if (refreshCallback === fetchBranch) refreshCallback = null;
    };
  }, [fetchBranch]);

  return gitBranch;
}

// ── Git Branch Indicator (independent from context usage) ──

function GitBranchIndicator({ branch }: { branch: string | null }) {
  if (!branch) return null;

  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <div className="flex items-center gap-1 text-[11px] font-medium text-muted-foreground">
          <GitBranch className="w-3 h-3" />
          <span className="truncate max-w-[120px]">{branch}</span>
        </div>
      </TooltipTrigger>
      <TooltipContent side="top">
        <p className="text-xs">Git branch: {branch}</p>
      </TooltipContent>
    </Tooltip>
  );
}

// ── Context Usage Bar ──

const DEFAULT_MAX_INPUT_TOKENS = 200_000;
const BAR_WIDTH = 10;

function getMaxInputTokensByModel(model: string): number {
  const lower = model.toLowerCase();
  if (lower.includes('claude')) return 200_000;
  if (lower.includes('gpt-3')) return 4_096;
  if (lower.includes('qwen-vl') || lower.includes('qwen-vision')) return 32_000;
  if (lower.includes('llava') || lower.includes('bakllava') || lower.includes('minicpm-v')) return 4_096;
  return DEFAULT_MAX_INPUT_TOKENS;
}

function getMaxInputTokens(model: string, capabilities?: { maxInputTokens?: number } | null): number {
  if (capabilities?.maxInputTokens) {
    return capabilities.maxInputTokens;
  }
  return getMaxInputTokensByModel(model);
}

function formatTokens(n: number): string {
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`;
  if (n >= 1_000) return `${(n / 1_000).toFixed(0)}K`;
  return String(n);
}

function getColorClass(pct: number): string {
  if (pct > 75) return 'text-red-500';
  if (pct >= 50) return 'text-amber-500';
  return 'text-green-500';
}

function renderBar(percentage: number, width: number): string {
  const filled = Math.round((percentage / 100) * width);
  const empty = width - filled;
  return '█'.repeat(filled) + '░'.repeat(empty);
}

export function ContextUsageBar() {
  const gitBranch = useGitBranch();

  const contextUsage = useChatStore((s) => s.contextUsage);
  const settings = useSettingsStore((s) => s.settings);

  const { percentage, colorClass, inputTokens, maxTokens, barText } = useMemo(() => {
    if (!contextUsage || contextUsage.inputTokens <= 0) {
      return {
        percentage: 0,
        colorClass: 'text-green-500',
        inputTokens: 0,
        maxTokens: DEFAULT_MAX_INPUT_TOKENS,
        barText: renderBar(0, BAR_WIDTH),
      };
    }

    const model = settings?.llm?.model || '';
    const capabilities = settings?.llm?.capabilities;
    const max = getMaxInputTokens(model, capabilities);
    const pct = Math.min(100, Math.round((contextUsage.inputTokens / max) * 100));

    return {
      percentage: pct,
      colorClass: getColorClass(pct),
      inputTokens: contextUsage.inputTokens,
      maxTokens: max,
      barText: renderBar(pct, BAR_WIDTH),
    };
  }, [contextUsage, settings]);

  const hasContext = contextUsage != null && inputTokens > 0;
  if (!gitBranch && !hasContext) {
    return null;
  }

  return (
    <div className="flex items-center justify-end gap-3 text-xs select-none cursor-default pb-1 px-2">
      <GitBranchIndicator branch={gitBranch} />
      {gitBranch && hasContext && (
        <span className="text-muted-foreground/30">·</span>
      )}
      {hasContext && (
        <Tooltip>
          <TooltipTrigger asChild>
            <div className="flex items-center gap-1.5">
              <span className="text-[11px] font-medium text-muted-foreground">Context</span>
              <span className={`text-[11px] font-medium ${colorClass}`}>{barText}</span>
              <span className={`text-[11px] font-medium tabular-nums w-7 text-right ${colorClass}`}>
                {percentage}%
              </span>
            </div>
          </TooltipTrigger>
          <TooltipContent side="top">
            <p className="text-xs">
              Input: {formatTokens(inputTokens)} / Max: {formatTokens(maxTokens)} ({percentage}%)
              {inputTokens > maxTokens && (
                <span className="text-amber-500 ml-1">(estimate)</span>
              )}
            </p>
          </TooltipContent>
        </Tooltip>
      )}
    </div>
  );
}
