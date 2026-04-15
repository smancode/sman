// src/stores/chat.ts

import { create } from 'zustand';
import { useWsConnection } from '@/stores/ws-connection';
import { sessionCache } from '@/lib/session-cache';
import type { ChatSession, ContentBlock, InitCard } from '@/types/chat';

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
  resolvedContent?: unknown;
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

// ── Error types ──

export interface ChatError {
  message: string;
  errorCode: string;
  rawError?: string;
}

export const ERROR_SUGGESTIONS: Record<string, string> = {
  rate_limit: '请等待 30 秒后重试',
  bad_request: '请修改输入内容后重试',
  auth_error: '前往「设置」页面检查 API Key',
  forbidden: '前往「设置」页面检查 API Key 权限',
  not_found: '前往「设置」页面检查模型名称是否正确',
  server_error: '等待几分钟后重试，或切换到其他模型',
  overloaded: '模型负载过高，请稍后重试',
  context_too_long: '尝试缩短对话或开启新会话',
  network_error: '检查网络连接和模型服务地址',
};

interface ChatState {
  // Messages
  messages: Message[];
  loading: boolean;
  error: ChatError | null;

  /** Shown when Claude takes too long to start responding */
  waitingHint: string | null;

  // Streaming
  sending: boolean;
  /** Streaming blocks for the CURRENT session (derived from streamingBlocksMap) */
  streamingBlocks: StreamingBlock[];

  // Sessions
  sessions: ChatSession[];
  currentSessionId: string;

  // Thinking toggle
  showThinking: boolean;

  // Init card
  initCard: InitCard | null;

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

// ── Per-session streaming state ──
// sessionId → streaming blocks for that session
// This map is outside Zustand to avoid deep-equal comparison overhead on every token.
const streamingBlocksMap = new Map<string, StreamingBlock[]>();

function getStreamingBlocks(sessionId: string): StreamingBlock[] {
  return streamingBlocksMap.get(sessionId) ?? [];
}

function setStreamingBlocks(sessionId: string, blocks: StreamingBlock[]): void {
  streamingBlocksMap.set(sessionId, blocks);
}

function clearStreamingBlocks(sessionId: string): void {
  streamingBlocksMap.delete(sessionId);
}

// Track per-session streaming cleanup functions so we can unsubscribe when session changes
const streamCleanups = new Map<string, () => void>();

function registerStreamCleanup(sessionId: string, cleanup: () => void): void {
  // If there's a previous cleanup (e.g. user sent new message before previous stream finished), call it
  const prev = streamCleanups.get(sessionId);
  if (prev) prev();
  streamCleanups.set(sessionId, cleanup);
}

function cleanupStream(sessionId: string): void {
  const fn = streamCleanups.get(sessionId);
  if (fn) {
    fn();
    streamCleanups.delete(sessionId);
  }
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

/** Append thinking content — always appends to the single thinking block */
function appendThinking(blocks: StreamingBlock[], text: string): StreamingBlock[] {
  // Find existing thinking block anywhere in the array
  const idx = blocks.findIndex(b => b.type === 'thinking');
  if (idx >= 0) {
    const block = blocks[idx] as StreamingThinkingBlock;
    return [
      ...blocks.slice(0, idx),
      { ...block, content: block.content + text },
      ...blocks.slice(idx + 1),
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

/** Pre-resolve content for memo stability — mirrors buildContent from Chat component */
function resolveContent(text: string, blocks?: unknown[]): unknown {
  if (!blocks || blocks.length === 0) return text;
  const hasTextBlock = (blocks as Array<{ type: string }>).some(b => b.type === 'text');
  if (hasTextBlock) return blocks;
  if (!text) return blocks;
  return [{ type: 'text', text }, ...blocks];
}

export const useChatStore = create<ChatState>((set, get) => ({
  messages: [],
  loading: false,
  error: null,
  waitingHint: null,
  sending: false,
  streamingBlocks: [],  // derived from streamingBlocksMap[currentSessionId]
  sessions: [],
  currentSessionId: '',
  showThinking: true,
  initCard: null,

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
          // Invalidate cache
          sessionCache.invalidate(sessionId);
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

  switchSession: async (sessionId: string) => {
    if (sessionId === get().currentSessionId) return;

    // Save current messages to cache
    const { currentSessionId, messages } = get();
    if (currentSessionId && messages.length > 0) {
      sessionCache.set(currentSessionId, messages);
    }

    // Read target session: memory first, then IndexedDB
    let cached = sessionCache.get(sessionId) as Message[] | null;
    if (!cached) {
      cached = (await sessionCache.getAsync(sessionId)) as Message[] | null;
    }

    // Guard: user may have triggered another switch while we awaited IndexedDB
    if (get().currentSessionId !== currentSessionId) return;

    // Determine if the target session has an active stream
    const targetBlocks = getStreamingBlocks(sessionId);
    const isTargetSending = targetBlocks.length > 0 || !!streamCleanups.has(sessionId);

    set({
      currentSessionId: sessionId,
      messages: cached ?? [],
      streamingBlocks: targetBlocks,
      error: null,
      sending: isTargetSending,
      loading: !cached,
    });

    // Always sync from backend
    get().loadHistory();
  },

  loadHistory: async () => {
    const client = getWsClient();
    if (!client) return;
    const sessionId = get().currentSessionId;
    if (!sessionId) return;

    if (!sessionCache.has(sessionId)) set({ loading: true, error: null });

    const unsub = wrapHandler(client, 'session.history', (data) => {
      // Ignore responses for other sessions — don't unsub, wait for ours
      if (String(data.sessionId) !== sessionId) return;
      unsub();

      // User already switched away — just update cache, don't touch UI
      if (get().currentSessionId !== sessionId) return;

      const serverMsgs: Message[] = Array.isArray(data.messages)
        ? data.messages.map((m: Record<string, unknown>) => {
            const content = String(m.content || '');
            const blocks = m.contentBlocks as ContentBlock[] | undefined;
            return {
              id: String(m.id || ''),
              sessionId: String(m.sessionId || ''),
              role: (String(m.role || 'user') as 'user' | 'assistant'),
              content,
              contentBlocks: blocks,
              createdAt: String(m.createdAt || ''),
              timestamp: typeof m.timestamp === 'number' ? m.timestamp : undefined,
              resolvedContent: resolveContent(content, blocks),
            };
          })
        : [];

      // Update cache
      sessionCache.set(sessionId, serverMsgs);

      // Backend has more messages than what's showing → update UI
      const { messages } = get();
      if (serverMsgs.length > messages.length) {
        set({ messages: serverMsgs, loading: false });
      } else {
        set({ loading: false });
      }

      // Auto-label from first user message
      const { sessions } = get();
      const session = sessions.find(s => s.key === sessionId);
      if (!session?.label) {
        const firstUser = serverMsgs.find(m => m.role === 'user' && m.content.trim());
        if (firstUser) {
          const text = firstUser.content.trim();
          const truncated = text.length > 20 ? `${text.slice(0, 20)}...` : text;
          get().updateSessionLabel(sessionId, truncated);
        }
      }
    });
    client.send({ type: 'session.history', sessionId });
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

    const { currentSessionId, sessions, messages } = get();
    if (!currentSessionId) return;

    // Before clearing streaming state, save any in-progress assistant content to messages
    const prevBlocks = getStreamingBlocks(currentSessionId);
    let allMessages = messages;
    if (prevBlocks.length > 0) {
      const contentBlocks = streamingBlocksToContentBlocks(prevBlocks);
      const textContent = prevBlocks
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

    // Reset per-session streaming state
    setStreamingBlocks(currentSessionId, []);
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

    // Capture sessionId at registration time — this is the primeKey for all streaming ops
    const streamSessionId = currentSessionId;

    // Cleanup any previous stream handlers for this session
    cleanupStream(streamSessionId);

    try {
      // ── Delta batching: accumulate tokens and flush every 50ms ──
      let pendingText = '';
      let pendingThinking = '';
      let flushTimer: ReturnType<typeof setTimeout> | null = null;

      // Helper: update streamingBlocks in the per-session map AND sync to store if currently visible
      const updateBlocks = (updater: (blocks: StreamingBlock[]) => StreamingBlock[]) => {
        const current = getStreamingBlocks(streamSessionId);
        const next = updater(current);
        setStreamingBlocks(streamSessionId, next);
        // Only update the store's streamingBlocks if this session is currently displayed
        if (get().currentSessionId === streamSessionId) {
          set({ streamingBlocks: next });
        }
      };

      const flushDeltas = () => {
        flushTimer = null;
        if (!pendingText && !pendingThinking) return;
        const textBatch = pendingText;
        const thinkBatch = pendingThinking;
        pendingText = '';
        pendingThinking = '';
        updateBlocks(blocks => {
          if (textBatch) blocks = appendLiveText(blocks, textBatch);
          if (thinkBatch) blocks = appendThinking(blocks, thinkBatch);
          return blocks;
        });
      };

      const scheduleFlush = () => {
        if (!flushTimer) {
          flushTimer = setTimeout(flushDeltas, 50);
        }
      };

      // Handle chat.segment: freeze the live text block, new text segment starting
      const unsubSegment = wrapHandler(client, 'chat.segment', (data) => {
        if (data.sessionId !== streamSessionId) return;
        const segType = String(data.segmentType || 'text');
        if (segType === 'text') {
          // Flush pending text first
          if (flushTimer) { clearTimeout(flushTimer); flushTimer = null; }
          flushDeltas();
          // Freeze the live text into a completed text block
          updateBlocks(blocks => freezeLiveText(blocks));
        }
      });

      // Waiting hint: show after 20s if no stream activity received
      let waitingTimer: ReturnType<typeof setTimeout> | null = setTimeout(() => {
        if (get().sending && get().currentSessionId === streamSessionId) {
          set({ waitingHint: 'Claude 正在响应中，请耐心等待…' });
        }
      }, 20_000);
      const clearWaitingHint = () => {
        if (waitingTimer) { clearTimeout(waitingTimer); waitingTimer = null; }
        if (get().waitingHint) set({ waitingHint: null });
      };

      // Monitor chat.aborted (stall or user-initiated)
      const unsubAborted = wrapHandler(client, 'chat.aborted', (data) => {
        if (data.sessionId !== streamSessionId) return;
        if (flushTimer) { clearTimeout(flushTimer); flushTimer = null; }
        flushDeltas();
        cleanup();

        // Freeze partial streaming content into an assistant message (same as chat.done)
        const frozen = freezeLiveText(getStreamingBlocks(streamSessionId));
        const contentBlocks = streamingBlocksToContentBlocks(frozen);
        const textContent = frozen
          .filter(b => b.type === 'text')
          .map(b => (b as StreamingTextBlock).content)
          .join('');

        clearStreamingBlocks(streamSessionId);

        const reason = String(data.reason || '');

        if (textContent.trim() || contentBlocks.length > 0) {
          // Save partial content as a message so user doesn't lose it
          const assistantMsg: Message = {
            id: crypto.randomUUID(),
            sessionId: streamSessionId,
            role: 'assistant',
            content: textContent.trim(),
            contentBlocks: contentBlocks.length > 0 ? contentBlocks : undefined,
            createdAt: new Date().toISOString(),
          };
          if (get().currentSessionId === streamSessionId) {
            const st = get();
            const finalMessages = [...st.messages, assistantMsg];
            set({
              messages: finalMessages,
              streamingBlocks: [],
              sending: false,
              waitingHint: null,
              ...(reason ? {
                error: {
                  message: { stall: '响应超时，已自动中断', v2_session_lost: '会话进程丢失，已自动中断', process_dead: '会话进程异常退出，已自动中断' }[reason] ?? '响应已中断',
                  errorCode: reason,
                },
              } : {}),
            });
            sessionCache.set(streamSessionId, finalMessages);
          } else {
            const cached = sessionCache.get(streamSessionId) as Message[] | null;
            const cachedMessages = cached ?? [];
            sessionCache.set(streamSessionId, [...cachedMessages, assistantMsg]);
          }
        } else {
          if (get().currentSessionId === streamSessionId) {
            set({
              streamingBlocks: [],
              sending: false,
              waitingHint: null,
              ...(reason ? {
                error: {
                  message: { stall: '响应超时，已自动中断', v2_session_lost: '会话进程丢失，已自动中断', process_dead: '会话进程异常退出，已自动中断' }[reason] ?? '响应已中断',
                  errorCode: reason,
                },
              } : {}),
            });
          }
        }
      });

      // Any stream activity cancels the waiting hint
      const clearWaitingOnActivity = () => {
        clearWaitingHint();
      };

      // Set up streaming handlers
      const unsubDelta = wrapHandler(client, 'chat.delta', (data) => {
        if (data.sessionId !== streamSessionId) return;
        clearWaitingOnActivity();
        const deltaType = String(data.deltaType || 'text');
        if (deltaType === 'thinking') {
          pendingThinking += String(data.content || '');
        } else {
          pendingText += String(data.content || '');
        }
        scheduleFlush();
      });

      const unsubToolStart = wrapHandler(client, 'chat.tool_start', (data) => {
        if (data.sessionId !== streamSessionId) return;
        clearWaitingOnActivity();
        // Flush pending deltas before adding tool block
        if (flushTimer) { clearTimeout(flushTimer); flushTimer = null; }
        flushDeltas();

        const toolId = String(data.toolId || '');
        const toolName = String(data.toolName || '');
        updateBlocks(blocks => [...blocks, {
          type: 'tool_use' as const,
          id: toolId,
          name: toolName,
          input: '',
          result: '',
          status: 'running' as const,
        }]);
      });

      const unsubToolDelta = wrapHandler(client, 'chat.tool_delta', (data) => {
        if (data.sessionId !== streamSessionId) return;
        const toolId = String(data.toolId || '');
        const content = String(data.content || '');
        updateBlocks(blocks => blocks.map(b =>
          b.type === 'tool_use' && b.id === toolId ? { ...b, input: b.input + content } : b
        ));
      });

      const unsubToolResult = wrapHandler(client, 'chat.tool_result', (data) => {
        if (data.sessionId !== streamSessionId) return;
        const toolUseId = String(data.toolUseId || '');
        const content = String(data.content || '');
        updateBlocks(blocks => blocks.map(b =>
          b.type === 'tool_use' && b.id === toolUseId ? { ...b, result: b.result + content } : b
        ));
      });

      const unsubToolProgress = wrapHandler(client, 'chat.tool_progress', (data) => {
        if (data.sessionId !== streamSessionId) return;
        const toolUseId = String(data.toolUseId || '');
        const elapsedSeconds = typeof data.elapsedSeconds === 'number' ? data.elapsedSeconds : undefined;
        updateBlocks(blocks => blocks.map(b =>
          b.type === 'tool_use' && b.id === toolUseId ? { ...b, elapsedSeconds } : b
        ));
      });

      const unsubToolEnd = wrapHandler(client, 'chat.tool_end', (data) => {
        if (data.sessionId !== streamSessionId) return;
        // Mark all running tools as completed
        updateBlocks(blocks => blocks.map(b =>
          b.type === 'tool_use' && b.status === 'running' ? { ...b, status: 'completed' as const } : b
        ));
      });

      const unsubDone = wrapHandler(client, 'chat.done', (data) => {
        if (data.sessionId !== streamSessionId) return;
        // Flush any remaining batched deltas before finalizing
        if (flushTimer) { clearTimeout(flushTimer); flushTimer = null; }
        flushDeltas();
        cleanup();

        const frozen = freezeLiveText(getStreamingBlocks(streamSessionId));
        const contentBlocks = streamingBlocksToContentBlocks(frozen);
        const textContent = frozen
          .filter(b => b.type === 'text')
          .map(b => (b as StreamingTextBlock).content)
          .join('');

        clearStreamingBlocks(streamSessionId);

        if (textContent.trim() || contentBlocks.length > 0) {
          const assistantMsg: Message = {
            id: crypto.randomUUID(),
            sessionId: streamSessionId,
            role: 'assistant',
            content: textContent.trim(),
            contentBlocks: contentBlocks.length > 0 ? contentBlocks : undefined,
            createdAt: new Date().toISOString(),
          };
          // Only update messages if this session is currently displayed
          if (get().currentSessionId === streamSessionId) {
            const st = get();
            const finalMessages = [...st.messages, assistantMsg];
            set({
              messages: finalMessages,
              streamingBlocks: [],
              sending: false,
              waitingHint: null,
            });
            sessionCache.set(streamSessionId, finalMessages);
          } else {
            // Session is in background — update cache only, don't touch visible messages
            const cached = sessionCache.get(streamSessionId) as Message[] | null;
            const cachedMessages = cached ?? [];
            const finalMessages = [...cachedMessages, assistantMsg];
            sessionCache.set(streamSessionId, finalMessages);
            // Clear sending flag if this was the active session
            if (get().currentSessionId === streamSessionId) {
              set({ sending: false });
            }
          }
        } else {
          if (get().currentSessionId === streamSessionId) {
            set({ streamingBlocks: [], sending: false, waitingHint: null });
          }
        }
      });

      const unsubErr = wrapHandler(client, 'chat.error', (data) => {
        if (data.sessionId !== streamSessionId) return;
        if (flushTimer) { clearTimeout(flushTimer); flushTimer = null; }
        pendingText = '';
        pendingThinking = '';
        cleanup();
        clearStreamingBlocks(streamSessionId);
        if (get().currentSessionId === streamSessionId) {
          set({
            error: {
              message: String(data.error || 'Unknown error'),
              errorCode: String(data.errorCode || 'unknown'),
              rawError: data.rawError ? String(data.rawError) : undefined,
            },
            streamingBlocks: [],
            sending: false,
            waitingHint: null,
          });
        }
      });

      const cleanup = () => {
        if (flushTimer) { clearTimeout(flushTimer); flushTimer = null; }
        if (waitingTimer) { clearTimeout(waitingTimer); waitingTimer = null; }
        unsubSegment();
        unsubDelta();
        unsubToolStart();
        unsubToolDelta();
        unsubToolResult();
        unsubToolProgress();
        unsubToolEnd();
        unsubDone();
        unsubErr();
        unsubAborted();
        streamCleanups.delete(streamSessionId);
      };

      // Register cleanup so switchSession can unsubscribe if needed
      registerStreamCleanup(streamSessionId, cleanup);

      // Send message
      const msg: Record<string, unknown> = { type: 'chat.send', sessionId: streamSessionId, content: trimmed };
      if (media && media.length > 0) {
        msg.media = media;
      }
      client.send(msg);
    } catch (err) {
      set({ error: { message: String(err), errorCode: 'unknown' }, sending: false });
    }
  },

  abortRun: () => {
    const client = getWsClient();
    const { currentSessionId } = get();
    // Cleanup stream handlers and clear per-session streaming state
    cleanupStream(currentSessionId);
    clearStreamingBlocks(currentSessionId);
    set({ sending: false, streamingBlocks: [], error: null });
    if (client && currentSessionId) {
      client.send({ type: 'chat.abort', sessionId: currentSessionId });
    }
  },

  clearError: () => set({ error: null }),
  toggleThinking: () => set((s) => ({ showThinking: !s.showThinking })),
  refresh: () => {
    const { currentSessionId } = get();
    if (currentSessionId) {
      sessionCache.invalidate(currentSessionId);
      cleanupStream(currentSessionId);
      clearStreamingBlocks(currentSessionId);
    }
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
