import { useState } from 'react';
import { t } from '@/locales';
import { RoomList } from './RoomList';
import { TaskBoard } from './TaskBoard';
import { AgentList } from './AgentList';
import { useWsConnection } from '@/stores/ws-connection';
import { useRooms } from '@/queries/use-hub';
import { useSettingsStore } from '@/stores/settings';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Save, Server } from 'lucide-react';

export function HubDashboard() {
  const [activeTab, setActiveTab] = useState<'rooms' | 'tasks' | 'agents'>('rooms');
  const [selectedRoomId, setSelectedRoomId] = useState<string | undefined>();
  const status = useWsConnection((s) => s.status);
  const { data: rooms, error: roomsError } = useRooms();
  const settings = useSettingsStore((s) => s.settings);
  const client = useWsConnection((s) => s.client);

  const hub = settings?.hub;
  const [hubUrl, setHubUrl] = useState(hub?.serverUrl ?? '');
  const [hubSaved, setHubSaved] = useState(false);
  const [showConfig, setShowConfig] = useState(false);

  const needsConfig = !hub?.serverUrl;
  const hasError = roomsError || (needsConfig && rooms === undefined);

  const handleHubSave = () => {
    client?.send({
      type: 'settings.update',
      hub: { serverUrl: hubUrl, enabled: !!hubUrl, updateUrl: hub?.updateUrl ?? '', adminToken: '' },
    });
    setHubSaved(true);
    setTimeout(() => setHubSaved(false), 2000);
  };

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
        <button
          onClick={() => setShowConfig(!showConfig)}
          className={`ml-auto rounded-md p-1.5 transition-colors ${showConfig ? 'bg-muted' : 'text-muted-foreground hover:bg-muted'}`}
          title={t('hub.settings.title')}
        >
          <Server className="h-4 w-4" />
        </button>
        {selectedRoomId && !showConfig && (
          <span className="ml-auto text-xs text-muted-foreground">
            {t('hub.room.selected')}: {selectedRoomId.slice(0, 8)}...
          </span>
        )}
      </div>

      {showConfig && (
        <div className="border-b bg-muted/30 px-4 py-3 space-y-3">
          <div className="flex items-end gap-3">
            <div className="flex-1 space-y-1">
              <Label className="text-xs">{t('hub.settings.serverUrl')}</Label>
              <Input
                placeholder="http://server:5882"
                value={hubUrl}
                onChange={(e) => setHubUrl(e.target.value)}
                className="h-8 text-sm"
              />
            </div>
            <Button variant="outline" size="sm" onClick={handleHubSave} disabled={!hubUrl}>
              <Save className="h-3.5 w-3.5 mr-1" />
              {hubSaved ? t('common.saved') : t('hub.settings.save')}
            </Button>
          </div>
          {hasError && (
            <p className="text-xs text-destructive">
              {roomsError ? (roomsError as Error).message : t('hub.status.notConfigured')}
            </p>
          )}
        </div>
      )}

      {!showConfig && hasError && (
        <div className="border-b bg-destructive/5 px-4 py-2 text-sm text-destructive flex items-center justify-between">
          <span>{roomsError ? (roomsError as Error).message : t('hub.status.notConfigured')}</span>
          <button onClick={() => setShowConfig(true)} className="text-xs underline">
            {t('hub.settings.title')}
          </button>
        </div>
      )}

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
