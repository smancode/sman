import { z } from 'zod';

export const AgentStatusSchema = z.enum(['online', 'offline', 'busy']);
export const TaskStatusSchema = z.enum(['queued', 'dispatched', 'running', 'completed', 'failed', 'cancelled']);

export const AgentCapabilitiesSchema = z.object({
  skills: z.array(z.string()).default([]),
  techStack: z.array(z.string()).default([]),
  projectType: z.string().default(''),
});

export const RoomSchema = z.object({
  id: z.string(),
  name: z.string(),
  description: z.string().nullable().default(null),
  owner_id: z.string(),
  active: z.number().default(1),
  max_agents: z.number().default(10),
  created_at: z.string(),
});

export const RoomMemberSchema = z.object({
  room_id: z.string(),
  client_id: z.string(),
  display_name: z.string(),
  role: z.enum(['owner', 'member']),
  joined_at: z.string(),
});

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
});

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
});

export const TaskEventSchema = z.object({
  id: z.number(),
  task_id: z.string(),
  event: z.string(),
  actor: z.string().nullable(),
  metadata: z.string().default('{}'),
  created_at: z.string(),
});

// WS event response schemas
export const RoomListUpdateSchema = z.object({
  type: z.literal('room.list.update'),
  rooms: z.array(RoomSchema).default([]),
});

export const RoomInfoUpdateSchema = z.object({
  type: z.literal('room.info.update'),
  room: RoomSchema,
  members: z.array(RoomMemberSchema).default([]),
  agents: z.array(AgentSchema).default([]),
});

export const AgentListUpdateSchema = z.object({
  type: z.literal('agent.list.update'),
  roomId: z.string(),
  agents: z.array(AgentSchema).default([]),
});

export const TaskListUpdateSchema = z.object({
  type: z.literal('task.list.update'),
  roomId: z.string(),
  tasks: z.array(TaskSchema).default([]),
});

export const TaskDetailUpdateSchema = z.object({
  type: z.literal('task.detail.update'),
  task: TaskSchema,
  events: z.array(TaskEventSchema).default([]),
});

export type Room = z.infer<typeof RoomSchema>;
export type RoomMember = z.infer<typeof RoomMemberSchema>;
export type Agent = z.infer<typeof AgentSchema>;
export type Task = z.infer<typeof TaskSchema>;
export type TaskEvent = z.infer<typeof TaskEventSchema>;
export type TaskStatusType = z.infer<typeof TaskStatusSchema>;
