// Project types
export interface Project {
    id: string;
    name: string;
    path: string;
    description?: string;
    createdAt: string;
    lastAccessed: string;
}

// Skill types
export interface SkillMeta {
    id: string;
    path: string;
    tags: string[];
    learnedFrom: string;
    updatedAt: number;
}

// Task types - Must match smanclaw-types/src/task.rs
export type TaskStatus = "pending" | "running" | "completed" | "failed";

export interface TaskStep {
    id: string;
    name: string;
    status: TaskStatus;
    output?: string;
    error?: string;
    startedAt?: number;
    completedAt?: number;
}

export interface FileChange {
    path: string;
    action: "Created" | "Modified" | "Deleted";
    linesAdded: number;
    linesRemoved: number;
}

// Backend Task structure - matches smanclaw_types::Task
// Fields are in camelCase due to serde(rename_all = "camelCase")
export interface Task {
    id: string;
    projectId: string;
    input: string; // Backend uses "input", not "prompt"
    status: TaskStatus;
    output?: string;
    error?: string;
    createdAt: string; // ISO 8601 datetime string from backend
    updatedAt: string;
    completedAt?: string;
    // Frontend-only fields for UI state
    progress?: number;
    steps?: TaskStep[];
    fileChanges?: FileChange[];
}

// Message types
export type MessageRole = "user" | "assistant" | "system";

export interface Message {
    id: string;
    role: MessageRole;
    content: string;
    timestamp: number;
    taskId?: string;
}

export interface ChatSession {
    id: string;
    projectId: string;
    messages: Message[];
    createdAt: number;
    updatedAt: number;
}

export interface ConversationRecord {
    id: string;
    project_id: string;
    title: string;
    created_at: string;
    updated_at: string;
}

export interface HistoryEntryRecord {
    id: string;
    conversation_id: string;
    role: "User" | "Assistant" | "System" | "user" | "assistant" | "system";
    content: string;
    timestamp: string;
}

export type MessageRoute = "direct" | "orchestrated";

export interface MessageRouteDecision {
    route: MessageRoute;
    reason: string;
    complexity: number;
    confidence: number;
}

// Settings types

// LLM Settings (OpenAI Compatible)
export interface LlmSettings {
    apiUrl: string;
    apiKey: string;
    defaultModel: string;
}

// Embedding Settings
export interface EmbeddingSettings {
    apiUrl: string;
    apiKey: string;
    model: string;
    dimensions: number;
}

// Qdrant Settings
export interface QdrantSettings {
    url: string;
    collection: string;
    apiKey?: string;
}

export interface WebSearchSettings {
    braveApiKey: string;
    tavilyApiKey: string;
    bingApiKey: string;
}

// Application Settings
export interface AppSettings {
    llm: LlmSettings;
    embedding?: EmbeddingSettings;
    qdrant?: QdrantSettings;
    webSearch: WebSearchSettings;
}

// Connection Test Result
export interface ConnectionTestResult {
    success: boolean;
    error?: string;
    latencyMs?: number;
}

// Legacy Settings (for UI preferences)
export interface Settings {
    theme: "dark" | "light" | "system";
    fontSize: "small" | "medium" | "large";
    autoSave: boolean;
    showFileChanges: boolean;
    maxHistoryItems: number;
}

// API response types
export interface ApiResponse<T> {
    success: boolean;
    data?: T;
    error?: string;
}

// Tauri command types
export interface ExecuteTaskRequest {
    projectId: string;
    prompt: string;
}

// Backend returns full Task object, not just id/status
export type ExecuteTaskResponse = Task;

// get_task returns Task or null
export type GetTaskStatusResponse = Task | null;

export interface ListProjectsResponse {
    projects: Project[];
}

export interface GetHistoryResponse {
    messages: Message[];
}

// ============================================================================
// Orchestration Types
// ============================================================================

/// Subtask status
export type SubTaskStatus = "pending" | "running" | "completed" | "failed";

/// Subtask info for DAG display
export interface SubTaskInfo {
    id: string;
    description: string;
    status: SubTaskStatus;
    depends_on: string[];
}

/// Result of execute_orchestrated_task command
export interface OrchestratedTaskResult {
    task_id: string;
    subtask_count: number;
    parallel_groups: string[][];
}

/// DAG response for get_task_dag command
export interface TaskDagResponse {
    task_id: string;
    tasks: SubTaskInfo[];
    parallel_groups: string[][];
    progress: OrchestrationProgress;
}

/// Progress information
export interface OrchestrationProgress {
    completed: number;
    total: number;
    percent: number;
}

// ============================================================================
// Progress Event Types (from smanclaw_types::events)
// ============================================================================

/// File action type
export type FileAction = "created" | "modified" | "deleted";

/// Task result
export interface TaskResult {
    task_id: string;
    success: boolean;
    output: string;
    error?: string;
    files_changed: Array<{
        path: string;
        action: FileAction;
    }>;
}

/// Progress event from backend
export type ProgressEvent =
    | { type: "task_started"; task_id: string }
    | { type: "tool_call"; tool: string; args: unknown }
    | { type: "file_read"; path: string }
    | { type: "file_written"; path: string; action: FileAction }
    | { type: "command_run"; command: string }
    | { type: "progress"; message: string; percent: number }
    | { type: "task_completed"; result: TaskResult }
    | { type: "task_failed"; error: string };

// ============================================================================
// Orchestration Event Types
// ============================================================================

/// Event payload for subtask start
export interface SubTaskStartedEvent {
    task_id: string;
    subtask_id: string;
    description: string;
}

/// Event payload for subtask completion
export interface SubTaskCompletedEvent {
    task_id: string;
    subtask_id: string;
    success: boolean;
    output: string;
    error?: string;
}

/// Event payload for test result
export interface TestResultEvent {
    task_id: string;
    subtask_id: string;
    passed: boolean;
    output: string;
    tests_run?: number;
    tests_passed?: number;
}

/// Event payload for orchestration progress
export interface OrchestrationProgressEvent {
    task_id: string;
    completed: number;
    total: number;
    percent: number;
}

/// Event payload for task DAG structure
export interface TaskDagEvent {
    task_id: string;
    tasks: SubTaskInfo[];
    parallel_groups: string[][];
}
