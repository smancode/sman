import { writable, derived } from "svelte/store";
import type {
    Task,
    TaskStatus,
    FileChange,
    SubTaskInfo,
    OrchestrationProgress,
    SubTaskStartedEvent,
    SubTaskCompletedEvent,
    OrchestrationProgressEvent,
    TaskDagEvent,
    TestResultEvent,
} from "../types";
import {
    taskApi,
    orchestrationApi,
    onSubTaskStarted,
    onSubTaskCompleted,
    onOrchestrationProgress,
    onTaskDag,
    onTestResult,
} from "../api/tauri";
import { listen } from "@tauri-apps/api/event";
import type { UnlistenFn } from "@tauri-apps/api/event";

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
    parallelGroups: new Map(),
};

// Main store
function createTasksStore() {
    const { subscribe, set, update } = writable<TasksState>(initialState);

    // Poll task until completed (standalone function)
    async function pollTaskUntilComplete(taskId: string) {
        console.log("pollTaskUntilComplete started for:", taskId);
        while (true) {
            await new Promise((r) => setTimeout(r, 1000));

            const statusRes = await taskApi.getStatus(taskId);
            console.log("pollTaskUntilComplete getStatus result:", statusRes);
            if (statusRes.success && statusRes.data) {
                const task = statusRes.data;
                console.log("pollTaskUntilComplete task:", task);

                update((state) => ({
                    ...state,
                    tasks: state.tasks.map((t) =>
                        t.id === taskId ? { ...t, ...task } : t,
                    ),
                    // Keep activeTaskId until task is truly done (completed/failed)
                    activeTaskId:
                        task.status === "completed" || task.status === "failed"
                            ? null
                            : state.activeTaskId,
                }));

                // Stop polling only when task reaches terminal state
                if (task.status === "completed" || task.status === "failed") {
                    console.log(
                        "pollTaskUntilComplete completed with status:",
                        task.status,
                    );
                    break;
                }
            }
        }
    }

    // Polling interval for active tasks
    let pollingInterval: ReturnType<typeof setInterval> | null = null;

    // Event listeners
    let eventUnlisteners: UnlistenFn[] = [];

    // Start polling for task updates
    function startPolling(taskId: string) {
        console.log("startPolling called for task:", taskId);
        if (pollingInterval) {
            clearInterval(pollingInterval);
        }

        pollingInterval = setInterval(async () => {
            console.log("Polling task:", taskId);
            const response = await taskApi.getStatus(taskId);

            console.log("Polling response:", response);

            if (response.success && response.data) {
                // Backend returns Task object (or null if not found)
                const taskData = response.data;
                console.log("Polling taskData:", taskData);

                // If task not found or not the right task, skip
                if (!taskData || taskData.id !== taskId) {
                    return;
                }

                update((state) => {
                    const newTasks = state.tasks.map((t) =>
                        t.id === taskId
                            ? {
                                  ...t,
                                  ...taskData, // Merge all fields from backend
                                  // Keep frontend-only fields
                                  progress: t.progress,
                                  steps: t.steps,
                                  fileChanges: t.fileChanges,
                              }
                            : t,
                    );
                    // If task is completed/failed/cancelled, clear active task
                    const newActiveTaskId =
                        taskData.status !== "running" &&
                        state.activeTaskId === taskId
                            ? null
                            : state.activeTaskId;
                    return {
                        ...state,
                        tasks: newTasks,
                        activeTaskId: newActiveTaskId,
                    };
                });

                // Stop polling if task is no longer running
                if (taskData.status !== "running") {
                    console.log("Task completed, stopping polling");
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
        console.log("setupEventListeners called");
        try {
            // Listen for task status events
            const unlistenTaskStatus = await listen<{
                task_id: string;
                status: string;
                message?: string;
            }>("task-status", (event) => {
                console.log("Received task-status event:", event.payload);
                const { task_id, status, message } = event.payload;

                update((state) => {
                    const hasTask = state.tasks.some((t) => t.id === task_id);
                    const tasks = hasTask
                        ? state.tasks.map((t) =>
                              t.id === task_id
                                  ? {
                                        ...t,
                                        status: status as TaskStatus,
                                        output: message || t.output,
                                        error:
                                            status === "failed"
                                                ? message
                                                : t.error,
                                    }
                                  : t,
                          )
                        : [
                              {
                                  id: task_id,
                                  projectId: "",
                                  input: "",
                                  status: status as TaskStatus,
                                  output: message,
                                  error:
                                      status === "failed"
                                          ? (message ?? "Task failed")
                                          : undefined,
                                  createdAt: new Date().toISOString(),
                                  updatedAt: new Date().toISOString(),
                                  progress: 0,
                                  steps: [],
                                  fileChanges: [],
                              },
                              ...state.tasks,
                          ];

                    const activeTaskId =
                        status === "running"
                            ? (state.activeTaskId ?? task_id)
                            : state.activeTaskId === task_id &&
                                (status === "completed" ||
                                    status === "failed" ||
                                    status === "cancelled")
                              ? null
                              : state.activeTaskId;

                    return { ...state, tasks, activeTaskId };
                });

                if (status !== "running") {
                    stopPolling();
                }
            });
            eventUnlisteners.push(unlistenTaskStatus);

            // Listen for file change events
            const unlistenFileChange = await listen<{
                path: string;
                action: string;
            }>("file-change", (event) => {
                const { path, action } = event.payload;

                update((state) => {
                    if (!state.activeTaskId) return state;

                    const tasks = state.tasks.map((t) => {
                        if (t.id !== state.activeTaskId) return t;

                        // Map action from backend to frontend type (capitalize first letter)
                        const mappedAction = (action.charAt(0).toUpperCase() +
                            action.slice(1)) as
                            | "Created"
                            | "Modified"
                            | "Deleted";

                        const fileChanges = [
                            ...(t.fileChanges || []),
                            {
                                path,
                                action: mappedAction,
                                linesAdded: 0,
                                linesRemoved: 0,
                            },
                        ];
                        return { ...t, fileChanges };
                    });

                    return { ...state, tasks };
                });
            });
            eventUnlisteners.push(unlistenFileChange);

            const unlistenTaskDag = await onTaskDag((event: TaskDagEvent) => {
                update((state) => {
                    const subtasks = new Map(state.subtasks);
                    const parallelGroups = new Map(state.parallelGroups);
                    subtasks.set(event.task_id, event.tasks);
                    parallelGroups.set(event.task_id, event.parallel_groups);
                    const activeTaskId = state.activeTaskId ?? event.task_id;
                    return { ...state, subtasks, parallelGroups, activeTaskId };
                });
            });
            eventUnlisteners.push(unlistenTaskDag);

            const unlistenSubTaskStarted = await onSubTaskStarted(
                (event: SubTaskStartedEvent) => {
                    update((state) => {
                        const subtasks = new Map(state.subtasks);
                        const current = subtasks.get(event.task_id) || [];
                        const exists = current.some(
                            (task) => task.id === event.subtask_id,
                        );
                        const next = exists
                            ? current.map((task) =>
                                  task.id === event.subtask_id
                                      ? {
                                            ...task,
                                            status: "running" as const,
                                        }
                                      : task,
                              )
                            : [
                                  ...current,
                                  {
                                      id: event.subtask_id,
                                      description: event.description,
                                      status: "running" as const,
                                      depends_on: [],
                                  },
                              ];
                        subtasks.set(event.task_id, next);
                        return { ...state, subtasks };
                    });
                },
            );
            eventUnlisteners.push(unlistenSubTaskStarted);

            const unlistenSubTaskCompleted = await onSubTaskCompleted(
                (event: SubTaskCompletedEvent) => {
                    update((state) => {
                        const subtasks = new Map(state.subtasks);
                        const current = subtasks.get(event.task_id) || [];
                        const next = current.map((task) =>
                            task.id === event.subtask_id
                                ? {
                                      ...task,
                                      status: event.success
                                          ? ("completed" as const)
                                          : ("failed" as const),
                                  }
                                : task,
                        );
                        if (
                            !next.some((task) => task.id === event.subtask_id)
                        ) {
                            next.push({
                                id: event.subtask_id,
                                description: event.subtask_id,
                                status: event.success
                                    ? ("completed" as const)
                                    : ("failed" as const),
                                depends_on: [],
                            });
                        }
                        subtasks.set(event.task_id, next);
                        return { ...state, subtasks };
                    });
                },
            );
            eventUnlisteners.push(unlistenSubTaskCompleted);

            const unlistenOrchestrationProgress = await onOrchestrationProgress(
                (event: OrchestrationProgressEvent) => {
                    update((state) => {
                        const orchestrationProgress = new Map(
                            state.orchestrationProgress,
                        );
                        orchestrationProgress.set(event.task_id, {
                            completed: event.completed,
                            total: event.total,
                            percent: event.percent,
                        });
                        const tasks = state.tasks.map((task) =>
                            task.id === event.task_id
                                ? {
                                      ...task,
                                      progress: event.percent,
                                      updatedAt: new Date().toISOString(),
                                  }
                                : task,
                        );
                        const activeTaskId =
                            state.activeTaskId ?? event.task_id;
                        return {
                            ...state,
                            orchestrationProgress,
                            tasks,
                            activeTaskId,
                        };
                    });
                },
            );
            eventUnlisteners.push(unlistenOrchestrationProgress);

            const unlistenTestResult = await onTestResult(
                (event: TestResultEvent) => {
                    update((state) => ({
                        ...state,
                        tasks: state.tasks.map((task) =>
                            task.id === event.task_id
                                ? {
                                      ...task,
                                      output: event.output,
                                      error: event.passed
                                          ? task.error
                                          : event.output,
                                  }
                                : task,
                        ),
                    }));
                },
            );
            eventUnlisteners.push(unlistenTestResult);
        } catch (e) {
            // Event listeners may fail during development hot reload
            // This is not critical - log but don't crash
            console.warn("Event listeners not available:", e);
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
                    isLoading: false,
                }));
            } else {
                update((state) => ({
                    ...state,
                    error: response.error || "Failed to load tasks",
                    isLoading: false,
                }));
            }
        },

        // Execute a new task (simple, non-orchestrated)
        async executeTask(projectId: string, prompt: string) {
            console.log("executeTask called:", projectId, prompt);
            update((state) => ({ ...state, isLoading: true, error: null }));

            const response = await taskApi.execute({ projectId, prompt });
            console.log("executeTask response:", response);

            if (response.success && response.data) {
                // Backend returns full Task object
                const taskData = response.data;
                console.log("executeTask taskData:", taskData);

                // Create a task object with frontend-only fields
                const newTask: Task = {
                    ...taskData, // Spread all backend fields
                    // Add frontend-only UI state fields
                    progress: 0,
                    steps: [],
                    fileChanges: [],
                };

                update((state) => ({
                    ...state,
                    tasks: [newTask, ...state.tasks],
                    activeTaskId: taskData.id,
                    isLoading: false,
                }));

                console.log(
                    "executeTask calling pollTaskUntilComplete:",
                    taskData.id,
                );
                // Start polling in background (don't await)
                pollTaskUntilComplete(taskData.id);

                return taskData.id;
            }

            update((state) => ({
                ...state,
                error: response.error || "Failed to execute task",
                isLoading: false,
            }));
            return null;
        },

        // Execute an orchestrated task with automatic decomposition
        async executeOrchestratedTask(projectId: string, prompt: string) {
            update((state) => ({ ...state, isLoading: true, error: null }));

            const response = await orchestrationApi.executeTask(
                projectId,
                prompt,
            );

            if (response.success && response.data) {
                const { task_id, subtask_count, parallel_groups } =
                    response.data;

                // Create a task object that matches backend Task structure
                const newTask: Task = {
                    id: task_id,
                    projectId: projectId,
                    input: prompt, // Backend uses "input", not "prompt"
                    status: "running",
                    createdAt: new Date().toISOString(),
                    updatedAt: new Date().toISOString(),
                    // Frontend-only UI state fields
                    progress: 0,
                    steps: [],
                    fileChanges: [],
                };

                update((state) => {
                    // Initialize orchestration state
                    const orchestrationProgress = new Map(
                        state.orchestrationProgress,
                    );
                    const parallelGroupsMap = new Map(state.parallelGroups);

                    orchestrationProgress.set(task_id, {
                        completed: 0,
                        total: subtask_count,
                        percent: 0,
                    });
                    parallelGroupsMap.set(task_id, parallel_groups);

                    return {
                        ...state,
                        tasks: [newTask, ...state.tasks],
                        activeTaskId: task_id,
                        isLoading: false,
                        orchestrationProgress,
                        parallelGroups: parallelGroupsMap,
                    };
                });

                pollTaskUntilComplete(task_id);
                return task_id;
            }

            update((state) => ({
                ...state,
                error: response.error || "Failed to execute orchestrated task",
                isLoading: false,
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
                        t.id === taskId
                            ? {
                                  ...t,
                                  status: "failed" as TaskStatus,
                                  error: "Task cancelled",
                              }
                            : t,
                    ),
                    activeTaskId:
                        state.activeTaskId === taskId
                            ? null
                            : state.activeTaskId,
                }));
                return true;
            }

            update((state) => ({
                ...state,
                error: response.error || "Failed to cancel task",
            }));
            return false;
        },

        // Set active task
        setActiveTask(taskId: string | null) {
            update((state) => ({ ...state, activeTaskId: taskId }));
        },

        // Update task status from event
        updateTaskStatus(taskId: string, status: string, message?: string) {
            console.log("updateTaskStatus called:", taskId, status, message);
            update((state) => {
                const tasks = state.tasks.map((t) =>
                    t.id === taskId
                        ? {
                              ...t,
                              status: status as TaskStatus,
                              output: message || t.output,
                              error: status === "failed" ? message : t.error,
                          }
                        : t,
                );
                const activeTaskId =
                    status !== "running" && state.activeTaskId === taskId
                        ? null
                        : state.activeTaskId;
                return { ...state, tasks, activeTaskId };
            });
        },

        // Update task file changes
        updateFileChanges(taskId: string, fileChanges: FileChange[]) {
            update((state) => ({
                ...state,
                tasks: state.tasks.map((t) =>
                    t.id === taskId ? { ...t, fileChanges } : t,
                ),
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
        },
    };
}

export const tasksStore = createTasksStore();

// Derived stores
export const tasks = derived(tasksStore, ($state) => $state.tasks);

export const activeTask = derived(tasksStore, ($state) =>
    $state.tasks.find((t) => t.id === $state.activeTaskId),
);

export const runningTasks = derived(tasksStore, ($state) =>
    $state.tasks.filter((t) => t.status === "running"),
);

export const completedTasks = derived(tasksStore, ($state) =>
    $state.tasks.filter((t) => t.status === "completed"),
);

export const failedTasks = derived(tasksStore, ($state) =>
    $state.tasks.filter((t) => t.status === "failed"),
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
