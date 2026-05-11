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

interface HubFetchResult {
  data: unknown;
  unreachable: boolean;
}

async function hubFetch(path: string, init?: RequestInit & { throwOnNetworkError?: true }): Promise<HubFetchResult> {
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
    return { data: null, unreachable: true };
  }
  if (!res.ok) {
    if (res.status === 503 || res.status === 502) {
      if (throwOnNetworkError) throw new Error('Hub server unreachable');
      return { data: null, unreachable: true };
    }
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
  return { data: await res.json(), unreachable: false };
}

async function hubMutate(path: string, init?: RequestInit): Promise<unknown> {
  const { data, unreachable } = await hubFetch(path, { ...init, throwOnNetworkError: true });
  if (unreachable) throw new Error('Hub server unreachable');
  return data;
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
      const { data: raw, unreachable } = await hubFetch('/rooms', { method: 'POST', body: JSON.stringify({ search: search || undefined, offset, limit }) });
      if (unreachable) return { rooms: EMPTY_ROOMS, total: 0, unreachable: true } as RoomListResult & { unreachable: boolean };
      const data = raw as { rooms?: unknown[]; total?: number } | null;
      const rooms = Array.isArray(raw) ? raw : data?.rooms ?? [];
      const total = data?.total ?? (Array.isArray(rooms) ? rooms.length : 0);
      const parsed = parseWithFallback({ type: 'room.list.update', rooms }, RoomListUpdateSchema, { rooms: EMPTY_ROOMS }, 'room.list');
      return { rooms: parsed.rooms ?? EMPTY_ROOMS, total, unreachable: false } as RoomListResult & { unreachable: boolean };
    },
    staleTime: 15_000,
  });
}

export function useRoom(roomId: string | undefined) {
  return useQuery({
    queryKey: ['hub', 'rooms', roomId] as const,
    queryFn: async () => {
      if (!roomId) return null;
      const { data: raw, unreachable } = await hubFetch(`/rooms/${roomId}`);
      if (unreachable) return null;
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
      hubMutate('/rooms', { method: 'POST', body: JSON.stringify(params) }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['hub', 'rooms'] }),
  });
}

export function useJoinRoom() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (params: { roomId: string; clientId?: string; displayName?: string }) =>
      hubMutate(`/rooms/${params.roomId}/join`, { method: 'POST', body: JSON.stringify({ clientId: params.clientId || 'desktop', displayName: params.displayName }) }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['hub', 'rooms'] }),
  });
}

export function useLeaveRoom() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (params: { roomId: string; clientId?: string }) =>
      hubMutate(`/rooms/${params.roomId}/leave`, { method: 'POST', body: JSON.stringify({ clientId: params.clientId || 'desktop' }) }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['hub', 'rooms'] }),
  });
}

// ---- Agent hooks ----

export function useRoomAgents(roomId: string | undefined) {
  return useQuery({
    queryKey: ['hub', 'rooms', roomId, 'agents'] as const,
    queryFn: async () => {
      if (!roomId) return EMPTY_AGENTS;
      const { data: raw, unreachable } = await hubFetch(`/rooms/${roomId}/agents`, { method: 'POST', body: JSON.stringify({ roomId }) });
      if (unreachable) return EMPTY_AGENTS;
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
      hubMutate('/agents', { method: 'POST', body: JSON.stringify(params) }),
    onSuccess: (_data, vars) => qc.invalidateQueries({ queryKey: ['hub', 'rooms', vars.roomId, 'agents'] }),
  });
}

// ---- Task hooks ----

export function useRoomTasks(roomId: string | undefined) {
  return useQuery({
    queryKey: ['hub', 'rooms', roomId, 'tasks'] as const,
    queryFn: async () => {
      if (!roomId) return EMPTY_TASKS;
      const { data: raw, unreachable } = await hubFetch(`/tasks?roomId=${roomId}`);
      if (unreachable) return EMPTY_TASKS;
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
      const { data: raw, unreachable } = await hubFetch(`/tasks/${taskId}`);
      if (unreachable) return null;
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
      hubMutate('/tasks', { method: 'POST', body: JSON.stringify(params) }),
    onSuccess: (_data, vars) => qc.invalidateQueries({ queryKey: ['hub', 'rooms', vars.roomId, 'tasks'] }),
  });
}

export function useCancelTask() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (params: { taskId: string }) =>
      hubMutate(`/tasks/${params.taskId}/cancel`, { method: 'POST' }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['hub'] }),
  });
}

export function useStopTask() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (params: { taskId: string }) =>
      hubMutate(`/tasks/${params.taskId}/stop`, { method: 'POST' }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['hub'] }),
  });
}

export function useDissolveRoom() {
  const qc = useQueryClient();
  return useMutation<unknown, Error, { roomId: string }>({
    mutationFn: (params) =>
      hubMutate(`/rooms/${params.roomId}/dissolve`, { method: 'POST', body: JSON.stringify({}) }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['hub', 'rooms'] }),
  });
}

export function useConfirmTask() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (params: { taskId: string }) =>
      hubMutate(`/tasks/${params.taskId}/confirm`, { method: 'POST' }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['hub'] }),
  });
}

export function useRejectTask() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (params: { taskId: string; reason?: string }) =>
      hubMutate(`/tasks/${params.taskId}/reject`, { method: 'POST', body: JSON.stringify(params) }),
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
      hubMutate(`/tasks/${params.taskId}/dispatch`, { method: 'POST', body: JSON.stringify(params) }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['hub'] }),
  });
}

// ---- Evaluation hooks ----

export function useEvaluationReports(taskId: string | undefined) {
  return useQuery({
    queryKey: ['hub', 'tasks', taskId, 'evaluations'] as const,
    queryFn: async () => {
      if (!taskId) return EMPTY_EVALUATIONS;
      const { data: raw, unreachable } = await hubFetch('/evaluations', { method: 'POST', body: JSON.stringify({ taskId }) });
      if (unreachable) return EMPTY_EVALUATIONS;
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
      hubMutate(`/evaluations/${params.reportId}/approve`, { method: 'POST' }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['hub'] }),
  });
}

export function useRejectReport() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (params: { reportId: string; comment?: string }) =>
      hubMutate(`/evaluations/${params.reportId}/reject`, { method: 'POST', body: JSON.stringify(params) }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['hub'] }),
  });
}

export function useStardomDevMode() {
  return useQuery({
    queryKey: ['stardom-dev-mode'],
    queryFn: async () => {
      const { data: raw } = await hubFetch('/stardom-dev-mode', {
        method: 'POST',
        body: JSON.stringify({}),
        throwOnNetworkError: true,
      });
      if (!raw || typeof raw !== 'object') return false;
      return (raw as { enabled?: boolean }).enabled === true;
    },
    staleTime: 0,
    retry: false,
  });
}
