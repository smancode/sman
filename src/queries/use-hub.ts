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

async function hubFetch(path: string, init?: RequestInit & { throwOnNetworkError?: true }): Promise<unknown> {
  const token = localStorage.getItem('sman-backend-token') || '';
  const { throwOnNetworkError, ...restInit } = init ?? {};
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 3000);
  const res = await fetch(`/api/hub${path}`, {
    ...restInit,
    signal: controller.signal,
    headers: {
      'Authorization': `Bearer ${token}`,
      'Content-Type': 'application/json',
      ...restInit?.headers,
    },
  }).catch(() => null);
  clearTimeout(timeout);

  if (!res) {
    if (throwOnNetworkError) throw new Error('Hub server unreachable');
    return null;
  }
  if (!res.ok) {
    if (!throwOnNetworkError && (res.status === 503 || res.status === 502)) return null;
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

export interface RoomListResult {
  rooms: Room[];
  total: number;
}

export function useRooms(search?: string, offset = 0, limit = 10) {
  return useQuery({
    queryKey: ['hub', 'rooms', search, offset, limit] as const,
    queryFn: async () => {
      const raw = await hubFetch('/rooms', { method: 'POST', body: JSON.stringify({ search: search || undefined, offset, limit }) });
      if (!raw) return { rooms: EMPTY_ROOMS, total: 0 } as RoomListResult;
      const data = raw as { rooms?: unknown[]; total?: number } | null;
      const rooms = Array.isArray(raw) ? raw : data?.rooms ?? [];
      const total = data?.total ?? (Array.isArray(rooms) ? rooms.length : 0);
      const parsed = parseWithFallback({ type: 'room.list.update', rooms }, RoomListUpdateSchema, { rooms: EMPTY_ROOMS }, 'room.list');
      return { rooms: parsed.rooms ?? EMPTY_ROOMS, total } as RoomListResult;
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
      hubFetch('/rooms', { method: 'POST', body: JSON.stringify(params), throwOnNetworkError: true }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['hub', 'rooms'] }),
  });
}

export function useJoinRoom() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (params: { roomId: string; clientId?: string; displayName?: string }) =>
      hubFetch(`/rooms/${params.roomId}/join`, { method: 'POST', body: JSON.stringify({ clientId: params.clientId || 'desktop', displayName: params.displayName }), throwOnNetworkError: true }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['hub', 'rooms'] }),
  });
}

export function useLeaveRoom() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (params: { roomId: string; clientId?: string }) =>
      hubFetch(`/rooms/${params.roomId}/leave`, { method: 'POST', body: JSON.stringify({ clientId: params.clientId || 'desktop' }), throwOnNetworkError: true }),
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
      if (!raw) return EMPTY_AGENTS;
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
      hubFetch('/agents', { method: 'POST', body: JSON.stringify(params), throwOnNetworkError: true }),
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
      if (!raw) return EMPTY_TASKS;
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
      if (!raw) return null;
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
      hubFetch('/tasks', { method: 'POST', body: JSON.stringify(params), throwOnNetworkError: true }),
    onSuccess: (_data, vars) => qc.invalidateQueries({ queryKey: ['hub', 'rooms', vars.roomId, 'tasks'] }),
  });
}

export function useCancelTask() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (params: { taskId: string }) =>
      hubFetch(`/tasks/${params.taskId}/cancel`, { method: 'POST', throwOnNetworkError: true }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['hub'] }),
  });
}

export function useStopTask() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (params: { taskId: string }) =>
      hubFetch(`/tasks/${params.taskId}/stop`, { method: 'POST', throwOnNetworkError: true }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['hub'] }),
  });
}

export function useDissolveRoom() {
  const qc = useQueryClient();
  return useMutation<unknown, Error, { roomId: string }>({
    mutationFn: (params) =>
      hubFetch(`/rooms/${params.roomId}/dissolve`, { method: 'POST', body: JSON.stringify({}), throwOnNetworkError: true }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['hub', 'rooms'] }),
  });
}

export function useConfirmTask() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (params: { taskId: string }) =>
      hubFetch(`/tasks/${params.taskId}/confirm`, { method: 'POST', throwOnNetworkError: true }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['hub'] }),
  });
}

export function useRejectTask() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (params: { taskId: string; reason?: string }) =>
      hubFetch(`/tasks/${params.taskId}/reject`, { method: 'POST', body: JSON.stringify(params), throwOnNetworkError: true }),
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
      hubFetch(`/tasks/${params.taskId}/dispatch`, { method: 'POST', body: JSON.stringify(params), throwOnNetworkError: true }),
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
      if (!raw) return EMPTY_EVALUATIONS;
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
      hubFetch(`/evaluations/${params.reportId}/approve`, { method: 'POST', throwOnNetworkError: true }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['hub'] }),
  });
}

export function useRejectReport() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (params: { reportId: string; comment?: string }) =>
      hubFetch(`/evaluations/${params.reportId}/reject`, { method: 'POST', body: JSON.stringify(params), throwOnNetworkError: true }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['hub'] }),
  });
}

// ---- Health check ----

export function useHubReachable(hubUrl?: string) {
  return useQuery({
    queryKey: ['hub', 'health'] as const,
    queryFn: async () => {
      if (!hubUrl) return false;
      try {
        const controller = new AbortController();
        const timeout = setTimeout(() => controller.abort(), 3000);
        const res = await fetch(`/api/hub/rooms`, {
          method: 'POST',
          signal: controller.signal,
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({}),
        });
        clearTimeout(timeout);
        return res.ok;
      } catch {
        return false;
      }
    },
    staleTime: 30_000,
    refetchInterval: 60_000,
    enabled: !!hubUrl,
  });
}
