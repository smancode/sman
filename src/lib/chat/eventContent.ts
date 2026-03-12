import type {
  ChatEventPayload,
  ChatHistoryMessage,
} from "../../core/openclaw/types";

type UnknownRecord = Record<string, unknown>;

function isRecord(value: unknown): value is UnknownRecord {
  return typeof value === "object" && value !== null;
}

function extractFromRecord(record: UnknownRecord): string {
  const directTextFields = [
    "text",
    "content",
    "message",
    "output",
    "response",
    "result",
  ];
  for (const field of directTextFields) {
    const candidate = record[field];
    const extracted = extractContentText(candidate);
    if (extracted.length > 0) {
      return extracted;
    }
  }

  if (Array.isArray(record.choices)) {
    for (const choice of record.choices) {
      const extracted = extractContentText(choice);
      if (extracted.length > 0) {
        return extracted;
      }
    }
  }

  return "";
}

export function extractContentText(content: unknown): string {
  if (Array.isArray(content)) {
    return content
      .map((item) => extractContentText(item))
      .filter((item) => item.length > 0)
      .join("");
  }

  if (typeof content === "string") {
    return content;
  }

  if (typeof content === "number" || typeof content === "boolean") {
    return String(content);
  }

  if (isRecord(content)) {
    return extractFromRecord(content);
  }

  return "";
}

export function extractChatEventContent(event: ChatEventPayload): string {
  const candidates: unknown[] = [
    event.message?.content,
    (event as unknown as UnknownRecord).content,
    (event as unknown as UnknownRecord).output,
    (event as unknown as UnknownRecord).response,
    (event as unknown as UnknownRecord).result,
  ];

  for (const candidate of candidates) {
    const extracted = extractContentText(candidate).trim();
    if (extracted.length > 0) {
      return extracted;
    }
  }

  return "";
}

export function getLatestAssistantMessageContent(
  history: ChatHistoryMessage[],
): string {
  for (let index = history.length - 1; index >= 0; index -= 1) {
    const entry = history[index];
    if (entry.role !== "assistant") {
      continue;
    }
    const extracted = extractContentText(entry.content).trim();
    if (extracted.length > 0) {
      return extracted;
    }
  }
  return "";
}
