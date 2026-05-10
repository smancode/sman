import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  parseWithFallback,
  RoomSchema,
  TaskSchema,
  TaskEventSchema,
  RoomListUpdateSchema,
  RoomInfoUpdateSchema,
  AgentListUpdateSchema,
  TaskListUpdateSchema,
  TaskDetailUpdateSchema,
  EMPTY_ROOMS,
  EMPTY_TASKS,
  EMPTY_AGENTS,
  EMPTY_EVENTS,
} from '@/schemas/hub';
import type { Room, Agent, Task, TaskEvent } from '@/schemas/hub';

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
    throw new Error(`Hub error: ${res.status} ${body}`);
  }
  return res.json();
}

// ---- Room hooks ----

export function useRooms() {
  return useQuery({
    queryKey: ['hub', 'rooms'] as const,
    queryFn: async () => {
      const raw = await hubFetch('/rooms');
      // Server returns array of rooms directly
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
    mutationFn: (params: { name: string; description?: string; maxAgents?: number }) =>
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
      const raw = await hubFetch('/agents');
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

export function useTaskDetail(taskId: string | undefined) {
  return useQuery({
    queryKey: ['hub', 'tasks', taskId] as const,
    queryFn: async () => {
      if (!taskId) return null;
      const raw = await hubFetch(`/tasks/${taskId}`);
      const parsed = parseWithFallback(raw, TaskDetailUpdateSchema, {
        task: null as unknown as Task,
        events: EMPTY_EVENTS,
      }, 'task.detail');
      return parsed;
    },
    enabled: !!taskId,
  });
}

export function useCreateTask() {
  const qc = useQueryClient();
  return useMutation<unknown, Error, { roomId: string; title: string; description?: string; priority?: number; context?: Record<string, unknown> }>({
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
