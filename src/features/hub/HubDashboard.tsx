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
import { useRooms, useCreateRoom, useJoinRoom, useLeaveRoom, useDissolveRoom, useRoomAgents } from '@/queries/use-hub';
import { TaskBoard } from './TaskBoard';
import { TaskDetail } from './TaskDetail';
import { AgentList } from './AgentList';
import {
  ChevronLeft, Plus, LogIn, LogOut, ListTodo, Bot, Search, Globe, Lock, Trash2, KeyRound, Shield, Eye, EyeOff,
} from 'lucide-react';
import { cn } from '@/lib/utils';
import { useNavigate } from 'react-router-dom';

export function HubDashboard() {
  const navigate = useNavigate();
  const [selectedRoomId, setSelectedRoomId] = useState<string | undefined>();
  const [rightTab, setRightTab] = useState<'tasks' | 'agents'>('tasks');
  const [selectedTaskId, setSelectedTaskId] = useState<string | undefined>();
  const status = useWsConnection((s) => s.status);
  const { data: agents } = useRoomAgents(selectedRoomId);
  const settings = useSettingsStore((s) => s.settings);

  const hub = settings?.hub;

  const [showCreate, setShowCreate] = useState(false);
  const [newName, setNewName] = useState('');
  const [joinId, setJoinId] = useState('');
  const [error, setError] = useState('');
  const [searchInput, setSearchInput] = useState('');
  const [appliedSearch, setAppliedSearch] = useState('');
  const [page, setPage] = useState(0);
  const PAGE_SIZE = 20;
  const [newRoomPublic, setNewRoomPublic] = useState(false);
  const [newDesc, setNewDesc] = useState('');
  const [newMaxAgents, setNewMaxAgents] = useState(10);
  const [newPassword, setNewPassword] = useState('');
  const [joinPasswordRoom, setJoinPasswordRoom] = useState<{ roomId: string; inline?: boolean } | null>(null);
  const [joinPasswordValue, setJoinPasswordValue] = useState('');
  const [showPassword, setShowPassword] = useState(false);

  const { data: roomResult, error: roomsError, isLoading } = useRooms(appliedSearch, page * PAGE_SIZE, PAGE_SIZE);
  const rooms = roomResult?.rooms;
  const totalRooms = roomResult?.total ?? 0;
  const hubUnreachable = roomResult?.unreachable === true;
  const selectedRoom = rooms?.find((r) => r.id === selectedRoomId);
  const totalPages = Math.max(1, Math.ceil(totalRooms / PAGE_SIZE));

  const createRoom = useCreateRoom();
  const joinRoom = useJoinRoom();
  const leaveRoom = useLeaveRoom();
  const dissolveRoom = useDissolveRoom();

  const handleCreate = () => {
    if (!newName.trim()) return;
    if (newPassword.length > 0 && newPassword.length !== 4) {
      setError(t('hub.room.passwordPlaceholder'));
      return;
    }
    setError('');
    createRoom.mutate({
      name: newName.trim(),
      description: newDesc.trim() || undefined,
      maxAgents: newMaxAgents,
      visibility: newRoomPublic ? 'public' : 'private',
      password: newPassword.length === 4 ? newPassword : undefined,
    }, {
      onSuccess: () => {
        setNewName(''); setNewDesc(''); setNewMaxAgents(10); setNewPassword('');
        setShowCreate(false); setNewRoomPublic(false);
      },
      onError: (err) => setError(err.message),
    });
  };

  const handleJoin = (roomId: string, password?: string) => {
    setError('');
    joinRoom.mutate({ roomId, password }, {
      onSuccess: () => { setJoinId(''); setJoinPasswordRoom(null); setJoinPasswordValue(''); },
      onError: (err) => {
        const msg = err.message;
        if (msg.includes('not found') || msg.includes('404')) setError(t('hub.room.joinNotFound'));
        else if (msg.includes('full') || msg.includes('409')) setError(t('hub.room.joinFull'));
        else if (msg.includes('Wrong password') || msg.includes('403')) setError(t('hub.room.wrongPassword'));
        else setError(msg);
      },
    });
  };

  const handleJoinById = () => {
    if (!joinId.trim()) return;
    handleJoin(joinId.trim());
  };

  const handleJoinWithPassword = () => {
    if (!joinPasswordRoom) return;
    handleJoin(joinPasswordRoom.roomId, joinPasswordValue.trim());
  };

  if (status !== 'connected') {
    return <FeedbackState state="error" title={t('hub.status.notConnected')} />;
  }

  const sidebar = (
    <TooltipProvider delayDuration={300}>
      <button
        onClick={() => navigate(-1)}
        className="flex items-center gap-1.5 text-sm text-muted-foreground hover:text-foreground mb-4 px-2"
      >
        <ChevronLeft className="h-4 w-4" />
        {t('cron.back')}
      </button>
      {hubUnreachable && hub?.serverUrl && (
        <div className="mb-3 rounded-md border border-yellow-500/30 bg-yellow-500/10 px-2.5 py-1.5 text-[11px] text-yellow-600 dark:text-yellow-400">
          {t('hub.status.serverUnreachable')}
        </div>
      )}
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
        </div>
      </div>

      {showCreate && (
        <div className="mb-2 space-y-1.5">
          <Input
            value={newName}
            onChange={(e) => setNewName(e.target.value)}
            placeholder={t('hub.room.namePlaceholder')}
            className="h-7 text-xs"
            onKeyDown={(e) => e.key === 'Enter' && handleCreate()}
            autoFocus
          />
          <textarea
            value={newDesc}
            onChange={(e) => setNewDesc(e.target.value)}
            placeholder={t('hub.room.descriptionPlaceholder')}
            className="w-full rounded-md border bg-transparent px-2 py-1 text-xs resize-none h-14 focus:outline-none focus:ring-1 focus:ring-ring"
          />
          <div className="flex gap-1.5 items-center">
            <div className="flex items-center gap-1 flex-1">
              <span className="text-[11px] text-muted-foreground shrink-0">{t('hub.room.maxAgents')}</span>
              <Input
                type="number"
                min={1}
                max={100}
                value={newMaxAgents}
                onChange={(e) => setNewMaxAgents(Number(e.target.value) || 10)}
                className="h-6 w-14 text-xs text-center"
              />
            </div>
            <div className="flex items-center gap-1 flex-1">
              <KeyRound className="h-3 w-3 text-muted-foreground shrink-0" />
              <Input
                value={newPassword}
                onChange={(e) => { const v = e.target.value.replace(/\D/g, '').slice(0, 4); setNewPassword(v); }}
                placeholder={t('hub.room.passwordPlaceholder')}
                className="h-6 w-16 text-xs"
                maxLength={4}
              />
            </div>
          </div>
          <div className="flex items-center justify-between">
            <button
              onClick={() => setNewRoomPublic(!newRoomPublic)}
              className={cn(
                'flex items-center gap-1 rounded px-2 py-0.5 text-[11px] transition-colors',
                newRoomPublic ? 'bg-primary/10 text-primary' : 'bg-muted text-muted-foreground',
              )}
            >
              {newRoomPublic ? <Globe className="h-3 w-3" /> : <Lock className="h-3 w-3" />}
              {newRoomPublic ? t('hub.room.public') : t('hub.room.private')}
            </button>
            <Button size="sm" className="h-7 px-2.5 text-xs" onClick={handleCreate} disabled={!newName.trim()}>
              {t('common.confirm')}
            </Button>
          </div>
        </div>
      )}

      <div className="flex gap-1 mb-1.5">
        <Input
          value={searchInput}
          onChange={(e) => setSearchInput(e.target.value)}
          placeholder={t('hub.room.searchPlaceholder')}
          className="h-6 text-xs flex-1"
          onKeyDown={(e) => { if (e.key === 'Enter') { setAppliedSearch(searchInput.trim()); setPage(0); } }}
        />
        <Button
          variant="ghost"
          size="sm"
          className="h-6 w-6 p-0 shrink-0"
          onClick={() => { setAppliedSearch(searchInput.trim()); setPage(0); }}
        >
          <Search className="h-3 w-3" />
        </Button>
      </div>

      <ScrollArea className="flex-1 -mx-1">
        <div className="space-y-0.5 px-1">
          {isLoading ? (
            <div className="py-4 text-center text-xs text-muted-foreground">{t('common.loading')}</div>
          ) : !rooms || rooms.length === 0 ? (
            <div className="py-4 text-center text-xs text-muted-foreground">
              {appliedSearch ? t('hub.room.searchEmpty') : t('hub.room.empty')}
            </div>
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
                <span className="shrink-0 flex items-center gap-0.5">
                  {room.hasPassword ? (
                    <KeyRound className="h-3 w-3 text-amber-500" />
                  ) : room.visibility === 'public' ? (
                    <Globe className="h-3 w-3 text-muted-foreground" />
                  ) : (
                    <Lock className="h-3 w-3 text-muted-foreground" />
                  )}
                </span>
                <span className="truncate flex-1">{room.name}</span>
                <span className="shrink-0 text-[10px] text-muted-foreground/60 truncate max-w-[90px]" title={room.owner_id}>{room.owner_id}</span>
                {room.isOwner ? (
                  <button
                    onClick={(e) => {
                      e.stopPropagation();
                      dissolveRoom.mutate({ roomId: room.id }, {
                        onSuccess: () => { if (selectedRoomId === room.id) setSelectedRoomId(undefined); },
                        onError: (err) => setError(err.message),
                      });
                    }}
                    className="shrink-0 opacity-0 group-hover:opacity-100 rounded p-0.5 text-muted-foreground hover:text-destructive transition-all"
                    title={t('hub.room.dissolve')}
                  >
                    <Trash2 className="h-3 w-3" />
                  </button>
                ) : room.isMember ? (
                  <button
                    onClick={(e) => {
                      e.stopPropagation();
                      leaveRoom.mutate({ roomId: room.id });
                      if (selectedRoomId === room.id) setSelectedRoomId(undefined);
                    }}
                    className="shrink-0 opacity-0 group-hover:opacity-100 rounded p-0.5 text-muted-foreground hover:text-destructive transition-all"
                    title={t('hub.room.leave')}
                  >
                    <LogOut className="h-3 w-3" />
                  </button>
                ) : (
                  <button
                    onClick={(e) => {
                      e.stopPropagation();
                      if (room.hasPassword) {
                        setJoinPasswordRoom({ roomId: room.id });
                        setJoinPasswordValue('');
                      } else {
                        handleJoin(room.id);
                      }
                    }}
                    className="shrink-0 opacity-0 group-hover:opacity-100 rounded p-0.5 text-muted-foreground hover:text-primary transition-all"
                    title={t('hub.room.join')}
                  >
                    <LogIn className="h-3 w-3" />
                  </button>
                )}
              </div>
            ))
          )}
        </div>
        {totalPages > 1 && (
          <div className="flex items-center justify-between px-2 py-1.5 border-t text-[11px] text-muted-foreground">
            <button
              onClick={() => setPage(Math.max(0, page - 1))}
              disabled={page === 0}
              className="hover:text-foreground disabled:opacity-30 disabled:cursor-default"
            >
              {t('hub.room.prevPage')}
            </button>
            <span>{page + 1}/{totalPages}</span>
            <button
              onClick={() => setPage(Math.min(totalPages - 1, page + 1))}
              disabled={page >= totalPages - 1}
              className="hover:text-foreground disabled:opacity-30 disabled:cursor-default"
            >
              {t('hub.room.nextPage')}
            </button>
          </div>
        )}
      </ScrollArea>

      <Separator className="my-2" />

      <div className="flex gap-1.5">
        <Input
          value={joinId}
          onChange={(e) => setJoinId(e.target.value)}
          placeholder={t('hub.room.joinId')}
          className="h-7 text-xs flex-1"
          onKeyDown={(e) => e.key === 'Enter' && handleJoinById()}
        />
        <Button variant="ghost" size="sm" className="h-7 px-2 text-xs shrink-0" onClick={handleJoinById} disabled={!joinId.trim()}>
          <LogIn className="h-3 w-3 mr-1" />
          {t('hub.room.join')}
        </Button>
      </div>

      {error && (
        <p className="mt-1.5 text-[11px] text-destructive truncate" title={error}>{error}</p>
      )}

      {roomsError && (
        <p className="mt-1.5 text-[11px] text-destructive">{(roomsError as Error).message}</p>
      )}

      {joinPasswordRoom && (
        <div className="mt-2 rounded-md border border-amber-500/30 bg-amber-500/5 p-2 space-y-1.5">
          <div className="flex items-center gap-1 text-[11px] text-amber-600 dark:text-amber-400">
            <KeyRound className="h-3 w-3" />
            {t('hub.room.inputPassword')}
          </div>
          <div className="flex gap-1.5">
            <Input
              value={joinPasswordValue}
              onChange={(e) => { const v = e.target.value.replace(/\D/g, '').slice(0, 4); setJoinPasswordValue(v); }}
              placeholder={t('hub.room.passwordPlaceholder')}
              className="h-7 text-xs flex-1"
              maxLength={4}
              autoFocus
              onKeyDown={(e) => e.key === 'Enter' && handleJoinWithPassword()}
            />
            <Button size="sm" className="h-7 px-2 text-xs shrink-0" onClick={handleJoinWithPassword} disabled={joinPasswordValue.length < 4}>
              {t('hub.room.join')}
            </Button>
          </div>
          <button
            onClick={() => { setJoinPasswordRoom(null); setJoinPasswordValue(''); }}
            className="text-[11px] text-muted-foreground hover:text-foreground"
          >
            {t('common.cancel')}
          </button>
        </div>
      )}
    </TooltipProvider>
  );

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
            <div className="ml-auto flex items-center gap-2">
              {selectedRoom?.owner_id && (
                <span className="text-[11px] text-muted-foreground">{t('hub.room.owner')}: {selectedRoom.owner_id}</span>
              )}
              <Badge variant="secondary" className="text-[11px] font-mono">
                {selectedRoomId.slice(0, 8)}
              </Badge>
            </div>
          </div>

          {selectedRoom && (
            <div className="flex items-center gap-3 border-b px-6 py-2 text-[11px] text-muted-foreground">
              {selectedRoom.description && (
                <span className="truncate" title={selectedRoom.description ?? undefined}>{selectedRoom.description}</span>
              )}
              <span className="shrink-0">{t('hub.room.maxAgents')}: {selectedRoom.max_agents}</span>
              {selectedRoom.hasPassword && (
                <span className="flex items-center gap-0.5 shrink-0 text-amber-500">
                  <Shield className="h-3 w-3" />
                  {selectedRoom.isOwner && selectedRoom.password ? (
                    <>
                      <span className="font-mono">{showPassword ? selectedRoom.password : '****'}</span>
                      <button
                        onClick={() => setShowPassword(!showPassword)}
                        className="ml-0.5 text-muted-foreground hover:text-foreground"
                      >
                        {showPassword ? <EyeOff className="h-3 w-3" /> : <Eye className="h-3 w-3" />}
                      </button>
                    </>
                  ) : (
                    t('hub.room.hasPassword')
                  )}
                </span>
              )}
              <span className="shrink-0 flex items-center gap-0.5">
                {selectedRoom.visibility === 'public' ? <Globe className="h-3 w-3" /> : <Lock className="h-3 w-3" />}
                {selectedRoom.visibility === 'public' ? t('hub.room.public') : t('hub.room.private')}
              </span>
            </div>
          )}

          <div className="flex-1 overflow-hidden">
            {rightTab === 'tasks' && (
              <TaskBoard roomId={selectedRoomId} onSelectTask={(id) => setSelectedTaskId(id)} />
            )}
            {rightTab === 'agents' && <AgentList roomId={selectedRoomId} />}
          </div>
        </>
      )}
    </div>
  ) : hubUnreachable && hub?.serverUrl ? (
    <FeedbackState
      state="error"
      title={t('hub.status.serverUnreachable')}
      description={t('hub.status.serverUnreachableDesc')}
    />
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
      contentClassName="h-full p-0"
    >
      {content}
    </PageLayout>
  );
}
