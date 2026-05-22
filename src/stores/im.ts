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

  // Real-time last activity (updated on every addMessage for sidebar preview/sort)
  roomLastActivity: Map<string, { lastMessage: string; lastMessageTime: number }>;

  // Per-room last message timestamp for reconnection sync
  roomLastMsgTimestamp: Map<string, number>;

  // Agent streaming state
  agentStreams: Map<string, string>; // messageId -> accumulated content

  // Presence
  onlineUsers: Set<string>;
  typingUsers: Map<string, string>; // roomId -> userId

  // My client ID (set from first im.sent ack)
  mySenderId: string | null;

  // Reply quote for pre-filling ChatInput
  replyQuote: { roomId: string; messageId: string; content: string } | null;

  // Actions — navigation
  selectRoom: (roomId: string | null) => void;
  setActiveTab: (tab: 'sessions' | 'groups') => void;

  // Actions — messages
  addMessage: (msg: IMMessage) => void;
  addOptimisticMessage: (msg: IMMessage) => void;
  setRoomMessages: (roomId: string, messages: IMMessage[]) => void;
  touchRoom: (roomId: string) => void;

  // Actions — agent streams
  appendAgentStream: (messageId: string, delta: string) => void;
  clearAgentStream: (messageId: string) => void;

  // Actions — presence
  setOnlineUsers: (users: string[]) => void;
  setTyping: (roomId: string, userId: string) => void;
  clearTyping: (roomId: string) => void;

  // Actions — reply quote
  setReplyQuote: (quote: { roomId: string; messageId: string; content: string } | null) => void;

  // Actions — identity
  setMySenderId: (id: string) => void;

  // Actions — reconnection sync
  syncAfterReconnect: () => void;
}

export const useIMStore = create<IMStore>((set, get) => ({
  selectedRoomId: null,
  activeTab: 'groups',
  roomMessages: new Map(),
  roomLastActivity: new Map(),
  roomLastMsgTimestamp: new Map(),
  agentStreams: new Map(),
  onlineUsers: new Set(),
  typingUsers: new Map(),
  replyQuote: null,
  mySenderId: null,

  selectRoom: (roomId) => {
    const prev = get().selectedRoomId;
    set({ selectedRoomId: roomId });
    // Notify server of room join/leave (like TailChat findAndJoinRoom)
    const client = getWsClient();
    if (client?.connected) {
      if (prev && prev !== roomId) {
        client.send({ type: 'im.room.leave', roomId: prev });
      }
      if (roomId) {
        client.send({ type: 'im.room.join', roomId });
      }
    }
  },
  setActiveTab: (tab) => set({ activeTab: tab }),

  addMessage: (msg) => set((state) => {
    const newMap = new Map(state.roomMessages);
    const existing = newMap.get(msg.roomId) || [];
    // Dedup by id — also replace any optimistic message with matching temp id
    const filtered = existing.filter(m => m.id !== msg.id && m.id !== `temp-${msg.id}`);
    newMap.set(msg.roomId, [...filtered, msg]);

    // Update sidebar preview only (not sort time)
    const newActivity = new Map(state.roomLastActivity);
    const prev = newActivity.get(msg.roomId);
    const preview = msg.type === 'text'
      ? (msg.content.length > 40 ? msg.content.slice(0, 40) + '...' : msg.content)
      : msg.type === 'agent_output' ? '[Agent 执行结果]'
      : msg.type === 'system' ? '[系统消息]'
      : '';
    newActivity.set(msg.roomId, {
      lastMessage: preview,
      lastMessageTime: prev?.lastMessageTime ?? 0,
    });

    const newTimestamps = new Map(state.roomLastMsgTimestamp);
    const prevTs = newTimestamps.get(msg.roomId) || 0;
    if (msg.timestamp > prevTs) {
      newTimestamps.set(msg.roomId, msg.timestamp);
    }

    return { roomMessages: newMap, roomLastActivity: newActivity, roomLastMsgTimestamp: newTimestamps };
  }),

  addOptimisticMessage: (msg) => set((state) => {
    const newMap = new Map(state.roomMessages);
    const existing = newMap.get(msg.roomId) || [];
    newMap.set(msg.roomId, [...existing, msg]);

    const newActivity = new Map(state.roomLastActivity);
    const prev = newActivity.get(msg.roomId);
    const preview = msg.content.length > 40 ? msg.content.slice(0, 40) + '...' : msg.content;
    newActivity.set(msg.roomId, {
      lastMessage: preview,
      lastMessageTime: prev?.lastMessageTime ?? 0,
    });

    return { roomMessages: newMap, roomLastActivity: newActivity };
  }),

  touchRoom: (roomId) => set((state) => {
    const newActivity = new Map(state.roomLastActivity);
    const prev = newActivity.get(roomId);
    newActivity.set(roomId, {
      lastMessage: prev?.lastMessage ?? '',
      lastMessageTime: Date.now(),
    });
    return { roomLastActivity: newActivity };
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
  setMySenderId: (id: string) => set({ mySenderId: id }),

  syncAfterReconnect: () => {
    const client = getWsClient();
    if (!client?.connected) return;
    const state = get();
    if (state.selectedRoomId) {
      client.send({ type: 'im.room.join', roomId: state.selectedRoomId });
      const lastTs = state.roomLastMsgTimestamp.get(state.selectedRoomId) || 0;
      client.send({ type: 'im.sync', roomId: state.selectedRoomId, afterTimestamp: lastTs });
    }
  },
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
  unsubs.push(wrapHandler(client, 'im.message', (msg) => {
    const raw = msg as Record<string, unknown>;
    const payload = (raw.data ?? raw) as Record<string, unknown>;
    const imMsg = parseIMMessage(payload);
    if (!imMsg.id) return;
    useIMStore.getState().addMessage(imMsg);
  }));

  // im.agent_delta → append streaming content
  unsubs.push(wrapHandler(client, 'im.agent_delta', (msg) => {
    const raw = msg as Record<string, unknown>;
    const payload = (raw.data ?? raw) as Record<string, unknown>;
    const messageId = String(payload.messageId || '');
    const content = String(payload.content || '');
    if (!messageId) return;
    useIMStore.getState().appendAgentStream(messageId, content);
  }));

  // im.agent_done → clear streaming, add final message
  unsubs.push(wrapHandler(client, 'im.agent_done', (msg) => {
    const raw = msg as Record<string, unknown>;
    const payload = (raw.data ?? raw) as Record<string, unknown>;
    const messageId = String(payload.messageId || '');
    if (messageId) {
      useIMStore.getState().clearAgentStream(messageId);
    }
    if (payload.message) {
      const finalMsg = parseIMMessage(payload.message);
      if (finalMsg.id) useIMStore.getState().addMessage(finalMsg);
    }
  }));

  // im.presence → update online users
  unsubs.push(wrapHandler(client, 'im.presence', (msg) => {
    const raw = msg as Record<string, unknown>;
    const payload = (raw.data ?? raw) as Record<string, unknown>;
    const users = Array.isArray(payload.users)
      ? payload.users.map((u: unknown) => String(u))
      : [];
    useIMStore.getState().setOnlineUsers(users);
  }));

  // im.typing → show typing indicator (auto-clears after 3s)
  unsubs.push(wrapHandler(client, 'im.typing', (msg) => {
    const raw = msg as Record<string, unknown>;
    const payload = (raw.data ?? raw) as Record<string, unknown>;
    const roomId = String(payload.roomId || '');
    const sender = String(payload.sender || '');
    if (!roomId || !sender) return;
    useIMStore.getState().setTyping(roomId, sender);
  }));

  // im.sync → merge synced messages
  unsubs.push(wrapHandler(client, 'im.sync', (msg) => {
    const raw = msg as Record<string, unknown>;
    const payload = (raw.data ?? raw) as Record<string, unknown>;
    const messages = Array.isArray(payload.messages) ? payload.messages : [];
    for (const m of messages) {
      const imMsg = parseIMMessage(m);
      if (imMsg.id) useIMStore.getState().addMessage(imMsg);
    }
  }));

  // im.whoami → receive our clientId from server
  unsubs.push(wrapHandler(client, 'im.whoami', (msg) => {
    const raw = msg as Record<string, unknown>;
    const payload = (raw.data ?? raw) as Record<string, unknown>;
    if (payload.clientId) {
      useIMStore.getState().setMySenderId(String(payload.clientId));
    }
  }));

  // im.sent → update mySenderId from server ack
  unsubs.push(wrapHandler(client, 'im.sent', (msg) => {
    const raw = msg as Record<string, unknown>;
    const payload = (raw.data ?? raw) as Record<string, unknown>;
    if (payload.sender && !useIMStore.getState().mySenderId) {
      useIMStore.getState().setMySenderId(String(payload.sender));
    }
  }));

  // Request clientId immediately so isSelf works for history messages
  if (!useIMStore.getState().mySenderId) {
    client.send({ type: 'im.whoami' });
  }

  imListenersCleanup = () => {
    for (const unsub of unsubs) unsub();
    imListenersCleanup = null;
  };
}
