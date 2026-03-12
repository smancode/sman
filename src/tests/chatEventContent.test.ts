import { describe, expect, it } from "vitest";
import type {
  ChatEventPayload,
  ChatHistoryMessage,
} from "../core/openclaw/types";
import {
  extractChatEventContent,
  getLatestAssistantMessageContent,
} from "../lib/chat/eventContent";

function buildBaseEvent(): ChatEventPayload {
  return {
    runId: "run-1",
    sessionKey: "main",
    seq: 1,
    state: "final",
  };
}

describe("extractChatEventContent", () => {
  it("extracts string content from message", () => {
    const event: ChatEventPayload = {
      ...buildBaseEvent(),
      message: {
        role: "assistant",
        content: "你好",
      },
    };

    expect(extractChatEventContent(event)).toBe("你好");
  });

  it("extracts text from content array", () => {
    const event: ChatEventPayload = {
      ...buildBaseEvent(),
      message: {
        role: "assistant",
        content: [
          { type: "text", text: "你好" },
          { type: "text", text: "，世界" },
        ],
      },
    };

    expect(extractChatEventContent(event)).toBe("你好，世界");
  });

  it("extracts fallback content from output payload", () => {
    const event = {
      ...buildBaseEvent(),
      state: "completed",
      output: {
        content: [{ type: "text", text: "从 output 提取" }],
      },
    } as ChatEventPayload;

    expect(extractChatEventContent(event)).toBe("从 output 提取");
  });
});

describe("getLatestAssistantMessageContent", () => {
  it("returns latest non-empty assistant message", () => {
    const history: ChatHistoryMessage[] = [
      { role: "user", content: "hi" },
      { role: "assistant", content: "" },
      { role: "assistant", content: "最终答案" },
    ];

    expect(getLatestAssistantMessageContent(history)).toBe("最终答案");
  });

  it("returns empty string when no assistant text exists", () => {
    const history: ChatHistoryMessage[] = [
      { role: "user", content: "only user" },
    ];

    expect(getLatestAssistantMessageContent(history)).toBe("");
  });
});
