/**
 * Business Systems Store (SmanBase)
 * Manages profiles via WebSocket — no Gateway dependency.
 */
import { create } from 'zustand';
import { useWsConnection } from '@/stores/ws-connection';
import { useChatStore } from '@/stores/chat';
import type {
  BusinessSystem,
  CreateBusinessSystemInput,
  UpdateBusinessSystemInput,
  SkillItem,
} from '@/types/business-system';

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

interface BusinessSystemsState {
  systems: BusinessSystem[];
  skills: SkillItem[];
  loading: boolean;
  error: string | null;

  // 左侧栏展开状态
  expandedSystems: Set<string>;

  // Actions
  loadSystems: () => Promise<void>;
  loadSkills: () => Promise<void>;
  createSystem: (input: CreateBusinessSystemInput) => Promise<BusinessSystem>;
  updateSystem: (systemId: string, updates: UpdateBusinessSystemInput) => Promise<BusinessSystem>;
  deleteSystem: (systemId: string) => Promise<void>;
  toggleSystemExpanded: (systemId: string, expanded: boolean) => void;

  // 辅助
  getSystemById: (systemId: string) => BusinessSystem | undefined;
}

export const useBusinessSystemsStore = create<BusinessSystemsState>((set, get) => ({
  systems: [],
  skills: [],
  loading: false,
  error: null,
  expandedSystems: new Set<string>(),

  toggleSystemExpanded: (systemId: string, expanded: boolean) => {
    set((state) => {
      const next = new Set(state.expandedSystems);
      if (expanded) next.add(systemId);
      else next.delete(systemId);
      return { expandedSystems: next };
    });
  },

  loadSystems: async () => {
    const client = getWsClient();
    if (!client) return;

    set({ loading: true, error: null });
    try {
      const unsub = wrapHandler(client, 'profile.list', (data) => {
        unsub();
        const systems = (Array.isArray(data.profiles)
          ? data.profiles
          : []) as BusinessSystem[];
        set({ systems, loading: false });

        // 自动加载第一个系统的会话
        if (systems.length > 0) {
          const chat = useChatStore.getState();
          if (!chat.currentSessionId) {
            chat.loadSessions(systems[0].systemId);
          }
        }
      });
      client.send({ type: 'profile.list' });
    } catch (err) {
      set({ error: String(err), loading: false });
    }
  },

  loadSkills: async () => {
    const client = getWsClient();
    if (!client) return;

    try {
      const unsub = wrapHandler(client, 'skills.list', (data) => {
        unsub();
        const skills = (Array.isArray(data.skills)
          ? data.skills
          : []) as SkillItem[];
        set({ skills });
      });
      client.send({ type: 'skills.list' });
    } catch (err) {
      console.warn('Failed to load skills:', err);
    }
  },

  createSystem: async (input: CreateBusinessSystemInput) => {
    const client = getWsClient();
    if (!client) throw new Error('Not connected');

    return new Promise<BusinessSystem>((resolve, reject) => {
      const unsub = wrapHandler(client, 'profile.created', (data) => {
        unsub();
        unsubErr();
        const profile = data.profile as BusinessSystem;
        set((s) => ({ systems: [...s.systems, profile] }));
        resolve(profile);
      });
      const unsubErr = wrapHandler(client, 'chat.error', (data) => {
        unsub();
        unsubErr();
        reject(new Error(String(data.error)));
      });
      client.send({ type: 'profile.create', ...input });
    });
  },

  updateSystem: async (systemId: string, updates: UpdateBusinessSystemInput) => {
    const client = getWsClient();
    if (!client) throw new Error('Not connected');

    return new Promise<BusinessSystem>((resolve, reject) => {
      const unsub = wrapHandler(client, 'profile.updated', (data) => {
        unsub();
        unsubErr();
        const profile = data.profile as BusinessSystem;
        set((s) => ({
          systems: s.systems.map((sys) =>
            sys.systemId === systemId ? profile : sys,
          ),
        }));
        resolve(profile);
      });
      const unsubErr = wrapHandler(client, 'chat.error', (data) => {
        unsub();
        unsubErr();
        reject(new Error(String(data.error)));
      });
      client.send({ type: 'profile.update', systemId, ...updates });
    });
  },

  deleteSystem: async (systemId: string) => {
    const client = getWsClient();
    if (!client) throw new Error('Not connected');

    return new Promise<void>((resolve, reject) => {
      const unsub = wrapHandler(client, 'profile.deleted', (data) => {
        unsub();
        unsubErr();
        set((s) => ({
          systems: s.systems.filter((sys) => sys.systemId !== systemId),
        }));
        resolve();
      });
      const unsubErr = wrapHandler(client, 'chat.error', (data) => {
        unsub();
        unsubErr();
        reject(new Error(String(data.error)));
      });
      client.send({ type: 'profile.delete', systemId });
    });
  },

  getSystemById: (systemId: string) => {
    return get().systems.find((s) => s.systemId === systemId);
  },
}));
