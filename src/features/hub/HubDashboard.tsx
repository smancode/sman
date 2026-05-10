import { useState } from 'react';
import { t } from '@/locales';
import { RoomList } from './RoomList';
import { TaskBoard } from './TaskBoard';
import { AgentList } from './AgentList';
import { useWsConnection } from '@/stores/ws-connection';

export function HubDashboard() {
  const [activeTab, setActiveTab] = useState<'rooms' | 'tasks' | 'agents'>('rooms');
  const [selectedRoomId, setSelectedRoomId] = useState<string | undefined>();
  const status = useWsConnection((s) => s.status);

  if (status !== 'connected') {
    return (
      <div className="flex h-full items-center justify-center text-muted-foreground">
        {t('hub.status.notConnected')}
      </div>
    );
  }

  return (
    <div className="flex h-full flex-col">
      <div className="flex items-center gap-1 border-b px-4 py-2">
        {(['rooms', 'tasks', 'agents'] as const).map((tab) => (
          <button
            key={tab}
            onClick={() => setActiveTab(tab)}
            className={`rounded-md px-3 py-1.5 text-sm transition-colors ${
              activeTab === tab
                ? 'bg-primary text-primary-foreground'
                : 'text-muted-foreground hover:bg-muted'
            }`}
          >
            {t(`hub.tab.${tab}`)}
          </button>
        ))}
        {selectedRoomId && (
          <span className="ml-auto text-xs text-muted-foreground">
            {t('hub.room.selected')}: {selectedRoomId.slice(0, 8)}...
          </span>
        )}
      </div>

      <div className="flex-1 overflow-auto">
        {activeTab === 'rooms' && (
          <RoomList selectedRoomId={selectedRoomId} onSelectRoom={setSelectedRoomId} />
        )}
        {activeTab === 'tasks' && selectedRoomId && (
          <TaskBoard roomId={selectedRoomId} />
        )}
        {activeTab === 'tasks' && !selectedRoomId && (
          <div className="flex h-full items-center justify-center text-muted-foreground">
            {t('hub.task.selectRoom')}
          </div>
        )}
        {activeTab === 'agents' && selectedRoomId && (
          <AgentList roomId={selectedRoomId} />
        )}
        {activeTab === 'agents' && !selectedRoomId && (
          <div className="flex h-full items-center justify-center text-muted-foreground">
            {t('hub.agent.selectRoom')}
          </div>
        )}
      </div>
    </div>
  );
}
