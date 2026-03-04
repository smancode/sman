// Project types
export interface Project {
  id: string;
  name: string;
  path: string;
  description?: string;
  createdAt: string;
  lastAccessed: string;
}

// Task types
export type TaskStatus = 'pending' | 'running' | 'completed' | 'error';

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
  action: 'create' | 'modify' | 'delete';
  linesAdded: number;
  linesRemoved: number;
}

export interface Task {
  id: string;
  projectId: string;
  prompt: string;
  status: TaskStatus;
  progress: number;
  steps: TaskStep[];
  fileChanges: FileChange[];
  output?: string;
  error?: string;
  createdAt: number;
  completedAt?: number;
}

// Message types
export type MessageRole = 'user' | 'assistant' | 'system';

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

// Application Settings
export interface AppSettings {
  llm: LlmSettings;
  embedding?: EmbeddingSettings;
  qdrant?: QdrantSettings;
}

// Connection Test Result
export interface ConnectionTestResult {
  success: boolean;
  error?: string;
  latencyMs?: number;
}

// Legacy Settings (for UI preferences)
export interface Settings {
  theme: 'dark' | 'light' | 'system';
  fontSize: 'small' | 'medium' | 'large';
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
  prompt: string;
  projectPath: string;
}

export interface ExecuteTaskResponse {
  taskId: string;
  status: TaskStatus;
}

export interface GetTaskStatusResponse {
  id: string;
  status: TaskStatus;
  progress: number;
  output?: string;
  error?: string;
}

export interface ListProjectsResponse {
  projects: Project[];
}

export interface GetHistoryResponse {
  messages: Message[];
}
