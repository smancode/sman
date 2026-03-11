import { EngineRegistry } from "./registry.js";
import type { ExecutionResult, TaskRecord, TaskStatus } from "./types.js";

export class UnifiedOrchestrator {
  private readonly tasks = new Map<string, TaskRecord>();

  constructor(private readonly registry: EngineRegistry) {}

  insertTask(task: TaskRecord): void {
    this.tasks.set(task.id, task);
  }

  status(taskId: string): TaskStatus | undefined {
    return this.tasks.get(taskId)?.status;
  }

  async executeTask(taskId: string, preferredEngine?: string): Promise<ExecutionResult> {
    const task = this.tasks.get(taskId);
    if (!task) {
      throw new Error(`task not found: ${taskId}`);
    }

    task.status = "running";
    try {
      const engine = this.registry.pick(preferredEngine);
      const result = await engine.execute({ taskId: task.id, prompt: task.payload });
      task.status = result.success ? "succeeded" : "failed";
      return result;
    } catch (error) {
      task.status = "failed";
      throw error;
    }
  }
}
