import { useState, useMemo, useEffect, useRef, useCallback } from 'react';
import { Plus, MessageSquare, Bot, Check, Search } from 'lucide-react';
import { t } from '@/locales';
import { useRoomList, useCreateRoom } from '@/queries/use-im';
import { useIMStore } from '@/stores/im';
import { useWsConnection } from '@/stores/ws-connection';
import { parseIMMessage } from '@/schemas/im';
import type { IMRoom, IMMessage } from '@/schemas/im';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

const DEFAULT_EMOJI = '🎯';

function getRoomEmoji(name: string): string {
  const emojiRegex = /\p{Emoji_Presentation}/u;
  for (const ch of name) {
    if (emojiRegex.test(ch)) return ch;
  }
  return DEFAULT_EMOJI;
}

function formatRelativeTime(timestamp: number | undefined): string {
  if (!timestamp) return '';
  const now = Date.now();
  const diff = now - timestamp;
  const minutes = Math.floor(diff / 60_000);
  if (minutes < 1) return t('im.online');
  if (minutes < 60) return `${minutes}m`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h`;
  const days = Math.floor(hours / 24);
  if (days < 7) return `${days}d`;
  return new Date(timestamp).toLocaleDateString();
}

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

export default function IMListPanel() {
  const { data: rooms = [], isLoading } = useRoomList();
  const selectedRoomId = useIMStore((s) => s.selectedRoomId);
  const selectRoom = useIMStore((s) => s.selectRoom);
  const roomLastActivity = useIMStore((s) => s.roomLastActivity);
  const roomUnreadCounts = useIMStore((s) => s.roomUnreadCounts);
  const createRoom = useCreateRoom();
  const [showCreate, setShowCreate] = useState(false);
  const [newRoomName, setNewRoomName] = useState('');

  // Search with debounce — server-side DB query
  const [searchInput, setSearchInput] = useState('');
  const [searchResult, setSearchResult] = useState<{ rooms: IMRoom[]; messages: IMMessage[] } | null>(null);
  const searchTimerRef = useRef<ReturnType<typeof setTimeout>>(undefined);
  const searchSeqRef = useRef(0);

  const handleSearchChange = useCallback((value: string) => {
    setSearchInput(value);
    clearTimeout(searchTimerRef.current);
    if (!value.trim()) {
      setSearchResult(null);
      return;
    }
    searchTimerRef.current = setTimeout(() => {
      const client = useWsConnection.getState().client;
      if (!client?.connected) return;
      const seq = ++searchSeqRef.current;
      const handler = (msg: Record<string, unknown>) => {
        const payload = (msg as Record<string, unknown>).data as Record<string, unknown>;
        if (seq !== searchSeqRef.current) return;
        const rooms = Array.isArray(payload.rooms) ? payload.rooms.map((r: unknown) => {
          const row = r as Record<string, unknown>;
          return {
            id: row.id as string,
            name: row.name as string,
            type: (row.type as 'group' | 'dm' | 'workspace') || 'group' as const,
            members: JSON.parse((row.members as string) || '[]'),
          };
        }) : [];
        const messages = Array.isArray(payload.messages) ? payload.messages.map((m: unknown) => parseIMMessage(m)).filter((m: IMMessage) => m.id) : [];
        setSearchResult({ rooms, messages });
        client.off('im.search', handler as (...a: unknown[]) => void);
      };
      client.on('im.search', handler as (...a: unknown[]) => void);
      client.send({ type: 'im.search', query: value.trim() });
    }, 200);
  }, []);

  // Merge realtime activity into room list, then sort by lastMessageTime desc
  const sortedRooms = useMemo(() => {
    return [...rooms]
      .filter((r: IMRoom) => r.type === 'dm' || r.type === 'group' || r.type === 'workspace')
      .map((r: IMRoom) => {
        const activity = roomLastActivity.get(r.id);
        if (!activity) return r;
        return {
          ...r,
          lastMessage: activity.lastMessage || r.lastMessage,
          lastMessageTime: activity.lastMessageTime || r.lastMessageTime,
        };
      })
      .sort((a, b) => (b.lastMessageTime || 0) - (a.lastMessageTime || 0));
  }, [rooms, roomLastActivity]);

  // Display rooms: search results or full sorted list
  const displayedRooms = useMemo(() => {
    if (!searchResult) return sortedRooms;
    // Use search result room IDs to filter, preserving sort order
    const resultIds = new Set(searchResult.rooms.map(r => r.id));
    // Also include rooms that had matching messages
    const msgRoomIds = new Set(searchResult.messages.map(m => m.roomId));
    const allMatched = new Set([...resultIds, ...msgRoomIds]);
    return sortedRooms.filter(r => allMatched.has(r.id));
  }, [sortedRooms, searchResult]);

  // Auto-select first room when entering IM page
  useEffect(() => {
    if (!selectedRoomId && sortedRooms.length > 0) {
      selectRoom(sortedRooms[0].id);
    }
  }, [selectedRoomId, sortedRooms, selectRoom]);

  const handleCreate = () => {
    const name = newRoomName.trim();
    if (!name) return;
    createRoom.mutate(
      { name, type: 'group' },
      {
        onSuccess: (room: IMRoom) => {
          selectRoom(room.id);
          setShowCreate(false);
          setNewRoomName('');
        },
      },
    );
  };

  return (
    <div className="flex flex-col h-full">
      {/* 新建对话 */}
      <div className="px-3 py-2">
        {showCreate ? (
          <div className="flex items-center gap-2">
            <input
              value={newRoomName}
              onChange={(e) => setNewRoomName(e.target.value)}
              placeholder={t('im.createRoom.namePlaceholder')}
              className="flex-1 bg-[hsl(var(--card))] border border-[hsl(var(--border))] rounded-lg px-3 py-1.5 text-sm text-foreground outline-none focus:border-[hsl(var(--primary))] placeholder:text-muted-foreground"
              onKeyDown={(e) => {
                if (e.key === 'Enter') handleCreate();
                if (e.key === 'Escape') { setShowCreate(false); setNewRoomName(''); }
              }}
              onBlur={() => {
                if (!newRoomName.trim()) { setShowCreate(false); }
              }}
              autoFocus
            />
            <button
              onClick={handleCreate}
              disabled={!newRoomName.trim() || createRoom.isPending}
              className="p-1.5 rounded-lg transition-colors text-primary hover:bg-[hsl(var(--muted))] disabled:opacity-30 disabled:text-muted-foreground"
            >
              <Check className="h-4 w-4" />
            </button>
          </div>
        ) : (
          <button
            onClick={() => setShowCreate(true)}
            className="flex items-center gap-2 w-full px-3 py-2 text-sm text-[hsl(var(--primary))] hover:bg-[hsl(var(--muted))] rounded-lg transition-colors"
          >
            <Plus className="w-4 h-4" />
            {t('im.newGroup')}
          </button>
        )}
      </div>

      {/* 搜索框 */}
      <div className="px-3 pb-2">
        <div className="flex items-center gap-2 bg-[hsl(var(--card))] border border-[hsl(var(--border))] rounded-lg px-2.5 py-1.5">
          <Search className="h-3.5 w-3.5 text-muted-foreground shrink-0" />
          <input
            value={searchInput}
            onChange={(e) => handleSearchChange(e.target.value)}
            placeholder={t('im.searchPlaceholder')}
            className="flex-1 bg-transparent text-sm text-foreground outline-none placeholder:text-muted-foreground"
          />
        </div>
      </div>

      {/* 列表 */}
      <div className="flex-1 overflow-y-auto">
        {isLoading && (
          <div className="px-4 py-8 text-center text-xs text-muted-foreground">Loading...</div>
        )}

        {!isLoading && displayedRooms.length === 0 && (
          <div className="px-4 py-8 text-center text-xs text-muted-foreground">
            {t('im.empty.noRooms')}
          </div>
        )}

        {displayedRooms.map((room) => {
          const isActive = selectedRoomId === room.id;
          const isDM = room.type === 'dm';
          const unreadCount = roomUnreadCounts.get(room.id) || 0;

          return (
            <div
              key={room.id}
              onClick={() => selectRoom(room.id)}
              className={`flex items-center gap-2 mx-2 pl-3 pr-1 py-2 rounded-lg cursor-pointer text-[13px] transition-colors ${
                isActive
                  ? 'bg-[hsl(var(--muted))] text-foreground font-semibold'
                  : 'hover:bg-[hsl(var(--muted))] text-foreground/60 hover:text-foreground'
              }`}
            >
              {isDM ? (
                <Bot className="h-3.5 w-3.5 shrink-0 text-primary" />
              ) : (
                <MessageSquare className="h-3.5 w-3.5 shrink-0" />
              )}

              <div className="flex-1 min-w-0">
                <div className="flex items-center justify-between">
                  <span className="truncate">
                    {room.type === 'workspace' ? t('im.workspace.selfHint') : room.name}
                  </span>
                  <span className="text-[11px] text-muted-foreground flex-shrink-0 ml-2">
                    {formatRelativeTime(room.lastMessageTime)}
                  </span>
                </div>
                {room.lastMessage && (
                  <div className="text-xs text-muted-foreground truncate mt-0.5">
                    {room.lastMessage}
                  </div>
                )}
              </div>

              {unreadCount > 0 && (
                <span className="ml-auto min-w-[18px] h-[18px] rounded-full bg-[hsl(var(--primary))] text-[hsl(var(--primary-foreground))] text-[10px] font-bold flex items-center justify-center px-1 flex-shrink-0">
                  {unreadCount > 99 ? '99+' : unreadCount}
                </span>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}
