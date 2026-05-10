import { useState } from 'react';
import { t } from '@/locales';
import { PageLayout } from '@/components/common/PageLayout';
import { FeedbackState } from '@/components/common/FeedbackState';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { ScrollArea } from '@/components/ui/scroll-area';
import { Tooltip, TooltipContent, TooltipTrigger, TooltipProvider } from '@/components/ui/tooltip';
import { Separator } from '@/components/ui/separator';
import { Badge } from '@/components/ui/badge';
import { useWsConnection } from '@/stores/ws-connection';
import { useSettingsStore } from '@/stores/settings';
import { useRooms, useCreateRoom, useJoinRoom, useLeaveRoom, useRoomAgents } from '@/queries/use-hub';
import { TaskBoard } from './TaskBoard';
import { TaskDetail } from './TaskDetail';
import { AgentList } from './AgentList';
import {
  ChevronLeft, Server, Plus, LogIn, LogOut, Settings2, Users, ListTodo, Bot,
} from 'lucide-react';
import { cn } from '@/lib/utils';
import { useNavigate } from 'react-router-dom';

export function HubDashboard() {
  const navigate = useNavigate();
  const [selectedRoomId, setSelectedRoomId] = useState<string | undefined>();
  const [rightTab, setRightTab] = useState<'tasks' | 'agents'>('tasks');
  const [selectedTaskId, setSelectedTaskId] = useState<string | undefined>();
  const status = useWsConnection((s) => s.status);
  const { data: rooms, error: roomsError, isLoading } = useRooms();
  const { data: agents } = useRoomAgents(selectedRoomId);
  const settings = useSettingsStore((s) => s.settings);
  const client = useWsConnection((s) => s.client);

  const hub = settings?.hub;
  const [showConfig, setShowConfig] = useState(false);
  const [hubUrl, setHubUrl] = useState(hub?.serverUrl ?? '');
  const [hubSaved, setHubSaved] = useState(false);

  const [showCreate, setShowCreate] = useState(false);
  const [newName, setNewName] = useState('');
  const [joinId, setJoinId] = useState('');
  const [error, setError] = useState('');
  const createRoom = useCreateRoom();
  const joinRoom = useJoinRoom();
  const leaveRoom = useLeaveRoom();

  const handleHubSave = () => {
    client?.send({
      type: 'settings.update',
      hub: { serverUrl: hubUrl, enabled: !!hubUrl, updateUrl: hub?.updateUrl ?? '', adminToken: '' },
    });
    setHubSaved(true);
    setTimeout(() => setHubSaved(false), 2000);
  };

  const handleCreate = () => {
    if (!newName.trim()) return;
    setError('');
    createRoom.mutate({ name: newName.trim() }, {
      onSuccess: () => { setNewName(''); setShowCreate(false); },
      onError: (err) => setError(err.message),
    });
  };

  const handleJoin = () => {
    if (!joinId.trim()) return;
    setError('');
    joinRoom.mutate({ roomId: joinId.trim() }, {
      onSuccess: () => setJoinId(''),
      onError: (err) => setError(err.message),
    });
  };

  if (status !== 'connected') {
    return <FeedbackState state="error" title={t('hub.status.notConnected')} />;
  }

  const needsConfig = !hub?.serverUrl;

  const sidebar = (
    <TooltipProvider delayDuration={300}>
      <button
        onClick={() => navigate(-1)}
        className="flex items-center gap-1.5 text-sm text-muted-foreground hover:text-foreground mb-4 px-2"
      >
        <ChevronLeft className="h-4 w-4" />
        {t('cron.back')}
      </button>
      <div className="flex items-center justify-between mb-2">
        <span className="text-xs font-semibold uppercase text-muted-foreground tracking-wider">
          {t('hub.tab.rooms')}
        </span>
        <div className="flex items-center gap-0.5">
          <Tooltip>
            <TooltipTrigger asChild>
              <button
                onClick={() => { setShowCreate(!showCreate); setError(''); }}
                className="rounded p-1 text-muted-foreground hover:bg-muted hover:text-foreground transition-colors"
              >
                <Plus className="h-3.5 w-3.5" />
              </button>
            </TooltipTrigger>
            <TooltipContent side="right">{t('hub.room.create')}</TooltipContent>
          </Tooltip>
          <Tooltip>
            <TooltipTrigger asChild>
              <button
                onClick={() => setShowConfig(!showConfig)}
                className={cn(
                  'rounded p-1 transition-colors',
                  showConfig ? 'bg-muted text-foreground' : 'text-muted-foreground hover:bg-muted hover:text-foreground'
                )}
              >
                <Settings2 className="h-3.5 w-3.5" />
              </button>
            </TooltipTrigger>
            <TooltipContent side="right">{t('hub.settings.title')}</TooltipContent>
          </Tooltip>
        </div>
      </div>

      {showConfig && (
        <div className="mb-2 space-y-2 rounded-md border bg-muted/30 p-2.5">
          <Input
            placeholder="http://server:5882"
            value={hubUrl}
            onChange={(e) => setHubUrl(e.target.value)}
            className="h-7 text-xs"
          />
          <Button variant="outline" size="sm" className="w-full h-7 text-xs" onClick={handleHubSave} disabled={!hubUrl}>
            {hubSaved ? t('common.saved') : t('hub.settings.save')}
          </Button>
          {needsConfig && (
            <p className="text-[11px] text-destructive">{t('hub.status.notConfigured')}</p>
          )}
        </div>
      )}

      {showCreate && (
        <div className="mb-2 space-y-1.5">
          <div className="flex gap-1.5">
            <Input
              value={newName}
              onChange={(e) => setNewName(e.target.value)}
              placeholder={t('hub.room.namePlaceholder')}
              className="h-7 text-xs flex-1"
              onKeyDown={(e) => e.key === 'Enter' && handleCreate()}
              autoFocus
            />
            <Button size="sm" className="h-7 px-2.5 text-xs shrink-0" onClick={handleCreate} disabled={!newName.trim()}>
              {t('common.confirm')}
            </Button>
          </div>
        </div>
      )}

      <ScrollArea className="flex-1 -mx-1">
        <div className="space-y-0.5 px-1">
          {isLoading ? (
            <div className="py-4 text-center text-xs text-muted-foreground">{t('common.loading')}</div>
          ) : !rooms || rooms.length === 0 ? (
            <div className="py-4 text-center text-xs text-muted-foreground">{t('hub.room.empty')}</div>
          ) : (
            rooms.map((room) => (
              <div
                key={room.id}
                onClick={() => { setSelectedRoomId(room.id); setSelectedTaskId(undefined); }}
                className={cn(
                  'group flex items-center gap-2 rounded-md px-2 py-1.5 cursor-pointer transition-colors text-sm',
                  selectedRoomId === room.id
                    ? 'bg-primary/10 text-primary font-medium'
                    : 'text-foreground hover:bg-muted'
                )}
              >
                <span className="truncate flex-1">{room.name}</span>
                <button
                  onClick={(e) => {
                    e.stopPropagation();
                    leaveRoom.mutate({ roomId: room.id });
                    if (selectedRoomId === room.id) setSelectedRoomId(undefined);
                  }}
                  className="shrink-0 opacity-0 group-hover:opacity-100 rounded p-0.5 text-muted-foreground hover:text-destructive transition-all"
                >
                  <LogOut className="h-3 w-3" />
                </button>
              </div>
            ))
          )}
        </div>
      </ScrollArea>

      <Separator className="my-2" />

      <div className="flex gap-1.5">
        <Input
          value={joinId}
          onChange={(e) => setJoinId(e.target.value)}
          placeholder={t('hub.room.joinId')}
          className="h-7 text-xs flex-1"
          onKeyDown={(e) => e.key === 'Enter' && handleJoin()}
        />
        <Button variant="ghost" size="sm" className="h-7 px-2 text-xs shrink-0" onClick={handleJoin} disabled={!joinId.trim()}>
          <LogIn className="h-3 w-3 mr-1" />
          {t('hub.room.join')}
        </Button>
      </div>

      {error && (
        <p className="mt-1.5 text-[11px] text-destructive truncate" title={error}>{error}</p>
      )}

      {roomsError && !needsConfig && (
        <p className="mt-1.5 text-[11px] text-destructive">{(roomsError as Error).message}</p>
      )}
    </TooltipProvider>
  );

  const sidebarFooter = !showConfig && needsConfig ? (
    <Button variant="ghost" size="sm" className="w-full text-xs text-muted-foreground" onClick={() => setShowConfig(true)}>
      <Server className="h-3 w-3 mr-1.5" />
      {t('hub.settings.title')}
    </Button>
  ) : undefined;

  const content = selectedRoomId ? (
    <div className="flex h-full flex-col">
      {selectedTaskId ? (
        <TaskDetail
          taskId={selectedTaskId}
          agents={agents || []}
          onBack={() => setSelectedTaskId(undefined)}
        />
      ) : (
        <>
          <div className="flex items-center gap-2 border-b px-6 py-3">
            <div className="flex items-center gap-2">
              <button
                onClick={() => setRightTab('tasks')}
                className={cn(
                  'flex items-center gap-1.5 rounded-md px-3 py-1.5 text-sm transition-colors',
                  rightTab === 'tasks' ? 'bg-primary/10 text-primary font-medium' : 'text-muted-foreground hover:bg-muted'
                )}
              >
                <ListTodo className="h-3.5 w-3.5" />
                {t('hub.tab.tasks')}
              </button>
              <button
                onClick={() => setRightTab('agents')}
                className={cn(
                  'flex items-center gap-1.5 rounded-md px-3 py-1.5 text-sm transition-colors',
                  rightTab === 'agents' ? 'bg-primary/10 text-primary font-medium' : 'text-muted-foreground hover:bg-muted'
                )}
              >
                <Bot className="h-3.5 w-3.5" />
                {t('hub.tab.agents')}
              </button>
            </div>
            <Badge variant="secondary" className="ml-auto text-[11px] font-mono">
              {selectedRoomId.slice(0, 8)}
            </Badge>
          </div>

          <div className="flex-1 overflow-hidden">
            {rightTab === 'tasks' && (
              <TaskBoard roomId={selectedRoomId} onSelectTask={(id) => setSelectedTaskId(id)} />
            )}
            {rightTab === 'agents' && <AgentList roomId={selectedRoomId} />}
          </div>
        </>
      )}
    </div>
  ) : (
    <FeedbackState
      state={isLoading ? 'loading' : rooms && rooms.length === 0 ? 'empty' : 'empty'}
      title={t('hub.room.selected')}
      description={t('hub.task.selectRoom')}
    />
  );

  return (
    <PageLayout
      sidebar={sidebar}
      sidebarFooter={sidebarFooter}
      contentClassName="h-full p-0"
    >
      {content}
    </PageLayout>
  );
}
