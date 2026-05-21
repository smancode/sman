import { useMemo, useState, useCallback } from 'react';
import { t } from '@/locales';
import { useIMStore } from '@/stores/im';
import { useRoomMessages } from '@/queries/use-im';
import { MessageList } from './MessageList';
import { ChatInput } from './ChatInput';
// ---------------------------------------------------------------------------
// Props
// ---------------------------------------------------------------------------

interface ChatWindowProps {
  roomId: string | null;
  onToggleMembers?: () => void;
}

// ---------------------------------------------------------------------------
// Client identity — matches server getClientId() format: username@ip
// In the browser, we derive it from the sender field of our own messages
// or use a placeholder until the first message is sent.
// For now we read from localStorage where the server may have set it.
// ---------------------------------------------------------------------------

function getClientId(): string {
  if (typeof window === 'undefined') return '';
  return localStorage.getItem('sman-client-id') || '';
}

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

export function ChatWindow({ roomId, onToggleMembers }: ChatWindowProps) {
  const roomMessages = useIMStore((s) => s.roomMessages);
  const storeReplyQuote = useIMStore((s) => s.replyQuote);
  const setReplyQuote = useIMStore((s) => s.setReplyQuote);
  const { data: fetchedMessages = [] } = useRoomMessages(roomId ?? undefined);
  const clientId = useMemo(() => getClientId(), []);

  // Pre-fill content from replyQuote when it matches current room
  const [prefillContent, setPrefillContent] = useState('');
  const [prefillConsumed, setPrefillConsumed] = useState(false);

  // When storeReplyQuote changes and matches current room, generate prefill text
  useMemo(() => {
    if (storeReplyQuote && storeReplyQuote.roomId === roomId && !prefillConsumed) {
      const quotePreview = storeReplyQuote.content.length > 50
        ? storeReplyQuote.content.slice(0, 50) + '...'
        : storeReplyQuote.content;
      setPrefillContent(`> ${quotePreview}\n\n`);
      setPrefillConsumed(false);
    }
  }, [storeReplyQuote, roomId, prefillConsumed]);

  const handleContentConsumed = useCallback(() => {
    setPrefillConsumed(true);
    setReplyQuote(null);
  }, [setReplyQuote]);

  // Merge: fetched messages from WS history + real-time messages from store
  const messages = useMemo(() => {
    if (!roomId) return [];
    const realtime = roomMessages.get(roomId) || [];
    if (realtime.length === 0) return fetchedMessages;

    // Merge and dedup by id
    const map = new Map<string, typeof fetchedMessages[0]>();
    for (const m of fetchedMessages) map.set(m.id, m);
    for (const m of realtime) map.set(m.id, m);
    return [...map.values()];
  }, [roomId, fetchedMessages, roomMessages]);

  // Empty state
  if (!roomId) {
    return (
      <div className="flex-1 flex flex-col bg-[hsl(var(--background))] min-w-0">
        <div className="flex-1 flex items-center justify-center">
          <p className="text-muted-foreground text-sm">{t('im.empty.selectHint')}</p>
        </div>
      </div>
    );
  }

  // Derive room info from the messages
  const memberCount = useMemo(() => {
    const senders = new Set(messages.map((m) => m.sender));
    return senders.size || 0;
  }, [messages]);

  return (
    <div className="flex-1 flex flex-col bg-[hsl(var(--background))] min-w-0">
      {/* Header */}
      <div className="px-5 py-3 border-b border-[hsl(var(--border))] flex items-center justify-between bg-[hsl(var(--card))]">
        <div className="flex items-center gap-3">
          <div className="w-9 h-9 rounded-[10px] bg-[hsl(var(--muted))] flex items-center justify-center text-base">
            💬
          </div>
          <div>
            <h3 className="text-[15px] font-semibold text-foreground">
              {/* Room name will come from room data in Task 13 */}
              {roomId ? t('im.groups') : ''}
            </h3>
            <span className="text-xs text-[hsl(var(--muted-foreground))]">
              {memberCount > 0
                ? `${memberCount} ${t('im.members')}`
                : ''}
            </span>
          </div>
        </div>
        <div className="flex gap-2">
          <button
            onClick={onToggleMembers}
            className="bg-[hsl(var(--card))] border border-[hsl(var(--border))] text-[hsl(var(--muted-foreground))] px-2.5 py-1.5 rounded-lg cursor-pointer text-xs transition-colors hover:bg-[hsl(var(--muted))] hover:text-foreground"
          >
            👥 {t('im.members')}
          </button>
        </div>
      </div>

      {/* Message list */}
      <MessageList messages={messages} clientId={clientId} />

      {/* Chat input with @mention */}
      <ChatInput
        roomId={roomId}
        clientId={clientId}
        initialContent={prefillContent}
        onContentConsumed={handleContentConsumed}
      />
    </div>
  );
}
