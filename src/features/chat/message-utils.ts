/**
 * Message content extraction helpers
 * Ported from OpenClaw's message-extract.ts to handle the various
 * message content formats returned by the Gateway.
 */
import type { RawMessage, ContentBlock } from '@/types/chat';
import type { Message } from '@/stores/chat';

/**
 * Build displayable content from a plain text string and optional content blocks.
 * If blocks contain a text block, use blocks directly; otherwise prepend a text block.
 */
export function buildContent(text: string, blocks?: unknown[]): unknown {
  if (!blocks || blocks.length === 0) return text;
  const hasTextBlock = (blocks as Array<{ type: string }>).some(b => b.type === 'text');
  if (hasTextBlock) return blocks;
  if (!text) return blocks;
  return [{ type: 'text', text }, ...blocks];
}

/**
 * Convert a createdAt date string to a Unix timestamp in seconds.
 * Returns undefined if the date is invalid or empty.
 */
export function safeTimestamp(createdAt: string): number | undefined {
  if (!createdAt) return undefined;
  const d = new Date(createdAt.includes('T') ? createdAt : createdAt.replace(' ', 'T') + 'Z');
  const ts = d.getTime() / 1000;
  return Number.isFinite(ts) ? ts : undefined;
}

/**
 * Group flat message list into conversation turns.
 * A turn starts at each user message and includes subsequent assistant messages.
 */
export function groupMessagesByTurn(messages: Message[]): Message[][] {
  const turns: Message[][] = [];
  let current: Message[] = [];
  for (const msg of messages) {
    if (msg.role === 'user' && current.length > 0) {
      turns.push(current);
      current = [];
    }
    current.push(msg);
  }
  if (current.length > 0) turns.push(current);
  return turns;
}

/**
 * Clean Gateway metadata from user message text for display.
 * Strips: [media attached: ... | ...], [message_id: ...],
 * the timestamp prefix [Day Date Time Timezone], and [工作目录: ...].
 */
function cleanUserText(text: string): string {
  return text
    // Remove [media attached: path (mime) | path] references
    .replace(/\s*\[media attached:[^\]]*\]/g, '')
    // Remove [message_id: uuid]
    .replace(/\s*\[message_id:\s*[^\]]+\]/g, '')
    // Remove Gateway-injected "Conversation info (untrusted metadata): ```json...```" block
    .replace(/^Conversation info\s*\([^)]*\):\s*```[a-z]*\n[\s\S]*?```\s*/i, '')
    // Fallback: remove "Conversation info (...): {...}" without code block wrapper
    .replace(/^Conversation info\s*\([^)]*\):\s*\{[\s\S]*?\}\s*/i, '')
    // Remove Gateway timestamp prefix like [Fri 2026-02-13 22:39 GMT+8]
    .replace(/^\[(?:Mon|Tue|Wed|Thu|Fri|Sat|Sun)\s+\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}\s+[^\]]+\]\s*/i, '')
    // Remove [工作目录: /path/to/workspace] prefix and following newlines
    .replace(/^\[工作目录:[^\]]*\]\n\n/, '')
    .trim();
}

/**
 * Extract displayable text from a message's content field.
 * Handles both string content and array-of-blocks content.
 * For user messages, strips Gateway-injected metadata.
 */
export function extractText(message: RawMessage | unknown): string {
  if (!message || typeof message !== 'object') return '';
  const msg = message as Record<string, unknown>;
  const content = msg.content;
  const isUser = msg.role === 'user';

  let result = '';

  if (typeof content === 'string') {
    result = content.trim().length > 0 ? content : '';
  } else if (Array.isArray(content)) {
    const parts: string[] = [];
    for (const block of content as ContentBlock[]) {
      if (block.type === 'text' && block.text) {
        if (block.text.trim().length > 0) {
          parts.push(block.text);
        }
      }
    }
    const combined = parts.join('\n\n');
    result = combined.trim().length > 0 ? combined : '';
  } else if (typeof msg.text === 'string') {
    // Fallback: try .text field
    result = msg.text.trim().length > 0 ? msg.text : '';
  }

  // Strip Gateway metadata from user messages for clean display
  if (isUser && result) {
    result = cleanUserText(result);
  }

  return result;
}

/**
 * Extract thinking/reasoning content from a message.
 * Returns null if no thinking content found.
 */
export function extractThinking(message: RawMessage | unknown): string | null {
  if (!message || typeof message !== 'object') return null;
  const msg = message as Record<string, unknown>;
  const content = msg.content;

  if (!Array.isArray(content)) return null;

  const parts: string[] = [];
  for (const block of content as ContentBlock[]) {
    if (block.type === 'thinking' && block.thinking) {
      const cleaned = block.thinking.trim();
      if (cleaned) {
        parts.push(cleaned);
      }
    }
  }

  const combined = parts.join('\n\n').trim();
  return combined.length > 0 ? combined : null;
}

/**
 * Extract media file references from Gateway-formatted user message text.
 * Returns array of { filePath, mimeType } from [media attached: path (mime) | path] patterns.
 */
export function extractMediaRefs(message: RawMessage | unknown): Array<{ filePath: string; mimeType: string }> {
  if (!message || typeof message !== 'object') return [];
  const msg = message as Record<string, unknown>;
  if (msg.role !== 'user') return [];
  const content = msg.content;

  let text = '';
  if (typeof content === 'string') {
    text = content;
  } else if (Array.isArray(content)) {
    text = (content as ContentBlock[])
      .filter(b => b.type === 'text' && b.text)
      .map(b => b.text!)
      .join('\n');
  }

  const refs: Array<{ filePath: string; mimeType: string }> = [];
  const regex = /\[media attached:\s*([^\s(]+)\s*\(([^)]+)\)\s*\|[^\]]*\]/g;
  let match;
  while ((match = regex.exec(text)) !== null) {
    refs.push({ filePath: match[1], mimeType: match[2] });
  }
  return refs;
}

/**
 * Extract image attachments from a message.
 * Returns array of { mimeType, data } for base64 images.
 */
export function extractImages(message: RawMessage | unknown): Array<{ mimeType: string; data: string }> {
  if (!message || typeof message !== 'object') return [];
  const msg = message as Record<string, unknown>;
  const content = msg.content;

  if (!Array.isArray(content)) return [];

  const images: Array<{ mimeType: string; data: string }> = [];
  for (const block of content as ContentBlock[]) {
    if (block.type === 'image') {
      // Path 1: Anthropic source-wrapped format
      if (block.source) {
        const src = block.source;
        if (src.type === 'base64' && src.media_type && src.data) {
          images.push({ mimeType: src.media_type, data: src.data });
        }
      }
      // Path 2: Flat format from Gateway tool results {data, mimeType}
      else if (block.data) {
        images.push({ mimeType: block.mimeType || 'image/jpeg', data: block.data });
      }
    }
  }

  return images;
}

/**
 * Extract tool use blocks from a message.
 * Handles both Anthropic format (tool_use in content array) and
 * OpenAI format (tool_calls array on the message object).
 */
export function extractToolUse(message: RawMessage | unknown): Array<{ id: string; name: string; input: unknown }> {
  if (!message || typeof message !== 'object') return [];
  const msg = message as Record<string, unknown>;
  const tools: Array<{ id: string; name: string; input: unknown }> = [];

  // Path 1: Anthropic/normalized format — tool_use / toolCall blocks inside content array
  const content = msg.content;
  if (Array.isArray(content)) {
    for (const block of content as ContentBlock[]) {
      if ((block.type === 'tool_use' || block.type === 'toolCall') && block.name) {
        tools.push({
          id: block.id || '',
          name: block.name,
          input: block.input ?? block.arguments,
        });
      }
    }
  }

  // Path 2: OpenAI format — tool_calls array on the message itself
  // Real-time streaming events from OpenAI-compatible models (DeepSeek, etc.)
  // use this format; the Gateway normalizes to Path 1 when storing history.
  if (tools.length === 0) {
    const toolCalls = msg.tool_calls ?? msg.toolCalls;
    if (Array.isArray(toolCalls)) {
      for (const tc of toolCalls as Array<Record<string, unknown>>) {
        const fn = (tc.function ?? tc) as Record<string, unknown>;
        const name = typeof fn.name === 'string' ? fn.name : '';
        if (!name) continue;
        let input: unknown;
        try {
          input = typeof fn.arguments === 'string' ? JSON.parse(fn.arguments) : fn.arguments ?? fn.input;
        } catch {
          input = fn.arguments;
        }
        tools.push({
          id: typeof tc.id === 'string' ? tc.id : '',
          name,
          input,
        });
      }
    }
  }

  return tools;
}

/**
 * Format a Unix timestamp (seconds) to relative time string.
 * Handles both numeric timestamps and ISO date strings.
 */
export function formatTimestamp(timestamp: unknown): string {
  if (!timestamp) return '';

  let date: Date;

  if (typeof timestamp === 'number') {
    // OpenClaw timestamps can be in seconds or milliseconds
    const ms = timestamp > 1e12 ? timestamp : timestamp * 1000;
    date = new Date(ms);
  } else if (typeof timestamp === 'string') {
    // Handle ISO date strings (e.g., "2026-03-26T10:30:00.000Z")
    date = new Date(timestamp);
  } else {
    return '';
  }

  if (isNaN(date.getTime())) return '';

  const now = new Date();
  const diffMs = now.getTime() - date.getTime();

  if (diffMs < 60000) return 'just now';
  if (diffMs < 3600000) return `${Math.floor(diffMs / 60000)}m ago`;
  if (diffMs < 86400000) return `${Math.floor(diffMs / 3600000)}h ago`;

  return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', hour12: false });
}

/**
 * Check if a message is an internal system notification that should be hidden from UI.
 * Messages containing [claude job done] or [claude job failed] are internal triggers.
 * Note: Messages may have [工作目录: ...] prefix, so we use includes() instead of startsWith().
 */
export function isInternalNotification(message: RawMessage | unknown): boolean {
  const text = extractText(message);
  return text.includes('[claude job done]') || text.includes('[claude job failed]');
}

// ── Tool display helpers ──

/** Tool name → Chinese description (keep original English name visible) */
const TOOL_DISPLAY_NAMES: Record<string, string> = {
  Agent: '派出助手',
  Read: '读取文件',
  Write: '写入文件',
  Edit: '编辑文件',
  Bash: '执行命令',
  Grep: '搜索内容',
  Glob: '查找文件',
  WebSearch: '搜索网络',
  WebFetch: '获取网页',
  LSP: '代码分析',
  ListMcpResourcesTool: '查询资源',
  TaskCreate: '创建任务',
  TaskUpdate: '更新任务',
  TaskList: '查看任务',
  AskUserQuestion: '询问用户',
};

/** Get display name like "Read - 读取文件" */
export function getToolDisplayName(name: string): string {
  const desc = TOOL_DISPLAY_NAMES[name];
  return desc ? `${name} - ${desc}` : name;
}

/** Format tool input JSON into a readable one-line summary */
export function formatToolSummary(name: string, input: unknown): string {
  if (input == null) return '';
  const str = typeof input === 'string' ? input : JSON.stringify(input);
  if (!str.trim()) return '';

  try {
    const obj = JSON.parse(str);
    if (name === 'Bash' && obj.command) return `$ ${obj.command}`;
    if (name === 'Read' && obj.file_path) return obj.file_path;
    if (name === 'Write' && obj.file_path) return obj.file_path;
    if (name === 'Edit' && obj.file_path) return obj.file_path;
    if (name === 'Grep' && obj.pattern) return `${obj.pattern}${obj.path ? ` in ${obj.path}` : ''}`;
    if (name === 'Glob' && obj.pattern) return obj.pattern;
    if (name === 'WebSearch' && obj.query) return obj.query;
    if (name === 'WebFetch' && obj.url) return obj.url;
    if (name === 'Agent' && obj.prompt) {
      const prompt = String(obj.prompt);
      return prompt.length > 80 ? prompt.slice(0, 77) + '...' : prompt;
    }
    // Generic: first key-value pair
    const entries = Object.entries(obj);
    if (entries.length > 0) {
      const [k, v] = entries[0];
      const val = typeof v === 'string' ? v : JSON.stringify(v);
      return `${k}: ${val.length > 60 ? val.slice(0, 57) + '...' : val}`;
    }
    return '';
  } catch {
    return str.length > 80 ? str.slice(0, 77) + '...' : str;
  }
}
