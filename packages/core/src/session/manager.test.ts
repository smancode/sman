/**
 * Session Manager Tests
 */

import { describe, test, expect, beforeEach } from "bun:test"
import { SessionManagerImpl, createSessionManager } from "./manager"
import type { Session, Message } from "../types"

describe("SessionManager", () => {
  let manager: ReturnType<typeof createSessionManager>

  beforeEach(() => {
    manager = createSessionManager()
  })

  describe("create", () => {
    test("should create a new session", async () => {
      const session = await manager.create("project-1")

      expect(session.id).toBeDefined()
      expect(session.projectId).toBe("project-1")
      expect(session.status).toBe("active")
      expect(session.title).toBeDefined()
    })

    test("should create session with parent", async () => {
      const parent = await manager.create("project-1")
      const child = await manager.create("project-1", parent.id)

      expect(child.parentId).toBe(parent.id)
    })
  })

  describe("get", () => {
    test("should return undefined for non-existent session", async () => {
      const session = await manager.get("non-existent")
      expect(session).toBeUndefined()
    })

    test("should return created session", async () => {
      const created = await manager.create("project-1")
      const session = await manager.get(created.id)

      expect(session).toEqual(created)
    })
  })

  describe("update", () => {
    test("should update session", async () => {
      const session = await manager.create("project-1")
      const updated = await manager.update(session.id, { title: "New Title" })

      expect(updated.title).toBe("New Title")
    })

    test("should throw for non-existent session", async () => {
      await expect(manager.update("non-existent", { title: "Test" })).rejects.toThrow()
    })
  })

  describe("delete", () => {
    test("should delete session", async () => {
      const session = await manager.create("project-1")
      await manager.delete(session.id)

      const result = await manager.get(session.id)
      expect(result).toBeUndefined()
    })
  })

  describe("list", () => {
    test("should list sessions by project", async () => {
      await manager.create("project-1")
      await manager.create("project-1")
      await manager.create("project-2")

      const sessions = await manager.list("project-1")
      expect(sessions).toHaveLength(2)
    })
  })

  describe("appendMessage", () => {
    test("should append message to session", async () => {
      const session = await manager.create("project-1")
      const message = await manager.appendMessage(session.id, {
        role: "user",
        parts: [{ type: "text", text: "Hello" }],
      })

      expect(message.id).toBeDefined()
      expect(message.sessionId).toBe(session.id)
      expect(message.role).toBe("user")
    })
  })

  describe("getHistory", () => {
    test("should return message history", async () => {
      const session = await manager.create("project-1")
      await manager.appendMessage(session.id, {
        role: "user",
        parts: [{ type: "text", text: "Hello" }],
      })
      await manager.appendMessage(session.id, {
        role: "assistant",
        parts: [{ type: "text", text: "Hi there!" }],
      })

      const history = await manager.getHistory(session.id)
      expect(history).toHaveLength(2)
    })
  })

  describe("subscribe", () => {
    test("should emit session.created event", async () => {
      const events: any[] = []
      manager.subscribe((event) => events.push(event))

      await manager.create("project-1")

      expect(events).toHaveLength(1)
      expect(events[0].type).toBe("session.created")
    })

    test("should unsubscribe when calling returned function", async () => {
      const events: any[] = []
      const unsubscribe = manager.subscribe((event) => events.push(event))

      await manager.create("project-1")
      unsubscribe()
      await manager.create("project-1")

      expect(events).toHaveLength(1)
    })
  })
})
