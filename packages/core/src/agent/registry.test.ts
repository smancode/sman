/**
 * Agent Registry Tests
 */

import { describe, test, expect, beforeEach } from "bun:test"
import { AgentRegistryImpl, createAgentRegistry } from "./registry"
import type { AgentConfig } from "./interface"

describe("AgentRegistry", () => {
  let registry: ReturnType<typeof createAgentRegistry>

  beforeEach(() => {
    registry = createAgentRegistry()
  })

  describe("register", () => {
    test("should register an agent", async () => {
      const config: AgentConfig = {
        name: "test-agent",
        mode: "primary",
        description: "Test agent",
      }

      await registry.register(config)

      const agent = await registry.get("test-agent")
      expect(agent).toBeDefined()
      expect(agent?.info.name).toBe("test-agent")
      expect(agent?.info.mode).toBe("primary")
    })

    test("should allow registering multiple agents", async () => {
      await registry.register({ name: "agent-1", mode: "primary" })
      await registry.register({ name: "agent-2", mode: "subagent" })

      const agents = await registry.getAll()
      expect(agents).toHaveLength(2)
    })
  })

  describe("unregister", () => {
    test("should unregister an agent", async () => {
      await registry.register({ name: "test-agent", mode: "primary" })
      await registry.unregister("test-agent")

      const agent = await registry.get("test-agent")
      expect(agent).toBeUndefined()
    })

    test("should not throw when unregistering non-existent agent", async () => {
      await expect(registry.unregister("non-existent")).resolves.toBeUndefined()
    })
  })

  describe("get", () => {
    test("should return undefined for non-existent agent", async () => {
      const agent = await registry.get("non-existent")
      expect(agent).toBeUndefined()
    })
  })

  describe("getAvailable", () => {
    test("should return only non-hidden agents", async () => {
      await registry.register({ name: "visible", mode: "primary" })
      await registry.register({ name: "hidden", mode: "primary", hidden: true })

      const available = await registry.getAvailable()
      expect(available).toHaveLength(1)
      expect(available[0].name).toBe("visible")
    })
  })

  describe("generate", () => {
    test("should generate agent config from description", async () => {
      const config = await registry.generate("An agent that helps with testing")

      expect(config.name).toBeDefined()
      expect(config.description).toBe("An agent that helps with testing")
      expect(config.mode).toBe("subagent")
      expect(config.systemPrompt).toContain("testing")
    })
  })
})
