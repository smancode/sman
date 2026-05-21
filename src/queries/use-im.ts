import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  parseIMRoom,
  parseIMMessage,
  EMPTY_ROOMS,
  EMPTY_MESSAGES,
} from '@/schemas/im';
import type { IMRoom, IMMessage } from '@/schemas/im';
import { useWsConnection } from '@/stores/ws-connection';

// ---------------------------------------------------------------------------
// HTTP helper — same pattern as hubFetch in use-hub.ts
// ---------------------------------------------------------------------------

interface IMFetchResult<T> {
  data: T;
  unreachable: boolean;
}

async function imFetch<T>(path: string, init?: RequestInit & { throwOnNetworkError?: true }): Promise<IMFetchResult<T>> {
  const token = localStorage.getItem('sman-backend-token') || '';
  const { throwOnNetworkError, ...restInit } = init ?? {};
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 5000);
  const res = await fetch(`/api/im${path}`, {
    ...restInit,
    signal: controller.signal,
    headers: {
      'Authorization': `Bearer ${token}`,
      'Content-Type': 'application/json',
      ...restInit?.headers,
    },
  }).catch(() => null);
  clearTimeout(timeout);

  if (!res) {
    if (throwOnNetworkError) throw new Error('IM server unreachable');
    return { data: null as unknown as T, unreachable: true };
  }
  if (!res.ok) {
    if (res.status === 503 || res.status === 502) {
      if (throwOnNetworkError) throw new Error('IM server unreachable');
      return { data: null as unknown as T, unreachable: true };
    }
    const body = await res.text().catch(() => '');
    let msg = `IM error: ${res.status}`;
    try {
      const parsed = JSON.parse(body);
      if (parsed.error) msg = parsed.error;
    } catch {
      if (body) msg += ` ${body}`;
    }
    throw new Error(msg);
  }
  const json = await res.json();
  return { data: json as T, unreachable: false };
}

async function imMutate<T = unknown>(path: string, init?: RequestInit): Promise<T> {
  const { data, unreachable } = await imFetch<T>(path, { ...init, throwOnNetworkError: true });
  if (unreachable) throw new Error('IM server unreachable');
  return data;
}

// ---------------------------------------------------------------------------
// WS helper — send via WS client and wait for response
// ---------------------------------------------------------------------------

type WsMsgHandler = (msg: Record<string, unknown>) => void;

function getWsClient() {
  return useWsConnection.getState().client;
}

function wrapWsHandler(
  client: { on: (e: string, h: (...a: unknown[]) => void) => void; off: (e: string, h: (...a: unknown[]) => void) => void },
  event: string,
  handler: WsMsgHandler,
) {
  const wrapped = (...args: unknown[]) => handler(args[0] as Record<string, unknown>);
  client.on(event, wrapped);
  return () => client.off(event, wrapped);
}

// ---------------------------------------------------------------------------
// Room hooks
// ---------------------------------------------------------------------------

export function useRoomList() {
  return useQuery({
    queryKey: ['im', 'rooms'] as const,
    queryFn: async () => {
      const { data: raw, unreachable } = await imFetch<unknown[]>('/rooms');
      if (unreachable) return EMPTY_ROOMS;
      const items = Array.isArray(raw) ? raw : [];
      return items.map(parseIMRoom);
    },
    staleTime: 10_000,
  });
}

// ---------------------------------------------------------------------------
// Message hooks — history via WS, real-time via Zustand store
// ---------------------------------------------------------------------------

export interface RoomMessagesOptions {
  /** Number of messages to load (default 50) */
  limit?: number;
  /** Load messages before this timestamp */
  before?: number;
}

export function useRoomMessages(roomId: string | undefined, options?: RoomMessagesOptions) {
  return useQuery({
    queryKey: ['im', 'rooms', roomId, 'messages', options?.limit, options?.before] as const,
    queryFn: async () => {
      if (!roomId) return EMPTY_MESSAGES;
      const client = getWsClient();
      if (!client || !client.connected) return EMPTY_MESSAGES;

      return new Promise<IMMessage[]>((resolve) => {
        const timeout = setTimeout(() => {
          unsub();
          resolve(EMPTY_MESSAGES);
        }, 8000);

        const unsub = wrapWsHandler(client, 'im.history', (data) => {
          if (String(data.roomId) !== roomId) return;
          clearTimeout(timeout);
          unsub();
          const messages = Array.isArray(data.messages) ? data.messages : [];
          resolve(messages.map(parseIMMessage));
        });

        client.send({
          type: 'im.history',
          roomId,
          limit: options?.limit ?? 50,
          before: options?.before,
        });
      });
    },
    enabled: !!roomId,
    staleTime: 30_000,
  });
}

// ---------------------------------------------------------------------------
// Mutation hooks
// ---------------------------------------------------------------------------

export interface CreateRoomParams {
  name: string;
  type?: 'group' | 'dm' | 'workspace';
  members?: string[];
}

export function useCreateRoom() {
  const qc = useQueryClient();
  return useMutation<IMRoom, Error, CreateRoomParams>({
    mutationFn: async (params) => {
      const raw = await imMutate<Record<string, unknown>>('/rooms', {
        method: 'POST',
        body: JSON.stringify(params),
      });
      return parseIMRoom(raw);
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ['im', 'rooms'] }),
  });
}

export interface SendMessageParams {
  roomId: string;
  content: string;
  mentionedAgents?: string[];
  quoteId?: string;
}

export function useSendMessage() {
  const qc = useQueryClient();
  return useMutation<void, Error, SendMessageParams>({
    mutationFn: async (params) => {
      const client = getWsClient();
      if (!client) throw new Error('Not connected');
      if (!client.connected) throw new Error('WebSocket not connected');

      return new Promise<void>((resolve, reject) => {
        const timeout = setTimeout(() => {
          unsubAck();
          reject(new Error('Send message timeout'));
        }, 10_000);

        const unsubAck = wrapWsHandler(client, 'im.sent', (data) => {
          if (String(data.roomId) !== params.roomId) return;
          clearTimeout(timeout);
          unsubAck();
          resolve();
        });

        client.send({
          type: 'im.send',
          roomId: params.roomId,
          content: params.content,
          mentionedAgents: params.mentionedAgents,
          quoteId: params.quoteId,
        });
      });
    },
    onSuccess: (_data, vars) => {
      qc.invalidateQueries({ queryKey: ['im', 'rooms', vars.roomId, 'messages'] });
    },
  });
}
