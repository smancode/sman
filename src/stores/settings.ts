/**
 * Settings Store (Sman)
 * Manages LLM + Web Search settings via WebSocket.
 */
import { create } from 'zustand';
import { useWsConnection } from '@/stores/ws-connection';
import type { SmanSettings, LlmConfig, WebSearchConfig } from '@/types/settings';

type MsgHandler = (msg: Record<string, unknown>) => void;

function getWsClient() {
  return useWsConnection.getState().client;
}

function wrapHandler(
  client: { on: (e: string, h: (...a: unknown[]) => void) => void; off: (e: string, h: (...a: unknown[]) => void) => void },
  event: string,
  handler: MsgHandler,
) {
  const wrapped = (...args: unknown[]) => handler(args[0] as Record<string, unknown>);
  client.on(event, wrapped);
  return () => client.off(event, wrapped);
}

interface SettingsState {
  settings: SmanSettings | null;
  loading: boolean;
  error: string | null;

  fetchSettings: () => Promise<void>;
  updateLlm: (updates: Partial<LlmConfig>) => Promise<void>;
  updateWebSearch: (updates: Partial<WebSearchConfig>) => Promise<void>;
  clearError: () => void;
}

const DEFAULT_SETTINGS: SmanSettings = {
  port: 5880,
  llm: { apiKey: '', model: 'claude-sonnet-4-6' },
  webSearch: {
    provider: 'builtin',
    braveApiKey: '',
    tavilyApiKey: '',
    maxUsesPerSession: 50,
  },
};

export const useSettingsStore = create<SettingsState>((set) => ({
  settings: null,
  loading: false,
  error: null,

  fetchSettings: async () => {
    const client = getWsClient();
    if (!client) return;

    set({ loading: true, error: null });
    try {
      const unsub = wrapHandler(client, 'settings.get', (data) => {
        unsub();
        const config = data.config as SmanSettings;
        set({ settings: config ?? DEFAULT_SETTINGS, loading: false });
      });
      client.send({ type: 'settings.get' });
    } catch (err) {
      set({ error: String(err), loading: false });
    }
  },

  updateLlm: async (updates) => {
    const client = getWsClient();
    if (!client) throw new Error('Not connected');

    return new Promise<void>((resolve, reject) => {
      const unsub = wrapHandler(client, 'settings.updated', (data) => {
        unsub();
        unsubErr();
        set({ settings: data.config as SmanSettings });
        resolve();
      });
      const unsubErr = wrapHandler(client, 'chat.error', (data) => {
        unsub();
        unsubErr();
        set({ error: String(data.error) });
        reject(new Error(String(data.error)));
      });
      client.send({ type: 'settings.update', llm: updates });
    });
  },

  updateWebSearch: async (updates) => {
    const client = getWsClient();
    if (!client) throw new Error('Not connected');

    return new Promise<void>((resolve, reject) => {
      const unsub = wrapHandler(client, 'settings.updated', (data) => {
        unsub();
        unsubErr();
        set({ settings: data.config as SmanSettings });
        resolve();
      });
      const unsubErr = wrapHandler(client, 'chat.error', (data) => {
        unsub();
        unsubErr();
        set({ error: String(data.error) });
        reject(new Error(String(data.error)));
      });
      client.send({ type: 'settings.update', webSearch: updates });
    });
  },

  clearError: () => set({ error: null }),
}));
