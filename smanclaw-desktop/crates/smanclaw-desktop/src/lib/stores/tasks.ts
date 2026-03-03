import { writable, derived, get } from 'svelte/store';
import type { Task, TaskStatus, FileChange } from '../types';
import { taskApi } from '../api/tauri';

// State interface
interface TasksState {
  tasks: Task[];
  activeTaskId: string | null;
  isLoading: boolean;
  error: string | null;
}

// Initial state
const initialState: TasksState = {
  tasks: [],
  activeTaskId: null,
  isLoading: false,
  error: null
};

// Main store
function createTasksStore() {
  const { subscribe, set, update } = writable<TasksState>(initialState);

  // Polling interval for active tasks
  let pollingInterval: ReturnType<typeof setInterval> | null = null;

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

  return {
    subscribe,

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

    // Execute a new task
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
      set(initialState);
    },

    // Cleanup
    destroy() {
      stopPolling();
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
