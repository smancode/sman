import { useState, useEffect } from 'react';
import { useWsConnection } from '@/stores/ws-connection';
import { setAuthToken } from '@/lib/auth';

const STORAGE_KEY_URL = 'sman-backend-url';
const STORAGE_KEY_TOKEN = 'sman-backend-token';

export function BackendSettings() {
  const { status, connect, disconnect, initToken, client } = useWsConnection();
  const [url, setUrl] = useState(() => localStorage.getItem(STORAGE_KEY_URL) || '');
  const [token, setToken] = useState(() => localStorage.getItem(STORAGE_KEY_TOKEN) || '');
  const [saved, setSaved] = useState(false);

  // Auto-init token on mount (local mode)
  useEffect(() => {
    if (!token) {
      initToken().then(() => {
        // After initToken, token is synced to WsClient + authFetch
      });
    }
  }, []);

  const handleSave = () => {
    localStorage.setItem(STORAGE_KEY_URL, url);
    localStorage.setItem(STORAGE_KEY_TOKEN, token);
    setAuthToken(token);
    if (client) {
      client.token = token;
    }
    setSaved(true);
    setTimeout(() => setSaved(false), 2000);
  };

  const handleReconnect = () => {
    disconnect();
    setTimeout(() => connect(), 500);
  };

  const statusText: Record<string, string> = {
    connected: '已连接',
    connecting: '连接中',
    disconnected: '未连接',
    auth_failed: '认证失败',
  };

  return (
    <div className="rounded-lg border bg-card text-card-foreground shadow-sm">
      <div className="flex flex-col space-y-1.5 p-6">
        <h3 className="text-2xl font-semibold leading-none tracking-tight">后端连接</h3>
        <p className="text-sm text-muted-foreground">
          配置后端服务地址和认证 Token。本机模式自动获取 Token，远程模式需手动输入。
        </p>
      </div>
      <div className="p-6 pt-0 space-y-4">
        <div className="space-y-2">
          <label className="text-sm font-medium leading-none">后端地址</label>
          <input
            className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm shadow-sm transition-colors placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring disabled:cursor-not-allowed disabled:opacity-50"
            value={url}
            onChange={(e) => setUrl(e.target.value)}
            placeholder="ws://localhost:5880/ws"
          />
        </div>
        <div className="space-y-2">
          <label className="text-sm font-medium leading-none">认证 Token</label>
          <input
            type="password"
            className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm shadow-sm transition-colors placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring disabled:cursor-not-allowed disabled:opacity-50"
            value={token}
            onChange={(e) => setToken(e.target.value)}
            placeholder="本机模式自动获取，远程模式需手动输入"
          />
        </div>
        <div className="flex items-center gap-2">
          <button
            className="inline-flex items-center justify-center whitespace-nowrap rounded-md text-sm font-medium transition-colors focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring disabled:pointer-events-none disabled:opacity-50 bg-primary text-primary-foreground shadow hover:bg-primary/90 h-8 px-3"
            onClick={handleSave}
          >
            {saved ? '已保存' : '保存'}
          </button>
          <button
            className="inline-flex items-center justify-center whitespace-nowrap rounded-md text-sm font-medium transition-colors focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring disabled:pointer-events-none disabled:opacity-50 border border-input bg-background shadow-sm hover:bg-accent hover:text-accent-foreground h-8 px-3"
            onClick={handleReconnect}
          >
            重新连接
          </button>
          <span className="text-sm text-muted-foreground ml-2">
            状态: {statusText[status] || status}
          </span>
        </div>
      </div>
    </div>
  );
}
