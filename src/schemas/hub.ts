import { z, type ZodType } from 'zod';

// ---------------------------------------------------------------------------
// Defensive Hub Schemas — borrowed from Multica's lenient schema philosophy
//
// Key design principles:
//   1. String enums stored as z.string(), not z.enum([...]) — a new server-side
//      status value should render as a generic fallback, never crash a safeParse
//   2. Every object schema uses .passthrough() — unknown server-side fields
//      pass through instead of being silently stripped
//   3. Arrays use .default([]) — missing fields degrade to empty lists
//   4. parseWithFallback is the ONLY way to consume API data — validation
//      failure logs a warning and returns the caller-supplied fallback,
//      never throws, never white-screens
// ---------------------------------------------------------------------------

// ── Lenient enum-like types ──

export const AgentStatusValues = ['online', 'offline', 'busy'] as const;
export type AgentStatusType = typeof AgentStatusValues[number];

export const TaskStatusValues = ['queued', 'dispatched', 'running', 'completed', 'failed', 'cancelled'] as const;
export type TaskStatusType = typeof TaskStatusValues[number];

// Schema accepts any string so new server values don't break parsing
export const AgentStatusSchema = z.string();
export const TaskStatusSchema = z.string();

// ── Object schemas (all .passthrough() to let unknown fields through) ──

export const AgentCapabilitiesSchema = z.object({
  skills: z.array(z.string()).default([]),
  techStack: z.array(z.string()).default([]),
  projectType: z.string().default(''),
}).passthrough();

export const RoomSchema = z.object({
  id: z.string(),
  name: z.string(),
  description: z.string().nullable().default(null),
  owner_id: z.string(),
  active: z.number().default(1),
  max_agents: z.number().default(10),
  created_at: z.string(),
}).passthrough();

export const RoomMemberSchema = z.object({
  room_id: z.string(),
  client_id: z.string(),
  display_name: z.string(),
  role: z.string().default('member'),
  joined_at: z.string(),
}).passthrough();

export const AgentSchema = z.object({
  id: z.string(),
  room_id: z.string(),
  client_id: z.string(),
  workspace: z.string(),
  capabilities: z.string().default('{}'),
  status: AgentStatusSchema,
  max_concurrent: z.number().default(2),
  last_heartbeat: z.string(),
  registered_at: z.string(),
}).passthrough();

export const TaskSchema = z.object({
  id: z.string(),
  room_id: z.string(),
  title: z.string(),
  description: z.string().nullable().default(null),
  status: TaskStatusSchema,
  priority: z.number().default(0),
  created_by: z.string(),
  assigned_to: z.string().nullable().default(null),
  context: z.string().default('{}'),
  result: z.string().nullable().default(null),
  error: z.string().nullable().default(null),
  retry_count: z.number().default(0),
  max_retries: z.number().default(3),
  started_at: z.string().nullable().default(null),
  completed_at: z.string().nullable().default(null),
  created_at: z.string(),
  updated_at: z.string(),
}).passthrough();

export const TaskEventSchema = z.object({
  id: z.number(),
  task_id: z.string(),
  event: z.string(),
  actor: z.string().nullable(),
  metadata: z.string().default('{}'),
  created_at: z.string(),
}).passthrough();

// ── WS event response schemas ──

export const RoomListUpdateSchema = z.object({
  type: z.string(),
  rooms: z.array(RoomSchema).default([]),
}).passthrough();

export const RoomInfoUpdateSchema = z.object({
  type: z.string(),
  room: RoomSchema,
  members: z.array(RoomMemberSchema).default([]),
  agents: z.array(AgentSchema).default([]),
}).passthrough();

export const AgentListUpdateSchema = z.object({
  type: z.string(),
  roomId: z.string(),
  agents: z.array(AgentSchema).default([]),
}).passthrough();

export const TaskListUpdateSchema = z.object({
  type: z.string(),
  roomId: z.string(),
  tasks: z.array(TaskSchema).default([]),
}).passthrough();

export const TaskDetailUpdateSchema = z.object({
  type: z.string(),
  task: TaskSchema,
  events: z.array(TaskEventSchema).default([]),
}).passthrough();

// ── Types (strict TS types derived from schemas + known values) ──

export type Room = z.infer<typeof RoomSchema>;
export type RoomMember = z.infer<typeof RoomMemberSchema>;
export type Agent = z.infer<typeof AgentSchema>;
export type Task = z.infer<typeof TaskSchema>;
export type TaskEvent = z.infer<typeof TaskEventSchema>;

// ── parseWithFallback: boundary defense ──

/**
 * Validate data against a Zod schema, returning parsed value on success
 * or caller-supplied fallback on failure. Never throws.
 *
 * This is the boundary defense that turns "API contract drifted" from a
 * white-screen incident into a degraded-but-rendering page.
 *
 * The return type is anchored to T (inferred from fallback), not to the
 * schema's z.infer. Schemas are intentionally lenient — string enums as
 * z.string() so unknown values still parse — so the parsed runtime value
 * can be wider than the strict TS type at the call site. The caller asserts
 * compatibility by typing the fallback.
 */
export function parseWithFallback<T>(
  data: unknown,
  schema: ZodType,
  fallback: T,
  endpoint: string,
): T {
  const result = schema.safeParse(data);
  if (result.success) return result.data as T;

  if (typeof console !== 'undefined') {
    console.warn(`[Hub] API response schema validation failed: ${endpoint}`, {
      endpoint,
      issues: result.error.issues,
    });
  }
  return fallback;
}

// ── Convenience fallbacks ──

export const EMPTY_ROOMS: Room[] = [];
export const EMPTY_TASKS: Task[] = [];
export const EMPTY_AGENTS: Agent[] = [];
export const EMPTY_EVENTS: TaskEvent[] = [];
