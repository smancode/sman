import { useMemo } from 'react';
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
  const { data: fetchedMessages = [] } = useRoomMessages(roomId ?? undefined);
  const clientId = useMemo(() => getClientId(), []);

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
      <div className="flex-1 flex flex-col bg-[#0a0a0f] min-w-0">
        <div className="flex-1 flex items-center justify-center">
          <p className="text-[#555568] text-sm">{t('im.empty.selectHint')}</p>
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
    <div className="flex-1 flex flex-col bg-[#0a0a0f] min-w-0">
      {/* Header */}
      <div className="px-5 py-3 border-b border-[#2a2a38] flex items-center justify-between bg-[#111118]">
        <div className="flex items-center gap-3">
          <div className="w-9 h-9 rounded-[10px] bg-[#2a2a38] flex items-center justify-center text-base">
            💬
          </div>
          <div>
            <h3 className="text-[15px] font-semibold text-[#e8e8ed]">
              {/* Room name will come from room data in Task 13 */}
              {roomId ? t('im.groups') : ''}
            </h3>
            <span className="text-xs text-[#8888a0]">
              {memberCount > 0
                ? `${memberCount} ${t('im.members')}`
                : ''}
            </span>
          </div>
        </div>
        <div className="flex gap-2">
          <button
            onClick={onToggleMembers}
            className="bg-[#1a1a24] border border-[#2a2a38] text-[#8888a0] px-2.5 py-1.5 rounded-lg cursor-pointer text-xs transition-colors hover:bg-[#22222e] hover:text-[#e8e8ed]"
          >
            👥 {t('im.members')}
          </button>
        </div>
      </div>

      {/* Message list */}
      <MessageList messages={messages} clientId={clientId} />

      {/* Chat input with @mention */}
      <ChatInput roomId={roomId} clientId={clientId} />
    </div>
  );
}
