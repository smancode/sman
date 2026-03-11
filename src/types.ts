export type EngineId = "claudecode";

export type TaskStatus = "pending" | "running" | "succeeded" | "failed";

export type TaskRecord = {
  id: string;
  payload: string;
  status: TaskStatus;
};

export type ExecutionRequest = {
  taskId: string;
  prompt: string;
};

export type ExecutionResult = {
  engine: EngineId;
  output: string;
  success: boolean;
};

export type EngineHealth = {
  ready: boolean;
  version: string;
};
