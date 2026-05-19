import { create } from 'zustand';
import { useWsConnection } from './ws-connection';
import type { Group, GroupTask, GroupSubtask } from '@/schemas/group';

interface GroupState {
  groups: Group[];
  tasks: Record<string, GroupTask[]>; // groupId -> tasks
  subtasks: Record<string, GroupSubtask[]>; // groupTaskId -> subtasks
  taskSessionMap: Record<string, string>; // sessionId -> groupId | groupTaskId
  pendingTaskSessionId: string | null;
  loading: boolean;
}

interface GroupActions {
  loadGroups: () => Promise<void>;
  loadTasks: (groupId: string) => Promise<void>;
  loadSubtasks: (taskId: string) => Promise<void>;
  createGroup: (name: string, workspaceIds: string[]) => Promise<void>;
  deleteGroup: (groupId: string) => Promise<void>;
  createTask: (groupId: string, title: string, description?: string, autoDispatch?: boolean) => Promise<void>;
  deleteTask: (taskId: string) => Promise<void>;
  dispatchSubtasks: (taskId: string, subtasks: Array<{ workspace: string; title: string; description?: string }>) => Promise<void>;
  isGroupTaskSession: (sessionId: string) => boolean;
  clearPendingTask: () => void;
}

export const useGroupStore = create<GroupState & GroupActions>((set, get) => {
  let messageHandler: ((msg: unknown) => void) | null = null;
  let registeredClient: import('@/lib/ws-client').WsClient | null = null;

  const ensureListener = () => {
    const client = useWsConnection.getState().client;
    if (!client || messageHandler === null) return;
    if (registeredClient === client) return;

    if (registeredClient && messageHandler) {
      registeredClient.off('message', messageHandler);
    }

    client.on('message', messageHandler);
    registeredClient = client;
  };

  messageHandler = (msg: unknown) => {
    const data = msg as { type: string; groups?: Group[]; tasks?: GroupTask[]; subtasks?: GroupSubtask[]; groupId?: string; taskId?: string; sessionId?: string };

    if (data.type === 'group.list') {
      set({ groups: data.groups || [] });
    }

    if (data.type === 'group-task.list' && data.groupId) {
      const tasks = data.tasks || [];
      set((state) => {
        const newTaskSessionMap = { ...state.taskSessionMap };
        for (const [sid, gid] of Object.entries(newTaskSessionMap)) {
          if (gid === data.groupId) delete newTaskSessionMap[sid];
        }
        for (const t of tasks) {
          newTaskSessionMap[t.id] = t.groupId;
        }
        return {
          tasks: { ...state.tasks, [data.groupId!]: tasks },
          taskSessionMap: newTaskSessionMap,
        };
      });
    }

    if (data.type === 'group-task.deleted' && data.taskId) {
      set((state) => {
        const newTaskSessionMap = { ...state.taskSessionMap };
        delete newTaskSessionMap[data.taskId!];
        const newTasks = { ...state.tasks };
        for (const [gid, tList] of Object.entries(newTasks)) {
          newTasks[gid] = tList.filter(t => t.id !== data.taskId);
        }
        return { taskSessionMap: newTaskSessionMap, tasks: newTasks };
      });
    }

    if (data.type === 'group-task.created' && data.sessionId) {
      set({ pendingTaskSessionId: String(data.sessionId) });
    }

    if (data.type === 'group-subtask.list' && data.taskId) {
      const subtasks = data.subtasks || [];
      const taskSessionPatch: Record<string, string> = {};
      for (const sub of subtasks) {
        taskSessionPatch[sub.sessionId] = sub.groupTaskId;
      }
      set((state) => ({
        subtasks: { ...state.subtasks, [data.taskId!]: subtasks },
        taskSessionMap: { ...state.taskSessionMap, ...taskSessionPatch },
      }));
    }
  };

  useWsConnection.subscribe((state) => {
    if (state.client && state.client !== registeredClient) {
      ensureListener();
    }
  });

  return {
    groups: [],
    tasks: {},
    subtasks: {},
    taskSessionMap: {},
    pendingTaskSessionId: null,
    loading: false,

    loadGroups: async () => {
      ensureListener();
      const client = useWsConnection.getState().client;
      if (!client) return;

      set({ loading: true });
      try {
        client.send({ type: 'group.list' });
      } finally {
        set({ loading: false });
      }
    },

    loadTasks: async (groupId: string) => {
      const client = useWsConnection.getState().client;
      if (!client) return;

      client.send({ type: 'group-task.list', groupId });
    },

    loadSubtasks: async (taskId: string) => {
      const client = useWsConnection.getState().client;
      if (!client) return;

      client.send({ type: 'group-subtask.list', taskId });
    },

    createGroup: async (name, workspaceIds) => {
      const client = useWsConnection.getState().client;
      if (!client) throw new Error('No WebSocket client available');

      const groupId = `group-${Date.now()}-${Math.random().toString(36).slice(2, 9)}`;
      client.send({ type: 'group.create', groupId, name, workspaceIds });
    },

    deleteGroup: async (groupId) => {
      const client = useWsConnection.getState().client;
      if (!client) throw new Error('No WebSocket client available');

      client.send({ type: 'group.delete', groupId });
    },

    createTask: async (groupId, title, description, autoDispatch) => {
      const client = useWsConnection.getState().client;
      if (!client) throw new Error('No WebSocket client available');

      client.send({
        type: 'group-task.create',
        groupId,
        title,
        description,
        autoDispatch: autoDispatch ? 1 : 0,
      });
    },

    deleteTask: async (taskId) => {
      const client = useWsConnection.getState().client;
      if (!client) throw new Error('No WebSocket client available');

      client.send({ type: 'group-task.delete', taskId });
    },

    dispatchSubtasks: async (taskId, subtasks) => {
      const client = useWsConnection.getState().client;
      if (!client) throw new Error('No WebSocket client available');

      client.send({ type: 'group-task.dispatch', taskId, subtasks });
    },

    isGroupTaskSession: (sessionId: string) => {
      return sessionId in get().taskSessionMap;
    },

    clearPendingTask: () => {
      set({ pendingTaskSessionId: null });
    },
  };
});
