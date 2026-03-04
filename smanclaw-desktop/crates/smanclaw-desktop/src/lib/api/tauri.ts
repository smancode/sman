import { invoke } from '@tauri-apps/api/core';
import { listen, type UnlistenFn } from '@tauri-apps/api/event';
import type {
  ApiResponse,
  ExecuteTaskRequest,
  ExecuteTaskResponse,
  GetTaskStatusResponse,
  ListProjectsResponse,
  GetHistoryResponse,
  Project,
  Task,
  Message,
  Settings,
  AppSettings,
  LlmSettings,
  EmbeddingSettings,
  QdrantSettings,
  ConnectionTestResult,
  OrchestratedTaskResult,
  TaskDagResponse,
  OrchestrationProgress,
  SubTaskStartedEvent,
  SubTaskCompletedEvent,
  TestResultEvent,
  OrchestrationProgressEvent,
  TaskDagEvent
} from '../types';

// Check if running in Tauri environment
function isTauri(): boolean {
  // Tauri 2.x uses __TAURI_INTERNALS__
  if (typeof window !== 'undefined') {
    return '__TAURI__' in window || '__TAURI_INTERNALS__' in window;
  }
  return false;
}

// Safe invoke wrapper
async function safeInvoke<T>(command: string, args?: Record<string, unknown>): Promise<ApiResponse<T>> {
  if (!isTauri()) {
    return {
      success: false,
      error: 'Not running in Tauri environment'
    };
  }

  try {
    const data = await invoke<T>(command, args);
    return { success: true, data };
  } catch (error) {
    return {
      success: false,
      error: error instanceof Error ? error.message : String(error)
    };
  }
}

// Project API
export const projectApi = {
  async list(): Promise<ApiResponse<Project[]>> {
    return safeInvoke<Project[]>('get_projects');
  },

  async get(id: string): Promise<ApiResponse<Project>> {
    return safeInvoke<Project>('get_project', { id });
  },

  async create(name: string, path: string, description?: string): Promise<ApiResponse<Project>> {
    return safeInvoke<Project>('add_project', { path });
  },

  async update(id: string, updates: Partial<Project>): Promise<ApiResponse<Project>> {
    return safeInvoke<Project>('update_project', { id, updates });
  },

  async delete(id: string): Promise<ApiResponse<void>> {
    return safeInvoke<void>('remove_project', { projectId: id });
  },

  async openDialog(): Promise<ApiResponse<string>> {
    console.log('[openDialog] Starting...');
    if (!isTauri()) {
      console.error('[openDialog] Not in Tauri environment');
      return { success: false, error: 'Not running in Tauri environment' };
    }

    try {
      console.log('[openDialog] Calling select_folder command...');
      const result = await invoke<string | null>('select_folder');
      console.log('[openDialog] Dialog result:', result);
      if (result) {
        return { success: true, data: result };
      }
      console.log('[openDialog] No folder selected');
      return { success: false, error: 'No folder selected' };
    } catch (error) {
      console.error('[openDialog] Error:', error);
      return {
        success: false,
        error: error instanceof Error ? error.message : String(error)
      };
    }
  }
};

// Task API
export const taskApi = {
  async execute(request: ExecuteTaskRequest): Promise<ApiResponse<ExecuteTaskResponse>> {
    return safeInvoke<ExecuteTaskResponse>('execute_task', { ...request });
  },

  async getStatus(taskId: string): Promise<ApiResponse<GetTaskStatusResponse>> {
    return safeInvoke<GetTaskStatusResponse>('get_task_status', { taskId });
  },

  async cancel(taskId: string): Promise<ApiResponse<void>> {
    return safeInvoke<void>('cancel_task', { taskId });
  },

  async list(projectId?: string): Promise<ApiResponse<Task[]>> {
    return safeInvoke<Task[]>('list_tasks', { projectId });
  }
};

// History API
export const historyApi = {
  async get(projectId: string): Promise<ApiResponse<GetHistoryResponse>> {
    return safeInvoke<GetHistoryResponse>('get_history', { projectId });
  },

  async addMessage(projectId: string, message: Omit<Message, 'id' | 'timestamp'>): Promise<ApiResponse<Message>> {
    return safeInvoke<Message>('add_message', { projectId, message });
  },

  async clear(projectId: string): Promise<ApiResponse<void>> {
    return safeInvoke<void>('clear_history', { projectId });
  }
};

// App Settings API (for API configuration)
export const appSettingsApi = {
  async get(): Promise<ApiResponse<AppSettings>> {
    return safeInvoke<AppSettings>('get_app_settings');
  },

  async update(settings: AppSettings): Promise<ApiResponse<AppSettings>> {
    return safeInvoke<AppSettings>('update_app_settings', { settings });
  },

  async testLlm(llm: LlmSettings): Promise<ApiResponse<ConnectionTestResult>> {
    return safeInvoke<ConnectionTestResult>('test_llm_connection', { settings: llm });
  },

  async testEmbedding(embedding: EmbeddingSettings): Promise<ApiResponse<ConnectionTestResult>> {
    return safeInvoke<ConnectionTestResult>('test_embedding_connection', { settings: embedding });
  },

  async testQdrant(qdrant: QdrantSettings): Promise<ApiResponse<ConnectionTestResult>> {
    return safeInvoke<ConnectionTestResult>('test_qdrant_connection', { settings: qdrant });
  }
};

// Settings API (for UI preferences)
export const settingsApi = {
  async get(): Promise<ApiResponse<Settings>> {
    return safeInvoke<Settings>('get_settings');
  },

  async update(settings: Partial<Settings>): Promise<ApiResponse<Settings>> {
    return safeInvoke<Settings>('update_settings', { settings });
  }
};

// File system API
export const fsApi = {
  async readTextFile(path: string): Promise<ApiResponse<string>> {
    return safeInvoke<string>('read_text_file', { path });
  },

  async writeTextFile(path: string, content: string): Promise<ApiResponse<void>> {
    return safeInvoke<void>('write_text_file', { path, content });
  },

  async exists(path: string): Promise<ApiResponse<boolean>> {
    return safeInvoke<boolean>('file_exists', { path });
  }
};

// Shell API
export const shellApi = {
  async execute(command: string, cwd?: string): Promise<ApiResponse<{ stdout: string; stderr: string; code: number }>> {
    return safeInvoke('execute_command', { command, cwd });
  }
};

// ============================================================================
// Orchestration API
// ============================================================================

export const orchestrationApi = {
  /// Execute an orchestrated task with automatic decomposition
  async executeTask(projectId: string, input: string): Promise<ApiResponse<OrchestratedTaskResult>> {
    return safeInvoke<OrchestratedTaskResult>('execute_orchestrated_task', { projectId, input });
  },

  /// Get the DAG structure for an orchestrated task
  async getTaskDag(taskId: string): Promise<ApiResponse<TaskDagResponse | null>> {
    return safeInvoke<TaskDagResponse | null>('get_task_dag', { taskId });
  },

  /// Get orchestration status for a task
  async getStatus(taskId: string): Promise<ApiResponse<OrchestrationProgress | null>> {
    return safeInvoke<OrchestrationProgress | null>('get_orchestration_status', { taskId });
  }
};

// ============================================================================
// Event Listeners
// ============================================================================

/// Subscribe to subtask started events
export async function onSubTaskStarted(callback: (event: SubTaskStartedEvent) => void): Promise<UnlistenFn> {
  if (!isTauri()) {
    return () => {};
  }
  return await listen<SubTaskStartedEvent>('subtask-started', (e) => callback(e.payload));
}

/// Subscribe to subtask completed events
export async function onSubTaskCompleted(callback: (event: SubTaskCompletedEvent) => void): Promise<UnlistenFn> {
  if (!isTauri()) {
    return () => {};
  }
  return await listen<SubTaskCompletedEvent>('subtask-completed', (e) => callback(e.payload));
}

/// Subscribe to test result events
export async function onTestResult(callback: (event: TestResultEvent) => void): Promise<UnlistenFn> {
  if (!isTauri()) {
    return () => {};
  }
  return await listen<TestResultEvent>('test-result', (e) => callback(e.payload));
}

/// Subscribe to orchestration progress events
export async function onOrchestrationProgress(callback: (event: OrchestrationProgressEvent) => void): Promise<UnlistenFn> {
  if (!isTauri()) {
    return () => {};
  }
  return await listen<OrchestrationProgressEvent>('orchestration-progress', (e) => callback(e.payload));
}

/// Subscribe to task DAG events
export async function onTaskDag(callback: (event: TaskDagEvent) => void): Promise<UnlistenFn> {
  if (!isTauri()) {
    return () => {};
  }
  return await listen<TaskDagEvent>('task-dag', (e) => callback(e.payload));
}

export { isTauri };
