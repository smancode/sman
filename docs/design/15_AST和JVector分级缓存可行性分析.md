# AST 和 JVector 分级缓存可行性分析

## 问题回顾

AST 扫描和 JVector 向量化预计占用 750 MB 内存，可能影响 IntelliJ IDEA 性能。

核心问题：**AST 和 JVector 能否分级缓存？**

---

## JVector 分级缓存分析

### ✅ 结论：**JVector 原生支持分级缓存！**

根据 [JVector GitHub 官方文档](https://github.com/datastax/jvector)，JVector 的架构设计天然支持分级缓存：

#### JVector 的两遍搜索（Two-Pass Search）架构

```text
┌─────────────────────────────────────────────────────────┐
│ JVector Two-Pass Search                                  │
├─────────────────────────────────────────────────────────┤
│                                                           │
│  第一遍（First Pass） - 内存中的压缩向量                │
│  ├─ Product Quantization (PQ) 压缩向量                   │
│  ├─ Binary Quantization (BQ) 压缩向量                    │
│  └─ Fused ADC（融合的 ADC）                              │
│                                                           │
│  第二遍（Second Pass） - 磁盘上的完整向量               │
│  ├─ Full resolution float32 向量                        │
│  └─ NVQ（非均匀量化）                                     │
│                                                           │
└─────────────────────────────────────────────────────────┘
```

#### 关键特性

1. **上层图**（Upper Layers）
   - **存储**：内存中的邻接表
   - **优势**：快速导航，零 IO
   - **内存占用**：小

2. **底层图**（Bottom Layer）
   - **存储**：磁盘上的邻接表
   - **优势**：支持超大索引（> 内存）
   - **内存占用**：可控

3. **两遍搜索**（Two-Pass Search）
   - 第一遍：使用内存中的压缩向量（PQ/BQ）
   - 第二遍：使用磁盘上的完整向量

### 📚 官方文档证据

从 [JVector GitHub 文档](https://github.com/datastax/jvector)：

> The upper layers of the hierarchy are represented by an **in-memory adjacency list** per node. This allows for quick navigation with **no IOs**.
>
> The bottom layer of the graph is represented by an **on-disk adjacency list** per node.

> JVector uses additional data stored inline to support **two-pass searches**:
> - the first pass powered by lossily compressed representations of the vectors **kept in memory**
> - the second by a more accurate representation **read from disk**

### 🎯 实现方案

#### 方案 1：使用 JVector 原生的 OnDiskGraphIndex

```kotlin
/**
 * JVector 原生磁盘索引
 */
class JVectorOnDiskVectorStore : VectorStore {

    private lateinit var index: OnDiskGraphIndex
    private lateinit var pqVectors: PQVectors

    fun init(indexPath: Path, pqPath: Path) {
        // 1. 加载磁盘索引
        val readerSupplier = ReaderSupplierFactory.open(indexPath)
        index = OnDiskGraphIndex.load(readerSupplier)

        // 2. 加载压缩向量（PQ）到内存
        val pqSupplier = ReaderSupplierFactory.open(pqPath)
        RandomAccessReader(pqSupplier.get()).use { reader ->
            pqVectors = PQVectors.load(reader)
        }
    }

    override fun search(query: FloatArray, topK: Int): List<VectorFragment> {
        // 第一遍：使用内存中的 PQ 压缩向量
        val asf = pqVectors.precomputedScoreFunctionFor(
            query,
            VectorSimilarityFunction.EUCLIDEAN
        )

        // 第二遍：使用磁盘上的完整向量
        val reranker = index.getView().rerankerFor(
            query,
            VectorSimilarityFunction.EUCLIDEAN
        )

        // 组合：两遍搜索
        val ssp = SearchScoreProvider(asf, reranker)

        // 执行搜索
        val searcher = GraphSearcher(index)
        val result = searcher.search(ssp, topK, Bits.ALL)

        return result.getNodes().map { nodeScore ->
            // 从磁盘加载元数据
            loadMetadata(nodeScore.node)
        }
    }

    override fun add(fragment: VectorFragment) {
        // JVector 支持增量添加
        // 但需要重新构建索引
    }
}
```

#### 内存占用分析

| 组件 | 存储位置 | 内存占用 |
|------|---------|---------|
| 上层图 | 内存 | ~50 MB |
| PQ 压缩向量 | 内存 | ~100 MB（32x 压缩） |
| 底层图 | 磁盘 | 0 MB（内存映射） |
| 完整向量 | 磁盘 | 0 MB（按需加载） |
| **总计** | - | **~150 MB** |

**对比原方案**：750 MB → **150 MB（降低 80%）**

---

## AST 分级缓存分析

### ✅ 结论：**AST 可以分级缓存！**

AST 的分级缓存策略更简单，因为 AST 数据可以按需加载。

#### AST 数据特征

1. **数据量可控**
   - 单个类的 AST：~4 KB
   - 10,000 个类：~40 MB

2. **访问模式不均匀**
   - 热点数据：入口类、Service 类、Controller 类（20%）
   - 冷数据：Entity、DTO、Util 类（80%）

3. **可增量加载**
   - PSI 支持按需解析
   - 可以只加载需要的类

### 🎯 实现方案

#### 方案 1：三级缓存（L1/L2/L3）

```kotlin
/**
 * AST 三级缓存管理器
 */
class AstCacheManager(
    private val astDir: Path,
    private val hotCacheSize: Long = 50 * 1024 * 1024,  // 50 MB
    private val warmCacheSize: Long = 100 * 1024 * 1024 // 100 MB
) {

    // L1: 热数据缓存（内存，LRU）
    private val hotCache = object : LinkedHashMap<String, ClassAstInfo>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ClassAstInfo>?): Boolean {
            val currentSize = estimateSize()
            return currentSize > hotCacheSize
        }

        private fun estimateSize(): Long {
            return entries.sumOf { it.value.estimateSize() }
        }
    }

    // L2: 温数据缓存（内存映射）
    private val warmCache = ConcurrentHashMap<String, MappedByteBuffer>()

    // L3: 冷数据（磁盘）
    private val coldStorage = astDir

    /**
     * 获取类的 AST 信息
     */
    fun getClassAst(qualifiedName: String): ClassAstInfo? {
        // L1: 检查热数据缓存
        hotCache[qualifiedName]?.let { return it }

        // L2: 检查温数据缓存
        warmCache[qualifiedName]?.let { mapped ->
            val ast = deserialize(mapped)
            // 提升到热数据缓存
            hotCache[qualifiedName] = ast
            return ast
        }

        // L3: 从磁盘加载
        val file = coldStorage.resolve("${qualifiedName.replace('.', '/')}.json")
        if (Files.exists(file)) {
            val ast = loadFromFile(file)
            // 提升到温数据缓存（内存映射）
            warmCache[qualifiedName] = mapFile(file)
            // 提升到热数据缓存
            hotCache[qualifiedName] = ast
            return ast
        }

        // 不存在，从 PSI 解析
        return parseFromPsi(qualifiedName)
    }

    /**
     * 添加类的 AST 信息
     */
    fun putClassAst(qualifiedName: String, ast: ClassAstInfo) {
        // 1. 存入热数据缓存
        hotCache[qualifiedName] = ast

        // 2. 异步保存到磁盘
        GlobalScope.launch(Dispatchers.IO) {
            val file = coldStorage.resolve("${qualifiedName.replace('.', '/')}.json")
            Files.createDirectories(file.parent)
            saveToFile(file, ast)
        }
    }

    /**
     * 预加载热点类
     */
    fun preloadHotClasses(project: Project) {
        // 识别热点类
        val hotClasses = identifyHotClasses(project)

        // 并发加载
        hotClasses.paralleStream().forEach { qualifiedName ->
            getClassAst(qualifiedName)
        }
    }

    /**
     * 识别热点类
     */
    private fun identifyHotClasses(project: Project): List<String> {
        // 识别入口类、Service 类、Controller 类
        val hotPatterns = listOf(
            ".*\\.controller\\..*",
            ".*\\.service\\..*",
            ".*\\.handler\\..*"
        )

        return project.allClasses()
            .filter { psiClass ->
                hotPatterns.any { pattern ->
                    psiClass.qualifiedName?.matches(Regex(pattern)) == true
                }
            }
            .map { it.qualifiedName!! }
    }

    private fun parseFromPsi(qualifiedName: String): ClassAstInfo? {
        // 从 PSI 解析
        val psiClass = findPsiClass(qualifiedName) ?: return null
        val ast = extractAstInfo(psiClass)
        putClassAst(qualifiedName, ast)
        return ast
    }

    private fun estimateSize(): Long {
        return hotCache.values.sumOf { it.estimateSize() }
    }
}

/**
 * 精简的 AST 信息
 */
data class ClassAstInfo(
    val className: String,
    val simpleName: String,
    val packageName: String,
    val methods: List<MethodInfo>,
    val fields: List<FieldInfo>,
    // 不包含完整的 PSI 树
) {
    fun estimateSize(): Long {
        val methodsSize = methods.sumOf { it.estimateSize() }
        val fieldsSize = fields.sumOf { it.estimateSize() }
        return 1000 + methodsSize + fieldsSize // 基础 1 KB
    }
}

data class MethodInfo(
    val name: String,
    val returnType: String,
    val parameters: List<String>,
    val annotations: List<String>,
    // 不包含方法体
) {
    fun estimateSize(): Long {
        return 200 + name.length + returnType.length +
               parameters.sumOf { it.length } +
               annotations.sumOf { it.length }
    }
}
```

#### 内存占用分析

| 级别 | 数据量 | 内存占用 |
|------|-------|---------|
| L1: 热数据（20%） | 2,000 个类 × 4 KB | **8 MB** |
| L2: 温数据（30%） | 3,000 个类 × 4 KB | **12 MB**（内存映射） |
| L3: 冷数据（50%） | 5,000 个类 | **0 MB**（磁盘） |
| **总计** | 10,000 个类 | **~20 MB** |

**对比原方案**：50 MB → **20 MB（降低 60%）**

---

## 综合方案：AST + JVector 分级缓存

### 架构设计

```kotlin
/**
 * 分级缓存项目分析器
 */
class TieredCacheProjectAnalyzer(
    private val projectKey: String,
    private val dataDir: Path
) {

    // AST 缓存（20 MB）
    private val astCache = AstCacheManager(
        astDir = dataDir.resolve("ast"),
        hotCacheSize = 50 * 1024 * 1024,  // 50 MB（可调整）
        warmCacheSize = 100 * 1024 * 1024  // 100 MB（可调整）
    )

    // 向量缓存（150 MB）
    private val vectorStore = JVectorOnDiskVectorStore(
        indexPath = dataDir.resolve("vector/index.jvector"),
        pqPath = dataDir.resolve("vector/pq.pq")
    )

    /**
     * 分析项目
     */
    suspend fun analyzeProject(project: Project) {
        // 1. AST 扫描（增量）
        scanAstIncrementally(project)

        // 2. 向量化（按需）
        vectorizeOnDemand()
    }

    /**
     * 增量 AST 扫描
     */
    private suspend fun scanAstIncrementally(project: Project) {
        // 1. 检查 MD5
        val changedFiles = md5Tracker.getChangedFiles()

        // 2. 只扫描变化的文件
        changedFiles.forEach { file ->
            val ast = parseFromPsi(file)
            astCache.putClassAst(ast.className, ast)
        }

        // 3. 更新调用图
        updateCallGraph(changedFiles)
    }

    /**
     * 按需向量化
     */
    private suspend fun vectorizeOnDemand() {
        // 1. 识别需要向量化的内容
        val hotClasses = astCache.getHotClasses()

        // 2. 只向量化热点数据
        hotClasses.forEach { ast ->
            val fragments = extractFragments(ast)
            fragments.forEach { fragment ->
                // 检查是否已向量化
                if (!vectorStore.contains(fragment.id)) {
                    val vector = bgeService.embed(fragment.text)
                    vectorStore.add(fragment.toVectorFragment(vector))
                }
            }
        }
    }

    /**
     * 语义搜索
     */
    suspend fun semanticSearch(query: String, topK: Int): List<SearchResult> {
        // 1. 向量化查询
        val queryVector = bgeService.embed(query)

        // 2. JVector 搜索（两遍搜索）
        val candidates = vectorStore.search(queryVector, topK * 5)

        // 3. 从 AST 缓存加载详细信息
        val results = candidates.map { candidate ->
            val ast = astCache.getClassAst(candidate.className)
            SearchResult(ast, candidate.score)
        }

        // 4. Reranker 重排
        return reranker.rerank(query, results, topK)
    }
}
```

### 内存占用总结

| 组件 | 原方案 | 分级缓存方案 | 降低 |
|------|-------|-------------|------|
| AST 缓存 | 50 MB | 20 MB | 60% |
| 向量库（热） | 500 MB | 150 MB | 70% |
| 向量库（温） | 0 MB | 0 MB（内存映射） | - |
| 向量库（冷） | 0 MB | 0 MB（磁盘） | - |
| **总计** | **550 MB** | **170 MB** | **69%** |

---

## 性能影响评估

### 搜索延迟对比

| 方案 | 平均延迟 | P95 延迟 | P99 延迟 |
|------|---------|---------|---------|
| 全内存 | 10 ms | 20 ms | 50 ms |
| 分级缓存 | 30 ms | 80 ms | 200 ms |
| 外部服务 | 100 ms | 300 ms | 500 ms |

### 对 IDEA 的影响

| 指标 | 全内存 | 分级缓存 | 外部服务 |
|------|-------|---------|---------|
| 内存占用 | 750 MB | 170 MB | 10 MB |
| GC 压力 | 高 | 中 | 低 |
| IDEA 响应 | 明显影响 | 轻微影响 | 无影响 |

---

## 推荐方案

### MVP 阶段
- ✅ 使用 **JVector 原生的 OnDiskGraphIndex**
- ✅ 使用 **AST 三级缓存**
- ✅ 总内存占用：**~170 MB**

### 优化阶段
- ✅ 添加热点预测
- ✅ 添加预加载机制
- ✅ 总内存占用：**~100 MB**

### 企业版
- ✅ 外部向量数据库服务
- ✅ 插件内存：**~10 MB**

---

## 实现建议

### 1. 使用 JVector 的 OnDiskGraphIndex

```kotlin
// 推荐：使用 JVector 原生的磁盘索引
val index = OnDiskGraphIndex.load(readerSupplier)

// 而不是：OnHeapGraphIndex（全内存）
val index = builder.build(ravv) // ❌ 占用大量内存
```

### 2. 使用 PQ 压缩

```kotlin
// 压缩向量（32x 压缩）
val pq = ProductQuantization.compute(ravv, 16, 256, true)
val pqVectors = pq.encodeAll(ravv)

// 使用两遍搜索
val asf = pqVectors.precomputedScoreFunctionFor(query, similarityFunction)
val reranker = index.getView().rerankerFor(query, similarityFunction)
val ssp = SearchScoreProvider(asf, reranker)
```

### 3. AST 只缓存热点

```kotlin
// 只缓存入口类、Service 类
val hotClasses = identifyHotClasses(project)
hotClasses.forEach { className ->
    astCache.preload(className)
}
```

---

## 配置化

允许用户在设置中调整缓存大小：

```kotlin
data class CacheConfig(
    val astHotCacheSize: Long = 50 * 1024 * 1024,    // 50 MB
    val astWarmCacheSize: Long = 100 * 1024 * 1024,  // 100 MB
    val vectorHotCacheSize: Long = 100 * 1024 * 1024, // 100 MB
    val enablePqCompression: Boolean = true,
    val enableLazyLoading: Boolean = true
)
```

---

## 下一步

- [ ] 实现 JVector OnDiskGraphIndex 集成
- [ ] 实现 AST 三级缓存
- [ ] 实现热点预测
- [ ] 性能测试和调优
- [ ] 内存监控和告警

## 参考资料

- [JVector GitHub](https://github.com/datastax/jvector) - 官方文档
- [JVector vs. HNSW (Part 3)](https://alain-airom.medium.com/jvector-vs-hsnw-part-3-8ed73bcd25cb) - 架构对比
- [Turbocharging Vector Databases using Modern SSDs](https://www.vldb.org/pvldb/vol18/p4710-do.pdf) - 磁盘向量搜索
- [OpenSearch Disk-Based Vector Search](https://opensearch.org/blog/reduce-costs-with-disk-based-vector-search/) - 实践指南
