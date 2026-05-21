import { create } from 'zustand';
import type { IMMessage } from '@/schemas/im';

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
}

export const useIMStore = create<IMStore>((set, get) => ({
  selectedRoomId: null,
  activeTab: 'groups',
  roomMessages: new Map(),
  agentStreams: new Map(),
  onlineUsers: new Set(),
  typingUsers: new Map(),

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

  setTyping: (roomId, userId) => set((state) => {
    const newMap = new Map(state.typingUsers);
    newMap.set(roomId, userId);
    return { typingUsers: newMap };
  }),

  clearTyping: (roomId) => set((state) => {
    const newMap = new Map(state.typingUsers);
    newMap.delete(roomId);
    return { typingUsers: newMap };
  }),
}));
