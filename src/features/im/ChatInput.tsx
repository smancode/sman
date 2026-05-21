import { useState, useRef, useCallback, useEffect, type KeyboardEvent, type ChangeEvent } from 'react';
import { t } from '@/locales';
import { useIMStore } from '@/stores/im';
import { useChatStore } from '@/stores/chat';
import { getAgentColor } from './utils';

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

interface MentionableAgent {
  id: string;
  name: string;
  workspace: string;
}

interface ChatInputProps {
  roomId: string;
  clientId: string;
  onSend?: (content: string, mentionedAgents: string[]) => void;
  initialContent?: string;
  onContentConsumed?: () => void;
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/** Extract unique workspace dirs from existing chat sessions */
function getWorkspaceAgents(sessions: { workspace?: string; key: string }[]): MentionableAgent[] {
  const seen = new Set<string>();
  const agents: MentionableAgent[] = [];

  for (const s of sessions) {
    const ws = s.workspace;
    if (!ws || seen.has(ws)) continue;
    seen.add(ws);

    const name = ws.split('/').pop() || ws;
    agents.push({ id: ws, name, workspace: ws });
  }

  return agents;
}

/** Extract all @mentions from text, returning unique agent names */
function extractMentions(text: string): string[] {
  const matches = text.match(/@(\S+)/g);
  if (!matches) return [];
  return [...new Set(matches.map((m) => m.slice(1)))];
}

// ---------------------------------------------------------------------------
// MentionPopup — filtered agent list above input
// ---------------------------------------------------------------------------

interface MentionPopupProps {
  agents: MentionableAgent[];
  filter: string;
  onSelect: (agent: MentionableAgent) => void;
  onClose: () => void;
}

function MentionPopup({ agents, filter, onSelect, onClose }: MentionPopupProps) {
  const popupRef = useRef<HTMLDivElement>(null);

  const filtered = filter
    ? agents.filter((a) => a.name.toLowerCase().includes(filter.toLowerCase()))
    : agents;

  // Close on click outside
  useEffect(() => {
    function handleClick(e: MouseEvent) {
      if (popupRef.current && !popupRef.current.contains(e.target as Node)) {
        onClose();
      }
    }
    document.addEventListener('mousedown', handleClick);
    return () => document.removeEventListener('mousedown', handleClick);
  }, [onClose]);

  // Close on Escape
  useEffect(() => {
    function handleKey(e: KeyboardEvent_) {
      if (e.key === 'Escape') onClose();
    }
    document.addEventListener('keydown', handleKey);
    return () => document.removeEventListener('keydown', handleKey);
  }, [onClose]);

  if (filtered.length === 0) return null;

  return (
    <div
      ref={popupRef}
      className="absolute bottom-full left-0 right-0 mb-1 bg-[hsl(var(--card))] border border-[hsl(var(--border))] rounded-xl shadow-2xl max-h-60 overflow-y-auto z-50"
    >
      <div className="px-3 py-2 text-xs text-muted-foreground border-b border-[hsl(var(--border))]">
        {t('im.agents')}
      </div>
      {filtered.map((agent) => (
        <button
          key={agent.id}
          className="w-full flex items-center gap-3 px-3 py-2.5 text-left cursor-pointer transition-colors hover:bg-[hsl(var(--muted))]"
          onMouseDown={(e) => {
            e.preventDefault();
            onSelect(agent);
          }}
        >
          <span
            className="w-8 h-8 rounded-lg flex items-center justify-center text-xs font-bold text-white flex-shrink-0"
            style={{ backgroundColor: getAgentColor(agent.id) }}
          >
            {agent.name.charAt(0).toUpperCase()}
          </span>
          <div className="min-w-0">
            <div className="text-sm text-foreground truncate">{agent.name}</div>
            <div className="text-xs text-muted-foreground truncate">{agent.workspace}</div>
          </div>
        </button>
      ))}
    </div>
  );
}

// Workaround: KeyboardEvent type for the document-level listener
type KeyboardEvent_ = globalThis.KeyboardEvent;

// ---------------------------------------------------------------------------
// ChatInput
// ---------------------------------------------------------------------------

export function ChatInput({
  roomId,
  clientId,
  onSend,
  initialContent,
  onContentConsumed,
}: ChatInputProps) {
  const [content, setContent] = useState('');
  const [showMention, setShowMention] = useState(false);
  const [mentionFilter, setMentionFilter] = useState('');
  const [mentionStart, setMentionStart] = useState(-1); // cursor position where @ was typed
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const initialConsumed = useRef(false);

  // Read sessions to derive agent list
  const sessions = useChatStore((s) => s.sessions);
  const agents = getWorkspaceAgents(sessions);

  // IM store for sending messages
  const addMessage = useIMStore((s) => s.addMessage);

  // Consume initialContent once
  useEffect(() => {
    if (initialContent && !initialConsumed.current) {
      setContent(initialContent);
      initialConsumed.current = true;
      onContentConsumed?.();
      textareaRef.current?.focus();
    }
  }, [initialContent, onContentConsumed]);

  // Auto-resize textarea
  const adjustHeight = useCallback(() => {
    const el = textareaRef.current;
    if (!el) return;
    el.style.height = 'auto';
    const lineHeight = parseFloat(getComputedStyle(el).lineHeight) || 20;
    const maxH = lineHeight * 6;
    el.style.height = Math.min(el.scrollHeight, maxH) + 'px';
  }, []);

  // Handle content change
  const handleChange = useCallback(
    (e: ChangeEvent<HTMLTextAreaElement>) => {
      const value = e.target.value;
      setContent(value);

      // Detect @ trigger: check if cursor is right after @ or within @word
      const cursorPos = e.target.selectionStart;

      // Find the last @ before cursor that's part of current word
      let atPos = -1;
      for (let i = cursorPos - 1; i >= 0; i--) {
        if (value[i] === '@') {
          atPos = i;
          break;
        }
        if (value[i] === ' ' || value[i] === '\n') {
          break;
        }
      }

      if (atPos >= 0 && (atPos === 0 || value[atPos - 1] === ' ' || value[atPos - 1] === '\n')) {
        setShowMention(true);
        setMentionStart(atPos);
        setMentionFilter(value.slice(atPos + 1, cursorPos));
      } else {
        setShowMention(false);
        setMentionStart(-1);
        setMentionFilter('');
      }

      // Use setTimeout(0) to avoid blocking input rendering
      setTimeout(adjustHeight, 0);
    },
    [adjustHeight],
  );

  // Handle agent selection from mention popup
  const handleMentionSelect = useCallback(
    (agent: MentionableAgent) => {
      if (mentionStart < 0) return;

      const before = content.slice(0, mentionStart);
      const after = content.slice(textareaRef.current?.selectionStart ?? content.length);
      const newContent = `${before}@${agent.name} ${after}`;

      setContent(newContent);
      setShowMention(false);
      setMentionStart(-1);
      setMentionFilter('');

      // Move cursor after the inserted mention
      setTimeout(() => {
        const pos = before.length + agent.name.length + 2; // +2 for @ and space
        textareaRef.current?.setSelectionRange(pos, pos);
        textareaRef.current?.focus();
        adjustHeight();
      }, 0);
    },
    [content, mentionStart, adjustHeight],
  );

  // Send message
  const handleSend = useCallback(() => {
    const trimmed = content.trim();
    if (!trimmed) return;

    const mentionedAgents = extractMentions(trimmed);

    if (onSend) {
      onSend(trimmed, mentionedAgents);
    } else {
      // Default: add to store as a text message
      addMessage({
        id: `${clientId}-${Date.now()}`,
        roomId,
        sender: clientId,
        content: trimmed,
        mentionedAgents,
        type: 'text',
        timestamp: Date.now(),
      });
    }

    setContent('');
    setShowMention(false);
    setMentionStart(-1);
    setMentionFilter('');

    // Reset textarea height
    setTimeout(() => {
      if (textareaRef.current) {
        textareaRef.current.style.height = 'auto';
      }
    }, 0);
  }, [content, roomId, clientId, onSend, addMessage]);

  // Keyboard handling
  const handleKeyDown = useCallback(
    (e: KeyboardEvent<HTMLTextAreaElement>) => {
      // Escape closes mention popup
      if (e.key === 'Escape' && showMention) {
        e.preventDefault();
        setShowMention(false);
        setMentionStart(-1);
        setMentionFilter('');
        return;
      }

      // Enter = send (without Shift)
      if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        handleSend();
        return;
      }
    },
    [showMention, handleSend],
  );

  // Toolbar button: toggle @ mention
  const handleMentionButton = useCallback(() => {
    if (showMention) {
      setShowMention(false);
      return;
    }

    // Insert @ at cursor or end
    const textarea = textareaRef.current;
    if (!textarea) return;

    const pos = textarea.selectionStart;
    const before = content.slice(0, pos);
    const after = content.slice(pos);

    // Add space before @ if needed
    const needSpace = pos > 0 && before[pos - 1] !== ' ' && before[pos - 1] !== '\n';
    const insert = needSpace ? ' @' : '@';
    const newContent = before + insert + after;

    setContent(newContent);
    setShowMention(true);
    setMentionStart(pos + (needSpace ? 1 : 0));
    setMentionFilter('');

    setTimeout(() => {
      const newPos = pos + insert.length;
      textarea.setSelectionRange(newPos, newPos);
      textarea.focus();
    }, 0);
  }, [content, showMention]);

  return (
    <div className="px-5 py-3 border-t border-[hsl(var(--border))] bg-[hsl(var(--card))]">
      {/* Toolbar */}
      <div className="flex items-center gap-1 mb-2">
        <button
          className="w-8 h-8 flex items-center justify-center rounded-lg text-muted-foreground cursor-pointer transition-colors hover:bg-[hsl(var(--muted))] hover:text-foreground text-sm"
          title="Attach file"
        >
          📎
        </button>
        <button
          className="w-8 h-8 flex items-center justify-center rounded-lg text-muted-foreground cursor-pointer transition-colors hover:bg-[hsl(var(--muted))] hover:text-foreground text-sm"
          title="Attach image"
        >
          🖼️
        </button>
        <button
          className={`w-8 h-8 flex items-center justify-center rounded-lg cursor-pointer transition-colors text-sm ${
            showMention
              ? 'bg-[hsl(var(--primary))]/20 text-[hsl(var(--primary))]'
              : 'text-muted-foreground hover:bg-[hsl(var(--muted))] hover:text-foreground'
          }`}
          onClick={handleMentionButton}
          title="@ Agent"
        >
          @
        </button>
        <div className="flex-1" />
      </div>

      {/* Input area with mention popup */}
      <div className="relative">
        {showMention && (
          <MentionPopup
            agents={agents}
            filter={mentionFilter}
            onSelect={handleMentionSelect}
            onClose={() => {
              setShowMention(false);
              setMentionStart(-1);
              setMentionFilter('');
            }}
          />
        )}

        <div className="flex gap-2 items-end">
          <textarea
            ref={textareaRef}
            value={content}
            onChange={handleChange}
            onKeyDown={handleKeyDown}
            placeholder={t('im.chatInput.placeholder')}
            rows={1}
            className="flex-1 bg-[hsl(var(--card))] border border-[hsl(var(--border))] rounded-xl px-3.5 py-2.5 text-sm text-foreground outline-none focus:border-[hsl(var(--primary))] placeholder:text-muted-foreground resize-none overflow-y-auto leading-5"
            style={{ maxHeight: '120px' }}
          />
          <button
            onClick={handleSend}
            disabled={!content.trim()}
            className="w-10 h-10 bg-[hsl(var(--primary))] border-none rounded-xl text-primary-foreground cursor-pointer text-base flex items-center justify-center flex-shrink-0 transition-opacity disabled:opacity-50 hover:opacity-90"
          >
            ➤
          </button>
        </div>
      </div>
    </div>
  );
}
