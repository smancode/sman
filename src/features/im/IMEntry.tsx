import { useState, useMemo } from 'react';
import { useLocale, t } from '@/locales';
import { useIMStore } from '@/stores/im';
import { useRoomList } from '@/queries/use-im';
import { GroupChatList } from './GroupChatList';
import { SessionList } from './SessionList';
import { ChatWindow } from './ChatWindow';
import { MemberPanel } from './MemberPanel';

// ---------------------------------------------------------------------------
// Tab type
// ---------------------------------------------------------------------------

type TabType = 'sessions' | 'groups';

// ---------------------------------------------------------------------------
// IMEntry — three-column layout: left panel + center chat + right members
// ---------------------------------------------------------------------------

export default function IMEntry() {
  useLocale();

  const { activeTab, selectedRoomId, selectRoom, setActiveTab } = useIMStore();
  const { data: rooms = [] } = useRoomList();
  const [showMembers, setShowMembers] = useState(false);

  const selectedRoom = useMemo(
    () => rooms.find((r) => r.id === selectedRoomId) ?? null,
    [rooms, selectedRoomId],
  );
  const onlineUsers = useIMStore((s) => s.onlineUsers);

  // Client identity: "username@host"
  const clientId = useMemo(() => {
    if (typeof window === 'undefined') return '';
    return localStorage.getItem('sman-client-id') || '';
  }, []);

  return (
    <div className="flex h-full w-full overflow-hidden">
      {/* ===== LEFT PANEL: 280px ===== */}
      <div className="w-[280px] flex-shrink-0 bg-[hsl(var(--card))] border-r border-[hsl(var(--border))] flex flex-col">
        {/* Tab switcher */}
        <div className="flex border-b border-[hsl(var(--border))]">
          <button
            onClick={() => setActiveTab('sessions')}
            className={`flex-1 py-2.5 text-center text-[13px] cursor-pointer border-b-2 transition-colors ${
              activeTab === 'sessions'
                ? 'text-[hsl(var(--primary))] border-b-[hsl(var(--primary))]'
                : 'text-muted-foreground border-b-transparent hover:text-[hsl(var(--muted-foreground))]'
            }`}
          >
            {t('im.sessions')}
          </button>
          <button
            onClick={() => setActiveTab('groups')}
            className={`flex-1 py-2.5 text-center text-[13px] cursor-pointer border-b-2 transition-colors ${
              activeTab === 'groups'
                ? 'text-[hsl(var(--primary))] border-b-[hsl(var(--primary))]'
                : 'text-muted-foreground border-b-transparent hover:text-[hsl(var(--muted-foreground))]'
            }`}
          >
            {t('im.groups')}
          </button>
        </div>

        {/* List content */}
        <div className="flex-1 overflow-hidden">
          {activeTab === 'groups' ? (
            <GroupChatList
              selectedRoomId={selectedRoomId}
              onSelect={selectRoom}
            />
          ) : (
            <SessionList
              selectedRoomId={selectedRoomId}
              onSelect={selectRoom}
            />
          )}
        </div>
      </div>

      {/* ===== CENTER: Chat Window ===== */}
      <ChatWindow
        roomId={selectedRoomId}
        onToggleMembers={() => setShowMembers((v) => !v)}
      />

      {/* ===== RIGHT: Member Panel ===== */}
      {showMembers && (
        <MemberPanel
          room={selectedRoom}
          onlineUsers={onlineUsers}
          clientId={clientId}
        />
      )}
    </div>
  );
}
