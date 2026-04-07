import { useState, useEffect } from 'react';
import { useWsConnection, recreateClient } from '@/stores/ws-connection';
import { setAuthToken, setHttpBaseUrl } from '@/lib/auth';
import { useChatStore } from '@/stores/chat';

const STORAGE_KEY_URL = 'sman-backend-url';
const STORAGE_KEY_SERVERS = 'sman-servers';

interface ServerEntry {
  name: string;
  url: string;
  token?: string;
}

const LOCALHOST_SERVER: ServerEntry = { name: 'localhost', url: '' };

function loadServers(): ServerEntry[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEY_SERVERS);
    if (raw) return JSON.parse(raw);
  } catch { /* ignore */ }
  return [LOCALHOST_SERVER];
}

function saveServers(servers: ServerEntry[]) {
  localStorage.setItem(STORAGE_KEY_SERVERS, JSON.stringify(servers));
}

function toWsUrl(input: string): string {
  const trimmed = input.trim();
  if (!trimmed) return '';
  if (trimmed.startsWith('ws://') || trimmed.startsWith('wss://')) return trimmed;
  return `ws://${trimmed}/ws`;
}

export function BackendSettings() {
  const { status, connect, disconnect, client } = useWsConnection();
  const [servers, setServers] = useState<ServerEntry[]>(loadServers);
  const [selectedName, setSelectedName] = useState(() => {
    const url = localStorage.getItem(STORAGE_KEY_URL) || '';
    if (!url) return LOCALHOST_SERVER.name;
    const s = loadServers().find(s => s.url === url);
    return s ? s.name : LOCALHOST_SERVER.name;
  });

  // Token: 本机从 localStorage 读（initToken 写入的），远程从 server entry 读
  const currentServer = servers.find(s => s.name === selectedName);
  const [token, setToken] = useState(() => {
    if (currentServer?.token) return currentServer.token;
    return localStorage.getItem('sman-backend-token') || '';
  });

  // 添加新服务器
  const [newAddr, setNewAddr] = useState('');
  const [newToken, setNewToken] = useState('');
  const [testing, setTesting] = useState(false);
  const [testResult, setTestResult] = useState<'ok' | 'fail' | null>(null);

  useEffect(() => {
    // 本机模式自动获取 token
    if (!currentServer?.url) {
      useWsConnection.getState().initToken();
    }
  }, []);

  const handleSelect = (name: string) => {
    setSelectedName(name);
    const server = servers.find(s => s.name === name);
    if (!server) return;
    const t = server.url ? (server.token || '') : (localStorage.getItem('sman-backend-token') || '');
    setToken(t);

    // 保存到 localStorage + 立即重连
    localStorage.setItem(STORAGE_KEY_URL, server.url);
    localStorage.setItem('sman-backend-token', t);
    setAuthToken(t);
    setHttpBaseUrl(server.url || '');
    recreateClient();

    disconnect();
    setTimeout(() => {
      connect();
      useChatStore.getState().loadSessions();
    }, 300);
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
        const entry: ServerEntry = { name, url: wsUrl, token: newToken };
        const updated = servers.filter(s => s.name !== name);
        updated.push(entry);
        setServers(updated);
        saveServers(updated);
        setSelectedName(name);
        setToken(newToken);
        localStorage.setItem(STORAGE_KEY_URL, wsUrl);
        localStorage.setItem('sman-backend-token', newToken);
        setAuthToken(newToken);
        setHttpBaseUrl(wsUrl);
        setTestResult('ok');
        setNewAddr('');
        setNewToken('');

        // 自动连接新添加的服务器
        recreateClient();
        disconnect();
        setTimeout(() => {
          connect();
          useChatStore.getState().loadSessions();
        }, 300);
      } else {
        setTestResult('fail');
      }
    } catch {
      setTestResult('fail');
    } finally {
      setTesting(false);
    }
  };

  const handleSaveAndConnect = () => {
    const server = servers.find(s => s.name === selectedName);
    if (!server) return;

    // 保存 token 到 server entry
    const updated = servers.map(s =>
      s.name === selectedName ? { ...s, token } : s
    );
    setServers(updated);
    saveServers(updated);

    // 写入 localStorage 供 WsClient 读取
    localStorage.setItem(STORAGE_KEY_URL, server.url);
    localStorage.setItem('sman-backend-token', token);
    setAuthToken(token);

    // Recreate WsClient (reads URL + token from localStorage)
    recreateClient();

    disconnect();
    setTimeout(() => connect(), 300);
  };

  const handleRemoveServer = (name: string) => {
    if (name === LOCALHOST_SERVER.name) return;
    const updated = servers.filter(s => s.name !== name);
    setServers(updated);
    saveServers(updated);
    if (selectedName === name) {
      setSelectedName(LOCALHOST_SERVER.name);
      localStorage.setItem(STORAGE_KEY_URL, '');
      const localToken = localStorage.getItem('sman-backend-token') || '';
      setToken(localToken);
    }
  };

  const statusText: Record<string, string> = {
    connected: '已连接',
    connecting: '连接中',
    disconnected: '未连接',
    auth_failed: '认证失败',
  };

  const isLocal = !currentServer?.url;

  return (
    <div className="rounded-lg border bg-card text-card-foreground shadow-sm">
      <div className="flex flex-col space-y-1.5 p-6">
        <h3 className="text-2xl font-semibold leading-none tracking-tight">后端连接</h3>
        <p className="text-sm text-muted-foreground">
          选择或添加后端服务器。本机模式自动获取 Token，远程模式需手动输入。
        </p>
      </div>
      <div className="p-6 pt-0 space-y-4">
        {/* 服务器选择 */}
        <div className="space-y-2">
          <label className="text-sm font-medium leading-none">后端服务器</label>
          <select
            className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm shadow-sm focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
            value={selectedName}
            onChange={(e) => handleSelect(e.target.value)}
          >
            {servers.map(s => (
              <option key={s.name} value={s.name}>
                {s.name}{s.url ? '' : ' (本机)'}
              </option>
            ))}
          </select>
        </div>

        {/* 当前服务器信息 */}
        {currentServer && currentServer.url && (
          <div className="text-xs text-muted-foreground">
            {currentServer.url}
            <button
              className="ml-2 text-red-500 hover:text-red-700 text-xs underline"
              onClick={() => handleRemoveServer(currentServer.name)}
            >
              删除
            </button>
          </div>
        )}

        {/* Token */}
        <div className="space-y-2">
          <label className="text-sm font-medium leading-none">认证 Token</label>
          <input
            type="password"
            className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm shadow-sm placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
            value={token}
            onChange={(e) => setToken(e.target.value)}
            placeholder={isLocal ? '本机自动获取' : '输入远程服务器 Token'}
          />
        </div>

        {/* 操作按钮 */}
        <div className="flex items-center gap-2">
          <button
            className="inline-flex items-center justify-center rounded-md text-sm font-medium bg-primary text-primary-foreground shadow hover:bg-primary/90 h-8 px-3"
            onClick={handleSaveAndConnect}
          >
            连接
          </button>
          <span className="text-sm text-muted-foreground ml-2">
            状态: {statusText[status] || status}
          </span>
        </div>

        {/* 添加新服务器 */}
        <div className="border-t pt-4 space-y-2">
          <label className="text-sm font-medium leading-none">添加远程服务器</label>
          <input
            className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm shadow-sm placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
            value={newAddr}
            onChange={(e) => { setNewAddr(e.target.value); setTestResult(null); }}
            placeholder=""
          />
          <input
            type="password"
            className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm shadow-sm placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
            value={newToken}
            onChange={(e) => { setNewToken(e.target.value); setTestResult(null); }}
            placeholder=""
          />
          <button
            className="inline-flex items-center justify-center rounded-md text-sm font-medium border border-input bg-background shadow-sm hover:bg-accent h-8 px-3 disabled:opacity-50"
            onClick={handleTestAndAdd}
            disabled={testing || !newAddr.trim()}
          >
            {testing ? '测试中...' : '测试并添加'}
          </button>
          {testResult === 'ok' && (
            <p className="text-xs text-green-600">连接成功，已添加到服务器列表</p>
          )}
          {testResult === 'fail' && (
            <p className="text-xs text-red-600">连接失败，请检查地址和端口</p>
          )}
        </div>
      </div>
    </div>
  );
}

/** 测试 WebSocket 连接是否可达 */
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
