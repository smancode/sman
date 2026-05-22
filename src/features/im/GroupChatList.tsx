import { useState } from 'react';
import { Plus, Check } from 'lucide-react';
import { t } from '@/locales';
import { useRoomList, useCreateRoom } from '@/queries/use-im';
import type { IMRoom } from '@/schemas/im';

// ---------------------------------------------------------------------------
// Props
// ---------------------------------------------------------------------------

interface GroupChatListProps {
  selectedRoomId: string | null;
  onSelect: (roomId: string) => void;
}

// ---------------------------------------------------------------------------
// Helper: room avatar emoji (first emoji char, or default)
// ---------------------------------------------------------------------------

const DEFAULT_EMOJI = '🎯';

function getRoomEmoji(name: string): string {
  const emojiRegex = /\p{Emoji_Presentation}/u;
  for (const ch of name) {
    if (emojiRegex.test(ch)) return ch;
  }
  return DEFAULT_EMOJI;
}

// ---------------------------------------------------------------------------
// Helper: relative time
// ---------------------------------------------------------------------------

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

export function GroupChatList({ selectedRoomId, onSelect }: GroupChatListProps) {
  const { data: rooms = [], isLoading } = useRoomList();
  const createRoom = useCreateRoom();
  const [showCreate, setShowCreate] = useState(false);
  const [newRoomName, setNewRoomName] = useState('');

  // Filter to group + workspace rooms only
  const groupRooms = rooms.filter((r: IMRoom) => r.type === 'group' || r.type === 'workspace');

  const handleCreate = () => {
    const name = newRoomName.trim();
    if (!name) return;
    createRoom.mutate(
      { name, type: 'group' },
      {
        onSuccess: (room: IMRoom) => {
          onSelect(room.id);
          setShowCreate(false);
          setNewRoomName('');
        },
      },
    );
  };

  return (
    <div className="flex flex-col h-full">
      {/* Create group button */}
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

      {/* Room list */}
      <div className="flex-1 overflow-y-auto">
        {isLoading && (
          <div className="px-4 py-8 text-center text-xs text-muted-foreground">Loading...</div>
        )}

        {!isLoading && groupRooms.length === 0 && (
          <div className="px-4 py-8 text-center text-xs text-muted-foreground">
            {t('im.empty.noRooms')}
          </div>
        )}

        {groupRooms.map((room: IMRoom) => {
          const isActive = selectedRoomId === room.id;
          const isWorkspace = room.type === 'workspace';
          const emoji = getRoomEmoji(room.name);

          return (
            <div
              key={room.id}
              onClick={() => onSelect(room.id)}
              className={`flex items-center gap-2.5 px-3 py-2.5 cursor-pointer transition-colors ${
                isActive ? 'bg-[hsl(var(--muted))]' : 'hover:bg-[hsl(var(--muted))]'
              }`}
            >
              {/* Avatar */}
              <div className={`w-10 h-10 rounded-xl flex items-center justify-center text-sm font-semibold flex-shrink-0 ${
                isWorkspace ? 'bg-[hsl(var(--muted))]' : 'bg-[hsl(var(--muted))]'
              }`}>
                {emoji}
              </div>

              {/* Info */}
              <div className="flex-1 min-w-0">
                <div className="flex items-center justify-between mb-0.5">
                  <span className="text-sm font-medium truncate">{room.name}</span>
                  <span className="text-[11px] text-muted-foreground flex-shrink-0 ml-2">
                    {formatRelativeTime(room.lastMessageTime)}
                  </span>
                </div>
                {isWorkspace ? (
                  <div className="text-xs text-muted-foreground italic truncate">
                    {t('im.workspace.selfHint')}
                  </div>
                ) : (
                  <div className="text-xs text-[hsl(var(--muted-foreground))] truncate">
                    {room.lastMessage || ''}
                  </div>
                )}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}
