import { create } from 'zustand';
import { useWsConnection } from '@/stores/ws-connection';

export interface AchievementView {
  id: string;
  category: string;
  tier: string;
  nameKey: string;
  descKey: string;
  icon: string;
  hidden: boolean;
  currentValue: number;
  threshold: number;
  unlockedAt: string | null;
  points: number;
}

export interface AchievementSummary {
  achievements: AchievementView[];
  stats: Record<string, string>;
  streak: { current: number; longest: number };
  totalPoints: number;
  level: string;
  levelProgress: { current: number; next: number; currentMin: number; nextMin: number; progress: number };
  totalUnlocked: number;
  totalAchievements: number;
}

export interface AchievementUnlockEvent {
  achievement: {
    id: string;
    nameKey: string;
    descKey: string;
    icon: string;
    tier: string;
    category: string;
    points: number;
    hidden: boolean;
  };
  totalPoints: number;
  level: string;
}

export interface LeaderboardEntry {
  rank: number;
  agentName: string;
  totalPoints: number;
  totalUnlocked: number;
  level: string;
}

interface AchievementState {
  summary: AchievementSummary | null;
  isLoading: boolean;
  recentUnlocks: AchievementUnlockEvent[];
  leaderboard: LeaderboardEntry[];
  leaderboardOnline: boolean;
  leaderboardClientId: string;
  fetchSummary: () => void;
  fetchLeaderboard: () => void;
  clearRecentUnlocks: () => void;
}

type WsClient = NonNullable<ReturnType<typeof useWsConnection.getState>['client']>;

function sendWsMessage(msg: Record<string, unknown>): void {
  const client = useWsConnection.getState().client;
  if (client) {
    client.send(msg);
  }
}

function registerAchievementListeners(): () => void {
  const client = useWsConnection.getState().client;
  if (!client) return () => {};

  const onMessage = (raw: unknown) => {
    const msg = raw as Record<string, unknown>;
    if (msg.type === 'achievement.data') {
      useAchievementStore.setState({
        summary: {
          achievements: (msg.achievements as AchievementView[]) || [],
          stats: (msg.stats as Record<string, string>) || {},
          streak: (msg.streak as { current: number; longest: number }) || { current: 0, longest: 0 },
          totalPoints: (msg.totalPoints as number) || 0,
          level: (msg.level as string) || 'bronze',
          levelProgress: (msg.levelProgress as { current: number; next: number; currentMin: number; nextMin: number; progress: number }) || { current: 0, next: 20, currentMin: 0, nextMin: 20, progress: 0 },
          totalUnlocked: (msg.totalUnlocked as number) || 0,
          totalAchievements: (msg.totalAchievements as number) || 0,
        },
        isLoading: false,
      });
    }

    if (msg.type === 'achievement.unlock') {
      const unlock = msg as unknown as AchievementUnlockEvent;
      useAchievementStore.setState((state) => ({
        recentUnlocks: [unlock, ...state.recentUnlocks].slice(0, 5),
      }));
    }

    if (msg.type === 'achievement.leaderboard') {
      useAchievementStore.setState({
        leaderboard: (msg.entries as LeaderboardEntry[]) || [],
        leaderboardOnline: (msg.isOnline as boolean) || false,
        leaderboardClientId: (msg.clientId as string) || '',
      });
    }
  };

  client.on('achievement.data', onMessage);
  client.on('achievement.unlock', onMessage);
  client.on('achievement.leaderboard', onMessage);

  return () => {
    client.off('achievement.data', onMessage);
    client.off('achievement.unlock', onMessage);
    client.off('achievement.leaderboard', onMessage);
  };
}

let listenersRegistered = false;
let unregisterFn: (() => void) | null = null;

function ensureListeners(): void {
  if (listenersRegistered) return;
  listenersRegistered = true;

  const unsub = useWsConnection.subscribe((state, prev) => {
    if (state.client && state.client !== prev.client) {
      if (unregisterFn) unregisterFn();
      unregisterFn = registerAchievementListeners();
    }
  });

  if (useWsConnection.getState().client) {
    unregisterFn = registerAchievementListeners();
  }

  // Store unsub for cleanup (in a real app we'd call it on unmount)
  void unsub;
}

export const useAchievementStore = create<AchievementState>((set) => ({
  summary: null,
  isLoading: false,
  recentUnlocks: [],
  leaderboard: [],
  leaderboardOnline: false,
  leaderboardClientId: '',

  fetchSummary: () => {
    ensureListeners();
    set({ isLoading: true });
    sendWsMessage({ type: 'achievement.list' });
  },

  fetchLeaderboard: () => {
    ensureListeners();
    sendWsMessage({ type: 'achievement.leaderboard' });
  },

  clearRecentUnlocks: () => {
    set({ recentUnlocks: [] });
  },
}));
