import { describe, expect, it } from "vitest";
import {
    extractDisplayableAssistantContent,
    hasDisplayableAssistantEntry,
    latestDisplayableAssistantEntry,
    sanitizeAssistantContent,
    shouldFinalizeAssistantReply,
    shouldStopWaitingAfterCompletion,
    stripToolCallBlocks,
} from "../chat/assistantContent";
import type { HistoryEntryRecord } from "../types";

function normalizeRole(role: HistoryEntryRecord["role"]): string {
    const normalized = role.toLowerCase();
    if (normalized === "assistant") {
        return "assistant";
    }
    if (normalized === "system") {
        return "system";
    }
    return "user";
}

describe("assistantContent", () => {
    it("移除工具调用标签并保留文本", () => {
        const value = stripToolCallBlocks(
            '<tool_calls>[{"name":"read"}]</tool_calls>\n最终答案',
        );
        expect(value).toBe("最终答案");
    });

    it("从 JSON content 中提取可展示文本", () => {
        const value = extractDisplayableAssistantContent(
            JSON.stringify({ content: "  已完成修复  " }),
        );
        expect(value).toBe("已完成修复");
    });

    it("当仅有工具调用时返回等待文案", () => {
        const value = sanitizeAssistantContent(
            JSON.stringify({
                tool_calls: [{ name: "read_file" }],
                content: "",
            }),
        );
        expect(value).toBe("处理中：模型正在调用工具，等待最终结果。");
    });

    it("中间过程文本应降级为处理中提示", () => {
        const value =
            sanitizeAssistantContent("让我查看还款计划引擎的配置和实现：");
        expect(value).toBe("处理中：模型正在整理最终结果。");
    });

    it("长文本交付内容不应被误判为中间态", () => {
        const value = sanitizeAssistantContent(
            "我来帮你增加气球贷还款方式。需要添加以下内容：\n1. 枚举定义\n2. 还款计划脚本\n3. 验收测试",
        );
        expect(value).toContain("气球贷还款方式");
    });

    it("仅将可展示 assistant 记录视为完成", () => {
        const waitingEntry: HistoryEntryRecord = {
            id: "1",
            conversation_id: "c1",
            role: "Assistant",
            content: JSON.stringify({
                tool_calls: [{ name: "grep" }],
                content: "",
            }),
            timestamp: new Date().toISOString(),
        };
        const readyEntry: HistoryEntryRecord = {
            ...waitingEntry,
            id: "2",
            content: "最终回复文本",
        };

        expect(hasDisplayableAssistantEntry(waitingEntry, normalizeRole)).toBe(
            false,
        );
        expect(hasDisplayableAssistantEntry(readyEntry, normalizeRole)).toBe(
            true,
        );
    });

    it("过程性 assistant 文本不应触发完成判定", () => {
        const entry: HistoryEntryRecord = {
            id: "a-prog",
            conversation_id: "c1",
            role: "Assistant",
            content: "让我查看还款计划引擎的配置和实现：",
            timestamp: new Date().toISOString(),
        };
        expect(hasDisplayableAssistantEntry(entry, normalizeRole)).toBe(false);
    });

    it("结构化交付文本应触发完成判定", () => {
        const entry: HistoryEntryRecord = {
            id: "a-final",
            conversation_id: "c1",
            role: "Assistant",
            content:
                "我来帮你增加气球贷还款方式。需要添加以下内容：\n1. 枚举\n2. 计算逻辑\n3. 测试",
            timestamp: new Date().toISOString(),
        };
        expect(hasDisplayableAssistantEntry(entry, normalizeRole)).toBe(true);
    });

    it("返回最后一条可展示 assistant 记录", () => {
        const entries: HistoryEntryRecord[] = [
            {
                id: "u1",
                conversation_id: "c1",
                role: "User",
                content: "帮我增加一个还款方式：气球贷",
                timestamp: "2026-03-07T10:00:00.000Z",
            },
            {
                id: "a1",
                conversation_id: "c1",
                role: "Assistant",
                content: JSON.stringify({ tool_calls: [{ name: "search" }] }),
                timestamp: "2026-03-07T10:00:01.000Z",
            },
            {
                id: "a2",
                conversation_id: "c1",
                role: "Assistant",
                content: "已完成气球贷还款方式接入",
                timestamp: "2026-03-07T10:00:10.000Z",
            },
        ];
        const latest = latestDisplayableAssistantEntry(entries, normalizeRole);
        expect(latest?.id).toBe("a2");
    });

    it("完成信号后仅接受时间匹配的最终消息", () => {
        const completionAt = Date.parse("2026-03-07T10:00:10.000Z");
        const earlyAssistantAt = Date.parse("2026-03-07T10:00:05.000Z");
        const finalAssistantAt = Date.parse("2026-03-07T10:00:10.200Z");
        expect(
            shouldFinalizeAssistantReply({
                latestAssistantTimestampMs: earlyAssistantAt,
                completionSignalAtMs: completionAt,
                stableAssistantPollCount: 1,
                elapsedMs: 3000,
            }),
        ).toBe(false);
        expect(
            shouldFinalizeAssistantReply({
                latestAssistantTimestampMs: finalAssistantAt,
                completionSignalAtMs: completionAt,
                stableAssistantPollCount: 1,
                elapsedMs: 3000,
            }),
        ).toBe(true);
    });

    it("完成信号后长时间无最终消息应停止等待", () => {
        expect(
            shouldStopWaitingAfterCompletion({
                completionSignalAtMs: Date.parse("2026-03-07T10:00:10.000Z"),
                pollsWithoutAssistantAfterCompletion: 5,
                elapsedMs: 16000,
            }),
        ).toBe(false);
        expect(
            shouldStopWaitingAfterCompletion({
                completionSignalAtMs: Date.parse("2026-03-07T10:00:10.000Z"),
                pollsWithoutAssistantAfterCompletion: 6,
                elapsedMs: 15000,
            }),
        ).toBe(true);
        expect(
            shouldStopWaitingAfterCompletion({
                completionSignalAtMs: null,
                pollsWithoutAssistantAfterCompletion: 99,
                elapsedMs: 60000,
            }),
        ).toBe(false);
    });
});
