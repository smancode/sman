import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  parseWithFallback,
  RoomSchema,
  TaskSchema,
  TaskEventSchema,
  EvaluationReportSchema,
  TaskAssignmentSchema,
  RoomListUpdateSchema,
  RoomInfoUpdateSchema,
  AgentListUpdateSchema,
  TaskListUpdateSchema,
  TaskDetailUpdateSchema,
  EMPTY_ROOMS,
  EMPTY_TASKS,
  EMPTY_AGENTS,
  EMPTY_EVENTS,
  EMPTY_EVALUATIONS,
  EMPTY_ASSIGNMENTS,
} from '@/schemas/hub';
import type { Room, Agent, Task, TaskEvent, EvaluationReport, TaskAssignment } from '@/schemas/hub';

async function hubFetch(path: string, init?: RequestInit): Promise<unknown> {
  const token = localStorage.getItem('sman-backend-token') || '';
  const res = await fetch(`/api/hub${path}`, {
    ...init,
    headers: {
      'Authorization': `Bearer ${token}`,
      'Content-Type': 'application/json',
      ...init?.headers,
    },
  });
  if (!res.ok) {
    const body = await res.text().catch(() => '');
    let msg = `Hub error: ${res.status}`;
    try {
      const parsed = JSON.parse(body);
      if (parsed.error) msg = parsed.error;
    } catch {
      if (body) msg += ` ${body}`;
    }
    throw new Error(msg);
  }
  return res.json();
}

// ---- Room hooks ----

export function useRooms() {
  return useQuery({
    queryKey: ['hub', 'rooms'] as const,
    queryFn: async () => {
      const raw = await hubFetch('/rooms', { method: 'POST', body: JSON.stringify({}) });
      const rooms = Array.isArray(raw) ? raw : (raw as { rooms?: unknown[] })?.rooms ?? [];
      const parsed = parseWithFallback({ type: 'room.list.update', rooms }, RoomListUpdateSchema, { rooms: EMPTY_ROOMS }, 'room.list');
      return parsed.rooms ?? EMPTY_ROOMS;
    },
    staleTime: 15_000,
  });
}

export function useRoom(roomId: string | undefined) {
  return useQuery({
    queryKey: ['hub', 'rooms', roomId] as const,
    queryFn: async () => {
      if (!roomId) return null;
      const raw = await hubFetch(`/rooms/${roomId}`);
      const parsed = parseWithFallback(raw, RoomInfoUpdateSchema, {
        room: null as unknown as Room,
        members: [],
        agents: EMPTY_AGENTS,
      }, 'room.info');
      return parsed;
    },
    enabled: !!roomId,
  });
}

export function useCreateRoom() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (params: { name: string; description?: string; maxAgents?: number; visibility?: 'public' | 'private' }) =>
      hubFetch('/rooms', { method: 'POST', body: JSON.stringify(params) }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['hub', 'rooms'] }),
  });
}

export function useJoinRoom() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (params: { roomId: string; clientId?: string; displayName?: string }) =>
      hubFetch(`/rooms/${params.roomId}/join`, { method: 'POST', body: JSON.stringify({ clientId: params.clientId || 'desktop', displayName: params.displayName }) }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['hub', 'rooms'] }),
  });
}

export function useLeaveRoom() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (params: { roomId: string; clientId?: string }) =>
      hubFetch(`/rooms/${params.roomId}/leave`, { method: 'POST', body: JSON.stringify({ clientId: params.clientId || 'desktop' }) }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['hub', 'rooms'] }),
  });
}

// ---- Agent hooks ----

export function useRoomAgents(roomId: string | undefined) {
  return useQuery({
    queryKey: ['hub', 'rooms', roomId, 'agents'] as const,
    queryFn: async () => {
      if (!roomId) return EMPTY_AGENTS;
      const raw = await hubFetch(`/rooms/${roomId}/agents`, { method: 'POST', body: JSON.stringify({ roomId }) });
      const agents = Array.isArray(raw) ? raw : [];
      const parsed = parseWithFallback({ type: 'agent.list.update', roomId, agents }, AgentListUpdateSchema, { agents: EMPTY_AGENTS }, 'agent.list');
      return parsed.agents ?? EMPTY_AGENTS;
    },
    enabled: !!roomId,
  });
}

export function useRegisterAgent() {
  const qc = useQueryClient();
  return useMutation<unknown, Error, { roomId: string; workspace: string; capabilities: { skills: string[]; techStack: string[]; projectType: string }; maxConcurrent?: number }>({
    mutationFn: (params) =>
      hubFetch('/agents', { method: 'POST', body: JSON.stringify(params) }),
    onSuccess: (_data, vars) => qc.invalidateQueries({ queryKey: ['hub', 'rooms', vars.roomId, 'agents'] }),
  });
}

// ---- Task hooks ----

export function useRoomTasks(roomId: string | undefined) {
  return useQuery({
    queryKey: ['hub', 'rooms', roomId, 'tasks'] as const,
    queryFn: async () => {
      if (!roomId) return EMPTY_TASKS;
      const raw = await hubFetch(`/tasks?roomId=${roomId}`);
      const tasks = Array.isArray(raw) ? raw : [];
      const parsed = parseWithFallback({ type: 'task.list.update', roomId, tasks }, TaskListUpdateSchema, { tasks: EMPTY_TASKS }, 'task.list');
      return parsed.tasks ?? EMPTY_TASKS;
    },
    enabled: !!roomId,
  });
}

export interface TaskDetailData {
  task: Task;
  events: TaskEvent[];
  evaluations: EvaluationReport[];
  assignments: TaskAssignment[];
}

export function useTaskDetail(taskId: string | undefined) {
  return useQuery({
    queryKey: ['hub', 'tasks', taskId] as const,
    queryFn: async () => {
      if (!taskId) return null;
      const raw = await hubFetch(`/tasks/${taskId}`);
      const parsed = parseWithFallback(raw, TaskDetailUpdateSchema, {
        task: null as unknown as Task,
        events: EMPTY_EVENTS,
        evaluations: EMPTY_EVALUATIONS,
        assignments: EMPTY_ASSIGNMENTS,
      }, 'task.detail');
      return parsed as TaskDetailData;
    },
    enabled: !!taskId,
  });
}

export interface CreateTaskParams {
  roomId: string;
  title: string;
  description?: string;
  priority?: number;
  context?: Record<string, unknown>;
  acceptanceCriteria?: string;
  subtasks?: { id: string; name: string; description?: string }[];
  autoExecute?: boolean;
  gitBranch?: string;
}

export function useCreateTask() {
  const qc = useQueryClient();
  return useMutation<unknown, Error, CreateTaskParams>({
    mutationFn: (params) =>
      hubFetch('/tasks', { method: 'POST', body: JSON.stringify(params) }),
    onSuccess: (_data, vars) => qc.invalidateQueries({ queryKey: ['hub', 'rooms', vars.roomId, 'tasks'] }),
  });
}

export function useCancelTask() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (params: { taskId: string }) =>
      hubFetch(`/tasks/${params.taskId}/cancel`, { method: 'POST' }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['hub'] }),
  });
}

export function useStopTask() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (params: { taskId: string }) =>
      hubFetch(`/tasks/${params.taskId}/stop`, { method: 'POST' }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['hub'] }),
  });
}

export function useDissolveRoom() {
  const qc = useQueryClient();
  return useMutation<unknown, Error, { roomId: string }>({
    mutationFn: (params) =>
      hubFetch(`/rooms/${params.roomId}/dissolve`, { method: 'POST', body: JSON.stringify({}) }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['hub', 'rooms'] }),
  });
}

export function useConfirmTask() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (params: { taskId: string }) =>
      hubFetch(`/tasks/${params.taskId}/confirm`, { method: 'POST' }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['hub'] }),
  });
}

export function useRejectTask() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (params: { taskId: string; reason?: string }) =>
      hubFetch(`/tasks/${params.taskId}/reject`, { method: 'POST', body: JSON.stringify(params) }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['hub'] }),
  });
}

export function useDispatchTask() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (params: {
      taskId: string;
      assignments: Array<{
        agentId: string;
        workspace: string;
        subtaskIds: string[];
        instructions?: string;
      }>;
    }) =>
      hubFetch(`/tasks/${params.taskId}/dispatch`, { method: 'POST', body: JSON.stringify(params) }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['hub'] }),
  });
}

// ---- Evaluation hooks ----

export function useEvaluationReports(taskId: string | undefined) {
  return useQuery({
    queryKey: ['hub', 'tasks', taskId, 'evaluations'] as const,
    queryFn: async () => {
      if (!taskId) return EMPTY_EVALUATIONS;
      const raw = await hubFetch('/evaluations', { method: 'POST', body: JSON.stringify({ taskId }) });
      const reports = Array.isArray(raw) ? raw : [];
      return reports as EvaluationReport[];
    },
    enabled: !!taskId,
  });
}

export function useApproveReport() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (params: { reportId: string }) =>
      hubFetch(`/evaluations/${params.reportId}/approve`, { method: 'POST' }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['hub'] }),
  });
}

export function useRejectReport() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (params: { reportId: string; comment?: string }) =>
      hubFetch(`/evaluations/${params.reportId}/reject`, { method: 'POST', body: JSON.stringify(params) }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['hub'] }),
  });
}
