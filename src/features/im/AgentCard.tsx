import { useState, useMemo } from 'react';
import { t } from '@/locales';
import type { IMMessage } from '@/schemas/im';
import { useIMStore } from '@/stores/im';
import { getAgentColor } from './utils';

// ---------------------------------------------------------------------------
// Props
// ---------------------------------------------------------------------------

interface AgentCardProps {
  message: IMMessage;
  isExpanded?: boolean;
  onToggleExpand?: () => void;
  onReplyInGroup?: (messageId: string, agentId: string) => void;
  onGoDM?: (agentId: string) => void;
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function getFirstLine(text: string): string {
  if (!text) return '';
  const lines = text.split('\n').filter(l => l.trim());
  return lines[0] || '';
}

function getStatusDisplay(status: string | undefined, color: string): { icon: string; label: string; pulsing: boolean } {
  switch (status) {
    case 'running':
      return { icon: '', label: t('im.agentCard.executing'), pulsing: true };
    case 'completed':
      return { icon: '✅', label: t('im.agentCard.completed'), pulsing: false };
    case 'failed':
      return { icon: '❌', label: t('im.agentCard.failed'), pulsing: false };
    default:
      return { icon: '', label: t('im.agentCard.executing'), pulsing: true };
  }
}

// ---------------------------------------------------------------------------
// AgentCard component
// ---------------------------------------------------------------------------

export function AgentCard({
  message,
  isExpanded: controlledExpanded,
  onToggleExpand,
  onReplyInGroup,
  onGoDM,
}: AgentCardProps) {
  // Local expand state (uncontrolled) or controlled via props
  const [internalExpanded, setInternalExpanded] = useState(false);
  const expanded = controlledExpanded ?? internalExpanded;

  const agentStreams = useIMStore((s) => s.agentStreams);
  const color = useMemo(() => getAgentColor(message.sender), [message.sender]);
  const streamContent = agentStreams.get(message.id);
  const displayContent = streamContent || message.content;
  const summary = useMemo(() => getFirstLine(displayContent), [displayContent]);
  const statusInfo = useMemo(() => getStatusDisplay(message.status, color), [message.status, color]);

  const handleToggle = () => {
    if (onToggleExpand) {
      onToggleExpand();
    } else {
      setInternalExpanded((prev) => !prev);
    }
  };

  const handleReplyInGroup = (e: React.MouseEvent) => {
    e.stopPropagation();
    onReplyInGroup?.(message.id, message.sender);
  };

  const handleGoDM = (e: React.MouseEvent) => {
    e.stopPropagation();
    onGoDM?.(message.sender);
  };

  return (
    <div
      className="my-1 rounded-xl border-2 border-solid overflow-hidden transition-all duration-200"
      style={{ borderColor: color }}
    >
      {/* Header — always visible, click to toggle */}
      <div
        className="flex items-center gap-2.5 px-3.5 py-2.5 cursor-pointer transition-colors"
        style={{ backgroundColor: `${color}0f` }}
        onClick={handleToggle}
      >
        {/* Avatar */}
        <div
          className="w-7 h-7 rounded-lg flex items-center justify-center text-xs font-bold flex-shrink-0"
          style={{ backgroundColor: `${color}33`, color }}
        >
          {'🤖'}
        </div>

        {/* Agent name */}
        <div className="flex-1 min-w-0">
          <div className="text-[13px] font-semibold truncate" style={{ color }}>
            {message.sender}
          </div>
        </div>

        {/* Status */}
        <div className="flex items-center gap-1 text-[11px] flex-shrink-0" style={{ color }}>
          {statusInfo.pulsing && (
            <span
              className="inline-block w-1.5 h-1.5 rounded-full animate-pulse"
              style={{ backgroundColor: color }}
            />
          )}
          {!statusInfo.pulsing && statusInfo.icon && (
            <span className="text-[11px]">{statusInfo.icon}</span>
          )}
          <span>{statusInfo.label}</span>
        </div>

        {/* Summary (collapsed only) */}
        {!expanded && (
          <div className="text-[13px] text-[#8888a0] truncate flex-shrink-0 max-w-[40%]">
            {summary}
          </div>
        )}

        {/* Expand/collapse toggle */}
        <div
          className="text-[10px] text-[#555568] bg-[#1a1a24] px-2 py-0.5 rounded flex-shrink-0"
        >
          {expanded ? t('im.agentCard.collapse') : t('im.agentCard.expand')}
        </div>
      </div>

      {/* Body — expanded only */}
      {expanded && (
        <div className="px-3.5 pb-3.5">
          {/* Content area — monospace, scrollable */}
          <div className="bg-[#0a0a0f] rounded-lg p-3 text-[13px] leading-relaxed text-[#8888a0] max-h-[300px] overflow-y-auto font-mono whitespace-pre-wrap">
            {displayContent}
          </div>

          {/* Reply box */}
          <div className="mt-2.5 rounded-[10px] border border-solid border-[#2a2a38] overflow-hidden">
            <textarea
              className="w-full bg-[#1a1a24] border-none px-3 py-2.5 text-[#e8e8ed] text-[13px] font-inherit resize-none outline-none min-h-[60px] leading-snug placeholder:text-[#555568]"
              placeholder={t('im.agentCard.replyPlaceholder')}
              onClick={(e) => e.stopPropagation()}
            />
            <div className="flex justify-end gap-2 px-3 py-2 bg-[#111118] border-t border-solid border-[#2a2a38]">
              <button
                className="px-4 py-1.5 rounded-lg cursor-pointer text-[12px] font-medium border-none transition-colors bg-[#1a1a24] text-[#e8e8ed] border border-solid border-[#2a2a38] hover:bg-[#22222e]"
                onClick={handleGoDM}
              >
                {t('im.agentCard.dm')}
              </button>
              <button
                className="px-4 py-1.5 rounded-lg cursor-pointer text-[12px] font-medium border-none transition-colors hover:opacity-85 text-white"
                style={{ backgroundColor: '#6c5ce7' }}
                onClick={handleReplyInGroup}
              >
                {t('im.agentCard.groupReply')}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
