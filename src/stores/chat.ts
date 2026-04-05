// src/stores/chat.ts

import { create } from 'zustand';
import { useWsConnection } from '@/stores/ws-connection';
import type { ChatSession, ContentBlock } from '@/types/chat';

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
  contentBlocks?: ContentBlock[];
  createdAt: string;
  timestamp?: number;
}

interface StreamingTool {
  id: string;
  name: string;
  input: string;
  status: 'running' | 'completed';
  elapsedSeconds?: number;
}

interface ChatState {
  // Messages
  messages: Message[];
  loading: boolean;
  error: string | null;

  // Streaming
  sending: boolean;
  streamingText: string;
  streamingThinking: string;
  streamingTools: StreamingTool[];

  // Sessions
  sessions: ChatSession[];
  currentSessionId: string;

  // Thinking toggle
  showThinking: boolean;

  // Actions
  createSessionWithWorkspace: (workspace: string) => Promise<string>;
  deleteSession: (sessionId: string) => Promise<void>;
  loadSessions: () => Promise<void>;
  switchSession: (sessionId: string) => void;
  loadHistory: () => Promise<void>;
  sendMessage: (content: string, media?: Array<{ type: string; mimeType: string; base64Data: string; fileName?: string }>) => Promise<void>;
  abortRun: () => void;
  clearError: () => void;
  toggleThinking: () => void;
  refresh: () => void;
  updateSessionLabel: (sessionId: string, label: string) => Promise<void>;
}

export const useChatStore = create<ChatState>((set, get) => ({
  messages: [],
  loading: false,
  error: null,
  sending: false,
  streamingText: '',
  streamingThinking: '',
  streamingTools: [],
  sessions: [],
  currentSessionId: '',
  showThinking: true,

  // Create a new session with workspace (directory path)
  createSessionWithWorkspace: async (workspace: string) => {
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
      // Use workspace path as systemId (directory name will be used as display name)
      client.send({ type: 'session.create', workspace });
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

  loadSessions: async () => {
    const client = getWsClient();
    if (!client) return;

    try {
      const unsub = wrapHandler(client, 'session.list', (data) => {
        if (!Array.isArray(data.sessions)) return;
        unsub();

        const sessions: ChatSession[] = data.sessions.map((s: Record<string, unknown>) => ({
          key: String(s.id),
          label: s.label ? String(s.label) : undefined,
          workspace: s.workspace ? String(s.workspace) : undefined,
          createdAt: s.createdAt ? String(s.createdAt) : undefined,
          lastActiveAt: s.lastActiveAt ? String(s.lastActiveAt) : undefined,
        }));

        const state = get();
        // Keep current session if it still exists, otherwise stay empty (show welcome screen)
        const nextId = sessions.find(s => s.key === state.currentSessionId)
          ? state.currentSessionId
          : '';

        set({ sessions, currentSessionId: nextId });

        if (nextId && state.currentSessionId !== nextId) {
          get().loadHistory();
        }
      });
      // Load ALL sessions
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
      streamingThinking: '',
      streamingTools: [],
      error: null,
      sending: false,
    });
    get().loadHistory();
  },

  loadHistory: async () => {
    const client = getWsClient();
    if (!client) return;
    const { currentSessionId, sessions } = get();
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
              contentBlocks: m.contentBlocks as ContentBlock[] | undefined,
              createdAt: String(m.createdAt || ''),
              timestamp: typeof m.timestamp === 'number' ? m.timestamp : undefined,
            }))
          : [];

        set({ messages: msgs, loading: false });

        // Generate session label from first user message if not set
        const session = sessions.find(s => s.key === currentSessionId);
        if (!session?.label) {
          const firstUser = msgs.find(m => m.role === 'user' && m.content.trim());
          if (firstUser) {
            const text = firstUser.content.trim();
            const truncated = text.length > 20 ? `${text.slice(0, 20)}...` : text;
            // Update in backend
            get().updateSessionLabel(currentSessionId, truncated);
          }
        }
      });
      client.send({ type: 'session.history', sessionId: currentSessionId });
    } catch (err) {
      console.warn('Failed to load history:', err);
      set({ loading: false });
    }
  },

  updateSessionLabel: async (sessionId: string, label: string) => {
    const client = getWsClient();
    if (!client) return;

    // Update local state immediately
    set((state) => ({
      sessions: state.sessions.map(s =>
        s.key === sessionId ? { ...s, label } : s
      )
    }));

    // Send to backend
    client.send({ type: 'session.updateLabel', sessionId, label });
  },

  sendMessage: async (content, media) => {
    const client = getWsClient();
    if (!client) return;

    const trimmed = content.trim();
    if (!trimmed && (!media || media.length === 0)) return;

    const { currentSessionId, sessions, streamingText, streamingThinking, streamingTools, messages } = get();
    if (!currentSessionId) return;

    // Before clearing streaming state, save any in-progress assistant content to messages
    // so the user doesn't lose what was already streamed when aborting to send a new message
    let allMessages = messages;
    if (streamingText.trim() || streamingThinking.trim() || streamingTools.length > 0) {
      const contentBlocks: ContentBlock[] = [];
      if (streamingThinking.trim()) {
        contentBlocks.push({ type: 'thinking', thinking: streamingThinking.trim() });
      }
      if (streamingText.trim()) {
        contentBlocks.push({ type: 'text', text: streamingText.trim() });
      }
      for (const tool of streamingTools) {
        try {
          const input = tool.input ? JSON.parse(tool.input) : {};
          contentBlocks.push({ type: 'tool_use', id: tool.id, name: tool.name, input });
        } catch {
          contentBlocks.push({ type: 'tool_use', id: tool.id, name: tool.name, input: tool.input });
        }
      }
      const partialAssistantMsg: Message = {
        id: crypto.randomUUID(),
        sessionId: currentSessionId,
        role: 'assistant',
        content: streamingText.trim(),
        contentBlocks: contentBlocks.length > 0 ? contentBlocks : undefined,
        createdAt: new Date().toISOString(),
      };
      allMessages = [...messages, partialAssistantMsg];
    }

    // Build contentBlocks from media so images render in chat
    const mediaContentBlocks: ContentBlock[] = [];
    if (media && media.length > 0) {
      for (const m of media) {
        if (m.mimeType.startsWith('image/')) {
          mediaContentBlocks.push({
            type: 'image',
            source: { type: 'base64', media_type: m.mimeType, data: m.base64Data },
          });
        }
      }
    }

    const userMsg: Message = {
      id: crypto.randomUUID(),
      sessionId: currentSessionId,
      role: 'user',
      content: trimmed,
      contentBlocks: mediaContentBlocks.length > 0 ? mediaContentBlocks : undefined,
      createdAt: new Date().toISOString(),
    };

    set({
      messages: [...allMessages, userMsg],
      sending: true,
      streamingText: '',
      streamingThinking: '',
      streamingTools: [],
      error: null,
    });

    // Update label from first message if not set
    const session = sessions.find(s => s.key === currentSessionId);
    if (!session?.label) {
      const labelText = trimmed || (media && media.length > 0 ? '[图片]' : '');
      if (labelText) {
        const truncated = labelText.length > 20 ? `${labelText.slice(0, 20)}...` : labelText;
        get().updateSessionLabel(currentSessionId, truncated);
      }
    }

    try {
      // Set up streaming handlers
      const unsubDelta = wrapHandler(client, 'chat.delta', (data) => {
        if (data.sessionId !== currentSessionId) return;
        const deltaType = String(data.deltaType || 'text');
        if (deltaType === 'thinking') {
          set((s) => ({
            streamingThinking: s.streamingThinking + String(data.content || ''),
          }));
        } else {
          set((s) => ({
            streamingText: s.streamingText + String(data.content || ''),
          }));
        }
      });

      const unsubToolStart = wrapHandler(client, 'chat.tool_start', (data) => {
        if (data.sessionId !== currentSessionId) return;
        const toolId = String(data.toolId || '');
        const toolName = String(data.toolName || '');
        set((s) => ({
          streamingTools: [...s.streamingTools, {
            id: toolId,
            name: toolName,
            input: '',
            status: 'running',
          }],
        }));
      });

      const unsubToolDelta = wrapHandler(client, 'chat.tool_delta', (data) => {
        if (data.sessionId !== currentSessionId) return;
        const toolId = String(data.toolId || '');
        const content = String(data.content || '');
        set((s) => ({
          streamingTools: s.streamingTools.map(t =>
            t.id === toolId ? { ...t, input: t.input + content } : t
          ),
        }));
      });

      const unsubToolProgress = wrapHandler(client, 'chat.tool_progress', (data) => {
        if (data.sessionId !== currentSessionId) return;
        const toolUseId = String(data.toolUseId || '');
        const elapsedSeconds = typeof data.elapsedSeconds === 'number' ? data.elapsedSeconds : undefined;
        set((s) => ({
          streamingTools: s.streamingTools.map(t =>
            t.id === toolUseId ? { ...t, elapsedSeconds } : t
          ),
        }));
      });

      const unsubToolEnd = wrapHandler(client, 'chat.tool_end', (_data) => {
        if (_data.sessionId !== currentSessionId) return;
        // Mark all running tools as completed
        set((s) => ({
          streamingTools: s.streamingTools.map(t =>
            t.status === 'running' ? { ...t, status: 'completed' as const } : t
          ),
        }));
      });

      const unsubDone = wrapHandler(client, 'chat.done', (data) => {
        if (data.sessionId !== currentSessionId) return;
        cleanup();

        const st = get();
        // Build content blocks
        const contentBlocks: ContentBlock[] = [];
        if (st.streamingText.trim()) {
          contentBlocks.push({ type: 'text', text: st.streamingText.trim() });
        }
        if (st.streamingThinking.trim()) {
          contentBlocks.push({ type: 'thinking', thinking: st.streamingThinking.trim() });
        }
        for (const tool of st.streamingTools) {
          try {
            const input = tool.input ? JSON.parse(tool.input) : {};
            contentBlocks.push({
              type: 'tool_use',
              id: tool.id,
              name: tool.name,
              input,
            });
          } catch {
            contentBlocks.push({
              type: 'tool_use',
              id: tool.id,
              name: tool.name,
              input: tool.input,
            });
          }
        }

        if (st.streamingText.trim() || contentBlocks.length > 0) {
          const assistantMsg: Message = {
            id: crypto.randomUUID(),
            sessionId: currentSessionId,
            role: 'assistant',
            content: st.streamingText.trim(),
            contentBlocks: contentBlocks.length > 0 ? contentBlocks : undefined,
            createdAt: new Date().toISOString(),
          };
          set({
            messages: [...st.messages, assistantMsg],
            streamingText: '',
            streamingThinking: '',
            streamingTools: [],
            sending: false,
          });
        } else {
          set({ streamingText: '', streamingThinking: '', streamingTools: [], sending: false });
        }
      });

      const unsubErr = wrapHandler(client, 'chat.error', (_data) => {
        cleanup();
        set({
          error: String(_data.error || 'Unknown error'),
          streamingText: '',
          streamingThinking: '',
          streamingTools: [],
          sending: false,
        });
      });

      const cleanup = () => {
        unsubDelta();
        unsubToolStart();
        unsubToolDelta();
        unsubToolProgress();
        unsubToolEnd();
        unsubDone();
        unsubErr();
      };

      // Send message
      const msg: Record<string, unknown> = { type: 'chat.send', sessionId: currentSessionId, content: trimmed };
      if (media && media.length > 0) {
        msg.media = media;
      }
      client.send(msg);
    } catch (err) {
      set({ error: String(err), sending: false });
    }
  },

  abortRun: () => {
    const client = getWsClient();
    const { currentSessionId } = get();
    set({ sending: false, streamingText: '', streamingThinking: '', streamingTools: [], error: null });
    if (client && currentSessionId) {
      client.send({ type: 'chat.abort', sessionId: currentSessionId });
    }
  },

  clearError: () => set({ error: null }),
  toggleThinking: () => set((s) => ({ showThinking: !s.showThinking })),
  refresh: () => {
    set({ messages: [], streamingText: '', streamingThinking: '', streamingTools: [], error: null, sending: false });
    get().loadHistory();
  },
}));
