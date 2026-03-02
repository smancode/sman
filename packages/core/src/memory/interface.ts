/**
 * SmanCode Core - Memory 记忆系统接口定义
 *
 * 记忆系统负责长期知识存储和语义检索
 */

import type { VectorDocument, SearchResult, Timestamp } from "../types"

// ============================================================================
// 向量存储接口
// ============================================================================

/** 向量存储配置 */
export interface VectorStoreConfig {
  /** 维度 */
  dimension: number
  /** 距离度量 */
  metric: "cosine" | "euclidean" | "dot"
  /** 索引类型 */
  indexType: "flat" | "ivf" | "hnsw"
  /** 持久化路径 */
  persistPath?: string
}

/** 向量存储接口 */
export interface VectorStore {
  /** 初始化 */
  init(config: VectorStoreConfig): Promise<void>

  /** 添加文档 */
  upsert(docs: VectorDocument[]): Promise<void>

  /** 删除文档 */
  delete(ids: string[]): Promise<void>

  /** 向量搜索 */
  search(query: number[], options: {
    topK: number
    filter?: Record<string, unknown>
  }): Promise<SearchResult[]>

  /** 获取文档 */
  get(id: string): Promise<VectorDocument | undefined>

  /** 批量获取 */
  getBatch(ids: string[]): Promise<VectorDocument[]>

  /** 统计数量 */
  count(filter?: Record<string, unknown>): Promise<number>

  /** 清空 */
  clear(): Promise<void>

  /** 关闭 */
  close(): Promise<void>
}

// ============================================================================
// Embedding 接口
// ============================================================================

/** Embedding 配置 */
export interface EmbeddingConfig {
  /** Provider 类型 */
  provider: "local" | "openai" | "voyage" | "bge" | "custom"
  /** 模型名称 */
  model?: string
  /** API Key */
  apiKey?: string
  /** Base URL */
  baseUrl?: string
  /** 维度 */
  dimension: number
  /** 批处理大小 */
  batchSize?: number
}

/** Embedding 接口 */
export interface EmbeddingService {
  /** 初始化 */
  init(config: EmbeddingConfig): Promise<void>

  /** 生成向量 */
  embed(texts: string[]): Promise<number[][]>

  /** 生成单个向量 */
  embedOne(text: string): Promise<number[]>

  /** 获取维度 */
  getDimension(): number

  /** 关闭 */
  close(): Promise<void>
}

// ============================================================================
// 记忆管理接口
// ============================================================================

/** 记忆类型 */
export type MemoryType =
  | "conversation"  // 对话记忆
  | "knowledge"     // 知识片段
  | "preference"    // 用户偏好
  | "pattern"       // 行为模式
  | "project"       // 项目知识

/** 记忆条目 */
export interface MemoryEntry {
  id: string
  type: MemoryType
  content: string
  embedding?: number[]
  metadata: {
    source: string
    projectId?: string
    sessionId?: string
    importance: number
    accessCount: number
  }
  timestamp: Timestamp
}

/** 记忆查询选项 */
export interface MemoryQueryOptions {
  /** 查询文本 */
  query?: string
  /** 向量查询 */
  vector?: number[]
  /** 类型过滤 */
  type?: MemoryType | MemoryType[]
  /** 项目过滤 */
  projectId?: string
  /** 时间范围 */
  timeRange?: {
    start?: number
    end?: number
  }
  /** 重要性过滤 */
  minImportance?: number
  /** 返回数量 */
  topK: number
  /** 混合搜索权重（0=纯BM25, 1=纯向量） */
  hybridWeight?: number
  /** 时间衰减系数 */
  timeDecay?: number
}

/** 记忆管理器接口 */
export interface MemoryManager {
  /** 初始化 */
  init(): Promise<void>

  /** 存储记忆 */
  store(entry: Omit<MemoryEntry, "id" | "timestamp">): Promise<MemoryEntry>

  /** 批量存储 */
  storeBatch(entries: Array<Omit<MemoryEntry, "id" | "timestamp">>): Promise<MemoryEntry[]>

  /** 搜索记忆 */
  search(options: MemoryQueryOptions): Promise<MemoryEntry[]>

  /** 获取记忆 */
  get(id: string): Promise<MemoryEntry | undefined>

  /** 更新记忆 */
  update(id: string, updates: Partial<MemoryEntry>): Promise<MemoryEntry>

  /** 删除记忆 */
  delete(id: string): Promise<void>

  /** 增加访问计数 */
  touch(id: string): Promise<void>

  /** 压缩旧记忆 */
  compact(options: {
    maxAge?: number
    maxCount?: number
    minImportance?: number
  }): Promise<number>

  /** 导出记忆 */
  export(options?: {
    type?: MemoryType
    projectId?: string
  }): Promise<MemoryEntry[]>

  /** 导入记忆 */
  import(entries: MemoryEntry[]): Promise<void>

  /** 关闭 */
  close(): Promise<void>
}

// ============================================================================
// 知识注入接口
// ============================================================================

/** 知识来源 */
export interface KnowledgeSource {
  type: "file" | "url" | "text" | "puzzle"
  path?: string
  url?: string
  content?: string
}

/** 知识注入器接口 */
export interface KnowledgeInjector {
  /** 注入知识 */
  inject(source: KnowledgeSource, options?: {
    projectId?: string
    type?: MemoryType
    importance?: number
  }): Promise<MemoryEntry[]>

  /** 从项目注入 */
  injectFromProject(projectPath: string): Promise<{
    files: number
    entries: number
  }>

  /** 从 Markdown 注入 */
  injectFromMarkdown(content: string, metadata?: Record<string, unknown>): Promise<MemoryEntry>

  /** 清除项目知识 */
  clearProject(projectId: string): Promise<void>
}
