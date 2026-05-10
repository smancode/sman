import { useState } from 'react';
import { t } from '@/locales';
import { useRooms, useCreateRoom, useJoinRoom, useLeaveRoom } from '@/queries/use-hub';

interface RoomListProps {
  selectedRoomId?: string;
  onSelectRoom: (roomId: string) => void;
}

export function RoomList({ selectedRoomId, onSelectRoom }: RoomListProps) {
  const { data: rooms, isLoading } = useRooms();
  const createRoom = useCreateRoom();
  const joinRoom = useJoinRoom();
  const leaveRoom = useLeaveRoom();
  const [showCreate, setShowCreate] = useState(false);
  const [newName, setNewName] = useState('');
  const [joinId, setJoinId] = useState('');

  const handleCreate = () => {
    if (!newName.trim()) return;
    createRoom.mutate({ name: newName.trim() }, {
      onSuccess: () => { setNewName(''); setShowCreate(false); },
    });
  };

  const handleJoin = () => {
    if (!joinId.trim()) return;
    joinRoom.mutate({ roomId: joinId.trim() }, {
      onSuccess: () => setJoinId(''),
    });
  };

  const handleLeave = (roomId: string) => {
    leaveRoom.mutate({ roomId });
    if (selectedRoomId === roomId) onSelectRoom('');
  };

  if (isLoading) {
    return <div className="p-4 text-muted-foreground">{t('common.loading')}</div>;
  }

  return (
    <div className="p-4 space-y-3">
      <div className="flex items-center gap-2">
        <button
          onClick={() => setShowCreate(!showCreate)}
          className="rounded-md bg-primary px-3 py-1.5 text-sm text-primary-foreground hover:bg-primary/90"
        >
          {t('hub.room.create')}
        </button>
        <input
          value={joinId}
          onChange={(e) => setJoinId(e.target.value)}
          placeholder={t('hub.room.joinId')}
          className="flex-1 rounded-md border px-2 py-1.5 text-sm"
          onKeyDown={(e) => e.key === 'Enter' && handleJoin()}
        />
        <button
          onClick={handleJoin}
          disabled={!joinId.trim()}
          className="rounded-md bg-secondary px-3 py-1.5 text-sm hover:bg-secondary/80 disabled:opacity-50"
        >
          {t('hub.room.join')}
        </button>
      </div>

      {showCreate && (
        <div className="flex items-center gap-2">
          <input
            value={newName}
            onChange={(e) => setNewName(e.target.value)}
            placeholder={t('hub.room.namePlaceholder')}
            className="flex-1 rounded-md border px-2 py-1.5 text-sm"
            onKeyDown={(e) => e.key === 'Enter' && handleCreate()}
            autoFocus
          />
          <button onClick={handleCreate} disabled={!newName.trim()} className="rounded-md bg-primary px-3 py-1.5 text-sm text-primary-foreground disabled:opacity-50">
            {t('common.confirm')}
          </button>
        </div>
      )}

      {!rooms || rooms.length === 0 ? (
        <div className="text-muted-foreground text-sm">{t('hub.room.empty')}</div>
      ) : (
        <div className="space-y-2">
          {rooms.map((room) => (
            <div
              key={room.id}
              onClick={() => onSelectRoom(room.id)}
              className={`cursor-pointer rounded-lg border p-3 transition-colors ${
                selectedRoomId === room.id ? 'border-primary bg-primary/5' : 'hover:bg-muted'
              }`}
            >
              <div className="flex items-center justify-between">
                <span className="font-medium">{room.name}</span>
                <span className="text-xs text-muted-foreground">
                  {room.owner_id.slice(0, 8)}...
                </span>
              </div>
              {room.description && (
                <p className="mt-1 text-sm text-muted-foreground">{room.description}</p>
              )}
              <div className="mt-2 flex items-center gap-2">
                <button
                  onClick={(e) => { e.stopPropagation(); handleLeave(room.id); }}
                  className="text-xs text-muted-foreground hover:text-destructive"
                >
                  {t('hub.room.leave')}
                </button>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
