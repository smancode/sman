import { z, type ZodType } from 'zod';

// ---------------------------------------------------------------------------
// Defensive Hub Schemas
// ---------------------------------------------------------------------------

export const AgentStatusValues = ['online', 'offline', 'busy'] as const;
export type AgentStatusType = typeof AgentStatusValues[number];

export const TaskStatusValues = ['draft', 'evaluating', 'confirmed', 'rejected', 'dispatched', 'running', 'stopping', 'completed', 'failed', 'cancelled', 'queued'] as const;
export type TaskStatusType = typeof TaskStatusValues[number];

export const AgentStatusSchema = z.string();
export const TaskStatusSchema = z.string();

// ── Object schemas ──

export const AgentCapabilitiesSchema = z.object({
  skills: z.array(z.string()).default([]),
  techStack: z.array(z.string()).default([]),
  projectType: z.string().default(''),
  summary: z.string().default(''),
  description: z.string().default(''),
}).passthrough();

export const RoomSchema = z.object({
  id: z.string(),
  name: z.string(),
  description: z.string().nullable().default(null),
  owner_id: z.string(),
  visibility: z.enum(['public', 'private']).default('private'),
  active: z.number().default(1),
  max_agents: z.number().default(10),
  created_at: z.string(),
  isOwner: z.boolean().optional().default(false),
  isMember: z.boolean().optional().default(false),
  hasPassword: z.boolean().optional().default(false),
  password: z.string().optional(),
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

export const SubtaskSchema = z.object({
  id: z.string(),
  name: z.string(),
  description: z.string().optional(),
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
  acceptance_criteria: z.string().nullable().default(null),
  subtasks: z.string().default('[]'),
  auto_execute: z.number().default(0),
  git_branch: z.string().nullable().default(null),
  version: z.number().default(1),
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

export const EvaluationReportSchema = z.object({
  id: z.string(),
  task_id: z.string(),
  agent_id: z.string(),
  workspace: z.string(),
  claimed_subtasks: z.string().default('[]'),
  approach: z.string().nullable().default(null),
  complexity: z.string().nullable().default(null),
  dependencies: z.string().default('[]'),
  raw_response: z.string().nullable().default(null),
  status: z.string().default('pending'),
  review_comment: z.string().nullable().default(null),
  created_at: z.string(),
  updated_at: z.string(),
}).passthrough();

export const TaskAssignmentSchema = z.object({
  id: z.string(),
  task_id: z.string(),
  agent_id: z.string(),
  workspace: z.string(),
  subtask_ids: z.string().default('[]'),
  instructions: z.string().nullable().default(null),
  report_id: z.string().nullable().default(null),
  status: z.string().default('assigned'),
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
  evaluations: z.array(EvaluationReportSchema).default([]),
  assignments: z.array(TaskAssignmentSchema).default([]),
}).passthrough();

// ── Types ──

export type Room = z.infer<typeof RoomSchema>;
export type RoomMember = z.infer<typeof RoomMemberSchema>;
export type Agent = z.infer<typeof AgentSchema>;
export type Task = z.infer<typeof TaskSchema>;
export type TaskEvent = z.infer<typeof TaskEventSchema>;
export type EvaluationReport = z.infer<typeof EvaluationReportSchema>;
export type TaskAssignment = z.infer<typeof TaskAssignmentSchema>;
export type Subtask = z.infer<typeof SubtaskSchema>;

// ── parseWithFallback ──

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
export const EMPTY_EVALUATIONS: EvaluationReport[] = [];
export const EMPTY_ASSIGNMENTS: TaskAssignment[] = [];
