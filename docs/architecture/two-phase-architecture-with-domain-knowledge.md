# LLM 驱动的代码分析架构设计

## 文档信息
- **创建时间**: 2026-01-14
- **版本**: 2.0
- **作者**: SmanAgent Team

---

## 1. 架构设计

### 1.1 核心理念

**LLM 自然驱动**：不是强制分阶段，而是让 LLM 根据需要自主决定流程。

```
用户问题
    ↓
LLM 分析 → 需要更多信息？
    ↓
是的 → 调用 search 工具（统一搜索代码+领域知识）
    ↓
获取搜索结果
    ↓
LLM 基于搜索结果 → 决定下一步
    ├→ 生成 SubTask 列表
    ├→ 继续搜索更多信息
    └→ 直接回答问题
```

### 1.2 search 工具

**核心组件**：`SearchTool` - 统一搜索工具

**功能**：
- 同时搜索代码和领域知识
- 返回统一格式的搜索结果
- LLM 可根据搜索结果决定下一步行动

**参数**：
- `query`: 搜索查询（必填）
- `topK`: 返回结果数量（默认 10）
- `type`: 搜索类型 - code/knowledge/both（默认 both）

### 1.3 领域知识

**存储**：`DomainKnowledge` 表（H2 数据库）
- `id`: UUID
- `projectKey`: 项目标识
- `title`: 知识标题
- `content`: 知识内容（纯文本）
- `embedding`: BGE-M3 向量（Base64 编码）
- `createdAt`, `updatedAt`: 时间戳

**Repository**：`DomainKnowledgeRepository`（使用 JdbcTemplate）

---

## 2. 数据模型

### 2.1 SearchResult（搜索结果）

```java
public class SearchResult {
    private String type;      // "code" 或 "knowledge"
    private String id;        // ID
    private String title;     // 标题
    private String content;   // 内容
    private double score;     // 相似度分数
    private String metadata;  // 元数据（JSON）
}
```

### 2.2 DomainKnowledge（领域知识）

```java
public class DomainKnowledge {
    private String id;
    private String projectKey;
    private String title;
    private String content;
    private String embedding;
    private Instant createdAt;
    private Instant updatedAt;
}
```

---

## 3. 服务接口

### 3.1 SearchService（统一搜索服务）

```java
public interface SearchService {
    enum SearchType {
        CODE,        // 仅搜索代码
        KNOWLEDGE,   // 仅搜索领域知识
        BOTH         // 同时搜索代码和领域知识
    }

    List<SearchResult> search(String query, String projectKey,
                              int topK, SearchType searchType);
}
```

**实现**：`SearchServiceImpl`
- 代码搜索：调用 `VectorSearchService.semanticSearch()`
- 知识搜索：查询 `DomainKnowledgeRepository.findAllWithEmbedding()`
- 混合排序：按 score 降序，返回 topK

---

## 4. 工具集成

### 4.1 SearchTool

**位置**：`com.smancode.smanagent.tools.search.SearchTool`

**工具定义**：
```java
@Component
public class SearchTool extends AbstractTool implements Tool {
    @Override
    public String getName() {
        return "search";
    }

    @Override
    public String getDescription() {
        return "统一搜索工具，同时搜索代码和领域知识";
    }

    @Override
    public Map<String, ParameterDef> getParameters() {
        Map<String, ParameterDef> params = new HashMap<>();
        params.put("query", new ParameterDef("query", String.class, true, "搜索查询"));
        params.put("topK", new ParameterDef("topK", Integer.class, false, "返回结果数量（默认 10）", 10));
        params.put("type", new ParameterDef("type", String.class, false, "搜索类型：code/knowledge/both（默认 both）", "both"));
        return params;
    }

    @Override
    public ToolResult execute(String projectKey, Map<String, Object> params) {
        // 调用 SearchService.search()
        // 返回格式化的搜索结果
    }
}
```

### 4.2 LLM 提示词

LLM 会自然地调用 `search` 工具：

```
你是一个专业的代码分析助手。

可用工具：
- search: 统一搜索代码和领域知识
  参数：query (必填), topK (可选), type (可选)

工作流程：
1. 理解用户问题
2. 如果需要更多信息，调用 search 工具
3. 基于搜索结果，分析并回答问题
4. 如果问题复杂，可以拆解为多个 SubTask
```

---

## 5. 完整流程示例

### 示例 1：简单问题

**用户**："PaymentService 类是做什么的？"

**LLM 流程**：
1. 分析问题 → 需要查看 PaymentService 的代码
2. 调用 `search(query="PaymentService 类作用", topK=5, type="code")`
3. 获取搜索结果
4. 基于搜索结果回答：PaymentService 是支付服务类...

### 示例 2：复杂问题

**用户**："如何添加新的还款方式？"

**LLM 流程**：
1. 分析问题 → 需要了解还款方式的实现
2. 调用 `search(query="还款方式 添加 流程", topK=10, type="both")`
3. 获取搜索结果（包含代码和领域知识）
4. 基于搜索结果，生成 SubTask 列表：
   - SubTask 1: 分析现有的还款方式实现
   - SubTask 2: 查找添加新还款方式的入口
   - SubTask 3: 分析需要修改的类和方法
5. 逐个执行 SubTask，生成最终答案

---

## 6. 实施计划

### Phase 1: 数据模型 ✅
- [x] `SearchResult`
- [x] `DomainKnowledge`
- [x] `DomainKnowledgeRepository`
- [x] 单元测试

### Phase 2: 搜索服务 ✅
- [x] `SearchService` 接口
- [x] `SearchServiceImpl` 实现
- [x] `SearchTool` 工具

### Phase 3: 向量搜索（待完善）
- [ ] BGE-M3 向量化服务
- [ ] BGE-Reranker 重排序
- [ ] 领域知识向量索引

### Phase 4: 集成测试
- [ ] 端到端测试
- [ ] 性能测试
- [ ] 用户反馈收集

---

## 7. 后续优化方向

1. **增量向量索引**：自动从代码和文档中提取领域知识
2. **智能缓存**：缓存常见问题的搜索结果
3. **成本优化**：减少重复搜索，复用搜索结果
4. **多轮对话**：支持用户追问，累积上下文

---

**文档结束**
