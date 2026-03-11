// src/core/openclaw/client.ts
/**
 * OpenClaw HTTP Client
 *
 * Communicates with the OpenClaw Sidecar process via HTTP.
 * The sidecar runs on localhost:3000 by default.
 */

import type {
  ChatRequest,
  ChatResponse,
  StreamChunk,
  SkillMeta,
  LearnRequest,
  LearnResponse,
  OpenClawHealth,
} from "./types";

const DEFAULT_BASE_URL = "http://127.0.0.1:3000";

export class OpenClawClient {
  private baseUrl: string;

  constructor(baseUrl: string = DEFAULT_BASE_URL) {
    this.baseUrl = baseUrl;
  }

  /**
   * Send a chat message and get a response
   */
  async chat(request: ChatRequest): Promise<ChatResponse> {
    const response = await fetch(`${this.baseUrl}/api/chat`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(request),
    });

    if (!response.ok) {
      throw new Error(`OpenClaw chat failed: ${response.statusText}`);
    }

    return response.json();
  }

  /**
   * Stream chat response for real-time UI updates
   */
  async *chatStream(request: ChatRequest): AsyncGenerator<StreamChunk> {
    const response = await fetch(`${this.baseUrl}/api/chat/stream`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(request),
    });

    if (!response.ok) {
      throw new Error(`OpenClaw stream failed: ${response.statusText}`);
    }

    const reader = response.body?.getReader();
    if (!reader) throw new Error("No response body");

    const decoder = new TextDecoder();
    let buffer = "";

    while (true) {
      const { done, value } = await reader.read();
      if (done) break;

      buffer += decoder.decode(value, { stream: true });

      // Process complete lines
      const lines = buffer.split("\n");
      buffer = lines.pop() || "";

      for (const line of lines) {
        if (line.startsWith("data: ")) {
          try {
            const chunk = JSON.parse(line.slice(6)) as StreamChunk;
            yield chunk;
          } catch {
            // Skip malformed chunks
          }
        }
      }
    }
  }

  /**
   * List available skills for a project
   */
  async listSkills(projectPath: string): Promise<SkillMeta[]> {
    const response = await fetch(
      `${this.baseUrl}/api/skills?project=${encodeURIComponent(projectPath)}`,
    );

    if (!response.ok) {
      throw new Error(`OpenClaw list skills failed: ${response.statusText}`);
    }

    return response.json();
  }

  /**
   * Analyze a conversation for learning opportunities
   */
  async analyzeLearning(request: LearnRequest): Promise<LearnResponse> {
    const response = await fetch(`${this.baseUrl}/api/learn`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(request),
    });

    if (!response.ok) {
      throw new Error(`OpenClaw learn failed: ${response.statusText}`);
    }

    return response.json();
  }

  /**
   * Check if OpenClaw sidecar is running and healthy
   */
  async healthCheck(): Promise<OpenClawHealth> {
    try {
      const response = await fetch(`${this.baseUrl}/health`, {
        method: "GET",
      });

      if (!response.ok) {
        return { status: "unhealthy" };
      }

      return response.json();
    } catch {
      return { status: "unhealthy" };
    }
  }

  /**
   * Simple connectivity test
   */
  async isAvailable(): Promise<boolean> {
    try {
      const response = await fetch(`${this.baseUrl}/health`, {
        method: "GET",
        signal: AbortSignal.timeout(2000),
      });
      return response.ok;
    } catch {
      return false;
    }
  }
}

// Singleton instance
let _client: OpenClawClient | null = null;

export function getOpenClawClient(): OpenClawClient {
  if (!_client) {
    _client = new OpenClawClient();
  }
  return _client;
}

export function setOpenClawBaseUrl(url: string): void {
  _client = new OpenClawClient(url);
}
