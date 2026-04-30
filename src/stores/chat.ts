// src/stores/chat.ts

import { create } from 'zustand';
import { useWsConnection } from '@/stores/ws-connection';
import { sessionCache } from '@/lib/session-cache';
import { contextUsageCache } from '@/lib/context-usage-cache';
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

export interface AttachedFileMeta {
  fileName: string;
  mimeType: string;
  fileSize: number;
  preview: string | null;
  filePath?: string;
}

export interface Message {
  id: string;
  sessionId: string;
  role: 'user' | 'assistant';
  content: string;
  contentBlocks?: ContentBlock[];
  createdAt: string;
  timestamp?: number;
  resolvedContent?: unknown;
  _attachedFiles?: AttachedFileMeta[];
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
  /** Human-readable summary from SDK (task_progress / tool_use_summary) */
  summary?: string;
  /** Task ID if this is an Agent/Task tool */
  taskId?: string;
  /** Description from task_started */
  taskDescription?: string;
}

/** A thinking block */
interface StreamingThinkingBlock {
  type: 'thinking';
  content: string;
}

/** An interactive question block from AskUserQuestion tool */
interface StreamingAskUserBlock {
  type: 'ask_user';
  askId: string;
  questions: Array<{
    question: string;
    header: string;
    options: Array<{ label: string; description: string }>;
    multiSelect: boolean;
  }>;
  answered: boolean;
  answers?: Record<string, string[]>;
}

export type StreamingBlock = StreamingTextBlock | StreamingTextLiveBlock | StreamingToolBlock | StreamingThinkingBlock | StreamingAskUserBlock;

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

  /** Context length warning for current session */
  contextWarning: { level: 'warning' | 'critical'; inputTokens: number; message: string } | null;

  /** Context usage stats for current session */
  contextUsage: { inputTokens: number; outputTokens: number } | null;

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

  // Auto-confirm toggle — automatically answer AskUserQuestion with "auto"
  autoConfirm: boolean;

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
  toggleAutoConfirm: () => void;
  clearContextWarning: () => void;
  refresh: () => void;
  updateSessionLabel: (sessionId: string, label: string) => Promise<void>;
  answerAskUser: (askId: string, answers: Record<string, string[]>) => void;
}

// ── Per-session streaming state ──
// sessionId → streaming blocks for that session
// This map is outside Zustand to avoid deep-equal comparison overhead on every token.
export const sendingSessions = new Set<string>();

const streamingBlocksMap = new Map<string, StreamingBlock[]>();

// ── Per-session sending state ──
// Tracks which sessions are currently streaming a response.
// This replaces a single global `sending` boolean to prevent session A's stream
// from blocking message submission in session B.

export function getStreamingBlocks(sessionId: string): StreamingBlock[] {
  return streamingBlocksMap.get(sessionId) ?? [];
}

function setStreamingBlocks(sessionId: string, blocks: StreamingBlock[]): void {
  streamingBlocksMap.set(sessionId, blocks);
}

export function clearStreamingBlocks(sessionId: string): void {
  streamingBlocksMap.delete(sessionId);
}

// Track per-session streaming cleanup functions so we can unsubscribe when session changes
const streamCleanups = new Map<string, () => void>();

// Monotonically increasing generation counter to distinguish old vs new streams for the same session
let streamGeneration = 0;

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
export function freezeLiveText(blocks: StreamingBlock[]): StreamingBlock[] {
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
export function resolveContent(text: string, blocks?: unknown[]): unknown {
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
  contextWarning: null,
  contextUsage: null,
  waitingHint: null,
  sending: false,
  streamingBlocks: [],  // derived from streamingBlocksMap[currentSessionId]
  sessions: [],
  currentSessionId: '',
  showThinking: true,
  autoConfirm: false,
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

    // Optimistic: remove from local state immediately for instant UI feedback
    sessionCache.invalidate(sessionId);
    const state = get();
    const remaining = state.sessions.filter(s => s.key !== sessionId);
    const switchingToDeleted = state.currentSessionId === sessionId;
    const newId = switchingToDeleted
      ? (remaining.length > 0 ? remaining[0].key : '')
      : state.currentSessionId;
    set({ sessions: remaining, currentSessionId: newId });
    if (switchingToDeleted) {
      sessionCache.invalidate(newId);
      get().loadHistory();
    }

    // Fire-and-forget WS — backend will also send session.deleted but we already updated
    client.send({ type: 'session.delete', sessionId });
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

    // Save current messages and context usage to cache
    const { currentSessionId, messages, contextUsage } = get();
    if (currentSessionId) {
      if (messages.length > 0) {
        // If current session is streaming, merge streaming content into cached messages
        const isStreaming = sendingSessions.has(currentSessionId);
        if (isStreaming) {
          const blocks = getStreamingBlocks(currentSessionId);
          const streamText = blocks
            .filter((b): b is StreamingTextBlock | StreamingTextLiveBlock => b.type === 'text' || b.type === 'text_live')
            .map(b => b.content)
            .join('');
          if (streamText) {
            const withStream = [...messages, {
              id: 'streaming-placeholder',
              sessionId: currentSessionId,
              role: 'assistant' as const,
              content: `[进行中] ${streamText}`,
              createdAt: new Date().toISOString(),
              resolvedContent: streamText,
            }];
            sessionCache.set(currentSessionId, withStream);
          } else {
            sessionCache.set(currentSessionId, messages);
          }
        } else {
          sessionCache.set(currentSessionId, messages);
        }
      }
      if (contextUsage) {
        contextUsageCache.set(currentSessionId, contextUsage);
      }
    }

    // Read target session: memory first, then IndexedDB
    let cached = sessionCache.get(sessionId) as Message[] | null;
    if (!cached) {
      cached = (await sessionCache.getAsync(sessionId)) as Message[] | null;
    }

    // Read target session context usage
    let cachedUsage = contextUsageCache.get(sessionId);
    if (!cachedUsage) {
      cachedUsage = await contextUsageCache.getAsync(sessionId);
    }

    // Guard: user may have triggered another switch while we awaited IndexedDB
    if (get().currentSessionId !== currentSessionId) return;

    // Determine if the target session has an active stream
    const targetBlocks = getStreamingBlocks(sessionId);
    const isTargetSending = sendingSessions.has(sessionId);

    set({
      currentSessionId: sessionId,
      messages: cached ?? [],
      streamingBlocks: targetBlocks,
      error: null,
      contextWarning: null,
      contextUsage: cachedUsage ?? null,
      sending: isTargetSending,
      loading: !cached,
    });

    // Skip loadHistory if target session is actively streaming —
    // server hasn't persisted the streaming message yet, loadHistory would
    // overwrite messages with stale data causing content to disappear
    if (!isTargetSending) {
      get().loadHistory();
    }
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
            const role = String(m.role || 'user') as 'user' | 'assistant';
            let attachedFiles: Array<{ fileName: string; mimeType: string; fileSize: number; preview: string | null; filePath?: string }> | undefined;

            // Reconstruct _attachedFiles for user messages
            if (role === 'user') {
              // From persisted contentBlocks (attached_file type)
              if (blocks) {
                const fileBlocks = blocks.filter((b: any) => b.type === 'attached_file');
                if (fileBlocks.length > 0) {
                  attachedFiles = fileBlocks.map((b: any) => ({
                    fileName: b.fileName || 'file',
                    mimeType: 'application/octet-stream',
                    fileSize: 0,
                    preview: null,
                    filePath: b.filePath,
                  }));
                }
              }
              // Fallback: extract from text [用户文件路径:[path1,path2]]
              if (!attachedFiles) {
                const pathMatch = content.match(/\[用户文件路径:\[([^\]]+)\]\]/);
                if (pathMatch) {
                  attachedFiles = pathMatch[1].split(',').map(fp => ({
                    fileName: fp.split(/[/\\]/).pop() || 'file',
                    mimeType: 'application/octet-stream',
                    fileSize: 0,
                    preview: null,
                    filePath: fp,
                  }));
                }
              }
            }

            return {
              id: String(m.id || ''),
              sessionId: String(m.sessionId || ''),
              role,
              content,
              contentBlocks: blocks,
              createdAt: String(m.createdAt || ''),
              timestamp: typeof m.timestamp === 'number' ? m.timestamp : undefined,
              resolvedContent: resolveContent(content, blocks),
              _attachedFiles: attachedFiles,
            };
          })
        : [];

      // Update cache
      sessionCache.set(sessionId, serverMsgs);

      // Always update UI with server data (content may differ even if length matches)
      // Restore context usage from server if available
      const serverUsage = data.usage as { inputTokens: number; outputTokens: number } | null | undefined;
      const contextUsage = serverUsage && serverUsage.inputTokens > 0 ? serverUsage : null;
      set({ messages: serverMsgs, loading: false, contextUsage });

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

    // Cannot send while a response is in progress for THIS session
    if (sendingSessions.has(currentSessionId)) return;

    // Extract file paths from text for UI display (format: [用户文件路径:[path1,path2]])
    const attachedFiles: Array<{ fileName: string; mimeType: string; fileSize: number; preview: string | null; filePath?: string }> = [];
    const pathMatch = trimmed.match(/\[用户文件路径:\[([^\]]+)\]\]/);
    if (pathMatch) {
      const paths = pathMatch[1].split(',');
      for (const fp of paths) {
        attachedFiles.push({
          fileName: fp.split(/[/\\]/).pop() || 'file',
          mimeType: 'application/octet-stream',
          fileSize: 0,
          preview: null,
          filePath: fp,
        });
      }
    }

    const userMsg: Message = {
      id: crypto.randomUUID(),
      sessionId: currentSessionId,
      role: 'user',
      content: trimmed,
      createdAt: new Date().toISOString(),
      _attachedFiles: attachedFiles.length > 0 ? attachedFiles : undefined,
    };

    // Reset per-session streaming state
    setStreamingBlocks(currentSessionId, []);
    sendingSessions.add(currentSessionId);

    // Capture sessionId at registration time — this is the primeKey for all streaming ops
    const streamSessionId = currentSessionId;

    // Capture generation to ignore stale aborted/done events from previous streams
    const myGeneration = ++streamGeneration;

    // Update UI: show user message and start sending indicator
    set({
      messages: [...messages, userMsg],
      sending: true,
      streamingBlocks: [],
      error: null,
      contextWarning: null,
    });

    // Yield to React so it can render the user message and "..." indicator immediately,
    // before we do the heavy work of registering stream handlers and sending WS.
    await new Promise<void>(r => setTimeout(r, 0));

    // Pre-heat session & refresh git branch (deferred from ChatInput to avoid blocking typing)
    get().preheatSession();
    (window as any).__sman_gitBranchRefresh?.();

    // Update label from first message if not set
    const session = sessions.find(s => s.key === currentSessionId);
    if (!session?.label) {
      // Strip file path tags for label display
      const labelContent = trimmed.replace(/\s*\[用户文件路径:\[[^\]]+\]\]/, '').trim();
      const labelText = labelContent || (attachedFiles.length > 0 ? `[${attachedFiles[0].fileName}]` : '');
      if (labelText) {
        const truncated = labelText.length > 20 ? `${labelText.slice(0, 20)}...` : labelText;
        get().updateSessionLabel(currentSessionId, truncated);
      }
    }

    // Cleanup any previous stream handlers for this session
    cleanupStream(streamSessionId);

    try {
      // ── Auto-save streaming content to session cache every 5s ──
      // Lightweight: only saves text content, no contentBlocks.
      // Ensures partial output survives aborts, errors, tab switches, or crashes.
      const autoSaveInterval = setInterval(() => {
        if (!sendingSessions.has(streamSessionId)) {
          clearInterval(autoSaveInterval);
          return;
        }
        const blocks = getStreamingBlocks(streamSessionId);
        if (blocks.length === 0) return;
        const frozen = freezeLiveText(blocks);
        const textContent = frozen
          .filter(b => b.type === 'text')
          .map(b => (b as StreamingTextBlock).content)
          .join('');
        if (!textContent.trim()) return;
        const cached = sessionCache.get(streamSessionId) as Message[] | null;
        const existing = cached ?? [];
        // Replace last auto-saved partial message, or append new one
        const lastIsPartial = existing.length > 0 && existing[existing.length - 1].role === 'assistant'
          && existing[existing.length - 1].content.startsWith('[进行中] ');
        const partialMsg: Message = {
          id: lastIsPartial ? existing[existing.length - 1].id : crypto.randomUUID(),
          sessionId: streamSessionId,
          role: 'assistant',
          content: `[进行中] ${textContent.trim()}`,
          createdAt: new Date().toISOString(),
        };
        const updated = lastIsPartial
          ? [...existing.slice(0, -1), partialMsg]
          : [...existing, partialMsg];
        sessionCache.set(streamSessionId, updated);
      }, 5_000);

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
        clearWaitingOnActivity();
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
          set({ waitingHint: 'LLM 正在响应中，请耐心等待…' });
        }
      }, 20_000);
      const clearWaitingHint = () => {
        if (waitingTimer) { clearTimeout(waitingTimer); waitingTimer = null; }
        if (get().waitingHint) set({ waitingHint: null });
      };

      // Monitor chat.aborted (stall or user-initiated) — ignore stale aborts from previous streams
      const unsubAborted = wrapHandler(client, 'chat.aborted', (data) => {
        if (data.sessionId !== streamSessionId) return;
        if (myGeneration !== streamGeneration) return; // Stale abort from a previous stream
        if (flushTimer) { clearTimeout(flushTimer); flushTimer = null; }
        flushDeltas();
        cleanup();
        sendingSessions.delete(streamSessionId);

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
            const finalMessages = [...removeAutoSavedPartial(st.messages), assistantMsg];
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
            const cachedMessages = removeAutoSavedPartial(cached ?? []);
            sessionCache.set(streamSessionId, [...cachedMessages, assistantMsg]);
          }
        } else {
          // Streaming collected nothing but backend may have saved partial content to SQLite.
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
            get().loadHistory();
          }
        }
      });

      // Any stream activity cancels the waiting hint
      let clearWaitingOnActivity = () => {
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
        clearWaitingOnActivity();
        const toolId = String(data.toolId || '');
        const content = String(data.content || '');
        updateBlocks(blocks => blocks.map(b =>
          b.type === 'tool_use' && b.id === toolId ? { ...b, input: b.input + content } : b
        ));
      });

      const unsubToolResult = wrapHandler(client, 'chat.tool_result', (data) => {
        if (data.sessionId !== streamSessionId) return;
        clearWaitingOnActivity();
        const toolUseId = String(data.toolUseId || '');
        const content = String(data.content || '');
        updateBlocks(blocks => blocks.map(b =>
          b.type === 'tool_use' && b.id === toolUseId ? { ...b, result: b.result + content } : b
        ));
      });

      const unsubToolProgress = wrapHandler(client, 'chat.tool_progress', (data: Record<string, unknown>) => {
        if (data.sessionId !== streamSessionId) return;
        clearWaitingOnActivity();
        const toolUseId = String(data.toolUseId || '');
        const elapsedSeconds = typeof data.elapsedSeconds === 'number' ? data.elapsedSeconds as number : undefined;
        const taskId = data.taskId ? String(data.taskId) : undefined;
        updateBlocks(blocks => blocks.map(b =>
          b.type === 'tool_use' && b.id === toolUseId
            ? { ...b, elapsedSeconds, ...(taskId ? { taskId } : {}) }
            : b
        ));
      });

      const unsubTaskStarted = wrapHandler(client, 'chat.task_started', (data: Record<string, unknown>) => {
        if (data.sessionId !== streamSessionId) return;
        const toolUseId = data.toolUseId ? String(data.toolUseId) : undefined;
        const taskId = data.taskId ? String(data.taskId) : undefined;
        const description = data.description ? String(data.description) : '';
        if (toolUseId) {
          updateBlocks(blocks => blocks.map(b =>
            b.type === 'tool_use' && b.id === toolUseId
              ? { ...b, taskId, taskDescription: description, summary: description || b.summary }
              : b
          ));
        }
      });

      const unsubTaskProgress = wrapHandler(client, 'chat.task_progress', (data: Record<string, unknown>) => {
        if (data.sessionId !== streamSessionId) return;
        const taskId = data.taskId ? String(data.taskId) : undefined;
        const description = data.description ? String(data.description) : '';
        const lastToolName = data.lastToolName ? String(data.lastToolName) : undefined;
        const summary = data.summary ? String(data.summary) : description;
        if (!summary) return;
        // Update by taskId or toolUseId
        const toolUseId = data.toolUseId ? String(data.toolUseId) : undefined;
        updateBlocks(blocks => blocks.map(b => {
          if (b.type !== 'tool_use') return b;
          if (toolUseId && b.id === toolUseId) return { ...b, summary, taskDescription: description };
          if (taskId && b.taskId === taskId) return { ...b, summary, taskDescription: description };
          return b;
        }));
      });

      const unsubTaskNotification = wrapHandler(client, 'chat.task_notification', (data: Record<string, unknown>) => {
        if (data.sessionId !== streamSessionId) return;
        const taskId = data.taskId ? String(data.taskId) : undefined;
        const summary = data.summary ? String(data.summary) : '';
        const toolUseId = data.toolUseId ? String(data.toolUseId) : undefined;
        // Update summary only — do NOT set status to 'completed' here.
        // task_notification fires for each sub-task inside an Agent, not for the Agent tool itself.
        // The tool status is only finalized by chat.tool_end (triggered by the next assistant event).
        if (!summary) return;
        updateBlocks(blocks => blocks.map(b => {
          if (b.type !== 'tool_use') return b;
          if (toolUseId && b.id === toolUseId) return { ...b, summary: summary || b.summary };
          if (taskId && b.taskId === taskId) return { ...b, summary: summary || b.summary };
          return b;
        }));
      });

      const unsubToolUseSummary = wrapHandler(client, 'chat.tool_use_summary', (data: Record<string, unknown>) => {
        if (data.sessionId !== streamSessionId) return;
        const summary = data.summary ? String(data.summary) : '';
        const precedingIds = (data.precedingToolUseIds as string[]) || [];
        if (!summary || precedingIds.length === 0) return;
        // Update the last preceding tool with the summary
        const lastId = precedingIds[precedingIds.length - 1];
        updateBlocks(blocks => blocks.map(b =>
          b.type === 'tool_use' && b.id === lastId ? { ...b, summary } : b
        ));
      });

      const unsubToolEnd = wrapHandler(client, 'chat.tool_end', (data) => {
        if (data.sessionId !== streamSessionId) return;
        const toolUseId = data.toolUseId ? String(data.toolUseId) : undefined;
        // Mark the specific tool as completed by id; fall back to all running tools if no id
        updateBlocks(blocks => blocks.map(b => {
          if (b.type !== 'tool_use') return b;
          if (toolUseId && b.id === toolUseId) return { ...b, status: 'completed' as const };
          if (!toolUseId && b.status === 'running') return { ...b, status: 'completed' as const };
          return b;
        }));
      });

      const unsubAskUser = wrapHandler(client, 'chat.ask_user', (data) => {
        if (data.sessionId !== streamSessionId) return;
        clearWaitingOnActivity();
        // Flush pending deltas before adding ask_user block
        if (flushTimer) { clearTimeout(flushTimer); flushTimer = null; }
        flushDeltas();
        // Freeze any live text first
        updateBlocks(blocks => freezeLiveText(blocks));

        const askId = String(data.askId || '');
        const questions = Array.isArray(data.questions) ? data.questions : [];
        updateBlocks(blocks => [...blocks, {
          type: 'ask_user' as const,
          askId,
          questions,
          answered: false,
        }]);

        // Auto-confirm (YOLO mode): automatically approve and continue without asking
        if (get().autoConfirm) {
          const autoAnswers: Record<string, string[]> = {};
          for (const q of questions) {
            const questionText = (q as { question: string }).question || '';
            autoAnswers[questionText] = ['继续，用你的最佳判断推进，不要再问我'];
          }
          setTimeout(() => {
            get().answerAskUser(askId, autoAnswers);
          }, 500);
        }
      });

      const unsubContextWarn = wrapHandler(client, 'chat.context_warning', (data: Record<string, unknown>) => {
        if (data.sessionId !== streamSessionId) return;
        if (myGeneration !== streamGeneration) return;
        if (get().currentSessionId === streamSessionId) {
          set({
            contextWarning: {
              level: data.level as 'warning' | 'critical',
              inputTokens: data.inputTokens as number,
              message: data.message as string,
            },
          });
        }
      });

      const unsubDone = wrapHandler(client, 'chat.done', (data) => {
        if (data.sessionId !== streamSessionId) return;
        if (myGeneration !== streamGeneration) return; // Stale done from a previous stream
        // Flush any remaining batched deltas before finalizing
        if (flushTimer) { clearTimeout(flushTimer); flushTimer = null; }
        flushDeltas();
        cleanup();
        sendingSessions.delete(streamSessionId);

        const frozen = freezeLiveText(getStreamingBlocks(streamSessionId));
        const contentBlocks = streamingBlocksToContentBlocks(frozen);
        const textContent = frozen
          .filter(b => b.type === 'text')
          .map(b => (b as StreamingTextBlock).content)
          .join('');

        clearStreamingBlocks(streamSessionId);

        const usage = data.usage as { inputTokens: number; outputTokens: number } | null | undefined;

        // Update context usage cache (memory + IndexedDB)
        if (usage && usage.inputTokens > 0) {
          contextUsageCache.set(streamSessionId, usage);
        }

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
            const finalMessages = [...removeAutoSavedPartial(st.messages), assistantMsg];
            set({
              messages: finalMessages,
              streamingBlocks: [],
              sending: false,
              waitingHint: null,
              ...(usage ? { contextUsage: usage } : {}),
            });
            sessionCache.set(streamSessionId, finalMessages);
          } else {
            // Session is in background — update cache only, don't touch visible messages
            const cached = sessionCache.get(streamSessionId) as Message[] | null;
            const finalMessages = [...removeAutoSavedPartial(cached ?? []), assistantMsg];
            sessionCache.set(streamSessionId, finalMessages);
            // Also clear visible sending if this background session was the one showing
            if (get().sending && !sendingSessions.has(get().currentSessionId)) {
              set({ sending: false });
            }
          }
        } else {
          // Streaming collected nothing — backend may have stored the message in SQLite.
          // Fallback: reload history from backend to avoid showing an empty response.
          if (get().currentSessionId === streamSessionId) {
            set({ streamingBlocks: [], sending: false, waitingHint: null, ...(usage ? { contextUsage: usage } : {}) });
            get().loadHistory();
          }
        }
      });

      const unsubErr = wrapHandler(client, 'chat.error', (data) => {
        if (data.sessionId !== streamSessionId) return;
        if (myGeneration !== streamGeneration) return; // Stale error from a previous stream
        if (flushTimer) { clearTimeout(flushTimer); flushTimer = null; }
        pendingText = '';
        pendingThinking = '';
        cleanup();
        sendingSessions.delete(streamSessionId);
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
        clearInterval(autoSaveInterval);
        unsubSegment();
        unsubDelta();
        unsubToolStart();
        unsubToolDelta();
        unsubToolResult();
        unsubToolProgress();
        unsubTaskStarted();
        unsubTaskProgress();
        unsubTaskNotification();
        unsubToolUseSummary();
        unsubToolEnd();
        unsubAskUser();
        unsubContextWarn();
        unsubDone();
        unsubErr();
        unsubAborted();
        streamCleanups.delete(streamSessionId);
      };

      // Register cleanup so switchSession can unsubscribe if needed
      registerStreamCleanup(streamSessionId, cleanup);

      // Safety net: if no chat.done/chat.error/chat.aborted received within 60s of last activity,
      // force-close the stream to prevent UI from being stuck forever.
      // This covers cases where backend crashes/restarts mid-stream or SDK stalls.
      let lastStreamActivity = Date.now();
      const stallCheckInterval = setInterval(() => {
        if (!sendingSessions.has(streamSessionId)) {
          clearInterval(stallCheckInterval);
          return;
        }
        const elapsed = Date.now() - lastStreamActivity;
        if (elapsed > 300_000) {
          console.warn(`[Sman] Stream stall detected for session ${streamSessionId} (no activity for ${Math.round(elapsed / 1000)}s), force-closing`);
          clearInterval(stallCheckInterval);
          // Save whatever we have
          const blocks = getStreamingBlocks(streamSessionId);
          const frozen = freezeLiveText(blocks);
          const textContent = frozen.filter(b => b.type === 'text').map(b => (b as { content: string }).content).join('');
          cleanup();
          sendingSessions.delete(streamSessionId);
          clearStreamingBlocks(streamSessionId);
          if (get().currentSessionId === streamSessionId) {
            if (textContent.trim()) {
              const assistantMsg: Message = {
                id: crypto.randomUUID(),
                sessionId: streamSessionId,
                role: 'assistant',
                content: textContent.trim(),
                createdAt: new Date().toISOString(),
              };
              set({ messages: [...get().messages, assistantMsg], streamingBlocks: [], sending: false, waitingHint: null });
            } else {
              set({
                sending: false,
                streamingBlocks: [],
                waitingHint: null,
                error: {
                  message: '响应超时，请重试或开启新会话',
                  errorCode: 'network_error',
                },
              });
            }
          }
        }
      }, 30_000);
      stallCheckInterval.unref?.();

      // Update lastStreamActivity on any stream event — patch the clearWaitingOnActivity
      const origClearWaiting = clearWaitingOnActivity;
      clearWaitingOnActivity = () => {
        lastStreamActivity = Date.now();
        origClearWaiting();
      };

      // Send message — file paths are already embedded in content text
      const msg: Record<string, unknown> = { type: 'chat.send', sessionId: streamSessionId, content: trimmed, autoConfirm: get().autoConfirm };
      client.send(msg);
    } catch (err) {
      set({ error: { message: String(err), errorCode: 'unknown' }, sending: false });
    }
  },

  abortRun: () => {
    const client = getWsClient();
    const { currentSessionId } = get();
    if (!client || !currentSessionId) return;

    // Send abort to backend — do NOT cleanup stream handlers or clear streaming blocks here.
    // The backend will send chat.aborted after finishing, and the existing handler will:
    // 1. Freeze partial streaming content into an assistant message (preserves what user saw)
    // 2. Clear streaming state and set sending=false
    // Premature cleanup would lose the partial content the user already received.
    client.send({ type: 'chat.abort', sessionId: currentSessionId });
  },

  clearError: () => set({ error: null }),

  answerAskUser: (askId, answers) => {
    const { currentSessionId } = get();
    if (!currentSessionId) return;

    // Update the ask_user block in streaming blocks
    const blocks = getStreamingBlocks(currentSessionId);
    const updated = blocks.map(b => {
      if (b.type === 'ask_user' && b.askId === askId) {
        return { ...b, answered: true, answers };
      }
      return b;
    });
    setStreamingBlocks(currentSessionId, updated);
    if (get().currentSessionId === currentSessionId) {
      set({ streamingBlocks: updated });
    }

    // Send answer to backend
    const client = getWsClient();
    if (client) {
      client.send({
        type: 'chat.answer_question',
        sessionId: currentSessionId,
        askId,
        answers,
      });
    }
  },
  toggleThinking: () => set((s) => ({ showThinking: !s.showThinking })),
  toggleAutoConfirm: () => set((s) => ({ autoConfirm: !s.autoConfirm })),
  clearContextWarning: () => set({ contextWarning: null }),
  refresh: () => {
    const { currentSessionId } = get();
    if (currentSessionId) {
      sessionCache.invalidate(currentSessionId);
      cleanupStream(currentSessionId);
      clearStreamingBlocks(currentSessionId);
    }
    set({ messages: [], streamingBlocks: [], error: null, contextWarning: null, contextUsage: null, sending: false });
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
      case 'ask_user':
        // Don't persist ask_user blocks — they are transient UI state
        break;
    }
  }
  return result;
}

/** Remove the last auto-saved "[进行中]" partial message from cached messages */
function removeAutoSavedPartial(cached: Message[]): Message[] {
  if (cached.length > 0 && cached[cached.length - 1].role === 'assistant'
    && cached[cached.length - 1].content.startsWith('[进行中] ')) {
    return cached.slice(0, -1);
  }
  return cached;
}
