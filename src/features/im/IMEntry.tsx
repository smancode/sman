import { useEffect, useMemo, useState, useCallback, useRef } from 'react';
import { useLocale } from '@/locales';
import { useIMStore } from '@/stores/im';
import { useRoomList, useRoomMessagesInfinite, fetchOlderMessages } from '@/queries/use-im';
import { MessageList } from './MessageList';
import { ChatInput } from './ChatInput';

// ---------------------------------------------------------------------------
// IMEntry — same structure as Chat (src/features/chat/index.tsx)
// ---------------------------------------------------------------------------

export default function IMEntry() {
  useLocale();

  const selectedRoomId = useIMStore((s) => s.selectedRoomId);
  const { data: rooms = [] } = useRoomList();
  const roomMessages = useIMStore((s) => s.roomMessages);
  const storeReplyQuote = useIMStore((s) => s.replyQuote);
  const setReplyQuote = useIMStore((s) => s.setReplyQuote);
  const { data: fetchedMessages = [] } = useRoomMessagesInfinite(selectedRoomId ?? undefined);

  const [prefillContent, setPrefillContent] = useState('');
  const [prefillConsumed, setPrefillConsumed] = useState(false);

  const [olderMessages, setOlderMessages] = useState<import('@/schemas/im').IMMessage[]>([]);
  const [isLoadingOlder, setLoadingOlder] = useState(false);
  const isLoadingOlderRef = useRef(false);

  const selectedRoom = useMemo(
    () => rooms.find((r) => r.id === selectedRoomId) ?? null,
    [rooms, selectedRoomId],
  );

  useMemo(() => {
    if (storeReplyQuote && storeReplyQuote.roomId === selectedRoomId && !prefillConsumed) {
      const quotePreview = storeReplyQuote.content.length > 50
        ? storeReplyQuote.content.slice(0, 50) + '...'
        : storeReplyQuote.content;
      setPrefillContent(`> ${quotePreview}\n\n`);
      setPrefillConsumed(false);
    }
  }, [storeReplyQuote, selectedRoomId, prefillConsumed]);

  const handleContentConsumed = useCallback(() => {
    setPrefillConsumed(true);
    setReplyQuote(null);
  }, [setReplyQuote]);

  useEffect(() => {
    setOlderMessages([]);
  }, [selectedRoomId]);

  const messages = useMemo(() => {
    if (!selectedRoomId) return [];
    const map = new Map<string, typeof fetchedMessages[0]>();
    for (const m of olderMessages) map.set(m.id, m);
    for (const m of fetchedMessages) map.set(m.id, m);
    for (const m of roomMessages.get(selectedRoomId) || []) map.set(m.id, m);
    return [...map.values()].sort((a, b) => a.timestamp - b.timestamp);
  }, [selectedRoomId, fetchedMessages, roomMessages, olderMessages]);

  const handleLoadOlder = useCallback(() => {
    if (!selectedRoomId || isLoadingOlderRef.current) return;
    const allMsgs = messages;
    if (allMsgs.length === 0) return;
    const oldestTs = allMsgs[0].timestamp;
    isLoadingOlderRef.current = true;
    setLoadingOlder(true);
    fetchOlderMessages(selectedRoomId, oldestTs).then((older) => {
      setOlderMessages((prev) => [...older, ...prev]);
      isLoadingOlderRef.current = false;
      setLoadingOlder(false);
    });
  }, [selectedRoomId, messages]);

  const hasActiveRoom = !!selectedRoomId;
  const isEmpty = messages.length === 0;

  return (
    <div className="relative flex flex-col h-full transition-colors duration-200 bg-transparent">
      {/* Messages */}
      <div className={isEmpty ? 'flex-1' : 'flex-1 overflow-y-auto'}>
        {isEmpty ? (
          <div className="relative h-full max-w-4xl mx-auto px-4">
            <div className="flex flex-col items-center justify-center h-full">
              <p className="text-muted-foreground text-sm">
                {!hasActiveRoom ? '' : ''}
              </p>
            </div>
          </div>
        ) : (
          <MessageList messages={messages} onLoadOlder={handleLoadOlder} isLoadingOlder={isLoadingOlder} />
        )}
      </div>

      {/* Input — same layout as session chat */}
      {hasActiveRoom && (
        <div className="mt-auto w-full mx-auto max-w-4xl px-2">
          <ChatInput
            roomId={selectedRoomId!}
            initialContent={prefillContent}
            onContentConsumed={handleContentConsumed}
          />
        </div>
      )}
    </div>
  );
}
