import { UnifiedOrchestrator } from "../orchestrator.js";
import { EngineRegistry } from "../registry.js";
import {
  ClaudeCodeEngine,
  createAcpxClaudeCodeRunner,
} from "../claudecode-engine.js";
import type { AcpxClient } from "../acpx.js";
import type { TaskRecord, ExecutionResult } from "../types.js";

export interface RuntimeGateway {
  execute(prompt: string, taskId: string): Promise<ExecutionResult>;
  getStatus(taskId: string): "pending" | "running" | "succeeded" | "failed" | undefined;
}

export function createRuntimeGateway(
  acpxClient?: AcpxClient
): RuntimeGateway {
  const registry = new EngineRegistry();

  const runner = acpxClient
    ? createAcpxClaudeCodeRunner(acpxClient)
    : undefined;

  const engine = new ClaudeCodeEngine(runner);
  registry.register(engine);

  const orchestrator = new UnifiedOrchestrator(registry);

  return {
    async execute(prompt: string, taskId: string): Promise<ExecutionResult> {
      const task: TaskRecord = {
        id: taskId,
        payload: prompt,
        status: "pending",
      };
      orchestrator.insertTask(task);
      return orchestrator.executeTask(taskId, "claudecode");
    },

    getStatus(taskId: string) {
      return orchestrator.status(taskId);
    },
  };
}
