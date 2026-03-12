import { describe, expect, it } from "vitest";
import { mapHistoryEntriesToMessages } from "../lib/chat/historyMapping";

describe("mapHistoryEntriesToMessages", () => {
  it("maps assistant records with createdAt timestamp", () => {
    const messages = mapHistoryEntriesToMessages([
      {
        id: "m1",
        role: "assistant",
        content: "```markdown\n答案\n```",
        createdAt: "2026-03-12T10:00:00.000Z",
      },
    ]);

    expect(messages).toHaveLength(1);
    expect(messages[0].role).toBe("assistant");
    expect(messages[0].content).toContain("答案");
    expect(messages[0].timestamp).toBe(1773309600000);
  });

  it("uses timestamp when provided", () => {
    const messages = mapHistoryEntriesToMessages([
      {
        id: "m2",
        role: "user",
        content: "hello",
        timestamp: "1773309700",
      },
    ]);

    expect(messages[0].timestamp).toBe(1773309700000);
  });

  it("falls back to current time when timestamp is invalid", () => {
    const before = Date.now();
    const messages = mapHistoryEntriesToMessages([
      {
        id: "m3",
        role: "assistant",
        content: "ok",
        timestamp: "not-a-time",
      },
    ]);
    const after = Date.now();

    expect(messages[0].timestamp).toBeGreaterThanOrEqual(before);
    expect(messages[0].timestamp).toBeLessThanOrEqual(after);
  });
});
