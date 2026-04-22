/**
 * Knowledge Extractor — extracts business knowledge, development conventions,
 * and technical knowledge from conversation history.
 *
 * Follows the same fire-and-forget pattern as UserProfileManager:
 * - recordTurn() called after each conversation turn, marks workspace dirty
 * - 10-minute interval + idle check before running LLM extraction
 * - Serial queue to prevent concurrent extractions
 *
 * Knowledge is stored per-user under {workspace}/.sman/knowledge/:
 * - business-{username}.md
 * - conventions-{username}.md
 * - technical-{username}.md
 *
 * Each knowledge entry is wrapped in hash markers for deduplication:
 * <!-- hash: abc123 -->
 * ... content ...
 * <!-- end: abc123 -->
 */

import fs from 'fs';
import path from 'path';
import os from 'os';
import crypto from 'crypto';
import { createLogger, type Logger } from './utils/logger.js';
import type { SessionStore, Message } from './session-store.js';
import type { KnowledgeExtractorStore } from './knowledge-extractor-store.js';

const KNOWLEDGE_CATEGORIES = ['business', 'conventions', 'technical'] as const;
type KnowledgeCategory = typeof KNOWLEDGE_CATEGORIES[number];

const CATEGORY_META: Record<KnowledgeCategory, { label: string; description: string }> = {
  business: {
    label: 'Business Knowledge',
    description: '业务知识：产品需求、用户流程、业务规则、领域术语',
  },
  conventions: {
    label: 'Development Conventions',
    description: '开发规范：编码约定、命名规则、架构决策、项目特定规则',
  },
  technical: {
    label: 'Technical Knowledge',
    description: '技术知识：API 细节、数据库 schema、三方集成、基础设施、算法',
  },
};

export class KnowledgeExtractor {
  private static readonly UPDATE_INTERVAL_MS = 10 * 60 * 1000; // 10 minutes
  private static readonly MAX_MESSAGES_PER_EXTRACTION = 50;
  private static readonly MAX_MESSAGE_LENGTH = 1000;
  private static readonly MAX_MESSAGES_CHARS = 30000;
  private config: import('./types.js').SmanConfig | null = null;
  private log: Logger;
  private updateQueue: Promise<void> = Promise.resolve();
  private lastUpdateTime: number = 0;
  private dirtyWorkspaces = new Set<string>();
  private getLastActivity: () => number = () => 0;
  private static readonly IDLE_THRESHOLD_MS = 3 * 60 * 1000; // 3 minutes
  private username: string;

  constructor(
    private store: SessionStore,
    private extractorStore: KnowledgeExtractorStore,
    config?: import('./types.js').SmanConfig,
  ) {
    this.log = createLogger('KnowledgeExtractor');
    this.config = config ?? null;
    this.username = os.userInfo().username;
  }

  updateConfig(config: import('./types.js').SmanConfig): void {
    this.config = config;
  }

  setActivityTimestampProvider(getLastActivity: () => number): void {
    this.getLastActivity = getLastActivity;
  }

  /**
   * Called after each conversation turn completes. Marks the workspace as
   * needing extraction. Actual extraction runs on 10-min idle cadence.
   */
  recordTurn(workspace: string): void {
    this.dirtyWorkspaces.add(workspace);
    const now = Date.now();
    if (now - this.lastUpdateTime < KnowledgeExtractor.UPDATE_INTERVAL_MS) {
      return;
    }
    this.tryFlush();
  }

  /**
   * Attempt to flush all dirty workspaces. Waits for idle, then extracts
   * knowledge from all sessions with new messages.
   */
  private tryFlush(): void {
    const workspaces = [...this.dirtyWorkspaces];
    if (workspaces.length === 0) return;
    this.dirtyWorkspaces.clear();
    this.lastUpdateTime = Date.now();

    this.updateQueue = this.updateQueue.then(async () => {
      // Wait until 3 minutes since last SDK activity
      while (true) {
        const last = this.getLastActivity();
        if (last === 0 || Date.now() - last >= KnowledgeExtractor.IDLE_THRESHOLD_MS) break;
        await new Promise(r => setTimeout(r, 2000));
      }
      try {
        for (const workspace of workspaces) {
          await this.extractForWorkspace(workspace);
        }
      } catch (err) {
        this.log.warn('Knowledge extraction skipped', { error: String(err) });
      }
    });
  }

  /**
   * Extract knowledge from all sessions in a workspace that have
   * messages beyond the last extraction point.
   */
  private async extractForWorkspace(workspace: string): Promise<void> {
    if (!this.config?.llm?.apiKey) {
      this.log.warn('No API key configured, skipping knowledge extraction');
      return;
    }

    const sessions = this.store.getSessionsByWorkspace(workspace);
    if (sessions.length === 0) return;

    // Collect new messages from all sessions, respecting extraction progress
    let allNewMessages: Array<{ sessionId: string; role: string; content: string }> = [];
    const progressUpdates: Array<{ sessionId: string; lastId: number }> = [];

    for (const session of sessions) {
      const progress = this.extractorStore.getProgress(workspace, session.id);
      const lastId = progress?.lastExtractedMessageId ?? 0;
      const messages = this.store.getMessagesAfterId(
        session.id,
        lastId,
        KnowledgeExtractor.MAX_MESSAGES_PER_EXTRACTION,
      );

      if (messages.length === 0) continue;

      for (const msg of messages) {
        allNewMessages.push({
          sessionId: session.id,
          role: msg.role,
          content: this.truncateContent(msg.content),
        });
      }
      progressUpdates.push({
        sessionId: session.id,
        lastId: messages[messages.length - 1].id,
      });
    }

    if (allNewMessages.length === 0) {
      this.log.info(`No new messages to extract for workspace ${workspace}`);
      return;
    }

    // Truncate total conversation text to fit token budget
    allNewMessages = this.truncateMessages(allNewMessages);

    // Read existing knowledge files
    const knowledgeDir = this.getKnowledgeDir(workspace);
    const existingKnowledge: Record<string, string> = {};
    for (const category of KNOWLEDGE_CATEGORIES) {
      const filePath = this.getKnowledgeFilePath(workspace, category);
      if (fs.existsSync(filePath)) {
        existingKnowledge[category] = fs.readFileSync(filePath, 'utf-8');
      }
    }

    // Call LLM to extract knowledge
    const extracted = await this.callLLMForExtraction(allNewMessages, existingKnowledge);
    if (!extracted) {
      this.log.warn('LLM returned no knowledge, skipping');
      // Still update progress so we don't reprocess these messages
      for (const { sessionId, lastId } of progressUpdates) {
        this.extractorStore.setProgress(workspace, sessionId, lastId);
      }
      return;
    }

    // Write knowledge files
    fs.mkdirSync(knowledgeDir, { recursive: true });
    for (const category of KNOWLEDGE_CATEGORIES) {
      const content = extracted[category];
      if (content && content.trim()) {
        const filePath = this.getKnowledgeFilePath(workspace, category);
        fs.writeFileSync(filePath, content, 'utf-8');
      }
    }

    // Update extraction progress
    for (const { sessionId, lastId } of progressUpdates) {
      this.extractorStore.setProgress(workspace, sessionId, lastId);
    }

    this.log.info(`Knowledge extracted for workspace ${workspace} (${allNewMessages.length} messages processed)`);
  }

  private getKnowledgeDir(workspace: string): string {
    return path.join(workspace, '.sman', 'knowledge');
  }

  private getKnowledgeFilePath(workspace: string, category: KnowledgeCategory): string {
    return path.join(this.getKnowledgeDir(workspace), `${category}-${this.username}.md`);
  }

  private truncateContent(text: string): string {
    if (text.length <= KnowledgeExtractor.MAX_MESSAGE_LENGTH) return text;
    return text.slice(0, KnowledgeExtractor.MAX_MESSAGE_LENGTH) + '...';
  }

  private truncateMessages(
    messages: Array<{ sessionId: string; role: string; content: string }>,
  ): Array<{ sessionId: string; role: string; content: string }> {
    let totalChars = 0;
    const result: typeof messages = [];
    // Take from the end (most recent) to stay within budget
    for (let i = messages.length - 1; i >= 0; i--) {
      totalChars += messages[i].content.length;
      if (totalChars > KnowledgeExtractor.MAX_MESSAGES_CHARS) break;
      result.unshift(messages[i]);
    }
    return result;
  }

  /**
   * Call LLM to extract knowledge from conversations and merge with existing knowledge.
   * Each knowledge entry is wrapped in hash markers for deduplication.
   */
  private async callLLMForExtraction(
    messages: Array<{ sessionId: string; role: string; content: string }>,
    existingKnowledge: Record<string, string>,
  ): Promise<Record<string, string> | null> {
    const config = this.config!;
    const model = config.llm.profileModel || config.llm.model;
    const baseUrl = (config.llm.baseUrl || 'https://api.anthropic.com').replace(/\/$/, '');

    const currentISOTime = new Date().toISOString();
    const systemPrompt = `你是一个知识提取器。目标：让知识文件始终反映项目当前的真实状态。

## 当前时间
${currentISOTime}

## 你的目标

每个知识文件必须是**活的**——反映项目此刻的真实情况，不是历史记录的堆砌。

具体来说：
- 同一问题如果对话中出现了更新的答案（方案 A → 方案 B），文件里只保留 B，删除 A
- 已有条目如果对话中没有推翻它，就原样保留
- 已有条目如果对话中证实已过时或被推翻，就删除
- 文件始终是当前最新的、准确的知识快照

## 质量门槛

只记录项目特有的、非显而易见的、对未来开发有指导意义的知识：
- ✅ 业务规则和决策（为什么要这样做）
- ✅ 踩过的坑和非 trivial 的解决方案
- ✅ 团队共识和非显而易见的约束

禁止记录：
- ❌ 通用技术知识、框架用法、工具常识
- ❌ 调试过程、LLM/SDK 机制、看代码就知道的信息
- ❌ 一过性且无参考价值的问题

宁缺毋滥。对话中没有真正有价值的知识时，原样返回已有内容。

## 三个类别

1. **business**: 产品需求、用户流程、业务规则、领域术语、权限规则
2. **conventions**: 编码约定、命名规则、架构决策、项目特定规则
3. **technical**: API 细节、数据库 schema、三方集成、基础设施、算法

## 输出格式

为三个类别分别输出完整的 markdown 文件内容：

---BUSINESS_START---
（business 类别的完整 markdown 文件内容）
---BUSINESS_END---
---CONVENTIONS_START---
（conventions 类别的完整 markdown 文件内容）
---CONVENTIONS_END---
---TECHNICAL_START---
（technical 类别的完整 markdown 文件内容）
---TECHNICAL_END---

每个文件格式：

\`\`\`
# {Category Label} — ${this.username}

> Last extracted: ${currentISOTime}

## 知识点标题
<!-- hash: {6位hex} -->
- 具体内容
<!-- end: {6位hex} -->
\`\`\`

- 每条知识用 \`<!-- hash: xxx --> ... <!-- end: xxx -->\` 包裹，开闭 hash 一致，6 位 hex
- 新条目用新 hash；更新旧条目时换新 hash
- 每条知识不超过 5 行，文件不超过 100 行（超出时合并或删最不重要的）`;

    const conversationText = messages.map((m, i) =>
      `[${m.role}] ${m.content}`
    ).join('\n\n');

    // Build existing knowledge context
    const existingParts: string[] = [];
    for (const category of KNOWLEDGE_CATEGORIES) {
      const content = existingKnowledge[category];
      if (content && content.trim()) {
        existingParts.push(`### 已有 ${category} 知识\n${content}`);
      } else {
        existingParts.push(`### 已有 ${category} 知识\n（空）`);
      }
    }

    const userPrompt = `## 待分析的对话（共 ${messages.length} 条）
${conversationText}

## 已有知识文件
${existingParts.join('\n\n')}

## 任务
分析对话，提取新知识，输出三个完整的知识文件。`;

    try {
      const response = await fetch(`${baseUrl}/v1/messages`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'x-api-key': config.llm.apiKey,
          'anthropic-version': '2023-06-01',
          'anthropic-dangerous-direct-browser-access': 'true',
        },
        body: JSON.stringify({
          model,
          max_tokens: 4096,
          system: systemPrompt,
          messages: [{ role: 'user', content: userPrompt }],
        }),
      });

      if (!response.ok) {
        const errorBody = await response.text();
        throw new Error(`LLM API error: ${response.status} ${errorBody}`);
      }

      const data = await response.json() as any;
      const text = data.content?.[0]?.text;
      if (!text) throw new Error('Empty response from LLM');

      return this.parseExtractedKnowledge(text);
    } catch (err) {
      this.log.warn('Knowledge extraction LLM call failed', { error: String(err) });
      return null;
    }
  }

  /**
   * Parse the LLM response into three knowledge files.
   */
  private parseExtractedKnowledge(text: string): Record<string, string> | null {
    const result: Record<string, string> = {};

    const categoryMap: Record<string, KnowledgeCategory> = {
      'BUSINESS': 'business',
      'CONVENTIONS': 'conventions',
      'TECHNICAL': 'technical',
    };

    for (const [marker, category] of Object.entries(categoryMap)) {
      const startRegex = new RegExp(`---${marker}_START---`, 's');
      const endRegex = new RegExp(`---${marker}_END---`, 's');

      const startMatch = text.match(startRegex);
      const endMatch = text.match(endRegex);

      if (startMatch && endMatch) {
        const startIndex = (startMatch.index ?? 0) + startMatch[0].length;
        const endIndex = endMatch.index ?? text.length;
        result[category] = text.slice(startIndex, endIndex).trim();
      }
    }

    // Validate at least one category was extracted
    if (Object.keys(result).length === 0) {
      return null;
    }

    // For missing categories, return empty (will not overwrite existing files)
    for (const category of KNOWLEDGE_CATEGORIES) {
      if (!result[category]) {
        result[category] = '';
      }
    }

    return result;
  }
}
