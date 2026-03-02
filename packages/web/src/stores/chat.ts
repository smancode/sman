/**
 * Chat State Store
 */

import { createSignal } from "solid-js"
import type { Message, Part, StreamEvent, Session } from "@smancode/core"

export interface ChatState {
  sessions: Session[]
  currentSession: Session | null
  messages: Message[]
  isStreaming: boolean
  isCompacting: boolean
  error: string | null
}

const [sessions, setSessions] = createSignal<Session[]>([])
const [currentSession, setCurrentSession] = createSignal<Session | null>(null)
const [messages, setMessages] = createSignal<Message[]>([])
const [isStreaming, setIsStreaming] = createSignal(false)
const [isCompacting, setIsCompacting] = createSignal(false)
const [error, setError] = createSignal<string | null>(null)

// Streaming state for current response
const [streamingText, setStreamingText] = createSignal("")
const [streamingPartId, setStreamingPartId] = createSignal<string | null>(null)

// Permission request state
const [pendingPermission, setPendingPermission] = createSignal<{
  id: string
  permission: string
  patterns: string[]
  context?: Record<string, unknown>
} | null>(null)

export const chatStore = {
  // Getters
  sessions,
  currentSession,
  messages,
  isStreaming,
  isCompacting,
  error,
  streamingText,
  streamingPartId,
  pendingPermission,

  // Actions
  setSessions,
  setCurrentSession,
  setMessages,
  addMessage: (message: Message) => {
    setMessages(prev => [...prev, message])
  },
  updateMessage: (messageId: string, updates: Partial<Message>) => {
    setMessages(prev =>
      prev.map(m => m.id === messageId ? { ...m, ...updates } : m)
    )
  },
  setIsStreaming,
  setIsCompacting,
  setError,
  clearError: () => setError(null),

  // Streaming helpers
  appendStreamingText: (text: string) => {
    setStreamingText(prev => prev + text)
  },
  clearStreamingText: () => {
    setStreamingText("")
    setStreamingPartId(null)
  },

  // Permission helpers
  setPendingPermission,
  clearPendingPermission: () => setPendingPermission(null),

  // Handle stream events
  handleStreamEvent: (event: StreamEvent) => {
    switch (event.type) {
      case "text_start":
        setStreamingPartId(event.partId)
        setStreamingText("")
        break
      case "text_delta":
        setStreamingText(prev => prev + event.delta)
        break
      case "text_end":
        // Text complete, will be added to message
        break
      case "tool_call":
        // Tool call started
        break
      case "tool_result":
        // Tool completed
        break
      case "step_start":
        setIsStreaming(true)
        break
      case "step_finish":
        // Step completed
        break
      case "error":
        setError(event.error)
        setIsStreaming(false)
        break
      case "done":
        setIsStreaming(false)
        setStreamingText("")
        setStreamingPartId(null)
        break
    }
  },

  // Reset
  reset: () => {
    setMessages([])
    setIsStreaming(false)
    setIsCompacting(false)
    setError(null)
    setStreamingText("")
    setStreamingPartId(null)
  },
}
