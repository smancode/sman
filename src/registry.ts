import type { EngineHealth, EngineId, ExecutionRequest, ExecutionResult } from "./types.js";

export interface ExecutionEngine {
  id(): EngineId;
  health(): EngineHealth;
  execute(request: ExecutionRequest): Promise<ExecutionResult>;
}

export class EngineRegistry {
  private claudecodeEngine?: ExecutionEngine;

  register(engine: ExecutionEngine): void {
    if (engine.id() !== "claudecode") {
      throw new Error(`unsupported engine: ${engine.id()}`);
    }
    this.claudecodeEngine = engine;
  }

  pick(preferred?: string): ExecutionEngine {
    if (preferred && preferred !== "claudecode") {
      throw new Error(`unsupported engine: ${preferred}`);
    }
    if (!this.claudecodeEngine) {
      throw new Error("claudecode engine not configured");
    }
    return this.claudecodeEngine;
  }
}
