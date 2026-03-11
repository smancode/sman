import { describe, expect, it } from "vitest";
import { ClaudeCodeEngine, createAcpxClaudeCodeRunner } from "../src/claudecode-engine.js";

describe("ClaudeCodeEngine", () => {
  it("通过 ACPX runner 调用 claudecode", async () => {
    const calls: string[] = [];
    const runner = createAcpxClaudeCodeRunner({
      async invoke(request) {
        calls.push(`${request.agent}:${request.taskId}`);
        return { output: `acpx:${request.prompt}` };
      }
    });
    const engine = new ClaudeCodeEngine(runner);

    const result = await engine.execute({ taskId: "t-acpx", prompt: "fix bug" });

    expect(calls).toEqual(["claudecode:t-acpx"]);
    expect(result.engine).toBe("claudecode");
    expect(result.output).toBe("acpx:fix bug");
    expect(result.success).toBe(true);
  });
});
