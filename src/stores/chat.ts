// src/stores/chat.ts

import { create } from 'zustand'
import { getGatewayClient } from '@/lib/gateway-client'
import type {
  RawMessage,
  ChatSession,
  ToolStatus,
  ContentBlock,
  AttachedFileMeta,
} from '@/types/chat'

const DEFAULT_CANONICAL_PREFIX = 'agent:main'
const DEFAULT_SESSION = `${DEFAULT_CANONICAL_PREFIX}:main`

/** Normalize a timestamp to milliseconds */
function toMs(ts: number): number {
  return ts < 1e12 ? ts * 1000 : ts
}

/** Extract plain text from message content */
function getMessageText(content: unknown): string {
  if (typeof content === 'string') return content
  if (Array.isArray(content)) {
    return (content as Array<{ type?: string; text?: string }>)
      .filter((b) => b.type === 'text' && b.text)
      .map((b) => b.text!)
      .join('\n')
  }
  return ''
}

function getAgentIdFromSessionKey(sessionKey: string): string {
  if (!sessionKey.startsWith('agent:')) return 'main'
  const parts = sessionKey.split(':')
  return parts[1] || 'main'
}

function parseSessionUpdatedAtMs(value: unknown): number | undefined {
  if (typeof value === 'number' && Number.isFinite(value)) {
    return toMs(value)
  }
  if (typeof value === 'string' && value.trim()) {
    const parsed = Date.parse(value)
    if (Number.isFinite(parsed)) {
      return parsed
    }
  }
  return undefined
}

function isToolResultRole(role: unknown): boolean {
  if (!role) return false
  const normalized = String(role).toLowerCase()
  return normalized === 'toolresult' || normalized === 'tool_result'
}

function isToolOnlyMessage(message: RawMessage | undefined): boolean {
  if (!message) return false
  if (isToolResultRole(message.role)) return true

  const content = message.content
  if (!Array.isArray(content)) return false

  let hasTool = false
  let hasText = false

  for (const block of content as ContentBlock[]) {
    if (block.type === 'tool_use' || block.type === 'tool_result' || block.type === 'toolCall' || block.type === 'toolResult') {
      hasTool = true
      continue
    }
    if (block.type === 'text' && block.text && block.text.trim()) {
      hasText = true
    }
  }

  return hasTool && !hasText
}

function hasNonToolAssistantContent(message: RawMessage | undefined): boolean {
  if (!message) return false
  if (typeof message.content === 'string' && message.content.trim()) return true

  const content = message.content
  if (Array.isArray(content)) {
    for (const block of content as ContentBlock[]) {
      if (block.type === 'text' && block.text && block.text.trim()) return true
      if (block.type === 'thinking' && block.thinking && block.thinking.trim()) return true
      if (block.type === 'image') return true
    }
  }
  return false
}

interface ChatState {
  // Messages
  messages: RawMessage[]
  loading: boolean
  error: string | null

  // Streaming
  sending: boolean
  activeRunId: string | null
  streamingText: string
  streamingMessage: unknown | null
  streamingTools: ToolStatus[]
  pendingFinal: boolean
  lastUserMessageAt: number | null
  pendingToolImages: AttachedFileMeta[]

  // Sessions
  sessions: ChatSession[]
  currentSessionKey: string
  currentAgentId: string
  sessionLabels: Record<string, string>
  sessionLastActivity: Record<string, number>

  // Thinking
  showThinking: boolean
  thinkingLevel: string | null

  // Actions
  loadSessions: () => Promise<void>
  switchSession: (key: string) => void
  newSession: () => void
  deleteSession: (key: string) => Promise<void>
  loadHistory: (quiet?: boolean) => Promise<void>
  sendMessage: (text: string, attachments?: Array<{
    fileName: string
    mimeType: string
    fileSize: number
    stagedPath: string
    preview: string | null
  }>) => Promise<void>
  abortRun: () => Promise<void>
  handleChatEvent: (event: Record<string, unknown>) => void
  toggleThinking: () => void
  refresh: () => Promise<void>
  clearError: () => void
}

export const useChatStore = create<ChatState>((set, get) => ({
  messages: [],
  loading: false,
  error: null,

  sending: false,
  activeRunId: null,
  streamingText: '',
  streamingMessage: null,
  streamingTools: [],
  pendingFinal: false,
  lastUserMessageAt: null,
  pendingToolImages: [],

  sessions: [],
  currentSessionKey: DEFAULT_SESSION,
  currentAgentId: 'main',
  sessionLabels: {},
  sessionLastActivity: {},

  showThinking: true,
  thinkingLevel: null,

  loadSessions: async () => {
    const client = getGatewayClient()
    if (!client) return

    try {
      const data = await client.rpc<Record<string, unknown>>('sessions.list', {})
      if (data) {
        const rawSessions = Array.isArray(data.sessions) ? data.sessions : []
        const sessions: ChatSession[] = rawSessions
          .map((s: Record<string, unknown>) => ({
            key: String(s.key || ''),
            label: s.label ? String(s.label) : undefined,
            displayName: s.displayName ? String(s.displayName) : undefined,
            thinkingLevel: s.thinkingLevel ? String(s.thinkingLevel) : undefined,
            model: s.model ? String(s.model) : undefined,
            updatedAt: parseSessionUpdatedAtMs(s.updatedAt),
          }))
          .filter((s: ChatSession) => s.key)

        const seen = new Set<string>()
        const dedupedSessions = sessions.filter((s) => {
          if (seen.has(s.key)) return false
          seen.add(s.key)
          return true
        })

        let nextSessionKey = get().currentSessionKey || DEFAULT_SESSION
        if (!dedupedSessions.find((s) => s.key === nextSessionKey) && dedupedSessions.length > 0) {
          nextSessionKey = dedupedSessions[0].key
        }

        const sessionsWithCurrent = !dedupedSessions.find((s) => s.key === nextSessionKey)
          ? [...dedupedSessions, { key: nextSessionKey, displayName: nextSessionKey }]
          : dedupedSessions

        const discoveredActivity = Object.fromEntries(
          sessionsWithCurrent
            .filter((session) => typeof session.updatedAt === 'number' && Number.isFinite(session.updatedAt))
            .map((session) => [session.key, session.updatedAt!])
        )

        set((state) => ({
          sessions: sessionsWithCurrent,
          currentSessionKey: nextSessionKey,
          currentAgentId: getAgentIdFromSessionKey(nextSessionKey),
          sessionLastActivity: {
            ...state.sessionLastActivity,
            ...discoveredActivity,
          },
        }))

        get().loadHistory()
      }
    } catch (err) {
      console.warn('Failed to load sessions:', err)
    }
  },

  switchSession: (key: string) => {
    if (key === get().currentSessionKey) return
    set({
      currentSessionKey: key,
      currentAgentId: getAgentIdFromSessionKey(key),
      messages: [],
      streamingText: '',
      streamingMessage: null,
      streamingTools: [],
      activeRunId: null,
      error: null,
      pendingFinal: false,
      lastUserMessageAt: null,
      pendingToolImages: [],
    })
    get().loadHistory()
  },

  newSession: () => {
    const { currentSessionKey, messages } = get()
    const leavingEmpty = !currentSessionKey.endsWith(':main') && messages.length === 0
    const newKey = `agent:main:session-${Date.now()}`
    const newSessionEntry: ChatSession = { key: newKey, displayName: newKey }

    set((s) => ({
      currentSessionKey: newKey,
      currentAgentId: 'main',
      sessions: [
        ...(leavingEmpty ? s.sessions.filter((sess) => sess.key !== currentSessionKey) : s.sessions),
        newSessionEntry,
      ],
      sessionLabels: leavingEmpty
        ? Object.fromEntries(Object.entries(s.sessionLabels).filter(([k]) => k !== currentSessionKey))
        : s.sessionLabels,
      sessionLastActivity: leavingEmpty
        ? Object.fromEntries(Object.entries(s.sessionLastActivity).filter(([k]) => k !== currentSessionKey))
        : s.sessionLastActivity,
      messages: [],
      streamingText: '',
      streamingMessage: null,
      streamingTools: [],
      activeRunId: null,
      error: null,
      pendingFinal: false,
      lastUserMessageAt: null,
      pendingToolImages: [],
    }))
  },

  deleteSession: async (key: string) => {
    const { currentSessionKey, sessions } = get()
    const remaining = sessions.filter((s) => s.key !== key)

    if (currentSessionKey === key) {
      const next = remaining[0]
      set((s) => ({
        sessions: remaining,
        sessionLabels: Object.fromEntries(Object.entries(s.sessionLabels).filter(([k]) => k !== key)),
        sessionLastActivity: Object.fromEntries(Object.entries(s.sessionLastActivity).filter(([k]) => k !== key)),
        messages: [],
        streamingText: '',
        streamingMessage: null,
        streamingTools: [],
        activeRunId: null,
        error: null,
        pendingFinal: false,
        lastUserMessageAt: null,
        pendingToolImages: [],
        currentSessionKey: next?.key ?? DEFAULT_SESSION,
        currentAgentId: getAgentIdFromSessionKey(next?.key ?? DEFAULT_SESSION),
      }))
      if (next) {
        get().loadHistory()
      }
    } else {
      set((s) => ({
        sessions: remaining,
        sessionLabels: Object.fromEntries(Object.entries(s.sessionLabels).filter(([k]) => k !== key)),
        sessionLastActivity: Object.fromEntries(Object.entries(s.sessionLastActivity).filter(([k]) => k !== key)),
      }))
    }
  },

  loadHistory: async (quiet = false) => {
    const client = getGatewayClient()
    if (!client) return

    const { currentSessionKey } = get()
    if (!quiet) set({ loading: true, error: null })

    try {
      const data = await client.rpc<Record<string, unknown>>('chat.history', {
        sessionKey: currentSessionKey,
        limit: 200,
      })

      if (data) {
        const rawMessages = Array.isArray(data.messages) ? (data.messages as RawMessage[]) : []
        const thinkingLevel = data.thinkingLevel ? String(data.thinkingLevel) : null
        const filteredMessages = rawMessages.filter((msg) => !isToolResultRole(msg.role))

        set({ messages: filteredMessages, thinkingLevel, loading: false })

        // Update session label
        const isMainSession = currentSessionKey.endsWith(':main')
        if (!isMainSession) {
          const firstUserMsg = filteredMessages.find((m) => m.role === 'user')
          if (firstUserMsg) {
            const labelText = getMessageText(firstUserMsg.content).trim()
            if (labelText) {
              const truncated = labelText.length > 50 ? `${labelText.slice(0, 50)}...` : labelText
              set((s) => ({
                sessionLabels: { ...s.sessionLabels, [currentSessionKey]: truncated },
              }))
            }
          }
        }

        // Record last activity
        const lastMsg = filteredMessages[filteredMessages.length - 1]
        if (lastMsg?.timestamp) {
          const lastAt = toMs(lastMsg.timestamp)
          set((s) => ({
            sessionLastActivity: { ...s.sessionLastActivity, [currentSessionKey]: lastAt },
          }))
        }
      }
    } catch (err) {
      console.warn('Failed to load chat history:', err)
      set({ messages: [], loading: false })
    }
  },

  sendMessage: async (text, attachments) => {
    const client = getGatewayClient()
    if (!client) return

    const trimmed = text.trim()
    if (!trimmed && (!attachments || attachments.length === 0)) return

    const { currentSessionKey } = get()
    const nowMs = Date.now()

    // Add user message optimistically
    const userMsg: RawMessage = {
      role: 'user',
      content: trimmed || (attachments?.length ? '(file attached)' : ''),
      timestamp: nowMs / 1000,
      id: crypto.randomUUID(),
      _attachedFiles: attachments?.map((a) => ({
        fileName: a.fileName,
        mimeType: a.mimeType,
        fileSize: a.fileSize,
        preview: a.preview,
        filePath: a.stagedPath,
      })),
    }

    set((s) => ({
      messages: [...s.messages, userMsg],
      sending: true,
      error: null,
      streamingText: '',
      streamingMessage: null,
      streamingTools: [],
      pendingFinal: false,
      lastUserMessageAt: nowMs,
    }))

    // Update session label
    const { sessionLabels, messages } = get()
    const isFirstMessage = !messages.slice(0, -1).some((m) => m.role === 'user')
    if (!currentSessionKey.endsWith(':main') && isFirstMessage && !sessionLabels[currentSessionKey] && trimmed) {
      const truncated = trimmed.length > 50 ? `${trimmed.slice(0, 50)}...` : trimmed
      set((s) => ({ sessionLabels: { ...s.sessionLabels, [currentSessionKey]: truncated } }))
    }

    // Mark session as active
    set((s) => ({ sessionLastActivity: { ...s.sessionLastActivity, [currentSessionKey]: nowMs } }))

    try {
      const idempotencyKey = crypto.randomUUID()
      const result = await client.rpc<{ runId?: string }>(
        'chat.send',
        {
          sessionKey: currentSessionKey,
          message: trimmed,
          deliver: false,
          idempotencyKey,
        },
        120000
      )

      if (result?.runId) {
        set({ activeRunId: result.runId })
      }
    } catch (err) {
      set({ error: String(err), sending: false })
    }
  },

  abortRun: async () => {
    const client = getGatewayClient()
    if (!client) return

    const { currentSessionKey } = get()
    set({
      sending: false,
      streamingText: '',
      streamingMessage: null,
      pendingFinal: false,
      lastUserMessageAt: null,
      pendingToolImages: [],
      streamingTools: [],
    })

    try {
      await client.rpc('chat.abort', { sessionKey: currentSessionKey })
    } catch (err) {
      set({ error: String(err) })
    }
  },

  handleChatEvent: (event: Record<string, unknown>) => {
    const eventState = String(event.state || '')
    const runId = String(event.runId || '')
    console.log('[ChatStore] handleChatEvent state:', eventState, 'runId:', runId, 'hasMessage:', !!event.message)

    const eventSessionKey = event.sessionKey != null ? String(event.sessionKey) : null
    const { activeRunId, currentSessionKey } = get()

    // Only process events for current session
    if (eventSessionKey != null && eventSessionKey !== currentSessionKey) return

    // Only process events for active run
    if (activeRunId && runId && runId !== activeRunId) return

    switch (eventState) {
      case 'started': {
        const { sending: currentSending } = get()
        if (!currentSending && runId) {
          set({ sending: true, activeRunId: runId, error: null })
        }
        break
      }
      case 'delta': {
        set((s) => ({
          streamingMessage: event.message ?? s.streamingMessage,
        }))
        break
      }
      case 'final': {
        const finalMsg = event.message as RawMessage | undefined
        if (finalMsg) {
          if (isToolResultRole(finalMsg.role)) {
            // Tool result - keep waiting for final response
            set({ streamingText: '', streamingMessage: null, pendingFinal: true })
            break
          }

          const toolOnly = isToolOnlyMessage(finalMsg)
          const hasOutput = hasNonToolAssistantContent(finalMsg)
          const msgId = finalMsg.id || `run-${runId}`

          // If has actual output content, we're done - clear all pending states
          if (hasOutput && !toolOnly) {
            console.log('[ChatStore] Final with output, clearing all states')
            set((s) => {
              const alreadyExists = s.messages.some((m) => m.id === msgId)
              if (alreadyExists) {
                return {
                  streamingText: '',
                  streamingMessage: null,
                  sending: false,
                  activeRunId: null,
                  pendingFinal: false,
                }
              }
              return {
                messages: [...s.messages, { ...finalMsg, id: msgId }],
                streamingText: '',
                streamingMessage: null,
                sending: false,
                activeRunId: null,
                pendingFinal: false,
              }
            })
            get().loadHistory(true)
          } else {
            console.log('[ChatStore] Final without output, toolOnly:', toolOnly, 'hasOutput:', hasOutput)
            // Tool-only or no output - keep waiting
            set((s) => {
              const alreadyExists = s.messages.some((m) => m.id === msgId)
              if (alreadyExists) {
                return { streamingText: '', streamingMessage: null, pendingFinal: true }
              }
              return {
                messages: [...s.messages, { ...finalMsg, id: msgId }],
                streamingText: '',
                streamingMessage: null,
                pendingFinal: true,
              }
            })
          }
        } else {
          // Final event without message - run is complete, clear all states
          console.log('[ChatStore] Final without message, clearing all states')
          set({
            streamingText: '',
            streamingMessage: null,
            sending: false,
            activeRunId: null,
            pendingFinal: false,
          })
          get().loadHistory()
        }
        break
      }
      case 'error': {
        const errorMsg = String(event.errorMessage || 'An error occurred')
        set({
          error: errorMsg,
          streamingText: '',
          streamingMessage: null,
          streamingTools: [],
          pendingFinal: false,
          pendingToolImages: [],
          sending: false,
          activeRunId: null,
          lastUserMessageAt: null,
        })
        break
      }
      case 'aborted': {
        set({
          sending: false,
          activeRunId: null,
          streamingText: '',
          streamingMessage: null,
          streamingTools: [],
          pendingFinal: false,
          lastUserMessageAt: null,
          pendingToolImages: [],
        })
        break
      }
    }
  },

  toggleThinking: () => set((s) => ({ showThinking: !s.showThinking })),

  refresh: async () => {
    const { loadHistory, loadSessions } = get()
    await Promise.all([loadHistory(), loadSessions()])
  },

  clearError: () => set({ error: null }),
}))
