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

// ── Streaming Block types ──

/** A frozen (completed) text segment */
interface StreamingTextBlock {
  type: 'text';
  content: string;
}

/** A streaming (in-progress) text segment — always the last text block */
interface StreamingTextLiveBlock {
  type: 'text_live';
  content: string;
}

/** A completed tool use */
interface StreamingToolBlock {
  type: 'tool_use';
  id: string;
  name: string;
  input: string;
  result: string;
  status: 'running' | 'completed';
  elapsedSeconds?: number;
}

/** A thinking block */
interface StreamingThinkingBlock {
  type: 'thinking';
  content: string;
}

export type StreamingBlock = StreamingTextBlock | StreamingTextLiveBlock | StreamingToolBlock | StreamingThinkingBlock;

interface ChatState {
  // Messages
  messages: Message[];
  loading: boolean;
  error: string | null;

  // Streaming
  sending: boolean;
  /** Ordered array of streaming blocks — rendered sequentially in UI */
  streamingBlocks: StreamingBlock[];

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
  preheatSession: () => void;
  abortRun: () => void;
  clearError: () => void;
  toggleThinking: () => void;
  refresh: () => void;
  updateSessionLabel: (sessionId: string, label: string) => Promise<void>;
}

// ── Helpers for streaming block management ──

/** Find the index of the last text_live block (should be at most one, at the end) */
function findLiveTextIndex(blocks: StreamingBlock[]): number {
  for (let i = blocks.length - 1; i >= 0; i--) {
    if (blocks[i].type === 'text_live') return i;
  }
  return -1;
}

/** Append text to the current live text block, or create one */
function appendLiveText(blocks: StreamingBlock[], text: string): StreamingBlock[] {
  const idx = findLiveTextIndex(blocks);
  if (idx >= 0) {
    const block = blocks[idx] as StreamingTextLiveBlock;
    return [
      ...blocks.slice(0, idx),
      { ...block, content: block.content + text },
      ...blocks.slice(idx + 1),
    ];
  }
  return [...blocks, { type: 'text_live' as const, content: text }];
}

/** Append thinking content */
function appendThinking(blocks: StreamingBlock[], text: string): StreamingBlock[] {
  // Find or create thinking block
  const last = blocks[blocks.length - 1];
  if (last && last.type === 'thinking') {
    return [
      ...blocks.slice(0, -1),
      { ...last, content: last.content + text },
    ];
  }
  return [...blocks, { type: 'thinking' as const, content: text }];
}

/** Freeze the current live text block into a completed text block */
function freezeLiveText(blocks: StreamingBlock[]): StreamingBlock[] {
  const idx = findLiveTextIndex(blocks);
  if (idx < 0) return blocks;
  const live = blocks[idx] as StreamingTextLiveBlock;
  if (!live.content.trim()) {
    // Empty live block — just remove it
    return [...blocks.slice(0, idx), ...blocks.slice(idx + 1)];
  }
  return [
    ...blocks.slice(0, idx),
    { type: 'text' as const, content: live.content },
    ...blocks.slice(idx + 1),
  ];
}

export const useChatStore = create<ChatState>((set, get) => ({
  messages: [],
  loading: false,
  error: null,
  sending: false,
  streamingBlocks: [],
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
      streamingBlocks: [],
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

  preheatSession: () => {
    const client = getWsClient();
    const { currentSessionId, sending } = get();
    if (!client || !currentSessionId || sending) return;
    client.send({ type: 'session.preheat', sessionId: currentSessionId });
  },

  sendMessage: async (content, media) => {
    const client = getWsClient();
    if (!client) return;

    const trimmed = content.trim();
    if (!trimmed && (!media || media.length === 0)) return;

    const { currentSessionId, sessions, streamingBlocks, messages } = get();
    if (!currentSessionId) return;

    // Before clearing streaming state, save any in-progress assistant content to messages
    let allMessages = messages;
    if (streamingBlocks.length > 0) {
      const contentBlocks = streamingBlocksToContentBlocks(streamingBlocks);
      const textContent = streamingBlocks
        .filter(b => b.type === 'text' || b.type === 'text_live')
        .map(b => (b as StreamingTextBlock | StreamingTextLiveBlock).content)
        .join('');
      const partialAssistantMsg: Message = {
        id: crypto.randomUUID(),
        sessionId: currentSessionId,
        role: 'assistant',
        content: textContent.trim(),
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
      streamingBlocks: [],
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
      // ── Delta batching: accumulate tokens and flush every 50ms ──
      let pendingText = '';
      let pendingThinking = '';
      let flushTimer: ReturnType<typeof setTimeout> | null = null;

      const flushDeltas = () => {
        flushTimer = null;
        if (!pendingText && !pendingThinking) return;
        const textBatch = pendingText;
        const thinkBatch = pendingThinking;
        pendingText = '';
        pendingThinking = '';
        set((s) => {
          let blocks = s.streamingBlocks;
          if (textBatch) blocks = appendLiveText(blocks, textBatch);
          if (thinkBatch) blocks = appendThinking(blocks, thinkBatch);
          return { streamingBlocks: blocks };
        });
      };

      const scheduleFlush = () => {
        if (!flushTimer) {
          flushTimer = setTimeout(flushDeltas, 50);
        }
      };

      // Handle chat.segment: freeze the live text block, new text segment starting
      const unsubSegment = wrapHandler(client, 'chat.segment', (data) => {
        if (data.sessionId !== currentSessionId) return;
        const segType = String(data.segmentType || 'text');
        if (segType === 'text') {
          // Flush pending text first
          if (flushTimer) { clearTimeout(flushTimer); flushTimer = null; }
          flushDeltas();
          // Freeze the live text into a completed text block
          set((s) => ({ streamingBlocks: freezeLiveText(s.streamingBlocks) }));
        }
      });

      // Set up streaming handlers
      const unsubDelta = wrapHandler(client, 'chat.delta', (data) => {
        if (data.sessionId !== currentSessionId) return;
        const deltaType = String(data.deltaType || 'text');
        if (deltaType === 'thinking') {
          pendingThinking += String(data.content || '');
        } else {
          pendingText += String(data.content || '');
        }
        scheduleFlush();
      });

      const unsubToolStart = wrapHandler(client, 'chat.tool_start', (data) => {
        if (data.sessionId !== currentSessionId) return;
        // Flush pending deltas before adding tool block
        if (flushTimer) { clearTimeout(flushTimer); flushTimer = null; }
        flushDeltas();

        const toolId = String(data.toolId || '');
        const toolName = String(data.toolName || '');
        set((s) => ({
          streamingBlocks: [...s.streamingBlocks, {
            type: 'tool_use' as const,
            id: toolId,
            name: toolName,
            input: '',
            result: '',
            status: 'running' as const,
          }],
        }));
      });

      const unsubToolDelta = wrapHandler(client, 'chat.tool_delta', (data) => {
        if (data.sessionId !== currentSessionId) return;
        const toolId = String(data.toolId || '');
        const content = String(data.content || '');
        set((s) => ({
          streamingBlocks: s.streamingBlocks.map(b =>
            b.type === 'tool_use' && b.id === toolId ? { ...b, input: b.input + content } : b
          ),
        }));
      });

      const unsubToolResult = wrapHandler(client, 'chat.tool_result', (data) => {
        if (data.sessionId !== currentSessionId) return;
        const toolUseId = String(data.toolUseId || '');
        const content = String(data.content || '');
        set((s) => ({
          streamingBlocks: s.streamingBlocks.map(b =>
            b.type === 'tool_use' && b.id === toolUseId ? { ...b, result: b.result + content } : b
          ),
        }));
      });

      const unsubToolProgress = wrapHandler(client, 'chat.tool_progress', (data) => {
        if (data.sessionId !== currentSessionId) return;
        const toolUseId = String(data.toolUseId || '');
        const elapsedSeconds = typeof data.elapsedSeconds === 'number' ? data.elapsedSeconds : undefined;
        set((s) => ({
          streamingBlocks: s.streamingBlocks.map(b =>
            b.type === 'tool_use' && b.id === toolUseId ? { ...b, elapsedSeconds } : b
          ),
        }));
      });

      const unsubToolEnd = wrapHandler(client, 'chat.tool_end', (_data) => {
        if (_data.sessionId !== currentSessionId) return;
        // Mark all running tools as completed
        set((s) => ({
          streamingBlocks: s.streamingBlocks.map(b =>
            b.type === 'tool_use' && b.status === 'running' ? { ...b, status: 'completed' as const } : b
          ),
        }));
      });

      const unsubDone = wrapHandler(client, 'chat.done', (data) => {
        if (data.sessionId !== currentSessionId) return;
        // Flush any remaining batched deltas before finalizing
        if (flushTimer) { clearTimeout(flushTimer); flushTimer = null; }
        flushDeltas();
        cleanup();

        const frozen = freezeLiveText(get().streamingBlocks);
        const contentBlocks = streamingBlocksToContentBlocks(frozen);
        const textContent = frozen
          .filter(b => b.type === 'text')
          .map(b => (b as StreamingTextBlock).content)
          .join('');

        if (textContent.trim() || contentBlocks.length > 0) {
          const assistantMsg: Message = {
            id: crypto.randomUUID(),
            sessionId: currentSessionId,
            role: 'assistant',
            content: textContent.trim(),
            contentBlocks: contentBlocks.length > 0 ? contentBlocks : undefined,
            createdAt: new Date().toISOString(),
          };
          const st = get();
          set({
            messages: [...st.messages, assistantMsg],
            streamingBlocks: [],
            sending: false,
          });
        } else {
          set({ streamingBlocks: [], sending: false });
        }
      });

      const unsubErr = wrapHandler(client, 'chat.error', (_data) => {
        if (flushTimer) { clearTimeout(flushTimer); flushTimer = null; }
        pendingText = '';
        pendingThinking = '';
        cleanup();
        set({
          error: String(_data.error || 'Unknown error'),
          streamingBlocks: [],
          sending: false,
        });
      });

      const cleanup = () => {
        if (flushTimer) { clearTimeout(flushTimer); flushTimer = null; }
        unsubSegment();
        unsubDelta();
        unsubToolStart();
        unsubToolDelta();
        unsubToolResult();
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
    set({ sending: false, streamingBlocks: [], error: null });
    if (client && currentSessionId) {
      client.send({ type: 'chat.abort', sessionId: currentSessionId });
    }
  },

  clearError: () => set({ error: null }),
  toggleThinking: () => set((s) => ({ showThinking: !s.showThinking })),
  refresh: () => {
    set({ messages: [], streamingBlocks: [], error: null, sending: false });
    get().loadHistory();
  },
}));

/** Convert streaming blocks to ContentBlock[] for message storage */
function streamingBlocksToContentBlocks(blocks: StreamingBlock[]): ContentBlock[] {
  const result: ContentBlock[] = [];
  for (const block of blocks) {
    switch (block.type) {
      case 'thinking':
        if (block.content.trim()) {
          result.push({ type: 'thinking', thinking: block.content.trim() });
        }
        break;
      case 'text':
      case 'text_live':
        if (block.content.trim()) {
          result.push({ type: 'text', text: block.content.trim() });
        }
        break;
      case 'tool_use':
        try {
          const input = block.input ? JSON.parse(block.input) : {};
          result.push({ type: 'tool_use', id: block.id, name: block.name, input });
        } catch {
          result.push({ type: 'tool_use', id: block.id, name: block.name, input: block.input });
        }
        break;
    }
  }
  return result;
}
