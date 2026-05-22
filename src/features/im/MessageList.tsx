import { useEffect, useRef, useMemo } from 'react';
import { t } from '@/locales';
import { useIMStore } from '@/stores/im';
import { MessageBubble } from './MessageBubble';
import { AgentCard } from './AgentCard';
import type { IMMessage } from '@/schemas/im';

// ---------------------------------------------------------------------------
// Props
// ---------------------------------------------------------------------------

interface MessageListProps {
  messages: IMMessage[];
  onLoadOlder?: () => void;
  isLoadingOlder?: boolean;
}

// ---------------------------------------------------------------------------
// Date divider helper
// ---------------------------------------------------------------------------

function getDateKey(ts: number): string {
  const d = new Date(ts);
  return `${d.getFullYear()}-${d.getMonth()}-${d.getDate()}`;
}

function formatDateDivider(ts: number): string {
  const d = new Date(ts);
  const now = new Date();
  const today = `${now.getFullYear()}-${now.getMonth()}-${now.getDate()}`;
  const yesterday = new Date(now);
  yesterday.setDate(yesterday.getDate() - 1);
  const yesterdayKey = `${yesterday.getFullYear()}-${yesterday.getMonth()}-${yesterday.getDate()}`;

  const key = getDateKey(ts);
  if (key === today) return t('im.dateToday');
  if (key === yesterdayKey) return t('im.dateYesterday');
  return d.toLocaleDateString();
}

// ---------------------------------------------------------------------------
// Intersection Observer hook for top pagination trigger
// ---------------------------------------------------------------------------

function useTopObserver(onReachTop: () => void, enabled: boolean) {
  const sentinelRef = useRef<HTMLDivElement>(null);
  const callbackRef = useRef(onReachTop);
  callbackRef.current = onReachTop;

  useEffect(() => {
    if (!enabled || !sentinelRef.current) return;

    const observer = new IntersectionObserver(
      ([entry]) => {
        if (entry?.isIntersecting) {
          callbackRef.current();
        }
      },
      { threshold: 0.1 },
    );

    observer.observe(sentinelRef.current);
    return () => observer.disconnect();
  }, [enabled]);

  return sentinelRef;
}

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

export function MessageList({ messages, onLoadOlder, isLoadingOlder }: MessageListProps) {
  const scrollRef = useRef<HTMLDivElement>(null);
  const bottomRef = useRef<HTMLDivElement>(null);
  const agentStreams = useIMStore((s) => s.agentStreams);
  const mySenderId = useIMStore((s) => s.mySenderId);
  const typingUsers = useIMStore((s) => s.typingUsers);
  const prevMsgCountRef = useRef(0);

  // Collect all unique senders for display name dedup
  const allSenders = useMemo(
    () => [...new Set(messages.map((m) => m.sender))],
    [messages],
  );

  // Sort messages by timestamp ASC
  const sorted = useMemo(
    () => [...messages].sort((a, b) => a.timestamp - b.timestamp),
    [messages],
  );

  // Auto-scroll to bottom when new messages arrive
  useEffect(() => {
    if (sorted.length > prevMsgCountRef.current) {
      bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
    }
    prevMsgCountRef.current = sorted.length;
  }, [sorted.length]);

  // Also scroll on initial load
  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'auto' });
  }, []);

  // Get typing user for this room
  const firstRoomId = messages[0]?.roomId;
  const typingUser = firstRoomId ? typingUsers.get(firstRoomId) : undefined;

  const sentinelRef = useTopObserver(() => onLoadOlder?.(), !!onLoadOlder);

  if (messages.length === 0) return null;

  return (
    <div ref={scrollRef} className="px-4 pt-3 pb-4 flex flex-col gap-0.5">
      {/* Sentinel for top pagination */}
      <div ref={sentinelRef} className="h-0" />

      {sorted.map((msg, idx) => {
        const prevMsg = idx > 0 ? sorted[idx - 1] : null;
        const showDivider = !prevMsg || getDateKey(msg.timestamp) !== getDateKey(prevMsg.timestamp);
        const isSelf = !msg.sender || msg.sender === mySenderId;

        return (
          <div key={msg.id}>
            {showDivider && (
              <div className="text-center py-3 relative">
                <span className="bg-[hsl(var(--background))] px-3 text-[11px] text-muted-foreground relative z-[1]">
                  {formatDateDivider(msg.timestamp)}
                </span>
                <div className="absolute top-1/2 left-0 right-0 h-px bg-[hsl(var(--border))]" />
              </div>
            )}
            {msg.type === 'agent_output' ? (
              <AgentCard
                message={msg}
                onReplyInGroup={(messageId, agentId) => {
                  // Quote agent content and pre-fill ChatInput in this room
                  const quotePreview = msg.content.length > 50
                    ? msg.content.slice(0, 50) + '...'
                    : msg.content;
                  useIMStore.getState().setReplyQuote({
                    roomId: msg.roomId,
                    messageId,
                    content: quotePreview,
                  });
                }}
                onGoDM={(agentId) => {
                  // Switch to sessions tab for DM
                  useIMStore.getState().setActiveTab('sessions');
                }}
              />
            ) : (
              <MessageBubble
                message={msg}
                isSelf={isSelf}
                allSenders={allSenders}
              />
            )}
          </div>
        );
      })}

      {/* Typing indicator */}
      {typingUser && (
        <div className="flex items-center gap-2 py-1.5 px-1">
          <div className="flex gap-1">
            <span className="w-1.5 h-1.5 bg-[hsl(var(--primary))] rounded-full animate-bounce" style={{ animationDelay: '0ms' }} />
            <span className="w-1.5 h-1.5 bg-[hsl(var(--primary))] rounded-full animate-bounce" style={{ animationDelay: '150ms' }} />
            <span className="w-1.5 h-1.5 bg-[hsl(var(--primary))] rounded-full animate-bounce" style={{ animationDelay: '300ms' }} />
          </div>
          <span className="text-xs text-muted-foreground">
            {typingUser.split('@')[0]} {t('im.typing')}
          </span>
        </div>
      )}

      {/* Bottom scroll anchor */}
      <div ref={bottomRef} />
    </div>
  );
}
