/**
 * SmanCode Core - Storage 存储接口定义
 *
 * 统一的存储抽象层
 */

// ============================================================================
// 键值存储接口
// ============================================================================

/** 键值存储接口 */
export interface KeyValueStore {
  /** 获取 */
  get<T = unknown>(key: string): Promise<T | undefined>

  /** 设置 */
  set<T = unknown>(key: string, value: T, options?: {
    ttl?: number
  }): Promise<void>

  /** 删除 */
  delete(key: string): Promise<void>

  /** 批量获取 */
  getBatch<T = unknown>(keys: string[]): Promise<Map<string, T>>

  /** 批量设置 */
  setBatch<T = unknown>(entries: Array<{ key: string; value: T }>, options?: {
    ttl?: number
  }): Promise<void>

  /** 检查存在 */
  has(key: string): Promise<boolean>

  /** 列出键 */
  keys(pattern?: string): Promise<string[]>

  /** 清空 */
  clear(): Promise<void>
}

// ============================================================================
// 文档存储接口
// ============================================================================

/** 查询条件 */
export interface QueryFilter {
  field: string
  operator: "eq" | "neq" | "gt" | "gte" | "lt" | "lte" | "in" | "nin" | "like" | "contains"
  value: unknown
}

/** 排序条件 */
export interface QuerySort {
  field: string
  direction: "asc" | "desc"
}

/** 查询选项 */
export interface QueryOptions {
  filter?: QueryFilter[]
  sort?: QuerySort[]
  limit?: number
  offset?: number
}

/** 文档存储接口 */
export interface DocumentStore<T extends { id: string }> {
  /** 创建 */
  create(doc: Omit<T, "id">): Promise<T>

  /** 获取 */
  get(id: string): Promise<T | undefined>

  /** 更新 */
  update(id: string, updates: Partial<T>): Promise<T>

  /** 删除 */
  delete(id: string): Promise<void>

  /** 查询 */
  query(options?: QueryOptions): Promise<T[]>

  /** 计数 */
  count(options?: Pick<QueryOptions, "filter">): Promise<number>

  /** 批量创建 */
  createBatch(docs: Array<Omit<T, "id">>): Promise<T[]>

  /** 批量删除 */
  deleteBatch(ids: string[]): Promise<void>

  /** 事务 */
  transaction<R>(fn: (tx: DocumentStore<T>) => Promise<R>): Promise<R>
}

// ============================================================================
// 文件存储接口
// ============================================================================

/** 文件信息 */
export interface FileInfo {
  path: string
  size: number
  mimeType: string
  lastModified: number
  checksum?: string
}

/** 文件存储接口 */
export interface FileStore {
  /** 读取文件 */
  read(path: string): Promise<Buffer>

  /** 写入文件 */
  write(path: string, data: Buffer | string, options?: {
    encoding?: BufferEncoding
  }): Promise<void>

  /** 追加文件 */
  append(path: string, data: Buffer | string): Promise<void>

  /** 删除文件 */
  delete(path: string): Promise<void>

  /** 检查文件存在 */
  exists(path: string): Promise<boolean>

  /** 获取文件信息 */
  stat(path: string): Promise<FileInfo>

  /** 列出目录 */
  list(dir: string, options?: {
    recursive?: boolean
    pattern?: string
  }): Promise<string[]>

  /** 创建目录 */
  mkdir(path: string): Promise<void>

  /** 移动文件 */
  move(src: string, dest: string): Promise<void>

  /** 复制文件 */
  copy(src: string, dest: string): Promise<void>
}

// ============================================================================
// Markdown 存储接口
// ============================================================================

/** Markdown 文档 */
export interface MarkdownDocument {
  path: string
  content: string
  frontmatter?: Record<string, unknown>
  metadata: {
    title?: string
    tags?: string[]
    created?: number
    updated?: number
  }
}

/** Markdown 存储接口 */
export interface MarkdownStore {
  /** 读取文档 */
  read(path: string): Promise<MarkdownDocument>

  /** 写入文档 */
  write(path: string, doc: Partial<MarkdownDocument>): Promise<void>

  /** 解析 frontmatter */
  parseFrontmatter(content: string): Record<string, unknown>

  /** 序列化 frontmatter */
  stringifyFrontmatter(frontmatter: Record<string, unknown>): string

  /** 搜索文档 */
  search(query: string, options?: {
    paths?: string[]
    tags?: string[]
  }): Promise<MarkdownDocument[]>

  /** 监听变更 */
  watch(callback: (event: {
    type: "create" | "update" | "delete"
    path: string
  }) => void): () => void
}
