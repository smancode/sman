/**
 * Settings Store (Sman)
 * Manages LLM + Web Search settings via WebSocket.
 */
import { create } from 'zustand';
import { useWsConnection } from '@/stores/ws-connection';
import type { SmanSettings, LlmConfig, WebSearchConfig, ChatbotConfig, DetectedCapabilities } from '@/types/settings';

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

export interface TestAndSaveResult {
  success: boolean;
  error?: string;
  capabilities?: DetectedCapabilities;
}

interface SettingsState {
  settings: SmanSettings | null;
  loading: boolean;
  error: string | null;

  fetchSettings: () => Promise<void>;
  updateLlm: (updates: Partial<LlmConfig>) => Promise<void>;
  updateWebSearch: (updates: Partial<WebSearchConfig>) => Promise<void>;
  updateChatbot: (updates: Partial<ChatbotConfig>) => Promise<void>;
  testAndSaveLlm: (apiKey: string, model: string, baseUrl?: string) => Promise<TestAndSaveResult>;
  clearError: () => void;
}

const DEFAULT_SETTINGS: SmanSettings = {
  port: 5880,
  llm: { apiKey: '', model: '', userProfile: true },
  webSearch: {
    provider: 'builtin',
    braveApiKey: '',
    tavilyApiKey: '',
    bingApiKey: '',
    maxUsesPerSession: 50,
  },
  chatbot: {
    enabled: false,
    wecom: { enabled: false, botId: '', secret: '' },
    feishu: { enabled: false, appId: '', appSecret: '' },
    weixin: { enabled: false },
  },
};

export const useSettingsStore = create<SettingsState>((set, get) => ({
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

  updateChatbot: async (updates) => {
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
      client.send({ type: 'settings.update', chatbot: updates });
    });
  },

  testAndSaveLlm: async (apiKey, model, baseUrl) => {
    const client = getWsClient();
    if (!client) throw new Error('Not connected');

    return new Promise<TestAndSaveResult>((resolve) => {
      const unsub = wrapHandler(client, 'settings.testResult', (data) => {
        unsub();
        const result = data as unknown as TestAndSaveResult;

        // Update settings in store on success (config already saved server-side)
        if (result.success) {
          const current = get().settings;
          if (current) {
            set({
              settings: {
                ...current,
                llm: {
                  ...current.llm,
                  apiKey,
                  model,
                  baseUrl,
                  capabilities: result.capabilities,
                },
              },
            });
          }
        }

        resolve(result);
      });

      client.send({
        type: 'settings.testAndSave',
        apiKey,
        model,
        baseUrl: baseUrl || undefined,
      });
    });
  },

  clearError: () => set({ error: null }),
}));
