import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import type { BusinessSystem, SystemSession, SystemsListResponse } from '@/types/business-system';
import { getGatewayClient } from '@/lib/gateway-client';

interface BusinessSystemsState {
  // 业务系统列表 (从 Gateway 获取)
  systems: BusinessSystem[];
  systemsLoading: boolean;
  systemsError: string | null;
  loadSystems: () => Promise<void>;

  // 会话列表 (本地存储)
  sessions: SystemSession[];
  currentSessionId: string | null;

  // 会话操作
  createSession: (systemId: string) => string;
  updateSessionLabel: (sessionId: string, label: string) => void;
  deleteSession: (sessionId: string) => void;
  switchSession: (sessionId: string) => void;

  // 辅助方法
  getSessionsBySystem: (systemId: string) => SystemSession[];
  getCurrentSession: () => SystemSession | null;
  getCurrentSystem: () => BusinessSystem | null;
  getSystemById: (systemId: string) => BusinessSystem | undefined;
}

export const useBusinessSystemsStore = create<BusinessSystemsState>()(
  persist(
    (set, get) => ({
      // 初始状态
      systems: [],
      systemsLoading: false,
      systemsError: null,
      sessions: [],
      currentSessionId: null,

      // 加载业务系统列表
      loadSystems: async () => {
        set({ systemsLoading: true, systemsError: null });
        try {
          const client = getGatewayClient();
          if (!client) {
            set({ systemsError: 'Gateway 未连接', systemsLoading: false });
            return;
          }

          const result = await client.rpc<SystemsListResponse>('systems.list', {});
          if (result?.systems) {
            set({ systems: result.systems, systemsLoading: false });
          } else {
            set({ systemsLoading: false });
          }
        } catch (error) {
          const message = error instanceof Error ? error.message : '加载业务系统失败';
          set({ systemsError: message, systemsLoading: false });
        }
      },

      // 创建新会话
      createSession: (systemId: string) => {
        const sessionId = `session-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
        const newSession: SystemSession = {
          id: sessionId,
          systemId,
          label: '新会话',
          createdAt: Date.now(),
        };

        set((state) => ({
          sessions: [...state.sessions, newSession],
          currentSessionId: sessionId,
        }));

        return sessionId;
      },

      // 更新会话名称
      updateSessionLabel: (sessionId: string, label: string) => {
        set((state) => ({
          sessions: state.sessions.map((s) =>
            s.id === sessionId ? { ...s, label, updatedAt: Date.now() } : s
          ),
        }));
      },

      // 删除会话
      deleteSession: (sessionId: string) => {
        set((state) => {
          const remaining = state.sessions.filter((s) => s.id !== sessionId);
          const newCurrentId =
            state.currentSessionId === sessionId
              ? remaining[0]?.id ?? null
              : state.currentSessionId;

          return {
            sessions: remaining,
            currentSessionId: newCurrentId,
          };
        });
      },

      // 切换会话
      switchSession: (sessionId: string) => {
        set({ currentSessionId: sessionId });
      },

      // 按系统分组获取会话
      getSessionsBySystem: (systemId: string) => {
        return get().sessions.filter((s) => s.systemId === systemId);
      },

      // 获取当前会话
      getCurrentSession: () => {
        const { sessions, currentSessionId } = get();
        return sessions.find((s) => s.id === currentSessionId) ?? null;
      },

      // 获取当前业务系统
      getCurrentSystem: () => {
        const { systems, sessions, currentSessionId } = get();
        const currentSession = sessions.find((s) => s.id === currentSessionId);
        if (!currentSession) return null;
        return systems.find((s) => s.id === currentSession.systemId) ?? null;
      },

      // 根据 ID 获取业务系统
      getSystemById: (systemId: string) => {
        return get().systems.find((s) => s.id === systemId);
      },
    }),
    {
      name: 'smanweb-sessions',
      partialize: (state) => ({
        sessions: state.sessions,
        currentSessionId: state.currentSessionId,
      }),
    }
  )
);
