import { useMemo, useState, useCallback } from 'react';
import { t } from '@/locales';
import { useIMStore } from '@/stores/im';
import { useRoomMessages } from '@/queries/use-im';
import { MessageList } from './MessageList';
import { ChatInput } from './ChatInput';

interface ChatWindowProps {
  roomId: string | null;
  roomName: string | null;
  onToggleMembers?: () => void;
}

function getClientId(): string {
  if (typeof window === 'undefined') return '';
  return localStorage.getItem('sman-client-id') || '';
}

export function ChatWindow({ roomId, roomName, onToggleMembers }: ChatWindowProps) {
  const roomMessages = useIMStore((s) => s.roomMessages);
  const storeReplyQuote = useIMStore((s) => s.replyQuote);
  const setReplyQuote = useIMStore((s) => s.setReplyQuote);
  const { data: fetchedMessages = [] } = useRoomMessages(roomId ?? undefined);
  const clientId = useMemo(() => getClientId(), []);

  const [prefillContent, setPrefillContent] = useState('');
  const [prefillConsumed, setPrefillConsumed] = useState(false);

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

  const messages = useMemo(() => {
    if (!roomId) return [];
    const realtime = roomMessages.get(roomId) || [];
    if (realtime.length === 0) return fetchedMessages;
    const map = new Map<string, typeof fetchedMessages[0]>();
    for (const m of fetchedMessages) map.set(m.id, m);
    for (const m of realtime) map.set(m.id, m);
    return [...map.values()];
  }, [roomId, fetchedMessages, roomMessages]);

  const memberCount = useMemo(() => {
    const senders = new Set(messages.map((m) => m.sender));
    return senders.size || 0;
  }, [messages]);

  // Empty state — all hooks above this line
  if (!roomId) {
    return (
      <div className="flex-1 flex flex-col min-w-0">
        <div className="flex-1 flex items-center justify-center">
          <p className="text-muted-foreground text-sm">{t('im.empty.selectHint')}</p>
        </div>
      </div>
    );
  }

  const isEmpty = messages.length === 0;

  return (
    <div className="relative flex flex-col h-full transition-colors duration-200 bg-transparent">
      {/* Messages */}
      <div className={isEmpty ? 'flex-1' : 'flex-1 overflow-y-auto'}>
        {isEmpty ? (
          <div className="relative h-full max-w-4xl mx-auto px-4 flex items-center justify-center">
            <p className="text-muted-foreground text-sm">{t('im.empty.noMessages')}</p>
          </div>
        ) : (
          <MessageList messages={messages} clientId={clientId} />
        )}
      </div>

      {/* Input — same layout as session chat */}
      <div className="mt-auto w-full mx-auto max-w-4xl px-2">
        <ChatInput
          roomId={roomId}
          clientId={clientId}
          initialContent={prefillContent}
          onContentConsumed={handleContentConsumed}
        />
      </div>
    </div>
  );
}
