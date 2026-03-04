import { writable, derived } from 'svelte/store';
import type {
  Task,
  TaskStatus,
  FileChange,
  SubTaskInfo,
  OrchestrationProgress
} from '../types';
import { taskApi, orchestrationApi } from '../api/tauri';
import {
  onSubTaskStarted,
  onSubTaskCompleted,
  onOrchestrationProgress,
  onTaskDag
} from '../api/tauri';
import type { UnlistenFn } from '@tauri-apps/api/event';

// State interface
interface TasksState {
  tasks: Task[];
  activeTaskId: string | null;
  isLoading: boolean;
  error: string | null;
  // Orchestration state
  subtasks: Map<string, SubTaskInfo[]>; // taskId -> subtasks
  orchestrationProgress: Map<string, OrchestrationProgress>; // taskId -> progress
  parallelGroups: Map<string, string[][]>; // taskId -> parallel groups
}

// Initial state
const initialState: TasksState = {
  tasks: [],
  activeTaskId: null,
  isLoading: false,
  error: null,
  subtasks: new Map(),
  orchestrationProgress: new Map(),
  parallelGroups: new Map()
};

// Main store
function createTasksStore() {
  const { subscribe, set, update } = writable<TasksState>(initialState);

  // Polling interval for active tasks
  let pollingInterval: ReturnType<typeof setInterval> | null = null;

  // Event listeners
  let eventUnlisteners: UnlistenFn[] = [];

  // Start polling for task updates
  function startPolling(taskId: string) {
    if (pollingInterval) {
      clearInterval(pollingInterval);
    }

    pollingInterval = setInterval(async () => {
      const response = await taskApi.getStatus(taskId);

      if (response.success && response.data) {
        const { status, progress, output, error } = response.data;

        update((state) => ({
          ...state,
          tasks: state.tasks.map((t) =>
            t.id === taskId
              ? {
                  ...t,
                  status: status as TaskStatus,
                  progress,
                  output: output || t.output,
                  error: error || t.error
                }
              : t
          )
        }));

        // Stop polling if task is no longer running
        if (status !== 'running') {
          stopPolling();
        }
      }
    }, 1000);
  }

  // Stop polling
  function stopPolling() {
    if (pollingInterval) {
      clearInterval(pollingInterval);
      pollingInterval = null;
    }
  }

  // Setup event listeners for orchestration
  async function setupEventListeners() {
    try {
      // Listen for subtask started events
      const unlistenStarted = await onSubTaskStarted((event) => {
        update((state) => {
          const subtasks = new Map(state.subtasks);
          const existing = subtasks.get(event.task_id) || [];
          const updated = existing.map((st) =>
            st.id === event.subtask_id
              ? { ...st, status: 'running' as const }
              : st
          );
          // Add if not exists
          if (!existing.find((st) => st.id === event.subtask_id)) {
            updated.push({
              id: event.subtask_id,
              description: event.description,
              status: 'running',
              depends_on: []
            });
          }
          subtasks.set(event.task_id, updated);
          return { ...state, subtasks };
        });
      });
      eventUnlisteners.push(unlistenStarted);

      // Listen for subtask completed events
      const unlistenCompleted = await onSubTaskCompleted((event) => {
        update((state) => {
          const subtasks = new Map(state.subtasks);
          const existing = subtasks.get(event.task_id) || [];
          const updated = existing.map((st) =>
            st.id === event.subtask_id
              ? {
                  ...st,
                  status: event.success ? 'completed' as const : 'failed' as const
                }
              : st
          );
          subtasks.set(event.task_id, updated);
          return { ...state, subtasks };
        });
      });
      eventUnlisteners.push(unlistenCompleted);

      // Listen for orchestration progress events
      const unlistenProgress = await onOrchestrationProgress((event) => {
        update((state) => {
          const orchestrationProgress = new Map(state.orchestrationProgress);
          orchestrationProgress.set(event.task_id, {
            completed: event.completed,
            total: event.total,
            percent: event.percent
          });
          return { ...state, orchestrationProgress };
        });
      });
      eventUnlisteners.push(unlistenProgress);

      // Listen for task DAG events
      const unlistenDag = await onTaskDag((event) => {
        update((state) => {
          const subtasks = new Map(state.subtasks);
          const parallelGroups = new Map(state.parallelGroups);
          subtasks.set(event.task_id, event.tasks);
          parallelGroups.set(event.task_id, event.parallel_groups);
          return { ...state, subtasks, parallelGroups };
        });
      });
      eventUnlisteners.push(unlistenDag);
    } catch (e) {
      console.error('Failed to setup event listeners:', e);
    }
  }

  // Cleanup event listeners
  function cleanupEventListeners() {
    eventUnlisteners.forEach((unlisten) => unlisten());
    eventUnlisteners = [];
  }

  return {
    subscribe,

    // Initialize store (call once at app startup)
    async initialize() {
      await setupEventListeners();
    },

    // Load tasks for a project
    async loadTasks(projectId?: string) {
      update((state) => ({ ...state, isLoading: true, error: null }));

      const response = await taskApi.list(projectId);

      if (response.success && response.data) {
        update((state) => ({
          ...state,
          tasks: response.data!,
          isLoading: false
        }));
      } else {
        update((state) => ({
          ...state,
          error: response.error || 'Failed to load tasks',
          isLoading: false
        }));
      }
    },

    // Execute a new task (simple, non-orchestrated)
    async executeTask(prompt: string, projectPath: string) {
      update((state) => ({ ...state, isLoading: true, error: null }));

      const response = await taskApi.execute({ prompt, projectPath });

      if (response.success && response.data) {
        const { taskId, status } = response.data;

        // Create a temporary task object
        const newTask: Task = {
          id: taskId,
          projectId: '',
          prompt,
          status: status as TaskStatus,
          progress: 0,
          steps: [],
          fileChanges: [],
          createdAt: Date.now()
        };

        update((state) => ({
          ...state,
          tasks: [newTask, ...state.tasks],
          activeTaskId: taskId,
          isLoading: false
        }));

        // Start polling for updates
        startPolling(taskId);

        return taskId;
      }

      update((state) => ({
        ...state,
        error: response.error || 'Failed to execute task',
        isLoading: false
      }));
      return null;
    },

    // Execute an orchestrated task with automatic decomposition
    async executeOrchestratedTask(projectId: string, prompt: string) {
      update((state) => ({ ...state, isLoading: true, error: null }));

      const response = await orchestrationApi.executeTask(projectId, prompt);

      if (response.success && response.data) {
        const { task_id, subtask_count, parallel_groups } = response.data;

        // Create a temporary task object
        const newTask: Task = {
          id: task_id,
          projectId: projectId,
          prompt,
          status: 'running',
          progress: 0,
          steps: [],
          fileChanges: [],
          createdAt: Date.now()
        };

        update((state) => {
          // Initialize orchestration state
          const orchestrationProgress = new Map(state.orchestrationProgress);
          const parallelGroupsMap = new Map(state.parallelGroups);

          orchestrationProgress.set(task_id, {
            completed: 0,
            total: subtask_count,
            percent: 0
          });
          parallelGroupsMap.set(task_id, parallel_groups);

          return {
            ...state,
            tasks: [newTask, ...state.tasks],
            activeTaskId: task_id,
            isLoading: false,
            orchestrationProgress,
            parallelGroups: parallelGroupsMap
          };
        });

        return task_id;
      }

      update((state) => ({
        ...state,
        error: response.error || 'Failed to execute orchestrated task',
        isLoading: false
      }));
      return null;
    },

    // Cancel a task
    async cancelTask(taskId: string) {
      const response = await taskApi.cancel(taskId);

      if (response.success) {
        stopPolling();

        update((state) => ({
          ...state,
          tasks: state.tasks.map((t) =>
            t.id === taskId ? { ...t, status: 'error' as TaskStatus, error: 'Task cancelled' } : t
          ),
          activeTaskId: state.activeTaskId === taskId ? null : state.activeTaskId
        }));
        return true;
      }

      update((state) => ({
        ...state,
        error: response.error || 'Failed to cancel task'
      }));
      return false;
    },

    // Set active task
    setActiveTask(taskId: string | null) {
      update((state) => ({ ...state, activeTaskId: taskId }));
    },

    // Update task file changes
    updateFileChanges(taskId: string, fileChanges: FileChange[]) {
      update((state) => ({
        ...state,
        tasks: state.tasks.map((t) => (t.id === taskId ? { ...t, fileChanges } : t))
      }));
    },

    // Clear error
    clearError() {
      update((state) => ({ ...state, error: null }));
    },

    // Reset store
    reset() {
      stopPolling();
      cleanupEventListeners();
      set(initialState);
    },

    // Cleanup
    destroy() {
      stopPolling();
      cleanupEventListeners();
    }
  };
}

export const tasksStore = createTasksStore();

// Derived stores
export const activeTask = derived(tasksStore, ($state) =>
  $state.tasks.find((t) => t.id === $state.activeTaskId)
);

export const runningTasks = derived(tasksStore, ($state) =>
  $state.tasks.filter((t) => t.status === 'running')
);

export const completedTasks = derived(tasksStore, ($state) =>
  $state.tasks.filter((t) => t.status === 'completed')
);

export const failedTasks = derived(tasksStore, ($state) =>
  $state.tasks.filter((t) => t.status === 'error')
);

// Orchestration derived stores
export const activeSubtasks = derived(tasksStore, ($state) => {
  if (!$state.activeTaskId) return [];
  return $state.subtasks.get($state.activeTaskId) || [];
});

export const activeOrchestrationProgress = derived(tasksStore, ($state) => {
  if (!$state.activeTaskId) return null;
  return $state.orchestrationProgress.get($state.activeTaskId) || null;
});

export const activeParallelGroups = derived(tasksStore, ($state) => {
  if (!$state.activeTaskId) return [];
  return $state.parallelGroups.get($state.activeTaskId) || [];
});
