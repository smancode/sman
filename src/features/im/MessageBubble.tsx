import { t } from '@/locales';
import { getAgentColor, getDisplayName } from './utils';
import type { IMMessage } from '@/schemas/im';

// ---------------------------------------------------------------------------
// Props
// ---------------------------------------------------------------------------

interface MessageBubbleProps {
  message: IMMessage;
  isSelf: boolean;
  allSenders: string[];
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function formatTime(ts: number): string {
  const d = new Date(ts);
  const hh = String(d.getHours()).padStart(2, '0');
  const mm = String(d.getMinutes()).padStart(2, '0');
  return `${hh}:${mm}`;
}

/**
 * Extract initials from sender for avatar display.
 * - "username@host" → first char of username
 * - "nk/sman-server" → "S" (first char after /)
 */
function getInitial(sender: string): string {
  const username = sender.split('@')[0];
  if (username.includes('/')) {
    const parts = username.split('/');
    return parts[parts.length - 1].charAt(0).toUpperCase();
  }
  return username.charAt(0).toUpperCase();
}

// ---------------------------------------------------------------------------
// @mention parser
// ---------------------------------------------------------------------------

const MENTION_REGEX = /@(\S+)/g;

interface MentionParseResult {
  text: string;
  isMention: boolean;
  color: string;
}

function parseMentions(content: string, isSelf: boolean): MentionParseResult[] {
  const parts: MentionParseResult[] = [];
  let lastIndex = 0;
  let match: RegExpExecArray | null;

  // Reset regex state
  const regex = new RegExp(MENTION_REGEX.source, 'g');

  while ((match = regex.exec(content)) !== null) {
    // Push text before the mention
    if (match.index > lastIndex) {
      parts.push({ text: content.slice(lastIndex, match.index), isMention: false, color: '' });
    }
    const mentionId = match[1];
    const color = getAgentColor(mentionId);
    parts.push({ text: match[0], isMention: true, color });
    lastIndex = regex.lastIndex;
  }

  // Remaining text
  if (lastIndex < content.length) {
    parts.push({ text: content.slice(lastIndex), isMention: false, color: '' });
  }

  return parts;
}

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

export function MessageBubble({ message, isSelf, allSenders }: MessageBubbleProps) {
  const { sender, content, timestamp, type, quoteId, attachments } = message;

  // System messages
  if (type === 'system') {
    return (
      <div className="text-center py-1.5 text-xs text-[#555568] italic">
        {content}
      </div>
    );
  }

  // Agent output messages — simple colored div placeholder (Task 10 replaces with AgentCard)
  if (type === 'agent_output') {
    const color = getAgentColor(sender);
    return (
      <div
        className="mx-0 my-1 rounded-xl border-2 overflow-hidden"
        style={{ borderColor: color }}
      >
        <div
          className="px-3.5 py-2.5 flex items-center gap-2.5 cursor-pointer"
          style={{ backgroundColor: `${color}0f` }}
        >
          <div
            className="w-7 h-7 rounded-lg flex items-center justify-center text-xs font-bold flex-shrink-0"
            style={{ backgroundColor: `${color}33`, color }}
          >
            🤖
          </div>
          <div className="flex-1 min-w-0">
            <div className="text-[13px] font-semibold" style={{ color }}>
              {getDisplayName(sender, allSenders)}
            </div>
          </div>
          <span className="text-[10px] text-[#555568]">
            {formatTime(timestamp)}
          </span>
        </div>
        <div className="px-3.5 pb-3 text-[13px] text-[#8888a0] whitespace-pre-wrap leading-relaxed">
          {content}
        </div>
      </div>
    );
  }

  // Regular text message
  const displayName = getDisplayName(sender, allSenders);
  const avatarColor = isSelf ? '#00b894' : getAgentColor(sender);
  const initial = getInitial(sender);
  const parsedContent = parseMentions(content, isSelf);

  return (
    <div className={`flex gap-2.5 py-1.5 max-w-[85%] ${isSelf ? 'flex-row-reverse self-end' : ''}`}>
      {/* Avatar */}
      <div
        className="w-7 h-7 rounded-lg flex items-center justify-center text-[11px] font-bold flex-shrink-0 mt-1"
        style={{ backgroundColor: avatarColor }}
      >
        {initial}
      </div>

      {/* Body */}
      <div className="min-w-0">
        {/* Meta: author + time */}
        <div className={`flex items-center gap-1.5 mb-0.5 ${isSelf ? 'flex-row-reverse' : ''}`}>
          <span className="text-xs font-semibold" style={{ color: avatarColor }}>
            {displayName}
          </span>
          <span className="text-[10px] text-[#555568]">
            {formatTime(timestamp)}
          </span>
        </div>

        {/* Quote reference */}
        {quoteId && (
          <div className="bg-[#0a0a0f] border-l-2 border-[#555568] px-2 py-1 rounded mb-1 text-xs text-[#555568] cursor-pointer hover:border-[#6c5ce7]">
            <span className="font-semibold">{t('im.chatInput.replyGroup')}</span>
          </div>
        )}

        {/* Bubble */}
        <div
          className={`px-3 py-2 rounded-xl text-sm leading-relaxed break-words ${
            isSelf
              ? 'bg-[#6c5ce7] text-white rounded-tr-sm'
              : 'bg-[#1a1a24] text-[#e8e8ed] rounded-tl-sm'
          }`}
        >
          {parsedContent.map((part, i) =>
            part.isMention ? (
              <span
                key={i}
                className={`px-0.5 py-px rounded font-medium cursor-pointer ${
                  isSelf
                    ? 'bg-white/20 text-white'
                    : ''
                }`}
                style={!isSelf ? { backgroundColor: `${part.color}26`, color: part.color } : undefined}
              >
                {part.text}
              </span>
            ) : (
              <span key={i}>{part.text}</span>
            ),
          )}
        </div>

        {/* File attachments */}
        {attachments && attachments.length > 0 && (
          <div className="mt-1 space-y-1">
            {attachments.map((file: { name?: string }, i: number) => (
              <div
                key={i}
                className="flex items-center gap-2 bg-[#0a0a0f] px-2.5 py-2 rounded-lg text-[13px] cursor-pointer hover:bg-[#22222e]"
              >
                <span className="text-[#555568]">📎</span>
                <span className="text-[#8888a0] truncate">{file.name || 'file'}</span>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
