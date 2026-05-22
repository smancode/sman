import { useState, useEffect } from 'react';
import { Shield, ShieldCheck, Gem, Star, Crown, Sparkles, Flame, Infinity, Loader2, AlertCircle } from 'lucide-react';
import { cn } from '@/lib/utils';
import { t } from '@/locales';
import { getAgentColor, getDisplayName } from './utils';
import { TIER_ICONS, TIER_COLORS } from '@/types/achievement';
import type { Tier } from '@/types/achievement';
import type { IMMessage } from '@/schemas/im';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';

// ---------------------------------------------------------------------------
// Props
// ---------------------------------------------------------------------------

interface MessageBubbleProps {
  message: IMMessage;
  isSelf: boolean;
  allSenders: string[];
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

const TIER_ICON_MAP: Record<string, React.ComponentType<{ className?: string }>> = {
  shield: Shield,
  'shield-check': ShieldCheck,
  gem: Gem,
  star: Star,
  crown: Crown,
  sparkles: Sparkles,
  flame: Flame,
  infinity: Infinity,
};

function formatTime(ts: number): string {
  const d = new Date(ts);
  const hh = String(d.getHours()).padStart(2, '0');
  const mm = String(d.getMinutes()).padStart(2, '0');
  return `${hh}:${mm}`;
}

function getInitial(sender: string): string {
  const username = sender.split('@')[0];
  if (username.includes('/')) {
    const parts = username.split('/');
    return parts[parts.length - 1].charAt(0).toUpperCase();
  }
  return username.charAt(0).toUpperCase();
}

// ---------------------------------------------------------------------------
// @mention parser
// ---------------------------------------------------------------------------

const MENTION_REGEX = /@(\S+)/g;

interface MentionParseResult {
  text: string;
  isMention: boolean;
  color: string;
}

function parseMentions(content: string): MentionParseResult[] {
  const parts: MentionParseResult[] = [];
  let lastIndex = 0;
  let match: RegExpExecArray | null;

  const regex = new RegExp(MENTION_REGEX.source, 'g');

  while ((match = regex.exec(content)) !== null) {
    if (match.index > lastIndex) {
      parts.push({ text: content.slice(lastIndex, match.index), isMention: false, color: '' });
    }
    const mentionId = match[1];
    const color = getAgentColor(mentionId);
    parts.push({ text: match[0], isMention: true, color });
    lastIndex = regex.lastIndex;
  }

  if (lastIndex < content.length) {
    parts.push({ text: content.slice(lastIndex), isMention: false, color: '' });
  }

  return parts;
}

// ---------------------------------------------------------------------------
// OtherUserMessage — shows avatar + tier name + border-only bubble
// ---------------------------------------------------------------------------

function OtherUserMessage({ message, allSenders }: { message: IMMessage; allSenders: string[] }) {
  const { sender, content, timestamp, quoteId, attachments } = message;
  const displayName = getDisplayName(sender, allSenders);
  const avatarColor = getAgentColor(sender);
  const initial = getInitial(sender);
  const parsedContent = parseMentions(content);

  return (
    <div className="flex gap-2.5 py-1.5 max-w-[85%]">
      {/* Avatar */}
      <div
        className="w-7 h-7 rounded-lg flex items-center justify-center text-[11px] font-bold flex-shrink-0 mt-1"
        style={{ backgroundColor: avatarColor }}
      >
        {initial}
      </div>

      {/* Body */}
      <div className="min-w-0">
        {/* Sender name with tier icon + color */}
        <SenderName displayName={displayName} timestamp={timestamp} />

        {/* Quote reference */}
        {quoteId && (
          <div className="bg-[hsl(var(--background))] border-l-2 border-muted-foreground px-2 py-1 rounded mb-1 text-xs text-muted-foreground cursor-pointer hover:border-[hsl(var(--primary))]">
            <span className="font-semibold">{t('im.chatInput.replyGroup')}</span>
          </div>
        )}

        {/* Bubble — border only, no bg */}
        <div className="px-3 py-2 rounded-xl rounded-tl-sm text-sm leading-relaxed break-words border border-[hsl(var(--border))] bg-transparent text-foreground">
          {parsedContent.map((part, i) =>
            part.isMention ? (
              <span
                key={i}
                className="px-0.5 py-px rounded font-medium cursor-pointer"
                style={{ backgroundColor: `${part.color}26`, color: part.color }}
              >
                {part.text}
              </span>
            ) : (
              <span key={i}>{part.text}</span>
            ),
          )}
        </div>

        {/* File attachments */}
        {attachments && attachments.length > 0 && (
          <div className="mt-1 space-y-1">
            {attachments.map((file: { name?: string }, i: number) => (
              <div
                key={i}
                className="flex items-center gap-2 bg-[hsl(var(--background))] px-2.5 py-2 rounded-lg text-[13px] cursor-pointer hover:bg-[hsl(var(--muted))]"
              >
                <span className="text-muted-foreground">📎</span>
                <span className="text-[hsl(var(--muted-foreground))] truncate">{file.name || 'file'}</span>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// SenderName — resolves tier icon + color for any sender
// ---------------------------------------------------------------------------

// Map of known senderId → tier (populated from achievement leaderboard or presence)
// For now, we look up by matching senderId against leaderboard entries or local level.

function SenderName({ displayName, timestamp }: {
  displayName: string;
  timestamp: number;
}) {
  return (
    <div className="flex items-center gap-1.5 mb-0.5">
      <span className="text-xs font-semibold text-muted-foreground">
        {displayName}
      </span>
      <span className="text-[10px] text-muted-foreground">
        {formatTime(timestamp)}
      </span>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

export function MessageBubble({ message, isSelf, allSenders }: MessageBubbleProps) {
  const { content, type } = message;

  // System messages
  if (type === 'system') {
    return (
      <div className="text-center py-1.5 text-xs text-muted-foreground italic">
        {content}
      </div>
    );
  }

  // Agent output messages
  if (type === 'agent_output') {
    return <AgentOutputMessage message={message} allSenders={allSenders} />;
  }

  // Self messages — just the right-side grey bubble, no name, no avatar
  if (isSelf) {
    return <SelfMessage message={message} />;
  }

  // Other users — avatar + name + border-only bubble
  return <OtherUserMessage message={message} allSenders={allSenders} />;
}

// ---------------------------------------------------------------------------
// SendStatus — optimistic message status indicator
// ---------------------------------------------------------------------------

type SendStatus = 'sending' | 'timeout';

function SendStatusIndicator({ status }: { status: SendStatus }) {
  if (status === 'sending') {
    return <Loader2 className="h-3.5 w-3.5 text-muted-foreground animate-spin shrink-0" />;
  }

  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <AlertCircle className="h-3.5 w-3.5 text-destructive shrink-0 cursor-pointer" />
      </TooltipTrigger>
      <TooltipContent side="left">
        <p>{t('im.sendTimeout')}</p>
      </TooltipContent>
    </Tooltip>
  );
}

// ---------------------------------------------------------------------------
// SelfMessage — right-aligned grey bubble, with optimistic status indicator
// ---------------------------------------------------------------------------

function SelfMessage({ message }: { message: IMMessage }) {
  const { content, timestamp, quoteId, attachments } = message;
  const parsedContent = parseMentions(content);
  const isOptimistic = message.id.startsWith('temp-');

  // Track optimistic send status: null → 'sending' (after 1s) → 'timeout' (after 5s)
  const [sendStatus, setSendStatus] = useState<SendStatus | null>(null);

  useEffect(() => {
    if (!isOptimistic) return;

    const t1 = setTimeout(() => setSendStatus('sending'), 1000);
    const t2 = setTimeout(() => setSendStatus('timeout'), 5000);

    return () => {
      clearTimeout(t1);
      clearTimeout(t2);
    };
  }, [isOptimistic, message.id]);

  // Once server message replaces optimistic, clear status
  useEffect(() => {
    if (!isOptimistic) {
      setSendStatus(null);
    }
  }, [isOptimistic]);

  return (
    <div className="flex justify-end py-1.5 max-w-[85%] self-end">
      <div className="min-w-0 flex flex-col items-end">
        <div className="flex items-center gap-1.5 mb-0.5">
          {sendStatus && <SendStatusIndicator status={sendStatus} />}
          <span className="text-[10px] text-muted-foreground">
            {formatTime(timestamp)}
          </span>
        </div>

        {/* Quote reference */}
        {quoteId && (
          <div className="bg-[hsl(var(--background))] border-l-2 border-muted-foreground px-2 py-1 rounded mb-1 text-xs text-muted-foreground cursor-pointer hover:border-[hsl(var(--primary))]">
            <span className="font-semibold">{t('im.chatInput.replyGroup')}</span>
          </div>
        )}

        {/* Bubble */}
        <div className="px-3 py-2 rounded-xl rounded-tr-sm text-sm leading-relaxed break-words bg-[hsl(var(--user-bubble-bg))] border border-[hsl(var(--user-bubble-fg))]/20 text-[hsl(var(--user-bubble-fg))]">
          {parsedContent.map((part, i) =>
            part.isMention ? (
              <span
                key={i}
                className="px-0.5 py-px rounded font-medium cursor-pointer bg-[hsl(var(--user-bubble-fg))]/15"
              >
                {part.text}
              </span>
            ) : (
              <span key={i}>{part.text}</span>
            ),
          )}
        </div>

        {/* File attachments */}
        {attachments && attachments.length > 0 && (
          <div className="mt-1 space-y-1">
            {attachments.map((file: { name?: string }, i: number) => (
              <div
                key={i}
                className="flex items-center gap-2 bg-[hsl(var(--background))] px-2.5 py-2 rounded-lg text-[13px] cursor-pointer hover:bg-[hsl(var(--muted))]"
              >
                <span className="text-muted-foreground">📎</span>
                <span className="text-[hsl(var(--muted-foreground))] truncate">{file.name || 'file'}</span>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// AgentOutputMessage — agent card with colored border
// ---------------------------------------------------------------------------

function AgentOutputMessage({ message, allSenders }: { message: IMMessage; allSenders: string[] }) {
  const { sender, content, timestamp } = message;
  const color = getAgentColor(sender);

  return (
    <div
      className="mx-0 my-1 rounded-xl border-2 overflow-hidden bg-transparent"
      style={{ borderColor: color }}
    >
      <div
        className="px-3.5 py-2.5 flex items-center gap-2.5 cursor-pointer"
        style={{ backgroundColor: `${color}0f` }}
      >
        <div
          className="w-7 h-7 rounded-lg flex items-center justify-center text-xs font-bold flex-shrink-0"
          style={{ backgroundColor: `${color}33`, color }}
        >
          🤖
        </div>
        <div className="flex-1 min-w-0">
          <div className="text-[13px] font-semibold" style={{ color }}>
            {getDisplayName(sender, allSenders)}
          </div>
        </div>
        <span className="text-[10px] text-muted-foreground">
          {formatTime(timestamp)}
        </span>
      </div>
      <div className="px-3.5 pb-3 text-[13px] text-[hsl(var(--muted-foreground))] whitespace-pre-wrap leading-relaxed">
        {content}
      </div>
    </div>
  );
}
