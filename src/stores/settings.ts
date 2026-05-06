/**
 * Settings Store (Sman)
 * Manages LLM + Web Search settings via WebSocket.
 */
import { create } from 'zustand';
import { useWsConnection } from '@/stores/ws-connection';
import type { SmanSettings, LlmConfig, LlmProfile, WebSearchConfig, ChatbotConfig, DetectedCapabilities } from '@/types/settings';

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
  testAndSaveLlm: (apiKey: string, model: string, baseUrl?: string, profileName?: string) => Promise<TestAndSaveResult>;
  fetchModels: (apiKey: string, baseUrl?: string) => Promise<{ models: { id: string; displayName?: string }[]; unsupported?: boolean }>;
  selectLlmProfile: (name: string) => Promise<void>;
  deleteLlmProfile: (name: string) => Promise<void>;
  clearError: () => void;
}

const DEFAULT_SETTINGS: SmanSettings = {
  port: 5880,
  llm: { apiKey: '', model: '', userProfile: true },
  savedLlms: [],
  currentLlmProfile: '',
  webSearch: {
    provider: 'builtin',
    braveApiKey: '',
    tavilyApiKey: '',
    bingApiKey: '',
    baiduApiKey: '',
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

    // Optimistic update — apply locally immediately
    const prev = get().settings;
    const optimistic = { ...prev, llm: { ...prev?.llm, ...updates } } as SmanSettings;
    set({ settings: optimistic });

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
        set({ settings: prev, error: String(data.error) });
        reject(new Error(String(data.error)));
      });
      client.send({ type: 'settings.update', llm: updates });
    });
  },

  updateWebSearch: async (updates) => {
    const client = getWsClient();
    if (!client) throw new Error('Not connected');

    const prev = get().settings;
    const optimistic = { ...prev, webSearch: { ...prev?.webSearch, ...updates } } as SmanSettings;
    set({ settings: optimistic });

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
        set({ settings: prev, error: String(data.error) });
        reject(new Error(String(data.error)));
      });
      client.send({ type: 'settings.update', webSearch: updates });
    });
  },

  updateChatbot: async (updates) => {
    const client = getWsClient();
    if (!client) throw new Error('Not connected');

    const prev = get().settings;
    const optimistic = { ...prev, chatbot: { ...prev?.chatbot, ...updates } } as SmanSettings;
    set({ settings: optimistic });

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
        set({ settings: prev, error: String(data.error) });
        reject(new Error(String(data.error)));
      });
      client.send({ type: 'settings.update', chatbot: updates });
    });
  },

  testAndSaveLlm: async (apiKey, model, baseUrl, profileName) => {
    const client = getWsClient();
    if (!client) throw new Error('Not connected');

    return new Promise<TestAndSaveResult>((resolve) => {
      const unsub = wrapHandler(client, 'settings.testResult', (data) => {
        unsub();
        const result = data as unknown as TestAndSaveResult & { savedLlms?: LlmProfile[] };

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
                savedLlms: result.savedLlms ?? current.savedLlms,
                currentLlmProfile: profileName || current.currentLlmProfile,
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
        profileName: profileName || undefined,
      });
    });
  },

  fetchModels: async (apiKey, baseUrl) => {
    const client = getWsClient();
    if (!client) throw new Error('Not connected');

    return new Promise<{ models: { id: string; displayName?: string }[]; unsupported?: boolean }>((resolve) => {
      const timer = setTimeout(() => {
        unsub();
        resolve({ models: [], unsupported: true });
      }, 15000);

      const unsub = wrapHandler(client, 'settings.modelsList', (data) => {
        clearTimeout(timer);
        unsub();
        resolve({
          models: (data.models as { id: string; displayName?: string }[]) ?? [],
          unsupported: data.unsupported === true,
        });
      });
      client.send({ type: 'settings.fetchModels', apiKey, baseUrl: baseUrl || undefined });
    });
  },

  selectLlmProfile: async (name) => {
    const client = getWsClient();
    if (!client) throw new Error('Not connected');

    return new Promise<void>((resolve, reject) => {
      const unsub = wrapHandler(client, 'settings.updated', (data) => {
        unsub();
        unsubErr();
        const config = data.config as SmanSettings;
        set({ settings: config });
        resolve();
      });
      const unsubErr = wrapHandler(client, 'chat.error', (data) => {
        unsub();
        unsubErr();
        set({ error: String(data.error) });
        reject(new Error(String(data.error)));
      });
      client.send({ type: 'settings.selectLlmProfile', profileName: name });
    });
  },

  deleteLlmProfile: async (name) => {
    const client = getWsClient();
    if (!client) throw new Error('Not connected');

    return new Promise<void>((resolve, reject) => {
      const unsub = wrapHandler(client, 'settings.updated', (data) => {
        unsub();
        unsubErr();
        const config = data.config as SmanSettings;
        set({ settings: config });
        resolve();
      });
      const unsubErr = wrapHandler(client, 'chat.error', (data) => {
        unsub();
        unsubErr();
        set({ error: String(data.error) });
        reject(new Error(String(data.error)));
      });
      client.send({ type: 'settings.deleteLlmProfile', profileName: name });
    });
  },

  clearError: () => set({ error: null }),
}));
