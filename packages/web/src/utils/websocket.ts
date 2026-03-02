/**
 * WebSocket Client for real-time communication
 */

import type { WSServerMessage, WSClientMessage, StreamEvent } from "@smancode/core"

export type MessageHandler = (message: WSServerMessage) => void

export class WebSocketClient {
  private ws: WebSocket | null = null
  private url: string
  private handlers: Set<MessageHandler> = new Set()
  private connectHandlers: Set<() => void> = new Set()
  private disconnectHandlers: Set<() => void> = new Set()
  private reconnectAttempts = 0
  private maxReconnectAttempts = 5
  private reconnectDelay = 1000

  constructor(url: string) {
    this.url = url
    this.connect()
  }

  private connect(): void {
    try {
      this.ws = new WebSocket(this.url)

      this.ws.onopen = () => {
        console.log("WebSocket connected")
        this.reconnectAttempts = 0
        this.connectHandlers.forEach(h => h())
      }

      this.ws.onmessage = (event) => {
        try {
          const message = JSON.parse(event.data) as WSServerMessage
          this.handlers.forEach(h => h(message))
        } catch (error) {
          console.error("Failed to parse WebSocket message:", error)
        }
      }

      this.ws.onclose = () => {
        console.log("WebSocket disconnected")
        this.disconnectHandlers.forEach(h => h())
        this.attemptReconnect()
      }

      this.ws.onerror = (error) => {
        console.error("WebSocket error:", error)
      }
    } catch (error) {
      console.error("Failed to create WebSocket:", error)
      this.attemptReconnect()
    }
  }

  private attemptReconnect(): void {
    if (this.reconnectAttempts < this.maxReconnectAttempts) {
      this.reconnectAttempts++
      setTimeout(() => this.connect(), this.reconnectDelay * this.reconnectAttempts)
    }
  }

  send(message: WSClientMessage): void {
    if (this.ws?.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify(message))
    } else {
      console.warn("WebSocket not connected, message not sent")
    }
  }

  subscribe(sessionId: string): void {
    this.send({ type: "subscribe", sessionId })
  }

  unsubscribe(sessionId: string): void {
    this.send({ type: "unsubscribe", sessionId })
  }

  sendMessage(sessionId: string, message: string, attachments?: Array<{
    name: string
    mimeType: string
    data: string
  }>): void {
    this.send({
      type: "send_message",
      sessionId,
      payload: { message, attachments },
    })
  }

  abort(sessionId: string): void {
    this.send({ type: "abort", sessionId })
  }

  confirmPermission(requestId: string, allow: boolean): void {
    this.send({ type: "permission_response", requestId, allow })
  }

  onMessage(handler: MessageHandler): () => void {
    this.handlers.add(handler)
    return () => this.handlers.delete(handler)
  }

  onConnect(handler: () => void): () => void {
    this.connectHandlers.add(handler)
    return () => this.connectHandlers.delete(handler)
  }

  onDisconnect(handler: () => void): () => void {
    this.disconnectHandlers.add(handler)
    return () => this.disconnectHandlers.delete(handler)
  }

  close(): void {
    if (this.ws) {
      this.ws.close()
      this.ws = null
    }
    this.handlers.clear()
    this.connectHandlers.clear()
    this.disconnectHandlers.clear()
  }

  get readyState(): number {
    return this.ws?.readyState ?? WebSocket.CLOSED
  }
}
