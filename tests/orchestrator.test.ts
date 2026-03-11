import { describe, expect, it } from "vitest";
import { EngineRegistry } from "../src/registry.js";
import { UnifiedOrchestrator } from "../src/orchestrator.js";
import { ClaudeCodeEngine } from "../src/claudecode-engine.js";

describe("UnifiedOrchestrator", () => {
  it("使用 claudecode 成功执行并更新状态", async () => {
    const registry = new EngineRegistry();
    registry.register(new ClaudeCodeEngine(async (prompt, taskId) => `ok:${taskId}:${prompt}`));
    const orchestrator = new UnifiedOrchestrator(registry);
    orchestrator.insertTask({ id: "t-1", payload: "implement", status: "pending" });

    const result = await orchestrator.executeTask("t-1", "claudecode");

    expect(result.engine).toBe("claudecode");
    expect(result.output).toContain("t-1");
    expect(orchestrator.status("t-1")).toBe("succeeded");
  });

  it("claudecode 执行异常时任务状态置为 failed", async () => {
    const registry = new EngineRegistry();
    registry.register(new ClaudeCodeEngine(async () => {
      throw new Error("boom");
    }));
    const orchestrator = new UnifiedOrchestrator(registry);
    orchestrator.insertTask({ id: "t-2", payload: "debug", status: "pending" });

    await expect(orchestrator.executeTask("t-2", "claudecode")).rejects.toThrow("boom");
    expect(orchestrator.status("t-2")).toBe("failed");
  });

  it("拒绝 claudecode 之外的执行器请求", async () => {
    const registry = new EngineRegistry();
    registry.register(new ClaudeCodeEngine(async () => "ok"));
    const orchestrator = new UnifiedOrchestrator(registry);
    orchestrator.insertTask({ id: "t-3", payload: "task", status: "pending" });

    await expect(orchestrator.executeTask("t-3", "opencode")).rejects.toThrow("unsupported engine");
    expect(orchestrator.status("t-3")).toBe("failed");
  });
});
