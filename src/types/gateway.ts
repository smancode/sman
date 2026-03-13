// src/types/gateway.ts

export interface GatewayConfig {
  url: string
  token?: string
  autoReconnect?: boolean
  reconnectInterval?: number
}

export interface GatewayHello {
  version: string
  features?: {
    methods?: string[]
  }
}

export interface GatewayStatus {
  state: 'connecting' | 'connected' | 'disconnected' | 'error'
  error?: string
  lastConnected?: number
}

export type GatewayEventHandler = (payload: unknown) => void

export interface GatewayRequest {
  type: 'req'
  id: string
  method: string
  params?: unknown
}

export interface GatewayResponse {
  type: 'res'
  id: string
  ok: boolean
  payload?: unknown
  error?: { code: string; message: string }
}

export interface GatewayEvent {
  type: 'event'
  event: string
  payload: unknown
  seq: number
}

export interface GatewayErrorInfo {
  code: string
  message: string
  details?: unknown
}

export class GatewayRequestError extends Error {
  readonly gatewayCode: string
  readonly details?: unknown

  constructor(error: GatewayErrorInfo) {
    super(error.message)
    this.name = 'GatewayRequestError'
    this.gatewayCode = error.code
    this.details = error.details
  }
}
