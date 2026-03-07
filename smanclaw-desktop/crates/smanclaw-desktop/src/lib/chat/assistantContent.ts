import type { HistoryEntryRecord } from "../types";

export function stripToolCallBlocks(content: string): string {
    return content
        .replace(/<tool_calls?>[\s\S]*?<\/tool_calls?>/gi, "")
        .replace(/<tool_call>[\s\S]*?<\/tool_call>/gi, "")
        .replace(/<\/?tool_calls?\b[^>]*>/gi, "")
        .trim();
}

export function extractDisplayableAssistantContent(
    content: string,
): string | null {
    const trimmed = content.trim();
    const maybeJson =
        (trimmed.startsWith("{") && trimmed.endsWith("}")) ||
        (trimmed.startsWith("[") && trimmed.endsWith("]"));
    if (maybeJson) {
        try {
            const parsed = JSON.parse(trimmed) as {
                content?: unknown;
                reasoning_content?: unknown;
                message?: unknown;
                tool_calls?: unknown;
            };
            const parsedContent =
                typeof parsed.content === "string" ? parsed.content.trim() : "";
            if (parsedContent.length > 0) {
                return parsedContent;
            }
            const reasoningContent =
                typeof parsed.reasoning_content === "string"
                    ? parsed.reasoning_content.trim()
                    : "";
            if (reasoningContent.length > 0) {
                return reasoningContent;
            }
            const messageContent =
                typeof parsed.message === "string" ? parsed.message.trim() : "";
            if (messageContent.length > 0) {
                return messageContent;
            }
            if (Array.isArray(parsed.tool_calls)) {
                return null;
            }
            return null;
        } catch {}
    }

    const withoutToolCallBlocks = stripToolCallBlocks(content);
    if (withoutToolCallBlocks.length > 0) {
        return withoutToolCallBlocks;
    }
    return null;
}

export function sanitizeAssistantContent(content: string): string {
    const extracted = extractDisplayableAssistantContent(content);
    if (extracted === null) {
        return "处理中：模型正在调用工具，等待最终结果。";
    }
    if (isLikelyInterimAssistantText(extracted)) {
        return "处理中：模型正在整理最终结果。";
    }
    return extracted;
}

export function hasDisplayableAssistantEntry(
    entry: HistoryEntryRecord,
    normalizeRole: (role: HistoryEntryRecord["role"]) => string,
): boolean {
    const extracted = extractDisplayableAssistantContent(entry.content);
    return (
        normalizeRole(entry.role) === "assistant" &&
        extracted !== null &&
        !isLikelyInterimAssistantText(extracted)
    );
}

export function latestDisplayableAssistantEntry(
    entries: HistoryEntryRecord[],
    normalizeRole: (role: HistoryEntryRecord["role"]) => string,
): HistoryEntryRecord | null {
    for (let index = entries.length - 1; index >= 0; index -= 1) {
        const entry = entries[index];
        if (hasDisplayableAssistantEntry(entry, normalizeRole)) {
            return entry;
        }
    }
    return null;
}

export function shouldFinalizeAssistantReply(params: {
    latestAssistantTimestampMs: number;
    completionSignalAtMs: number | null;
    stableAssistantPollCount: number;
    elapsedMs: number;
}): boolean {
    const {
        latestAssistantTimestampMs,
        completionSignalAtMs,
        stableAssistantPollCount,
        elapsedMs,
    } = params;
    if (completionSignalAtMs !== null) {
        return latestAssistantTimestampMs >= completionSignalAtMs - 1500;
    }
    return stableAssistantPollCount >= 3 && elapsedMs >= 8000;
}

function isLikelyInterimAssistantText(content: string): boolean {
    const normalized = content.trim().toLowerCase();
    if (normalized.length === 0) {
        return true;
    }
    const compact = normalized.replace(/\s+/g, " ");
    const startsWithInterimPrefix = [
        "让我",
        "我来",
        "我先",
        "我将",
        "let me",
        "i will",
        "i'll",
    ].some((prefix) => normalized.startsWith(prefix));
    const hasInterimVerb = [
        "查看",
        "检查",
        "分析",
        "搜索",
        "查找",
        "核对",
        "look at",
        "check",
        "analyze",
        "inspect",
    ].some((verb) => normalized.includes(verb));
    const endsAsLeadIn =
        normalized.endsWith(":") ||
        normalized.endsWith("：") ||
        normalized.endsWith("...") ||
        normalized.endsWith("…");
    const hasDeliveryCue = [
        "已完成",
        "完成了",
        "需要添加以下内容",
        "验收",
        "测试",
        "方案",
        "```",
        "1.",
        "2.",
        "###",
        "|",
    ].some((token) => compact.includes(token));
    if (hasDeliveryCue) {
        return false;
    }
    const isShortLeadIn = compact.length <= 120;
    return (
        ((startsWithInterimPrefix && hasInterimVerb) || endsAsLeadIn) &&
        isShortLeadIn
    );
}
