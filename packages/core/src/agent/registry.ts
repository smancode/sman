/**
 * SmanCode Core - Agent 注册表实现
 */

import type { AgentExecutor, AgentConfig, AgentRegistry, AgentContext } from "./interface"
import type { AgentInfo, StreamEvent } from "../types"
import { ulid } from "ulid"

/** 简单的 Agent 执行器实现 */
class SimpleAgentExecutor implements AgentExecutor {
  info: AgentInfo
  private config: AgentConfig

  constructor(config: AgentConfig) {
    this.config = config
    this.info = {
      name: config.name,
      description: config.description,
      mode: config.mode,
      model: config.model,
      systemPrompt: config.systemPrompt,
      temperature: config.temperature,
      maxSteps: config.maxSteps,
      tools: config.tools,
      hidden: config.hidden,
    }
  }

  async init(): Promise<void> {
    if (this.config.onInit) {
      await this.config.onInit({} as AgentContext)
    }
  }

  async *execute(input, ctx): AsyncGenerator<StreamEvent, import("./interface").AgentOutput> {
    // 基础实现，由具体 Agent 覆盖
    yield { type: "text_start", partId: ulid() }
    yield { type: "text_delta", partId: "", delta: "Agent executing..." }
    yield { type: "done", finishReason: "stop" }

    return {
      finished: true,
      finishReason: "stop",
      message: {
        id: ulid(),
        sessionId: ctx.sessionId,
        role: "assistant",
        parts: [{ type: "text", text: "Executed" }],
        timestamp: { created: Date.now(), updated: Date.now() },
      },
    }
  }

  async destroy(): Promise<void> {
    // 清理资源
  }
}

/** Agent 注册表实现 */
export class AgentRegistryImpl implements AgentRegistry {
  private agents = new Map<string, AgentExecutor>()

  async register(config: AgentConfig): Promise<void> {
    const executor = new SimpleAgentExecutor(config)
    await executor.init()
    this.agents.set(config.name, executor)
  }

  async unregister(name: string): Promise<void> {
    const executor = this.agents.get(name)
    if (executor) {
      await executor.destroy()
      this.agents.delete(name)
    }
  }

  async get(name: string): Promise<AgentExecutor | undefined> {
    return this.agents.get(name)
  }

  async getAll(): Promise<AgentExecutor[]> {
    return Array.from(this.agents.values())
  }

  async getAvailable(): Promise<AgentInfo[]> {
    return Array.from(this.agents.values())
      .filter(a => !a.info.hidden)
      .map(a => a.info)
  }

  async generate(description: string): Promise<AgentConfig> {
    // 基础实现，返回一个根据描述生成的配置
    return {
      name: `custom-${ulid().toLowerCase()}`,
      description,
      mode: "subagent",
      systemPrompt: `You are an AI assistant specialized in: ${description}`,
    }
  }
}

/** 创建默认 Agent 注册表 */
export function createAgentRegistry(): AgentRegistry {
  return new AgentRegistryImpl()
}
