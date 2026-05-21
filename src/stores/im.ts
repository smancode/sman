import { create } from 'zustand';
import { useWsConnection } from '@/stores/ws-connection';
import type { IMMessage } from '@/schemas/im';
import { parseIMMessage } from '@/schemas/im';

// ---------------------------------------------------------------------------
// WS helpers — same pattern as chat.ts wrapHandler
// ---------------------------------------------------------------------------

type MsgHandler = (msg: Record<string, unknown>) => void;

function getWsClient() {
  return useWsConnection.getState().client;
}

function wrapHandler(
  client: { on: (e: string, h: (...a: unknown[]) => void) => void; off: (e: string, h: (...a: unknown[]) => void) => void },
  event: string,
  handler: MsgHandler,
) {
  const wrapped = (...args: unknown[]) => handler(args[0] as Record<string, unknown>);
  client.on(event, wrapped);
  return () => client.off(event, wrapped);
}

// ---------------------------------------------------------------------------
// Typing auto-clear timers (per room)
// ---------------------------------------------------------------------------

const typingTimers = new Map<string, ReturnType<typeof setTimeout>>();

// ---------------------------------------------------------------------------
// Store interface
// ---------------------------------------------------------------------------

interface IMStore {
  // Navigation state
  selectedRoomId: string | null;
  activeTab: 'sessions' | 'groups';

  // Real-time message cache (per room)
  roomMessages: Map<string, IMMessage[]>;

  // Agent streaming state
  agentStreams: Map<string, string>; // messageId -> accumulated content

  // Presence
  onlineUsers: Set<string>;
  typingUsers: Map<string, string>; // roomId -> userId

  // Reply quote for pre-filling ChatInput
  replyQuote: { roomId: string; messageId: string; content: string } | null;

  // Actions — navigation
  selectRoom: (roomId: string | null) => void;
  setActiveTab: (tab: 'sessions' | 'groups') => void;

  // Actions — messages
  addMessage: (msg: IMMessage) => void;
  setRoomMessages: (roomId: string, messages: IMMessage[]) => void;

  // Actions — agent streams
  appendAgentStream: (messageId: string, delta: string) => void;
  clearAgentStream: (messageId: string) => void;

  // Actions — presence
  setOnlineUsers: (users: string[]) => void;
  setTyping: (roomId: string, userId: string) => void;
  clearTyping: (roomId: string) => void;

  // Actions — reply quote
  setReplyQuote: (quote: { roomId: string; messageId: string; content: string } | null) => void;
}

export const useIMStore = create<IMStore>((set, get) => ({
  selectedRoomId: null,
  activeTab: 'groups',
  roomMessages: new Map(),
  agentStreams: new Map(),
  onlineUsers: new Set(),
  typingUsers: new Map(),
  replyQuote: null,

  selectRoom: (roomId) => set({ selectedRoomId: roomId }),
  setActiveTab: (tab) => set({ activeTab: tab }),

  addMessage: (msg) => set((state) => {
    const newMap = new Map(state.roomMessages);
    const existing = newMap.get(msg.roomId) || [];
    // Dedup by id
    if (existing.some(m => m.id === msg.id)) return state;
    newMap.set(msg.roomId, [...existing, msg]);
    return { roomMessages: newMap };
  }),

  setRoomMessages: (roomId, messages) => set((state) => {
    const newMap = new Map(state.roomMessages);
    newMap.set(roomId, messages);
    return { roomMessages: newMap };
  }),

  appendAgentStream: (messageId, delta) => set((state) => {
    const newMap = new Map(state.agentStreams);
    newMap.set(messageId, (newMap.get(messageId) || '') + delta);
    return { agentStreams: newMap };
  }),

  clearAgentStream: (messageId) => set((state) => {
    const newMap = new Map(state.agentStreams);
    newMap.delete(messageId);
    return { agentStreams: newMap };
  }),

  setOnlineUsers: (users) => set({ onlineUsers: new Set(users) }),

  setTyping: (roomId, userId) => {
    // Clear existing timer for this room
    const existing = typingTimers.get(roomId);
    if (existing) clearTimeout(existing);

    // Auto-clear typing after 3 seconds
    const timer = setTimeout(() => {
      get().clearTyping(roomId);
      typingTimers.delete(roomId);
    }, 3000);
    typingTimers.set(roomId, timer);

    set((state) => {
      const newMap = new Map(state.typingUsers);
      newMap.set(roomId, userId);
      return { typingUsers: newMap };
    });
  },

  clearTyping: (roomId) => set((state) => {
    const newMap = new Map(state.typingUsers);
    newMap.delete(roomId);
    return { typingUsers: newMap };
  }),

  setReplyQuote: (quote) => set({ replyQuote: quote }),
}));

// ---------------------------------------------------------------------------
// WS event listener registration — called once from ws-connection.ts
// ---------------------------------------------------------------------------

let imListenersCleanup: (() => void) | null = null;

export function registerIMListeners() {
  const client = getWsClient();
  if (!client) return;

  // Clean up previous listeners if re-registering
  if (imListenersCleanup) {
    imListenersCleanup();
  }

  const unsubs: (() => void)[] = [];

  // im.message → add to roomMessages
  unsubs.push(wrapHandler(client, 'im.message', (data) => {
    const msg = parseIMMessage(data);
    if (!msg.id) return;
    useIMStore.getState().addMessage(msg);
  }));

  // im.agent_delta → append streaming content
  unsubs.push(wrapHandler(client, 'im.agent_delta', (data) => {
    const messageId = String(data.messageId || '');
    const content = String(data.content || '');
    if (!messageId) return;
    useIMStore.getState().appendAgentStream(messageId, content);
  }));

  // im.agent_done → clear streaming, add final message
  unsubs.push(wrapHandler(client, 'im.agent_done', (data) => {
    const messageId = String(data.messageId || '');
    if (messageId) {
      useIMStore.getState().clearAgentStream(messageId);
    }
    // If the server sends a final message, add it
    if (data.message) {
      const msg = parseIMMessage(data.message);
      if (msg.id) useIMStore.getState().addMessage(msg);
    }
  }));

  // im.presence → update online users
  unsubs.push(wrapHandler(client, 'im.presence', (data) => {
    const users = Array.isArray(data.users)
      ? data.users.map((u: unknown) => String(u))
      : [];
    useIMStore.getState().setOnlineUsers(users);
  }));

  // im.typing → show typing indicator (auto-clears after 3s)
  unsubs.push(wrapHandler(client, 'im.typing', (data) => {
    const roomId = String(data.roomId || '');
    const sender = String(data.sender || '');
    if (!roomId || !sender) return;
    useIMStore.getState().setTyping(roomId, sender);
  }));

  imListenersCleanup = () => {
    for (const unsub of unsubs) unsub();
    imListenersCleanup = null;
  };
}
