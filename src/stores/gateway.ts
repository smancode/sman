// src/stores/gateway.ts

import { create } from 'zustand'
import { persist } from 'zustand/middleware'
import type { GatewayStatus } from '@/types/gateway'

const BACKEND_CONFIG_URL = '/api/config';

/**
 * Fetch server-side configuration from backend
 * Returns null if backend is not available or request fails
 */
export async function fetchServerConfig(): Promise<{ gateway: { url: string; token: string } } | null> {
  try {
    const response = await fetch(BACKEND_CONFIG_URL);
    if (!response.ok) return null;
    return await response.json();
  } catch {
    return null;
  }
}

interface GatewayState {
  // Config
  url: string
  token: string
  setConfig: (url: string, token: string) => void

  // Status
  status: GatewayStatus
  setStatus: (status: GatewayStatus) => void

  // Connection
  connected: boolean
  setConnected: (connected: boolean) => void
}

export const useGatewayStore = create<GatewayState>()(
  persist(
    (set) => ({
      url: '',
      token: '',
      setConfig: (url, token) => set({ url, token }),

      status: { state: 'disconnected' },
      setStatus: (status) => set({ status }),

      connected: false,
      setConnected: (connected) => set({ connected }),
    }),
    {
      name: 'smanweb-gateway',
      partialize: (state) => ({ url: state.url, token: state.token }),
    }
  )
)
