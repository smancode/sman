import type { Message } from "../types";
import { sanitizeAssistantContent } from "./assistantContent";

type HistoryEntryLike = {
  id: string;
  role: string;
  content: string;
  timestamp?: string;
  createdAt?: string;
};

function normalizeRole(role: string): Message["role"] {
  const normalized = role.toLowerCase();
  if (normalized === "assistant") return "assistant";
  if (normalized === "system") return "system";
  return "user";
}

function normalizeTimestamp(value: string): number {
  const numeric = Number(value);
  if (Number.isFinite(numeric) && numeric > 0) {
    return numeric > 1e12 ? numeric : numeric * 1000;
  }

  const parsed = Date.parse(value);
  if (Number.isFinite(parsed)) {
    return parsed;
  }
  return Date.now();
}

export function mapHistoryEntriesToMessages(
  entries: HistoryEntryLike[],
): Message[] {
  return entries.map((entry) => {
    const role = normalizeRole(entry.role);
    return {
      id: entry.id,
      role,
      content:
        role === "assistant"
          ? sanitizeAssistantContent(entry.content)
          : entry.content,
      timestamp: normalizeTimestamp(entry.timestamp || entry.createdAt || ""),
    };
  });
}
