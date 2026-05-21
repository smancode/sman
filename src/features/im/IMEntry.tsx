import { useState, useMemo } from 'react';
import { useLocale } from '@/locales';
import { useIMStore } from '@/stores/im';
import { useRoomList } from '@/queries/use-im';
import { ChatWindow } from './ChatWindow';
import { MemberPanel } from './MemberPanel';

// ---------------------------------------------------------------------------
// IMEntry — center chat + right members (left list is in Sidebar IMListPanel)
// ---------------------------------------------------------------------------

export default function IMEntry() {
  useLocale();

  const selectedRoomId = useIMStore((s) => s.selectedRoomId);
  const { data: rooms = [] } = useRoomList();
  const [showMembers, setShowMembers] = useState(false);

  const selectedRoom = useMemo(
    () => rooms.find((r) => r.id === selectedRoomId) ?? null,
    [rooms, selectedRoomId],
  );
  const onlineUsers = useIMStore((s) => s.onlineUsers);

  const clientId = useMemo(() => {
    if (typeof window === 'undefined') return '';
    return localStorage.getItem('sman-client-id') || '';
  }, []);

  return (
    <div className="flex h-full w-full overflow-hidden">
      <ChatWindow
        roomId={selectedRoomId}
        onToggleMembers={() => setShowMembers((v) => !v)}
      />

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
