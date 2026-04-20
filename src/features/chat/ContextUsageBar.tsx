import { useMemo } from 'react';
import { useChatStore } from '@/stores/chat';
import { useSettingsStore } from '@/stores/settings';
import { cn } from '@/lib/utils';
import {
  Tooltip,
  TooltipContent,
  TooltipTrigger,
} from '@/components/ui/tooltip';

const DEFAULT_MAX_INPUT_TOKENS = 200_000;

function getMaxInputTokensByModel(model: string): number {
  const lower = model.toLowerCase();
  if (lower.includes('claude')) return 200_000;
  if (lower.includes('gpt-4') && !lower.includes('gpt-3')) return 128_000;
  if (lower.includes('gpt-3')) return 4_096;
  if (lower.includes('deepseek')) return 64_000;
  if (lower.includes('qwen-vl') || lower.includes('qwen-vision')) return 32_000;
  if (lower.includes('glm') && lower.includes('vision')) return 128_000;
  if (lower.includes('llava') || lower.includes('bakllava') || lower.includes('minicpm-v')) return 4_096;
  if (lower.includes('llama') && lower.includes('vision')) return 128_000;
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

export function ContextUsageBar() {
  const contextUsage = useChatStore((s) => s.contextUsage);
  const settings = useSettingsStore((s) => s.settings);

  const { percentage, colorClass, inputTokens, maxTokens } = useMemo(() => {
    if (!contextUsage || contextUsage.inputTokens <= 0) {
      return { percentage: 0, colorClass: 'bg-green-500', inputTokens: 0, maxTokens: DEFAULT_MAX_INPUT_TOKENS };
    }

    const model = settings?.llm?.model || '';
    const capabilities = settings?.llm?.capabilities;
    const max = getMaxInputTokens(model, capabilities);
    const pct = Math.min(100, Math.round((contextUsage.inputTokens / max) * 100));

    let colorClass = 'bg-green-500';
    if (pct > 75) colorClass = 'bg-red-500';
    else if (pct >= 50) colorClass = 'bg-amber-500';

    return { percentage: pct, colorClass, inputTokens: contextUsage.inputTokens, maxTokens: max };
  }, [contextUsage, settings]);

  if (!contextUsage || inputTokens <= 0) {
    return null;
  }

  const filledBlocks = Math.round(percentage / 10);
  const emptyBlocks = 10 - filledBlocks;

  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <div className="flex items-center gap-1.5 text-xs text-muted-foreground select-none cursor-default">
          <span className="text-[11px] font-medium">Context</span>
          <div className="flex gap-px">
            {Array.from({ length: filledBlocks }).map((_, i) => (
              <div key={`f-${i}`} className={cn('w-2 h-3.5 rounded-sm', colorClass)} />
            ))}
            {Array.from({ length: emptyBlocks }).map((_, i) => (
              <div key={`e-${i}`} className="w-2 h-3.5 rounded-sm bg-muted" />
            ))}
          </div>
          <span className="text-[11px] font-medium tabular-nums w-8 text-right">{percentage}%</span>
        </div>
      </TooltipTrigger>
      <TooltipContent side="top">
        <p className="text-xs">
          Input: {formatTokens(inputTokens)} / Max: {formatTokens(maxTokens)} ({percentage}%)
        </p>
      </TooltipContent>
    </Tooltip>
  );
}
