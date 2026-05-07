import { useState, useEffect } from 'react';
import { useWsConnection, recreateClient } from '@/stores/ws-connection';
import { setAuthToken, setHttpBaseUrl } from '@/lib/auth';
import { useChatStore } from '@/stores/chat';
import { useCronStore } from '@/stores/cron';
import { t } from '@/locales';

const STORAGE_KEY_SERVERS = 'sman-servers';
const STORAGE_KEY_SELECTED = 'sman-selected-server';

interface ServerEntry {
  name: string;      // display name (alias or address)
  url: string;
  token: string;
  alias?: string;    // user-friendly alias
}

const LOCALHOST_NAME = 'localhost';

function loadServers(): ServerEntry[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEY_SERVERS);
    if (raw) {
      const list: ServerEntry[] = JSON.parse(raw);
      if (list.some(s => s.name === LOCALHOST_NAME)) return list;
    }
  } catch { /* ignore */ }
  return [{ name: LOCALHOST_NAME, url: '', token: '', alias: t('settings.backend.local') }];
}

function saveServers(servers: ServerEntry[]) {
  localStorage.setItem(STORAGE_KEY_SERVERS, JSON.stringify(servers));
}

function loadSelectedName(): string {
  return localStorage.getItem(STORAGE_KEY_SELECTED) || LOCALHOST_NAME;
}

function toWsUrl(input: string): string {
  const trimmed = input.trim();
  if (!trimmed) return '';
  if (trimmed.startsWith('ws://') || trimmed.startsWith('wss://')) return trimmed;
  return `ws://${trimmed}/ws`;
}

/** 连接后端并刷新所有数据 */
function connectAndRefresh() {
  const ws = useWsConnection.getState();
  ws.disconnect();
  setTimeout(() => {
    ws.connect();
    useChatStore.getState().loadSessions();
    useCronStore.getState().fetchTasks();
  }, 300);
}

export function BackendSettings({ id }: { id?: string }) {
  const { status, connect, disconnect } = useWsConnection();
  const [servers, setServers] = useState<ServerEntry[]>(loadServers);
  const [selectedName, setSelectedName] = useState(loadSelectedName);

  const currentServer = servers.find(s => s.name === selectedName);

  // Token editor (remote servers only; localhost auto-fetches)
  const [token, setToken] = useState(() => currentServer?.token || '');
  const [newAddr, setNewAddr] = useState('');
  const [newAlias, setNewAlias] = useState('');
  const [newToken, setNewToken] = useState('');
  const [testing, setTesting] = useState(false);
  const [testResult, setTestResult] = useState<'ok' | 'fail' | null>(null);

  const isLocal = !currentServer?.url;

  // On mount: sync localhost token into server entry (App.tsx already handles init + connect)
  useEffect(() => {
    if (isLocal) {
      const localToken = localStorage.getItem('sman-backend-token') || '';
      if (localToken) {
        updateServerToken(LOCALHOST_NAME, localToken);
        setToken(localToken);
      }
    }
  }, []);

  /** Update token for a server entry in both state and localStorage */
  function updateServerToken(name: string, t: string) {
    setServers(prev => {
      const updated = prev.map(s => s.name === name ? { ...s, token: t } : s);
      saveServers(updated);
      return updated;
    });
  }

  /** Apply server selection: write to localStorage + recreate client */
  async function applyServer(server: ServerEntry) {
    let effectiveToken = server.token;

    // localhost: always fetch fresh token from local backend
    if (!server.url) {
      await useWsConnection.getState().initToken();
      effectiveToken = localStorage.getItem('sman-backend-token') || '';
      updateServerToken(LOCALHOST_NAME, effectiveToken);
      setToken(effectiveToken);
    } else {
      setToken(effectiveToken);
    }

    // Sync all auth state
    localStorage.setItem('sman-backend-url', server.url);
    localStorage.setItem('sman-backend-token', effectiveToken);
    localStorage.setItem(STORAGE_KEY_SELECTED, server.name);
    setAuthToken(effectiveToken);
    setHttpBaseUrl(server.url || '');
    recreateClient();

    connectAndRefresh();
  }

  const handleSelect = (name: string) => {
    setSelectedName(name);
    const server = servers.find(s => s.name === name);
    if (!server) return;
    applyServer(server);
  };

  const handleSaveAndConnect = () => {
    if (!currentServer) return;

    // Save edited token back to server entry
    updateServerToken(currentServer.name, token);
    applyServer({ ...currentServer, token });
  };

  const handleTestAndAdd = async () => {
    const wsUrl = toWsUrl(newAddr);
    if (!wsUrl) return;

    setTesting(true);
    setTestResult(null);

    try {
      const ok = await testConnection(wsUrl);
      if (ok) {
        const name = newAddr.trim().replace(/\/$/, '');
        const entry: ServerEntry = {
          name,
          url: wsUrl,
          token: newToken,
          alias: newAlias.trim() || undefined,
        };
        const updated = [...servers.filter(s => s.name !== name), entry];
        setServers(updated);
        saveServers(updated);
        setSelectedName(name);
        setToken(newToken);
        setTestResult('ok');
        setNewAddr('');
        setNewAlias('');
        setNewToken('');

        applyServer(entry);
      } else {
        setTestResult('fail');
      }
    } catch {
      setTestResult('fail');
    } finally {
      setTesting(false);
    }
  };

  const handleRemoveServer = (name: string) => {
    if (name === LOCALHOST_NAME) return;
    const updated = servers.filter(s => s.name !== name);
    setServers(updated);
    saveServers(updated);
    if (selectedName === name) {
      handleSelect(LOCALHOST_NAME);
    }
  };

  const statusText: Record<string, string> = {
    connected: t('settings.backend.status.connected'),
    connecting: t('settings.backend.status.connecting'),
    disconnected: t('settings.backend.status.disconnected'),
    auth_failed: t('settings.backend.status.authFailed'),
  };

  return (
    <div id={id} className="rounded-lg border bg-card text-card-foreground shadow-sm">
      <div className="flex flex-col space-y-1.5 p-6">
        <h3 className="text-2xl font-semibold leading-none tracking-tight">{t('settings.backend.title')}</h3>
        <p className="text-sm text-muted-foreground">
          {t('settings.backend.description')}
        </p>
      </div>
      <div className="p-6 pt-0 space-y-4">
        {/* Server selector */}
        <div className="space-y-2">
          <label className="text-sm font-medium leading-none">{t('settings.backend.server')}</label>
          <select
            className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm shadow-sm focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
            value={selectedName}
            onChange={(e) => handleSelect(e.target.value)}
          >
            {servers.map(s => {
              const label = s.alias
                ? `${s.alias} (${s.name})`
                : s.name + (s.url ? ` (${s.url})` : ` (${t('settings.backend.local')})`);
              return (
                <option key={s.name} value={s.name}>
                  {label}
                </option>
              );
            })}
          </select>
        </div>

        {/* Remote server info + delete */}
        {currentServer && currentServer.url && (
          <div className="text-xs text-muted-foreground">
            {currentServer.alias ? `${currentServer.alias} (${currentServer.url})` : currentServer.url}
            <button
              className="ml-2 text-red-500 hover:text-red-700 text-xs underline"
              onClick={() => handleRemoveServer(currentServer.name)}
            >
              {t('settings.backend.delete')}
            </button>
          </div>
        )}

        {/* Token (read-only for localhost) */}
        <div className="space-y-2">
          <label className="text-sm font-medium leading-none">{t('settings.backend.token')}</label>
          <input
            type="password"
            className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm shadow-sm placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring disabled:opacity-50 disabled:cursor-not-allowed"
            value={token}
            onChange={(e) => setToken(e.target.value)}
            placeholder={isLocal ? t('settings.backend.token.auto') : t('settings.backend.token.placeholder')}
            readOnly={isLocal}
          />
        </div>

        {/* Connect button */}
        <div className="flex items-center gap-2">
          <button
            className="inline-flex items-center justify-center rounded-md text-sm font-medium bg-primary text-primary-foreground shadow hover:bg-primary/90 h-8 px-3"
            onClick={handleSaveAndConnect}
          >
            {t('settings.backend.connect')}
          </button>
          <span className="text-sm text-muted-foreground ml-2">
            {t('settings.backend.status.label')} {statusText[status] || status}
          </span>
        </div>

        {/* Add remote server */}
        <div className="border-t pt-4 space-y-2">
          <label className="text-sm font-medium leading-none">{t('settings.backend.addServer')}</label>
          <input
            className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm shadow-sm placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
            value={newAlias}
            onChange={(e) => { setNewAlias(e.target.value); setTestResult(null); }}
            placeholder={t('settings.backend.alias')}
          />
          <input
            className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm shadow-sm placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
            value={newAddr}
            onChange={(e) => { setNewAddr(e.target.value); setTestResult(null); }}
            placeholder={t('settings.backend.address')}
          />
          <input
            type="password"
            className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm shadow-sm placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
            value={newToken}
            onChange={(e) => { setNewToken(e.target.value); setTestResult(null); }}
            placeholder={t('settings.backend.tokenLabel')}
          />
          <button
            className="inline-flex items-center justify-center rounded-md text-sm font-medium border border-input bg-background shadow-sm hover:bg-accent h-8 px-3 disabled:opacity-50"
            onClick={handleTestAndAdd}
            disabled={testing || !newAddr.trim()}
          >
            {testing ? t('settings.backend.testing') : t('settings.backend.testAndAdd')}
          </button>
          {testResult === 'ok' && (
            <p className="text-xs text-green-600">{t('settings.backend.testSuccess')}</p>
          )}
          {testResult === 'fail' && (
            <p className="text-xs text-red-600">{t('settings.backend.testFailed')}</p>
          )}
        </div>
      </div>
    </div>
  );
}

/** Test if a WebSocket endpoint is reachable */
function testConnection(wsUrl: string): Promise<boolean> {
  return new Promise((resolve) => {
    try {
      const ws = new WebSocket(wsUrl);
      const timeout = setTimeout(() => {
        ws.close();
        resolve(false);
      }, 5000);
      ws.onopen = () => {
        clearTimeout(timeout);
        ws.close();
        resolve(true);
      };
      ws.onerror = () => {
        clearTimeout(timeout);
        resolve(false);
      };
    } catch {
      resolve(false);
    }
  });
}
