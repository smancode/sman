import { invoke } from "@tauri-apps/api/core";

export type TaskStatus = "pending" | "running" | "succeeded" | "failed";

export interface CreateTaskRequest {
  projectId: string;
  prompt: string;
}

export interface TaskResponse {
  id: string;
  status: TaskStatus;
  createdAt: string;
}

export interface ProjectResponse {
  id: string;
  name: string;
  path: string;
  createdAt: string;
}

// Project commands
export async function listProjects(): Promise<ProjectResponse[]> {
  return invoke<ProjectResponse[]>("list_projects");
}

export async function createProject(
  name: string,
  path: string
): Promise<ProjectResponse> {
  return invoke<ProjectResponse>("create_project", { name, path });
}

export async function deleteProject(projectId: string): Promise<void> {
  return invoke("delete_project", { projectId });
}

// Task commands
export async function createTask(
  request: CreateTaskRequest
): Promise<TaskResponse> {
  return invoke<TaskResponse>("create_task", { ...request });
}

export async function executeTask(taskId: string): Promise<void> {
  return invoke("execute_task", { taskId });
}

export async function getTaskStatus(taskId: string): Promise<TaskStatus> {
  return invoke<TaskStatus>("get_task_status", { taskId });
}

export async function cancelTask(taskId: string): Promise<void> {
  return invoke("cancel_task", { taskId });
}

// Settings commands
export async function getSettings(): Promise<Record<string, unknown>> {
  return invoke("get_settings");
}

export async function updateSettings(
  settings: Record<string, unknown>
): Promise<void> {
  return invoke("update_settings", { settings });
}

// Conversation commands
export async function getConversationHistory(
  projectId: string
): Promise<unknown[]> {
  return invoke("get_conversation_history", { projectId });
}

export async function clearConversationHistory(projectId: string): Promise<void> {
  return invoke("clear_conversation_history", { projectId });
}
