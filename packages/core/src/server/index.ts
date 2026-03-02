/**
 * SmanCode Core - API Server
 */

import { Hono } from "hono"
import { cors } from "hono/cors"
import { serve } from "bun"
import { createSessionManager } from "../session/manager"
import { createEventBus } from "../bus"

// Create Hono app
const app = new Hono()

// CORS middleware
app.use("*", cors({
  origin: ["http://localhost:3000", "http://127.0.0.1:3000"],
  allowMethods: ["GET", "POST", "PUT", "DELETE", "OPTIONS"],
  allowHeaders: ["Content-Type", "Authorization"],
}))

// Initialize services
const sessionManager = createSessionManager()
const eventBus = createEventBus()

// WebSocket connections
const wsClients = new Set<WebSocket>()
const sessionSubscriptions = new Map<string, Set<WebSocket>>()

// ============================================================================
// REST API Routes
// ============================================================================

// Create session
app.post("/api/sessions", async (c) => {
  const body = await c.req.json()
  const session = await sessionManager.create(body.projectId || "default", body.parentId)
  return c.json({ session })
})

// Get session
app.get("/api/sessions/:id", async (c) => {
  const session = await sessionManager.get(c.req.param("id"))
  if (!session) return c.json({ error: "Session not found" }, 404)
  const messages = await sessionManager.getHistory(session.id)
  return c.json({ session, messages })
})

// List sessions
app.get("/api/sessions", async (c) => {
  const projectId = c.req.query("projectId") || "default"
  const limit = parseInt(c.req.query("limit") || "20")
  const sessions = await sessionManager.list(projectId)
  return c.json({ sessions: sessions.slice(0, limit), total: sessions.length })
})

// Delete session
app.delete("/api/sessions/:id", async (c) => {
  await sessionManager.delete(c.req.param("id"))
  return c.json({ success: true })
})

// Get messages
app.get("/api/sessions/:id/messages", async (c) => {
  const limit = parseInt(c.req.query("limit") || "100")
  const messages = await sessionManager.getHistory(c.req.param("id"))
  return c.json({ messages: messages.slice(-limit) })
})

// Append message (for non-WebSocket clients)
app.post("/api/sessions/:id/messages", async (c) => {
  const body = await c.req.json()
  const message = await sessionManager.appendMessage(c.req.param("id"), {
    role: body.role || "user",
    parts: body.parts || [{ type: "text", text: body.message }],
  })
  return c.json({ message })
})

// Health check
app.get("/api/health", (c) => {
  return c.json({ status: "ok", timestamp: Date.now() })
})

// ============================================================================
// WebSocket Server
// ============================================================================

const server = serve({
  port: 4000,
  hostname: "localhost",

  async fetch(req, server) {
    const url = new URL(req.url)

    // WebSocket upgrade
    if (url.pathname === "/ws") {
      const success = server.upgrade(req)
      if (success) return undefined
      return new Response("WebSocket upgrade failed", { status: 400 })
    }

    // Handle HTTP requests with Hono
    return app.fetch(req, undefined, {
      // Pass execution context if needed
    } as any)
  },

  websocket: {
    open(ws) {
      wsClients.add(ws as any)
      console.log("WebSocket client connected")
    },

    close(ws) {
      wsClients.delete(ws as any)
      // Clean up subscriptions
      for (const [sessionId, clients] of sessionSubscriptions) {
        clients.delete(ws as any)
        if (clients.size === 0) {
          sessionSubscriptions.delete(sessionId)
        }
      }
      console.log("WebSocket client disconnected")
    },

    async message(ws, message) {
      try {
        const data = JSON.parse(message.toString())
        handleWebSocketMessage(ws as any, data)
      } catch (error) {
        console.error("Failed to handle WebSocket message:", error)
        ws.send(JSON.stringify({ type: "error", message: "Invalid message format" }))
      }
    },
  },
})

// ============================================================================
// WebSocket Message Handler
// ============================================================================

async function handleWebSocketMessage(ws: WebSocket, data: any) {
  switch (data.type) {
    case "ping":
      ws.send(JSON.stringify({ type: "pong" }))
      break

    case "subscribe": {
      const sessionId = data.sessionId
      if (!sessionSubscriptions.has(sessionId)) {
        sessionSubscriptions.set(sessionId, new Set())
      }
      sessionSubscriptions.get(sessionId)!.add(ws)
      ws.send(JSON.stringify({ type: "subscribed", sessionId }))
      break
    }

    case "unsubscribe": {
      const sessionId = data.sessionId
      sessionSubscriptions.get(sessionId)?.delete(ws)
      ws.send(JSON.stringify({ type: "unsubscribed", sessionId }))
      break
    }

    case "send_message": {
      const sessionId = data.sessionId
      const payload = data.payload

      // Add user message
      await sessionManager.appendMessage(sessionId, {
        role: "user",
        parts: [{ type: "text", text: payload.message }],
      })

      // Broadcast to session subscribers
      broadcastToSession(sessionId, {
        type: "stream_event",
        sessionId,
        event: { type: "step_start", part: { type: "step_start", stepId: "1" } },
      })

      // Simulate AI response (replace with actual LLM call)
      setTimeout(() => {
        broadcastToSession(sessionId, {
          type: "stream_event",
          sessionId,
          event: { type: "text_start", partId: "response-1" },
        })

        const response = "I received your message. This is a placeholder response."
        let i = 0
        const interval = setInterval(() => {
          if (i < response.length) {
            broadcastToSession(sessionId, {
              type: "stream_event",
              sessionId,
              event: { type: "text_delta", partId: "response-1", delta: response[i] },
            })
            i++
          } else {
            clearInterval(interval)
            broadcastToSession(sessionId, {
              type: "stream_event",
              sessionId,
              event: { type: "text_end", partId: "response-1" },
            })
            broadcastToSession(sessionId, {
              type: "stream_event",
              sessionId,
              event: { type: "done", finishReason: "stop" },
            })
          }
        }, 30)
      }, 500)
      break
    }

    case "abort": {
      // Handle abort
      ws.send(JSON.stringify({ type: "aborted", sessionId: data.sessionId }))
      break
    }

    case "permission_response": {
      // Handle permission response
      console.log("Permission response:", data.requestId, data.allow)
      break
    }

    default:
      ws.send(JSON.stringify({ type: "error", message: `Unknown message type: ${data.type}` }))
  }
}

// ============================================================================
// Helper Functions
// ============================================================================

function broadcastToSession(sessionId: string, message: any) {
  const clients = sessionSubscriptions.get(sessionId)
  if (clients) {
    const data = JSON.stringify(message)
    for (const client of clients) {
      if (client.readyState === WebSocket.OPEN) {
        client.send(data)
      }
    }
  }
}

console.log(`🚀 SmanCode API Server running at http://localhost:4000`)
console.log(`📡 WebSocket endpoint: ws://localhost:4000/ws`)

export { app, server }
