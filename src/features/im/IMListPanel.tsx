import { useState } from 'react';
import { Plus } from 'lucide-react';
import { t } from '@/locales';
import { useRoomList, useCreateRoom } from '@/queries/use-im';
import { useIMStore } from '@/stores/im';
import { getAgentColor } from './utils';
import type { IMRoom } from '@/schemas/im';

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
  const createRoom = useCreateRoom();
  const [showCreate, setShowCreate] = useState(false);
  const [newRoomName, setNewRoomName] = useState('');

  // 混合显示 dm + group + workspace，按 lastMessageTime 降序
  const sortedRooms = [...rooms]
    .filter((r: IMRoom) => r.type === 'dm' || r.type === 'group' || r.type === 'workspace')
    .sort((a: IMRoom, b: IMRoom) => (b.lastMessageTime || 0) - (a.lastMessageTime || 0));

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
      {/* 新建群聊 */}
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
              autoFocus
            />
            <button
              onClick={handleCreate}
              disabled={!newRoomName.trim() || createRoom.isPending}
              className="bg-[hsl(var(--primary))] text-primary-foreground text-xs px-3 py-1.5 rounded-lg hover:opacity-85 disabled:opacity-50"
            >
              {t('im.createRoom.confirm')}
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

      {/* 列表 */}
      <div className="flex-1 overflow-y-auto">
        {isLoading && (
          <div className="px-4 py-8 text-center text-xs text-muted-foreground">Loading...</div>
        )}

        {!isLoading && sortedRooms.length === 0 && (
          <div className="px-4 py-8 text-center text-xs text-muted-foreground">
            {t('im.empty.noRooms')}
          </div>
        )}

        {sortedRooms.map((room: IMRoom) => {
          const isActive = selectedRoomId === room.id;
          const isDM = room.type === 'dm';
          const isWorkspace = room.type === 'workspace';

          // 私聊：用 agent 颜色头像；群聊：用 emoji
          const color = isDM ? getAgentColor(room.members[0] || room.id) : '';

          return (
            <div
              key={room.id}
              onClick={() => selectRoom(room.id)}
              className={`flex items-center gap-2.5 px-3 py-2.5 cursor-pointer transition-colors ${
                isActive ? 'bg-[hsl(var(--muted))]' : 'hover:bg-[hsl(var(--muted))]'
              }`}
            >
              {/* 头像 */}
              <div
                className={`w-10 h-10 rounded-xl flex items-center justify-center text-sm font-semibold flex-shrink-0 ${
                  isDM ? 'border-2' : ''
                }`}
                style={isDM ? {
                  borderColor: color,
                  backgroundColor: `${color}1a`,
                } : {
                  backgroundColor: 'hsl(var(--muted))',
                }}
              >
                {isDM ? '🤖' : getRoomEmoji(room.name)}
              </div>

              {/* 信息 */}
              <div className="flex-1 min-w-0">
                <div className="flex items-center justify-between mb-0.5">
                  <span className="text-sm font-medium truncate" style={isDM ? { color } : undefined}>
                    {isWorkspace ? t('im.workspace.selfHint') : room.name}
                  </span>
                  <span className="text-[11px] text-muted-foreground flex-shrink-0 ml-2">
                    {formatRelativeTime(room.lastMessageTime)}
                  </span>
                </div>
                <div className="text-xs text-[hsl(var(--muted-foreground))] truncate">
                  {room.lastMessage || ''}
                </div>
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}
