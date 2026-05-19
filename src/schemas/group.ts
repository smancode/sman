import { z, type ZodType } from 'zod';

// ---------------------------------------------------------------------------
// Group Schemas
// ---------------------------------------------------------------------------

export const GroupStatusValues = ['active', 'archived'] as const;
export type GroupStatusType = typeof GroupStatusValues[number];

export const TaskStatusValues = ['draft', 'active', 'completed', 'failed'] as const;
export type TaskStatusType = typeof TaskStatusValues[number];

// ── Object schemas ──

export const GroupSchema = z.object({
  id: z.string(),
  name: z.string(),
  workspaceIds: z.array(z.string()).default([]),
  status: z.string(),
  createdAt: z.string(),
  updatedAt: z.string(),
}).passthrough();

export const GroupTaskSchema = z.object({
  id: z.string(),
  groupId: z.string(),
  title: z.string(),
  description: z.string().nullable().default(null),
  autoDispatch: z.number().default(0),
  status: z.string(),
  createdAt: z.string(),
  updatedAt: z.string(),
}).passthrough();

export const GroupSubtaskSchema = z.object({
  id: z.string(),
  groupTaskId: z.string(),
  sessionId: z.string(),
  workspace: z.string(),
  title: z.string(),
  description: z.string().nullable().default(null),
  createdAt: z.string(),
  updatedAt: z.string(),
}).passthrough();

// ── WS event response schemas ──

export const GroupListUpdateSchema = z.object({
  type: z.string(),
  groups: z.array(GroupSchema).default([]),
}).passthrough();

// ── Types ──

export type Group = z.infer<typeof GroupSchema>;
export type GroupTask = z.infer<typeof GroupTaskSchema>;
export type GroupSubtask = z.infer<typeof GroupSubtaskSchema>;

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
    console.warn(`[Group] API response schema validation failed: ${endpoint}`, {
      endpoint,
      issues: result.error.issues,
    });
  }
  return fallback;
}

// ── Convenience fallbacks ──

export const EMPTY_GROUPS: Group[] = [];
export const EMPTY_TASKS: GroupTask[] = [];
export const EMPTY_SUBTASKS: GroupSubtask[] = [];
