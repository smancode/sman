# SiliconMan 架构设计文档

**文档版本**: v1.0
**创建日期**: 2025-01-05
**作者**: SiliconMan Team
**状态**: 设计阶段

---

## 1. 总体架构

### 1.1 架构图

```
┌─────────────────────────────────────────────────────────────┐
│ IDE Plugin（前端）                                          │
│   - 简化的聊天 UI（Markdown + Mermaid 渲染）              │
│   - 历史会话管理                                            │
│   - 本地文件读写（PSI API）                                 │
└─────────────────────────────────────────────────────────────┘
         ↓ WebSocket (现有，保留)
┌─────────────────────────────────────────────────────────────┐
│ Agent 后端（核心服务）                                      │
│                                                              │
│  ┌──────────────────────────────────────────────────────┐  │
│  │ 核心能力层（保留）                                    │  │
│  │   - Spoon AST 分析                                    │  │
│  │   - 调用链分析                                        │  │
│  │   - 向量检索（BGE-M3 + Reranker）                     │  │
│  │   - 领域知识 IoC                                       │  │
│  │   - 案例 IoC                                           │  │
│  └──────────────────────────────────────────────────────┘  │
│                         ↓                                  │
│  ┌──────────────────────────────────────────────────────┐  │
│  │ Claude Code 集成层                                    │  │
│  │   - 进程池管理（15个预启动进程）                       │  │
│  │   - HTTP 工具 API                                     │  │
│  │   - 会话管理（requestId.json）                        │  │
│  └──────────────────────────────────────────────────────┘  │
│                         ↓                                  │
│  ┌──────────────────────────────────────────────────────┐  │
│  │ HTTP 工具 API                                         │  │
│  │   POST /api/claude-code/tools/execute                │  │
│  │   - vector_search（向量搜索）                        │  │
│  │   - read_class（读取类）                             │  │
│  │   - call_chain（调用链分析）                         │  │
│  │   - apply_change（代码修改）                         │  │
│  └──────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
         ↓ Claude Code 调用工具（通过 HTTP）
┌─────────────────────────────────────────────────────────────┐
│ Claude Code CLI（Agent 引擎，后端管理）                   │
│   - 三阶段工作流（Analyze → Plan → Execute）              │
│   - Prompt 体系（CLAUDE.md）                              │
│   - 通过 HTTP API 调用后端工具                           │
│   - 行为完全可控（禁止 Read/Edit，强制使用 http_tool）   │
└─────────────────────────────────────────────────────────────┘
```

---

### 1.2 核心原则

#### 1.2.1 单一职责原则

**组件职责清晰划分**：

| 组件 | 职责 | 不做什么 |
|------|------|----------|
| **Claude Code** | Agent 编排、三阶段工作流、Prompt 工程 | 不直接访问文件、不访问数据库 |
| **Agent 后端** | 工具提供者（向量搜索、调用链、AST 分析） | 不做 Agent 编排 |
| **IDE Plugin** | UI 展示、本地文件读写 | 不做代码分析、不做 AI 推理 |

**公共能力只在一处**：
- 文件路径处理：`shared/api-schema/PathUtils.kt`
- 数据模型：`shared/api-schema/models/`
- HTTP 客户端：`shared/api-schema/http/`

---

#### 1.2.2 最小转化原则

**对 Claude Code 和 IDE Plugin 的输入输出不做过多转化**：

```java
// ❌ 错误：做过多转化
ClaudeCodeMessage → InternalMessage → ToolRequest → HTTPRequest

// ✅ 正确：最小转化
ClaudeCodeMessage → HTTPRequest（直接透传）
```

**好处**：
- 减少数据丢失
- 降低维护成本
- 提高性能

---

#### 1.2.3 线程池统一管理

**所有异步操作使用统一的线程池**：

```java
// 统一的线程池配置
@Configuration
public class ThreadPoolConfig {

    @Bean("taskExecutor")
    public ExecutorService taskExecutor() {
        return new ThreadPoolExecutor(
            10,  // 核心线程数
            50,  // 最大线程数
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(1000),
            new ThreadFactoryBuilder().setNameFormat("sman-task-%d").build()
        );
    }
}
```

---

## 2. 数据流

### 2.1 用户请求流程

```
1. 用户在 IDE 输入："分析文件过滤的代码"
   ↓
2. IDE Plugin → WebSocket → Agent 后端
   {
     "type": "ANALYZE",
     "requestId": "req-123",
     "message": "分析文件过滤的代码"
   }
   ↓
3. Agent 后端：加载历史会话
   ↓
4. Agent 后端：从进程池获取 Claude Code worker
   ↓
5. Agent 后端 → Claude Code
   {
     "type": "user_message",
     "sessionId": "req-123",
     "content": "分析文件过滤的代码",
     "history": [...]  // 历史会话
   }
   ↓
6. Claude Code：读取 CLAUDE.md，了解规则
   ↓
7. Claude Code：调用 http_tool("vector_search", {"query": "文件过滤"})
   ↓
8. Claude Code → Agent 后端 HTTP API
   POST /api/claude-code/tools/execute
   {
     "toolName": "vector_search",
     "params": {"query": "文件过滤", "top_k": 10}
   }
   ↓
9. Agent 后端：执行向量搜索（直接执行）
   ↓
10. Agent 后端 → Claude Code：返回搜索结果
    ↓
11. Claude Code：调用 http_tool("read_class", {"className": "FileFilter"})
    ↓
12. Claude Code → Agent 后端 HTTP API
    ↓
13. Agent 后端：判断需要访问本地文件 → 通过 WebSocket 转发给 IDE Plugin
    {
      "type": "TOOL_CALL",
      "toolName": "read_class",
      "params": {"className": "FileFilter"}
    }
    ↓
14. IDE Plugin：使用 PSI API 读取本地文件
    ↓
15. IDE Plugin → Agent 后端：返回类结构
    {
      "type": "TOOL_RESULT",
      "success": true,
      "result": "..."
    }
    ↓
16. Agent 后端 → Claude Code：返回结果
    ↓
17. Claude Code：生成最终结论
    ↓
18. Agent 后端 → IDE Plugin：通过 WebSocket 推送
    {
      "type": "COMPLETE",
      "requestId": "req-123",
      "result": "..."
    }
    ↓
19. IDE Plugin：渲染 Markdown + Mermaid
```

---

## 3. 接口分层

### 3.1 接口总览

| 接口 | 提供方 | 消费方 | 协议 | 用途 |
|------|--------|--------|------|------|
| **WebSocket API** | Agent 后端 | IDE Plugin | WebSocket | 双向通信（消息推送、工具调用） |
| **HTTP Tool API** | Agent 后端 | Claude Code | HTTP | Claude Code 调用后端工具 |
| **HTTP Rest API** | Agent 后端 | 外部系统 | HTTP | 管理 API（健康检查、配置） |

### 3.2 WebSocket API（IDE Plugin ↔ Agent 后端）

**用途**：
- IDE Plugin 发送分析请求
- Agent 后端推送分析进度
- Agent 后端转发工具调用
- IDE Plugin 返回工具结果

**详见**: [04-ide-plugin-api-spec.md](./04-ide-plugin-api-spec.md)

---

### 3.3 HTTP Tool API（Claude Code ↔ Agent 后端）

**用途**：
- Claude Code 调用后端工具
- Agent 后端返回工具结果

**详见**: [06-claude-code-integration.md](./06-claude-code-integration.md)

---

## 4. 核心能力保留

### 4.1 Agent 后端保留的能力

| 能力 | 说明 | 实现 |
|------|------|------|
| **Spoon AST 分析** | Java 代码结构分析、提取 | `SpoonAstService` |
| **调用链分析** | 方法调用关系分析 | `CallChainService` |
| **向量检索** | BGE-M3 + Reranker 语义搜索 | `VectorSearchService` |
| **领域知识 IoC** | 注入领域知识（业务概念） | `DomainKnowledgeService` |
| **案例 IoC** | 注入历史成功案例 | `CasePatternService` |
| **定时刷新缓存** | 定期刷新向量索引、调用链缓存 | `CacheRefreshService` |
| **缓存持久化** | 缓存存储在 `agent/data/` | `CachePersistenceService` |

---

### 4.2 IDE Plugin 保留的能力

| 能力 | 说明 | 实现 |
|------|------|------|
| **本地文件读写** | 使用 PSI API 读取文件 | `LocalFileService` |
| **历史会话** | 会话历史管理 | `SessionHistoryService` |
| **Markdown 渲染** | Markdown 渲染（flexmark） | `MarkdownRenderer` |
| **Mermaid 渲染** | Mermaid 图表渲染 | `MermaidRenderer` |
| **对话框 UI** | 聊天界面（简化版） | `ChatPanel` |

---

## 5. 技术栈

### 5.1 Agent 后端

| 技术 | 版本 | 用途 |
|------|------|------|
| Java | 21+ | 开发语言 |
| Spring Boot | 3.2.5 | 应用框架 |
| Gradle | 8.0+ | 构建工具 |
| Spoon | 11.0.0 | AST 分析 |
| JVector | 3.0.6 | 向量索引 |
| H2 Database | 2.2.224 | 嵌入式数据库（可选持久化） |

---

### 5.2 IDE Plugin

| 技术 | 版本 | 用途 |
|------|------|------|
| Kotlin | 1.9.20 | 开发语言 |
| IntelliJ Platform SDK | 2024.1+ | 插件开发 |
| flexmark-java | 0.64.8 | Markdown 渲染 |
| OkHttp | 4.12.0 | HTTP 客户端 |

---

### 5.3 Claude Code

| 技术 | 版本 | 用途 |
|------|------|------|
| Node.js | 18+ | 运行环境 |
| TypeScript | - | 开发语言 |
| @anthropic-ai/claude-code | Latest | CLI 工具 |

---

## 6. 部署架构

### 6.1 开发环境

```
开发者本地机器：
- IntelliJ IDEA（运行 IDE Plugin）
- Agent 后端（本地运行）
- Claude Code CLI（本地安装）
```

---

### 6.2 生产环境

```
服务器（16C32G）：
- Agent 后端（Spring Boot）
- Claude Code 进程池（15个进程）
- H2 Database（嵌入式，可选）

开发者机器：
- IntelliJ IDEA + IDE Plugin
```

---

## 7. 扩展点设计

### 7.1 当前扩展能力

| 扩展点 | 说明 | 接口 |
|--------|------|------|
| **向量搜索** | 自定义向量模型、索引策略 | `VectorSearchProvider` |
| **调用链查询** | 自定义调用链分析深度、方向 | `CallChainProvider` |
| **领域知识** | 注入自定义领域知识 | `DomainKnowledgeProvider` |
| **案例分析** | 注入历史案例 | `CasePatternProvider` |

---

### 7.2 未来扩展能力

| 扩展点 | 说明 | 计划 |
|--------|------|------|
| **业务图谱** | 构建业务关系图谱 | Q2 2025 |
| **代码重构** | 自动化重构建议 | Q3 2025 |
| **测试生成** | 自动生成单元测试 | Q4 2025 |

---

## 8. 非功能需求

### 8.1 性能要求

| 指标 | 目标 |
|------|------|
| **并发能力** | 15-20 个并发请求 |
| **响应时间** | <5 秒（进程已启动） |
| **向量搜索** | <2 秒 |
| **调用链分析** | <3 秒 |

---

### 8.2 可靠性要求

| 指标 | 目标 |
|------|------|
| **进程健康检查** | 1 分钟/次 |
| **自动重启** | 进程崩溃后 5 秒内重启 |
| **缓存持久化** | 定期刷新，避免丢失 |

---

### 8.3 可维护性要求

| 指标 | 目标 |
|------|------|
| **单一职责** | 每个类只做一件事 |
| **代码行数** | 单文件不超过 500 行 |
| **目录结构** | 单文件夹不超过 8 个文件 |

---

## 9. 关键设计决策

### 9.1 为什么选择 Claude Code？

| 优势 | 说明 |
|------|------|
| **工程化成熟** | 经过大量实战验证的 Prompt 体系 |
| **三阶段工作流** | Analyze → Plan → Execute，逻辑清晰 |
| **社区生态** | 丰富的 MCP Servers、Slash Commands |
| **维护成本低** | 不需要自己维护 Agent 逻辑 |

---

### 9.2 为什么用 HTTP 而不是 MCP？

| 对比项 | MCP | HTTP |
|--------|-----|-----|
| **复杂度** | 高（stdio 通信） | 低（RESTful API） |
| **实现语言** | Python/Node.js | Java（后端现有） |
| **调试难度** | 高（流式输出） | 低（标准 HTTP） |
| **部署** | 需要 MCP Server | 集成在后端 |

**结论**：后端和 Claude Code 在同一台服务器上，直接 HTTP 更简单。

---

### 9.3 为什么用进程池而不是单进程多会话？

| 对比项 | 进程池 | 单进程多会话 |
|--------|--------|-------------|
| **并发能力** | 15-20 | 50-100 |
| **响应速度** | 快 | 中（排队） |
| **隔离性** | 好 | 差 |
| **实现复杂度** | 中 | 高 |
| **调试难度** | 低 | 高 |

**结论**：进程池更适合 Windows 环境，可以平滑演进到分层架构。

---

## 10. 下一步

- [ ] 编写接口规范文档
- [ ] 实现 Agent 后端核心能力
- [ ] 实现 IDE Plugin 简化 UI
- [ ] 集成 Claude Code
- [ ] 测试和优化

---

**文档结束**
