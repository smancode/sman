import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useWsConnection } from '@/stores/ws-connection';
import {
  parseWithFallback,
  RoomSchema,
  AgentSchema,
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

function sendToHub(msg: Record<string, unknown>): Promise<unknown> {
  return new Promise((resolve, reject) => {
    const client = useWsConnection.getState().client;
    if (!client) {
      reject(new Error('WS not connected'));
      return;
    }

    const typeStr = msg.type as string;
    const responseType = typeStr === 'room.create' ? 'room.created'
      : typeStr === 'room.join' ? 'room.joined'
      : typeStr === 'room.leave' ? 'room.left'
      : typeStr === 'room.list' ? 'room.list.update'
      : typeStr === 'room.info' ? 'room.info.update'
      : typeStr === 'agent.list' ? 'agent.list.update'
      : typeStr === 'task.list' ? 'task.list.update'
      : typeStr === 'task.detail' ? 'task.detail.update'
      : typeStr;

    const timer = setTimeout(() => reject(new Error('Timeout')), 10000);
    const handler = (data: unknown) => {
      clearTimeout(timer);
      client.off(responseType, handler);
      resolve(data);
    };
    const errorHandler = (data: unknown) => {
      clearTimeout(timer);
      client.off(responseType, handler);
      reject(new Error((data as { reason?: string })?.reason || 'Error'));
    };

    client.on(responseType, handler);
    client.on('room.error', errorHandler);
    client.on('agent.error', errorHandler);
    client.on('task.error', errorHandler);

    client.send(msg);
  });
}

// ---- Room hooks ----

export function useRooms() {
  return useQuery({
    queryKey: ['hub', 'rooms'] as const,
    queryFn: async () => {
      const raw = await sendToHub({ type: 'room.list' });
      const parsed = parseWithFallback(raw, RoomListUpdateSchema, { rooms: EMPTY_ROOMS }, 'room.list');
      return parsed.rooms ?? EMPTY_ROOMS;
    },
    staleTime: 30_000,
  });
}

export function useRoom(roomId: string | undefined) {
  return useQuery({
    queryKey: ['hub', 'rooms', roomId] as const,
    queryFn: async () => {
      if (!roomId) return null;
      const raw = await sendToHub({ type: 'room.info', roomId });
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
      sendToHub({ type: 'room.create', ...params }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['hub', 'rooms'] }),
  });
}

export function useJoinRoom() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (params: { roomId: string; displayName?: string }) =>
      sendToHub({ type: 'room.join', ...params }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['hub', 'rooms'] }),
  });
}

export function useLeaveRoom() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (params: { roomId: string }) =>
      sendToHub({ type: 'room.leave', ...params }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['hub', 'rooms'] }),
  });
}

// ---- Agent hooks ----

export function useRoomAgents(roomId: string | undefined) {
  return useQuery({
    queryKey: ['hub', 'rooms', roomId, 'agents'] as const,
    queryFn: async () => {
      if (!roomId) return EMPTY_AGENTS;
      const raw = await sendToHub({ type: 'agent.list', roomId });
      const parsed = parseWithFallback(raw, AgentListUpdateSchema, { agents: EMPTY_AGENTS }, 'agent.list');
      return parsed.agents ?? EMPTY_AGENTS;
    },
    enabled: !!roomId,
  });
}

export function useRegisterAgent() {
  const qc = useQueryClient();
  return useMutation<unknown, Error, { roomId: string; workspace: string; capabilities: { skills: string[]; techStack: string[]; projectType: string }; maxConcurrent?: number }>({
    mutationFn: (params) =>
      sendToHub({ type: 'agent.register', ...params }),
    onSuccess: (_data, vars) => qc.invalidateQueries({ queryKey: ['hub', 'rooms', vars.roomId, 'agents'] }),
  });
}

// ---- Task hooks ----

export function useRoomTasks(roomId: string | undefined) {
  return useQuery({
    queryKey: ['hub', 'rooms', roomId, 'tasks'] as const,
    queryFn: async () => {
      if (!roomId) return EMPTY_TASKS;
      const raw = await sendToHub({ type: 'task.list', roomId });
      const parsed = parseWithFallback(raw, TaskListUpdateSchema, { tasks: EMPTY_TASKS }, 'task.list');
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
      const raw = await sendToHub({ type: 'task.detail', taskId });
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
      sendToHub({ type: 'task.create', ...params }),
    onSuccess: (_data, vars) => qc.invalidateQueries({ queryKey: ['hub', 'rooms', vars.roomId, 'tasks'] }),
  });
}

export function useCancelTask() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (params: { taskId: string }) =>
      sendToHub({ type: 'task.cancel', ...params }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['hub'] }),
  });
}
