import { useState } from 'react';
import { Plus } from 'lucide-react';
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
              className="flex-1 bg-[#1a1a24] border border-[#2a2a38] rounded-lg px-3 py-1.5 text-sm text-[#e8e8ed] outline-none focus:border-[#6c5ce7] placeholder:text-[#555568]"
              onKeyDown={(e) => {
                if (e.key === 'Enter') handleCreate();
                if (e.key === 'Escape') { setShowCreate(false); setNewRoomName(''); }
              }}
              autoFocus
            />
            <button
              onClick={handleCreate}
              disabled={!newRoomName.trim() || createRoom.isPending}
              className="bg-[#6c5ce7] text-white text-xs px-3 py-1.5 rounded-lg hover:opacity-85 disabled:opacity-50"
            >
              {t('im.createRoom.confirm')}
            </button>
          </div>
        ) : (
          <button
            onClick={() => setShowCreate(true)}
            className="flex items-center gap-2 w-full px-3 py-2 text-sm text-[#a29bfe] hover:bg-[#22222e] rounded-lg transition-colors"
          >
            <Plus className="w-4 h-4" />
            {t('im.newGroup')}
          </button>
        )}
      </div>

      {/* Room list */}
      <div className="flex-1 overflow-y-auto">
        {isLoading && (
          <div className="px-4 py-8 text-center text-xs text-[#555568]">Loading...</div>
        )}

        {!isLoading && groupRooms.length === 0 && (
          <div className="px-4 py-8 text-center text-xs text-[#555568]">
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
                isActive ? 'bg-[rgba(108,92,231,0.12)]' : 'hover:bg-[#22222e]'
              }`}
            >
              {/* Avatar */}
              <div className={`w-10 h-10 rounded-xl flex items-center justify-center text-sm font-semibold flex-shrink-0 ${
                isWorkspace ? 'bg-[rgba(108,92,231,0.12)]' : 'bg-[#2a2a38]'
              }`}>
                {emoji}
              </div>

              {/* Info */}
              <div className="flex-1 min-w-0">
                <div className="flex items-center justify-between mb-0.5">
                  <span className="text-sm font-medium truncate">{room.name}</span>
                  <span className="text-[11px] text-[#555568] flex-shrink-0 ml-2">
                    {formatRelativeTime(room.lastMessageTime)}
                  </span>
                </div>
                {isWorkspace ? (
                  <div className="text-xs text-[#555568] italic truncate">
                    {t('im.workspace.selfHint')}
                  </div>
                ) : (
                  <div className="text-xs text-[#8888a0] truncate">
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
