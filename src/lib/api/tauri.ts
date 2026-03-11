import { invoke } from "@tauri-apps/api/core";
import { listen, type UnlistenFn } from "@tauri-apps/api/event";
import type {
  ApiResponse,
  ExecuteTaskRequest,
  ExecuteTaskResponse,
  GetTaskStatusResponse,
  Project,
  SkillMeta,
  Task,
  Message,
  ConversationRecord,
  HistoryEntryRecord,
  MessageRouteDecision,
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
  TaskDagEvent,
} from "../types";

// Check if running in Tauri environment
function isTauri(): boolean {
  if (typeof window !== "undefined") {
    return "__TAURI__" in window || "__TAURI_INTERNALS__" in window;
  }
  return false;
}

// Safe invoke wrapper
async function safeInvoke<T>(
  command: string,
  args?: Record<string, unknown>,
  timeoutMs?: number,
): Promise<ApiResponse<T>> {
  try {
    const invokePromise = invoke<T>(command, args);
    const data =
      typeof timeoutMs === "number" && timeoutMs > 0
        ? await Promise.race<T>([
            invokePromise,
            new Promise<T>((_, reject) => {
              setTimeout(() => {
                reject(new Error(`请求超时（${command}，${timeoutMs}ms）`));
              }, timeoutMs);
            }),
          ])
        : await invokePromise;
    return { success: true, data };
  } catch (error) {
    return {
      success: false,
      error: error instanceof Error ? error.message : String(error),
    };
  }
}

// Project API
export const projectApi = {
  async list(): Promise<ApiResponse<Project[]>> {
    return safeInvoke<Project[]>("get_projects");
  },

  async create(path: string): Promise<ApiResponse<Project>> {
    return safeInvoke<Project>("add_project", { path });
  },

  async delete(projectId: string): Promise<ApiResponse<void>> {
    return safeInvoke<void>("remove_project", { projectId });
  },

  async getSkills(projectId: string): Promise<ApiResponse<SkillMeta[]>> {
    return safeInvoke<SkillMeta[]>("get_project_skills", {
      projectId,
    });
  },

  async getGlobalSkills(): Promise<ApiResponse<SkillMeta[]>> {
    return safeInvoke<SkillMeta[]>("get_global_skills");
  },

  async testSendMessage(
    projectId: string,
    message: string,
  ): Promise<ApiResponse<string>> {
    return safeInvoke<string>("test_send_message", {
      projectId,
      content: message,
    });
  },

  async openDialog(): Promise<ApiResponse<string>> {
    if (!isTauri()) {
      return {
        success: false,
        error: "Not running in Tauri environment",
      };
    }

    try {
      const result = await invoke<string | null>("select_folder");
      if (result) {
        return { success: true, data: result };
      }
      return { success: false, error: "No folder selected" };
    } catch (error) {
      return {
        success: false,
        error: error instanceof Error ? error.message : String(error),
      };
    }
  },
};

// Task API
export const taskApi = {
  async execute(
    request: ExecuteTaskRequest,
  ): Promise<ApiResponse<ExecuteTaskResponse>> {
    return safeInvoke<ExecuteTaskResponse>("execute_task", {
      projectId: request.projectId,
      input: request.prompt,
    });
  },

  async getStatus(taskId: string): Promise<ApiResponse<GetTaskStatusResponse>> {
    return safeInvoke<GetTaskStatusResponse>("get_task", {
      taskId,
    });
  },

  async cancel(taskId: string): Promise<ApiResponse<void>> {
    return safeInvoke<void>("cancel_task", { taskId });
  },

  async list(projectId?: string): Promise<ApiResponse<Task[]>> {
    return safeInvoke<Task[]>("list_tasks", { projectId });
  },
};

export const conversationApi = {
  async list(projectId: string): Promise<ApiResponse<ConversationRecord[]>> {
    return safeInvoke<ConversationRecord[]>(
      "list_conversations",
      {
        projectId,
      },
      10000,
    );
  },

  async create(
    projectId: string,
    title: string,
  ): Promise<ApiResponse<ConversationRecord>> {
    return safeInvoke<ConversationRecord>(
      "create_conversation",
      {
        projectId,
        title,
      },
      12000,
    );
  },

  async getMessages(
    conversationId: string,
  ): Promise<ApiResponse<HistoryEntryRecord[]>> {
    return safeInvoke<HistoryEntryRecord[]>(
      "get_conversation_messages",
      {
        conversationId,
      },
      10000,
    );
  },

  async sendMessage(
    conversationId: string,
    content: string,
  ): Promise<ApiResponse<HistoryEntryRecord>> {
    return safeInvoke<HistoryEntryRecord>(
      "send_message",
      {
        conversationId,
        content,
      },
      15000,
    );
  },

  async decideRoute(
    projectId: string,
    content: string,
  ): Promise<ApiResponse<MessageRouteDecision>> {
    return safeInvoke<MessageRouteDecision>(
      "decide_message_route",
      {
        projectId,
        content,
      },
      20000,
    );
  },
};

// App Settings API
export const appSettingsApi = {
  async get(): Promise<ApiResponse<AppSettings>> {
    return safeInvoke<AppSettings>("get_app_settings");
  },

  async update(settings: AppSettings): Promise<ApiResponse<AppSettings>> {
    return safeInvoke<AppSettings>("update_app_settings", { settings });
  },

  async testLlm(llm: LlmSettings): Promise<ApiResponse<ConnectionTestResult>> {
    return safeInvoke<ConnectionTestResult>("test_llm_connection", {
      settings: llm,
    });
  },

  async testLlmDirect(): Promise<ApiResponse<ConnectionTestResult>> {
    return safeInvoke<ConnectionTestResult>("test_llm_direct_chat");
  },

  async testEmbedding(
    embedding: EmbeddingSettings,
  ): Promise<ApiResponse<ConnectionTestResult>> {
    return safeInvoke<ConnectionTestResult>("test_embedding_connection", {
      settings: embedding,
    });
  },

  async testQdrant(
    qdrant: QdrantSettings,
  ): Promise<ApiResponse<ConnectionTestResult>> {
    return safeInvoke<ConnectionTestResult>("test_qdrant_connection", {
      settings: qdrant,
    });
  },
};

// Orchestration API
export const orchestrationApi = {
  async executeTask(
    projectId: string,
    input: string,
    conversationId?: string,
  ): Promise<ApiResponse<OrchestratedTaskResult>> {
    return safeInvoke<OrchestratedTaskResult>("execute_orchestrated_task", {
      projectId,
      input,
      conversationId,
    });
  },

  async getTaskDag(
    taskId: string,
  ): Promise<ApiResponse<TaskDagResponse | null>> {
    return safeInvoke<TaskDagResponse | null>("get_task_dag", {
      taskId,
    });
  },

  async getStatus(
    taskId: string,
  ): Promise<ApiResponse<OrchestrationProgress | null>> {
    return safeInvoke<OrchestrationProgress | null>(
      "get_orchestration_status",
      { taskId },
    );
  },
};

// Event Listeners
export async function onSubTaskStarted(
  callback: (event: SubTaskStartedEvent) => void,
): Promise<UnlistenFn> {
  if (!isTauri()) return () => {};
  return await listen<SubTaskStartedEvent>("subtask-started", (e) =>
    callback(e.payload),
  );
}

export async function onSubTaskCompleted(
  callback: (event: SubTaskCompletedEvent) => void,
): Promise<UnlistenFn> {
  if (!isTauri()) return () => {};
  return await listen<SubTaskCompletedEvent>("subtask-completed", (e) =>
    callback(e.payload),
  );
}

export async function onTestResult(
  callback: (event: TestResultEvent) => void,
): Promise<UnlistenFn> {
  if (!isTauri()) return () => {};
  return await listen<TestResultEvent>("test-result", (e) =>
    callback(e.payload),
  );
}

export async function onOrchestrationProgress(
  callback: (event: OrchestrationProgressEvent) => void,
): Promise<UnlistenFn> {
  if (!isTauri()) return () => {};
  return await listen<OrchestrationProgressEvent>(
    "orchestration-progress",
    (e) => callback(e.payload),
  );
}

export async function onTaskDag(
  callback: (event: TaskDagEvent) => void,
): Promise<UnlistenFn> {
  if (!isTauri()) return () => {};
  return await listen<TaskDagEvent>("task-dag", (e) => callback(e.payload));
}

export { isTauri };

// Alias for backward compatibility
export { appSettingsApi as settingsApi };

// OpenClaw Sidecar API
export const openclawApi = {
  async start(): Promise<ApiResponse<string>> {
    return safeInvoke<string>("start_openclaw_server");
  },

  async stop(): Promise<ApiResponse<string>> {
    return safeInvoke<string>("stop_openclaw_server");
  },

  async checkHealth(): Promise<ApiResponse<boolean>> {
    return safeInvoke<boolean>("check_openclaw_health");
  },

  async isRunning(): Promise<ApiResponse<boolean>> {
    return safeInvoke<boolean>("is_server_running");
  },

  getPort(): number {
    // Port is hardcoded on both sides, return constant
    return 18790;
  },

  getLocalPath(): Promise<ApiResponse<string>> {
    return safeInvoke<string>("get_sman_local_path");
  },
};
