import type {
  EngineHealth,
  ExecutionRequest,
  ExecutionResult,
} from "./types.js";
import type { ExecutionEngine } from "./registry.js";
import type { AcpxClient } from "./acpx.js";

export type ClaudeCodeRunner = (
  prompt: string,
  taskId: string,
) => Promise<string>;

export class ClaudeCodeEngine implements ExecutionEngine {
  constructor(private readonly runner: ClaudeCodeRunner = defaultRunner) {}

  id() {
    return "claudecode" as const;
  }

  health(): EngineHealth {
    return {
      ready: true,
      version: "0.1.0",
    };
  }

  async execute(request: ExecutionRequest): Promise<ExecutionResult> {
    const output = await this.runner(request.prompt, request.taskId);
    return {
      engine: "claudecode",
      output,
      success: true,
    };
  }
}

export const createAcpxClaudeCodeRunner = (
  client: AcpxClient,
): ClaudeCodeRunner => {
  return async (prompt, taskId) => {
    const response = await client.invoke({
      agent: "claudecode",
      taskId,
      prompt,
    });
    return response.output;
  };
};

const defaultRunner: ClaudeCodeRunner = async (prompt, taskId) => {
  const stub = process.env.SMAN_CLAUDECODE_STUB_OUTPUT;
  if (stub && stub.trim().length > 0) {
    return `${stub}:${taskId}:${prompt}`;
  }
  throw new Error(
    "SMAN_CLAUDECODE_STUB_OUTPUT 未配置，当前仅支持注入 ACPX runner 或设置 stub 输出",
  );
};
