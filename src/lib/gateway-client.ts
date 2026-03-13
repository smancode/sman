// src/lib/gateway-client.ts

import type {
  GatewayConfig,
  GatewayEventHandler,
  GatewayHello,
  GatewayErrorInfo,
} from '@/types/gateway'
import { GatewayRequestError } from '@/types/gateway'

type PendingRequest = {
  resolve: (value: unknown) => void
  reject: (error: Error) => void
  timeout: ReturnType<typeof setTimeout>
}

const DEFAULT_RECONNECT_INTERVAL = 3000
const DEFAULT_CONNECT_TIMEOUT = 10000
const DEFAULT_REQUEST_TIMEOUT = 30000

export class GatewayClient {
  private ws: WebSocket | null = null
  private config: GatewayConfig
  private pendingRequests = new Map<string, PendingRequest>()
  private eventHandlers = new Map<string, Set<GatewayEventHandler>>()
  private connectPromise: Promise<void> | null = null
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null
  private closed = false
  private backoffMs = 800

  onStatusChange?: (state: 'connecting' | 'connected' | 'disconnected' | 'error', error?: string) => void
  onHello?: (hello: GatewayHello) => void

  constructor(config: GatewayConfig) {
    if (!config.url) {
      throw new Error('Gateway URL is required')
    }
    this.config = {
      autoReconnect: true,
      reconnectInterval: DEFAULT_RECONNECT_INTERVAL,
      ...config,
    }
  }

  get connected(): boolean {
    return this.ws?.readyState === WebSocket.OPEN
  }

  async connect(): Promise<void> {
    if (this.ws?.readyState === WebSocket.OPEN) {
      return
    }
    if (this.connectPromise) {
      await this.connectPromise
      return
    }

    this.closed = false
    this.connectPromise = this.openSocket()
    try {
      await this.connectPromise
    } finally {
      this.connectPromise = null
    }
  }

  disconnect(): void {
    this.closed = true
    this.clearReconnectTimer()
    if (this.ws) {
      this.ws.close()
      this.ws = null
    }
    this.flushPendingRequests(new Error('Gateway connection closed'))
    this.onStatusChange?.('disconnected')
  }

  async rpc<T>(method: string, params?: unknown, timeoutMs = DEFAULT_REQUEST_TIMEOUT): Promise<T> {
    await this.connect()
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) {
      throw new Error('Gateway socket is not connected')
    }

    const id = this.generateId()
    const request = {
      type: 'req',
      id,
      method,
      params,
    }

    return await new Promise<T>((resolve, reject) => {
      const timeout = setTimeout(() => {
        this.pendingRequests.delete(id)
        reject(new Error(`Gateway RPC timeout: ${method}`))
      }, timeoutMs)

      this.pendingRequests.set(id, {
        resolve: resolve as (value: unknown) => void,
        reject,
        timeout,
      })
      this.ws!.send(JSON.stringify(request))
    })
  }

  on(eventName: string, handler: GatewayEventHandler): () => void {
    const handlers = this.eventHandlers.get(eventName) || new Set<GatewayEventHandler>()
    handlers.add(handler)
    this.eventHandlers.set(eventName, handlers)

    return () => {
      const current = this.eventHandlers.get(eventName)
      current?.delete(handler)
      if (current && current.size === 0) {
        this.eventHandlers.delete(eventName)
      }
    }
  }

  updateConfig(config: Partial<GatewayConfig>): void {
    this.config = { ...this.config, ...config }
  }

  private async openSocket(): Promise<void> {
    this.onStatusChange?.('connecting')

    await new Promise<void>((resolve, reject) => {
      const ws = new WebSocket(this.config.url)
      let resolved = false
      let challengeTimer: ReturnType<typeof setTimeout> | null = null

      const cleanup = () => {
        if (challengeTimer) {
          clearTimeout(challengeTimer)
          challengeTimer = null
        }
      }

      const resolveOnce = () => {
        if (!resolved) {
          resolved = true
          cleanup()
          resolve()
        }
      }

      const rejectOnce = (error: Error) => {
        if (!resolved) {
          resolved = true
          cleanup()
          this.onStatusChange?.('error', error.message)
          reject(error)
        }
      }

      ws.onopen = () => {
        challengeTimer = setTimeout(() => {
          rejectOnce(new Error('Gateway connect challenge timeout'))
          ws.close()
        }, DEFAULT_CONNECT_TIMEOUT)
      }

      ws.onmessage = (event) => {
        try {
          const message = JSON.parse(String(event.data)) as Record<string, unknown>

          // Handle connect.challenge event
          if (message.type === 'event' && message.event === 'connect.challenge') {
            // Challenge received, send connect frame (no nonce needed in params)
            this.sendConnectFrame(ws)
            return
          }

          // Handle connect response
          if (message.type === 'res' && typeof message.id === 'string') {
            if (String(message.id).startsWith('connect-')) {
              if (message.ok === false || message.error) {
                const errorInfo = this.parseError(message)
                rejectOnce(new GatewayRequestError(errorInfo))
                return
              }
              this.ws = ws
              this.onStatusChange?.('connected')
              this.backoffMs = 800
              const hello = message.payload as GatewayHello
              this.onHello?.(hello)
              resolveOnce()
              return
            }

            // Handle pending request response
            const pending = this.pendingRequests.get(message.id)
            if (pending) {
              clearTimeout(pending.timeout)
              this.pendingRequests.delete(message.id)
              if (message.ok === false || message.error) {
                const errorInfo = this.parseError(message)
                pending.reject(new GatewayRequestError(errorInfo))
              } else {
                pending.resolve(message.payload)
              }
            }
            return
          }

          // Handle events
          if (message.type === 'event' && typeof message.event === 'string') {
            this.emitEvent(message.event, message.payload)
            return
          }

          // Handle legacy method format
          if (typeof message.method === 'string') {
            this.emitEvent(message.method, message.params)
          }
        } catch (error) {
          rejectOnce(error instanceof Error ? error : new Error(String(error)))
        }
      }

      ws.onerror = () => {
        rejectOnce(new Error('Gateway WebSocket error'))
      }

      ws.onclose = (event) => {
        this.ws = null
        cleanup()
        if (!resolved) {
          rejectOnce(new Error(`Gateway WebSocket closed before connect: ${event.code} ${event.reason}`))
          return
        }
        this.flushPendingRequests(new Error('Gateway connection closed'))
        this.emitEvent('__close__', { code: event.code, reason: event.reason })
        this.onStatusChange?.('disconnected')
        this.scheduleReconnect()
      }
    })
  }

  private sendConnectFrame(ws: WebSocket): void {
    const connectFrame = {
      type: 'req',
      id: `connect-${Date.now()}`,
      method: 'connect',
      params: {
        minProtocol: 3,
        maxProtocol: 3,
        client: {
          id: 'gateway-client',
          displayName: 'SmanWeb',
          version: '0.1.0',
          platform: typeof navigator !== 'undefined' ? navigator.platform : 'unknown',
          mode: 'ui',
        },
        auth: this.config.token ? { token: this.config.token } : undefined,
        caps: [],
        role: 'operator',
        scopes: ['operator.admin'],
      },
    }
    ws.send(JSON.stringify(connectFrame))
  }

  private parseError(message: Record<string, unknown>): GatewayErrorInfo {
    const error = message.error
    if (typeof error === 'object' && error !== null) {
      const err = error as { code?: string; message?: string; details?: unknown }
      return {
        code: err.code || 'UNKNOWN',
        message: err.message || 'Gateway request failed',
        details: err.details,
      }
    }
    return {
      code: 'UNKNOWN',
      message: String(error || 'Gateway request failed'),
    }
  }

  private scheduleReconnect(): void {
    if (this.closed || !this.config.autoReconnect) {
      return
    }
    this.clearReconnectTimer()
    const delay = this.backoffMs
    this.backoffMs = Math.min(this.backoffMs * 1.7, 15000)
    this.reconnectTimer = setTimeout(() => {
      this.connect().catch(() => {
        // Reconnect error is handled by status change
      })
    }, delay)
  }

  private clearReconnectTimer(): void {
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer)
      this.reconnectTimer = null
    }
  }

  private flushPendingRequests(error: Error): void {
    for (const [, request] of this.pendingRequests) {
      clearTimeout(request.timeout)
      request.reject(error)
    }
    this.pendingRequests.clear()
  }

  private emitEvent(eventName: string, payload: unknown): void {
    const handlers = this.eventHandlers.get(eventName)
    if (!handlers) return
    for (const handler of handlers) {
      try {
        handler(payload)
      } catch {
        // Ignore handler failures
      }
    }
  }

  private generateId(): string {
    return `${Date.now()}-${Math.random().toString(16).slice(2)}`
  }
}

// Singleton instance (created lazily)
let gatewayClientInstance: GatewayClient | null = null

export function createGatewayClient(config: GatewayConfig): GatewayClient {
  if (gatewayClientInstance) {
    gatewayClientInstance.updateConfig(config)
    return gatewayClientInstance
  }
  gatewayClientInstance = new GatewayClient(config)
  return gatewayClientInstance
}

export function getGatewayClient(): GatewayClient | null {
  return gatewayClientInstance
}

export function resetGatewayClient(): void {
  if (gatewayClientInstance) {
    gatewayClientInstance.disconnect()
    gatewayClientInstance = null
  }
}
