/**
 * Event Bus Tests
 */

import { describe, test, expect, beforeEach } from "bun:test"
import { EventBusImpl, createEventBus } from "./index"

describe("EventBus", () => {
  let bus: ReturnType<typeof createEventBus>

  beforeEach(() => {
    bus = createEventBus()
  })

  describe("publish/subscribe", () => {
    test("should publish and receive events", async () => {
      const received: any[] = []
      bus.subscribe("test.event", (event) => {
        received.push(event)
      })

      await bus.publish("test.event", { data: "test" })

      expect(received).toHaveLength(1)
      expect(received[0].type).toBe("test.event")
      expect(received[0].payload).toEqual({ data: "test" })
    })

    test("should support multiple listeners", async () => {
      const results: string[] = []
      bus.subscribe("test", () => results.push("first"))
      bus.subscribe("test", () => results.push("second"))

      await bus.publish("test", {})

      expect(results).toEqual(["first", "second"])
    })

    test("should support once option", async () => {
      const count = { value: 0 }
      bus.subscribe("test", () => count.value++, { once: true })

      await bus.publish("test", {})
      await bus.publish("test", {})

      expect(count.value).toBe(1)
    })

    test("should support priority ordering", async () => {
      const order: number[] = []
      bus.subscribe("test", () => order.push(1), { priority: 1 })
      bus.subscribe("test", () => order.push(2), { priority: 10 })
      bus.subscribe("test", () => order.push(3), { priority: 5 })

      await bus.publish("test", {})

      expect(order).toEqual([2, 3, 1])
    })
  })

  describe("unsubscribe", () => {
    test("should unsubscribe listener", async () => {
      const received: any[] = []
      const listener = (event: any) => received.push(event)

      bus.subscribe("test", listener)
      await bus.publish("test", { data: 1 })

      bus.unsubscribe("test", listener)
      await bus.publish("test", { data: 2 })

      expect(received).toHaveLength(1)
      expect(received[0].payload.data).toBe(1)
    })
  })

  describe("subscribeAll", () => {
    test("should receive all events", async () => {
      const received: any[] = []
      bus.subscribeAll((event) => received.push(event))

      await bus.publish("event.a", {})
      await bus.publish("event.b", {})

      expect(received).toHaveLength(2)
    })
  })

  describe("hasListeners", () => {
    test("should return true when listeners exist", () => {
      bus.subscribe("test", () => {})
      expect(bus.hasListeners("test")).toBe(true)
    })

    test("should return false when no listeners", () => {
      expect(bus.hasListeners("test")).toBe(false)
    })
  })

  describe("listenerCount", () => {
    test("should return correct count", () => {
      bus.subscribe("test", () => {})
      bus.subscribe("test", () => {})
      bus.subscribe("test", () => {}, { once: true })

      expect(bus.listenerCount("test")).toBe(3)
    })
  })

  describe("clear", () => {
    test("should clear all listeners", () => {
      bus.subscribe("test", () => {})
      bus.subscribe("other", () => {})
      bus.subscribeAll(() => {})

      bus.clear()

      expect(bus.hasListeners("test")).toBe(false)
      expect(bus.hasListeners("other")).toBe(false)
    })
  })

  describe("metadata", () => {
    test("should include metadata in events", async () => {
      const received: any[] = []
      bus.subscribe("test", (event) => received.push(event))

      await bus.publish("test", { data: "test" }, { source: "unit-test" })

      expect(received[0].metadata).toEqual({ source: "unit-test" })
    })
  })

  describe("error handling", () => {
    test("should not break chain on listener error", async () => {
      const results: string[] = []
      bus.subscribe("test", () => {
        throw new Error("Listener error")
      })
      bus.subscribe("test", () => results.push("second"))

      await bus.publish("test", {})

      expect(results).toEqual(["second"])
    })
  })
})
