import { z, type ZodType } from 'zod';

// ---------------------------------------------------------------------------
// Defensive IM Schemas
// ---------------------------------------------------------------------------

export const IMMessageTypeValues = ['text', 'agent_output', 'system'] as const;
export type IMMessageType = typeof IMMessageTypeValues[number];

export const IMMessageStatusValues = ['running', 'completed', 'failed'] as const;
export type IMMessageStatus = typeof IMMessageStatusValues[number];

export const IMRoomTypeValues = ['group', 'dm', 'workspace'] as const;
export type IMRoomType = typeof IMRoomTypeValues[number];

// ── Object schemas ──

export const IMMessageSchema = z.object({
  id: z.string(),
  roomId: z.string(),
  sender: z.string(),
  content: z.string(),
  mentionedAgents: z.array(z.string()).default([]),
  quoteId: z.string().optional(),
  type: z.enum(IMMessageTypeValues).default('text'),
  status: z.enum(IMMessageStatusValues).optional(),
  attachments: z.array(z.any()).optional(),
  sessionId: z.string().optional(),
  timestamp: z.number(),
  seq: z.number().default(0),
}).passthrough();

export const IMRoomSchema = z.object({
  id: z.string(),
  name: z.string(),
  type: z.enum(IMRoomTypeValues).default('group'),
  members: z.array(z.string()),
  lastMessage: z.string().optional(),
  lastMessageTime: z.number().optional(),
}).passthrough();

// ── Types ──

export type IMMessage = z.infer<typeof IMMessageSchema>;
export type IMRoom = z.infer<typeof IMRoomSchema>;

// ── parseWithFallback ──

export function parseIMMessage(data: unknown): IMMessage {
  const result = IMMessageSchema.safeParse(data);
  if (result.success) return result.data;
  return {
    id: '',
    roomId: '',
    sender: '',
    content: '',
    timestamp: 0,
    mentionedAgents: [],
    type: 'text',
    seq: 0,
  };
}

export function parseIMRoom(data: unknown): IMRoom {
  const result = IMRoomSchema.safeParse(data);
  if (result.success) return result.data;
  return {
    id: '',
    name: '',
    members: [],
    type: 'group',
  };
}

// ── Convenience fallbacks ──

export const EMPTY_MESSAGES: IMMessage[] = [];
export const EMPTY_ROOMS: IMRoom[] = [];
