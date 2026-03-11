// src/core/openclaw/types.ts
/**
 * OpenClaw API Type Definitions
 *
 * These types define the interface between SMAN and OpenClaw Sidecar.
 * OpenClaw runs as a sidecar process and exposes HTTP API.
 */

export interface OpenClawConfig {
  skills?: {
    load?: {
      extraDirs?: string[];
    };
  };
}

export interface ChatMessage {
  role: "user" | "assistant" | "system";
  content: string;
}

export interface ChatRequest {
  messages: ChatMessage[];
  systemPrompt?: string;
  skillFilter?: string[];
}

export interface ChatResponse {
  message: ChatMessage;
  done: boolean;
}

export interface StreamChunk {
  type: "text" | "tool_call" | "tool_result" | "done";
  content?: string;
  toolName?: string;
  toolArgs?: Record<string, unknown>;
  toolResult?: string;
}

export interface SkillMeta {
  name: string;
  description: string;
  filePath: string;
}

export interface LearnRequest {
  conversation: ChatMessage[];
  projectContext: string;
}

export interface LearnResponse {
  updates: Array<{
    type: "habit" | "memory" | "skill";
    path: string;
    content: string;
    reason: string;
  }> | null;
}

export interface OpenClawHealth {
  status: "healthy" | "unhealthy";
  version?: string;
  uptime?: number;
}
