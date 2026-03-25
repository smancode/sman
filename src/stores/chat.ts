// src/stores/chat.ts

import { create } from 'zustand';
import { useWsConnection } from '@/stores/ws-connection';
import type { ChatSession } from '@/types/chat';

type MsgHandler = (msg: Record<string, unknown>) => void;

function getWsClient() {
  return useWsConnection.getState().client;
}

/** Wrap a typed handler to match WsClient's EventHandler signature */
function wrapHandler(client: { on: (e: string, h: (...a: unknown[]) => void) => void; off: (e: string, h: (...a: unknown[]) => void) => void }, event: string, handler: MsgHandler) {
  const wrapped = (...args: unknown[]) => handler(args[0] as Record<string, unknown>);
  client.on(event, wrapped);
  return () => client.off(event, wrapped);
}

interface Message {
  id: string;
  sessionId: string;
  role: 'user' | 'assistant';
  content: string;
  createdAt: string;
}

interface ChatState {
  // Messages
  messages: Message[];
  loading: boolean;
  error: string | null;

  // Streaming
  sending: boolean;
  streamingText: string;

  // Sessions
  sessions: ChatSession[];
  currentSessionId: string;
  sessionLabels: Record<string, string>;

  // Thinking
  showThinking: boolean;

  // Actions
  createSession: (systemId: string) => Promise<string>;
  deleteSession: (sessionId: string) => Promise<void>;
  loadSessions: (systemId?: string) => Promise<void>;
  switchSession: (sessionId: string) => void;
  loadHistory: () => Promise<void>;
  sendMessage: (content: string) => Promise<void>;
  abortRun: () => void;
  clearError: () => void;
  toggleThinking: () => void;
  refresh: () => void;
}

export const useChatStore = create<ChatState>((set, get) => ({
  messages: [],
  loading: false,
  error: null,
  sending: false,
  streamingText: '',
  sessions: [],
  currentSessionId: '',
  sessionLabels: {},
  showThinking: true,

  // Create a new session via backend
  createSession: async (systemId: string) => {
    const client = getWsClient();
    if (!client) throw new Error('Not connected');
    if (!client.connected) throw new Error('WebSocket not connected');

    return new Promise<string>((resolve, reject) => {
      const timeout = setTimeout(() => {
        unsub();
        unsubErr();
        reject(new Error('Create session timeout'));
      }, 10000);

      const unsub = wrapHandler(client, 'session.created', (data) => {
        clearTimeout(timeout);
        if (data.sessionId) {
          unsub();
          resolve(String(data.sessionId));
        }
      });
      const unsubErr = wrapHandler(client, 'chat.error', (data) => {
        clearTimeout(timeout);
        unsub();
        unsubErr();
        reject(new Error(String(data.error)));
      });
      client.send({ type: 'session.create', systemId });
    });
  },

  deleteSession: async (sessionId: string) => {
    const client = getWsClient();
    if (!client) throw new Error('Not connected');

    return new Promise<void>((resolve, reject) => {
      const unsub = wrapHandler(client, 'session.deleted', (data) => {
        if (String(data.sessionId) === sessionId) {
          unsub();
          unsubErr();
          // Remove from local state and switch session if needed
          const state = get();
          const remaining = state.sessions.filter(s => s.key !== sessionId);
          const newId = state.currentSessionId === sessionId
            ? (remaining.length > 0 ? remaining[0].key : '')
            : state.currentSessionId;
          set({ sessions: remaining, currentSessionId: newId });
          if (newId && newId !== state.currentSessionId) {
            get().switchSession(newId);
          }
          resolve();
        }
      });
      const unsubErr = wrapHandler(client, 'chat.error', (data) => {
        unsub();
        unsubErr();
        reject(new Error(String(data.error)));
      });
      client.send({ type: 'session.delete', sessionId });
    });
  },

  loadSessions: async (_systemId?: string) => {
    const client = getWsClient();
    if (!client) return;

    try {
      const unsub = wrapHandler(client, 'session.list', (data) => {
        if (!Array.isArray(data.sessions)) return;
        unsub();

        const sessions: ChatSession[] = data.sessions.map((s: Record<string, unknown>) => ({
          key: String(s.id),
          label: s.label ? String(s.label) : undefined,
          systemId: s.systemId ? String(s.systemId) : undefined,
          createdAt: s.createdAt ? String(s.createdAt) : undefined,
          lastActiveAt: s.lastActiveAt ? String(s.lastActiveAt) : undefined,
        }));

        const state = get();
        // Keep current session if it still exists, otherwise pick first
        const nextId = sessions.find(s => s.key === state.currentSessionId)
          ? state.currentSessionId
          : sessions.length > 0 ? sessions[0].key : '';

        set({ sessions, currentSessionId: nextId });

        if (nextId && state.currentSessionId !== nextId) {
          get().loadHistory();
        }
      });
      // Load ALL sessions (no filter) so the tree can group by system
      client.send({ type: 'session.list' });
    } catch (err) {
      console.warn('Failed to load sessions:', err);
    }
  },

  switchSession: (sessionId: string) => {
    if (sessionId === get().currentSessionId) return;
    set({
      currentSessionId: sessionId,
      messages: [],
      streamingText: '',
      error: null,
      sending: false,
    });
    get().loadHistory();
  },

  loadHistory: async () => {
    const client = getWsClient();
    if (!client) return;
    const { currentSessionId } = get();
    if (!currentSessionId) return;

    set({ loading: true, error: null });
    try {
      const unsub = wrapHandler(client, 'session.history', (data) => {
        unsub();

        const msgs: Message[] = Array.isArray(data.messages)
          ? data.messages.map((m: Record<string, unknown>) => ({
              id: String(m.id || ''),
              sessionId: String(m.sessionId || ''),
              role: (String(m.role || 'user') as 'user' | 'assistant'),
              content: String(m.content || ''),
              createdAt: String(m.createdAt || ''),
            }))
          : [];

        set({ messages: msgs, loading: false });

        // Generate session label from first user message if not set
        const state = get();
        if (!state.sessionLabels[currentSessionId]) {
          const firstUser = msgs.find(m => m.role === 'user' && m.content.trim());
          if (firstUser) {
            const text = firstUser.content.trim();
            const truncated = text.length > 50 ? `${text.slice(0, 50)}...` : text;
            set((s) => ({ sessionLabels: { ...s.sessionLabels, [currentSessionId]: truncated } }));
          }
        }
      });
      client.send({ type: 'session.history', sessionId: currentSessionId });
    } catch (err) {
      console.warn('Failed to load history:', err);
      set({ loading: false });
    }
  },

  sendMessage: async (content: string) => {
    const client = getWsClient();
    if (!client) return;

    const trimmed = content.trim();
    if (!trimmed) return;

    const { currentSessionId, messages } = get();
    if (!currentSessionId) return;

    // Optimistic user message
    const userMsg: Message = {
      id: crypto.randomUUID(),
      sessionId: currentSessionId,
      role: 'user',
      content: trimmed,
      createdAt: new Date().toISOString(),
    };

    set({
      messages: [...messages, userMsg],
      sending: true,
      streamingText: '',
      error: null,
    });

    // Update label from first message
    const state = get();
    if (!state.sessionLabels[currentSessionId] && trimmed) {
      const truncated = trimmed.length > 50 ? `${trimmed.slice(0, 50)}...` : trimmed;
      set((s) => ({ sessionLabels: { ...s.sessionLabels, [currentSessionId]: truncated } }));
    }

    try {
      // Set up streaming handlers
      const unsubDelta = wrapHandler(client, 'chat.delta', (data) => {
        if (data.sessionId !== currentSessionId) return;
        set((s) => ({
          streamingText: s.streamingText + String(data.content || ''),
        }));
      });

      const unsubDone = wrapHandler(client, 'chat.done', (data) => {
        if (data.sessionId !== currentSessionId) return;
        cleanup();

        const st = get();
        if (st.streamingText.trim()) {
          const assistantMsg: Message = {
            id: crypto.randomUUID(),
            sessionId: currentSessionId,
            role: 'assistant',
            content: st.streamingText.trim(),
            createdAt: new Date().toISOString(),
          };
          set({
            messages: [...st.messages, assistantMsg],
            streamingText: '',
            sending: false,
          });
        } else {
          set({ streamingText: '', sending: false });
        }
      });

      const unsubErr = wrapHandler(client, 'chat.error', (_data) => {
        cleanup();
        set({
          error: String(_data.error || 'Unknown error'),
          streamingText: '',
          sending: false,
        });
      });

      const cleanup = () => {
        unsubDelta();
        unsubDone();
        unsubErr();
      };

      // Send message
      client.send({ type: 'chat.send', sessionId: currentSessionId, content: trimmed });
    } catch (err) {
      set({ error: String(err), sending: false });
    }
  },

  abortRun: () => {
    const client = getWsClient();
    const { currentSessionId } = get();
    set({ sending: false, streamingText: '', error: null });
    if (client && currentSessionId) {
      client.send({ type: 'chat.abort', sessionId: currentSessionId });
    }
  },

  clearError: () => set({ error: null }),
  toggleThinking: () => set((s) => ({ showThinking: !s.showThinking })),
  refresh: () => {
    set({ messages: [], streamingText: '', error: null, sending: false });
    get().loadHistory();
  },
}));
