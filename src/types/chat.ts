// src/types/chat.ts

/** Metadata for attached files */
export interface AttachedFileMeta {
  fileName: string
  mimeType: string
  fileSize: number
  preview: string | null
  filePath?: string
}

/** Raw message from Gateway chat.history */
export interface RawMessage {
  role: 'user' | 'assistant' | 'system' | 'toolresult'
  content: unknown // string | ContentBlock[]
  timestamp?: number
  id?: string
  toolCallId?: string
  toolName?: string
  details?: unknown
  isError?: boolean
  _attachedFiles?: AttachedFileMeta[]
}

/** Content block inside a message */
export interface ContentBlock {
  type: 'text' | 'image' | 'thinking' | 'tool_use' | 'tool_result' | 'toolCall' | 'toolResult'
  text?: string
  thinking?: string
  source?: { type: string; media_type?: string; data?: string; url?: string }
  data?: string
  mimeType?: string
  id?: string
  name?: string
  input?: unknown
  arguments?: unknown
  content?: unknown
}

/** Session from session.list */
export interface ChatSession {
  key: string
  label?: string
  systemId?: string
  createdAt?: string
  lastActiveAt?: string
}

export interface ToolStatus {
  id?: string
  toolCallId?: string
  name: string
  status: 'running' | 'completed' | 'error'
  durationMs?: number
  summary?: string
  updatedAt: number
}

