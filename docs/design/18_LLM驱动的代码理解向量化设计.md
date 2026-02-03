# 18 - LLM 驱动的代码理解向量化设计

## 核心理念

> "我们做的事情，是理解这个系统的代码！并且是**提前理解**，这样才能回答业务相关的问题！"

## 当前问题

### 测试结果

| 查询 | 预期结果 | 实际结果 | 状态 |
|------|---------|---------|------|
| "放款是哪个接口？" | 返回 `DisburseHandler` | 返回"未找到相关代码片段" | ❌ |
| "DisburseHandler 是什么？" | 返回业务描述 | 返回类名 | ⚠️ |

### 根本原因

**当前的向量化实现**：
- 只存储类名：`DisburseHandler`
- 没有业务描述
- 没有方法级向量
- 没有使用 LLM 理解代码

**用户期望的实现**：
- LLM 分析类和方法，生成业务描述
- 生成 `.md` 文档持久化
- 类和方法分别向量化
- 支持中文业务查询

## 设计目标

### 1. 文件级别的代码理解

```
DisburseHandler.java
    ↓ LLM 分析
DisburseHandler.md
    ├── 类业务描述
    ├── 方法列表（含源码）
    └── 向量化数据
```

### 2. 五种代码类型的向量化

| 类型 | 向量化内容 | 存储格式 |
|------|-----------|---------|
| 1.1 类 | 类定义 + 业务描述 + 字段 + 方法摘要 | 1条向量/类 |
| 1.2 方法 | 完整源码 + 上下文 | 1条向量/方法 |
| 1.3 Enum | 定义 + 字典映射 | 1条向量/枚举 |
| 1.4 XML (MyBatis) | SQL 逻辑 + 注释 | 1条向量/statement |
| 1.5 XML (配置) | 扁平化配置 + 引用关系 | 1条向量/配置块 |

### 3. 向量化流程

```
1. 扫描项目文件
   ↓
2. 对于每个文件：
   a. 计算文件 MD5
   b. 如果 MD5 变化或不存在：
      - LLM 分析文件内容
      - 生成 .md 文档（类 + 方法，用 --- 分隔）
      - 解析 .md 文档，提取向量化数据
      - 分别向量化类和方法
      - 存储到向量数据库
   c. 如果 MD5 未变化：
      - 跳过（使用缓存）
```

### 4. .md 文档格式

#### 4.1 Java 类文件

```markdown
# DisburseHandler

## 类信息

- **完整签名**: `public class DisburseHandler extends AbstractTxnHandler`
- **包名**: `com.autoloop.loan.handler`
- **注解**: `@Component`, `@Slf4j`

## 业务描述

放款处理器，处理贷款发放业务。负责调用支付系统执行资金划转，更新贷款状态。

## 核心数据模型

- `amount` (BigDecimal): 放款金额
- `accountId` (String): 账户ID
- `loanId` (String): 贷款ID

## 包含功能

- `execute(Txn txn)`: 执行放款交易
- `validate(DisburseRequest)`: 验证放款请求
- `updateLoanStatus(LoanStatus)`: 更新贷款状态

---

## 方法：execute

### 签名
`public void execute(Txn txn)`

### 参数
- `txn`: 交易对象

### 返回值
void

### 异常
- `PaymentException`: 支付异常
- `ValidationException`: 验证异常

### 业务描述
执行放款交易，调用支付系统划转资金到指定账户。

### 源码
```java
public void execute(Txn txn) {
    // 1. 验证账户状态
    validateAccount(txn.getAccountId());

    // 2. 调用支付系统
    PaymentResult result = paymentService.transfer(
        txn.getAmount(),
        txn.getAccountId()
    );

    // 3. 更新贷款状态
    loanService.updateStatus(
        txn.getLoanId(),
        LoanStatus.DISBURSED
    );

    log.info("放款成功: loanId={}, amount={}",
        txn.getLoanId(), txn.getAmount());
}
```

---

## 方法：validate

### 签名
`public void validate(DisburseRequest request)`

### 参数
- `request`: 放款请求对象

### 返回值
void

### 异常
- `ValidationException`: 验证失败时抛出

### 业务描述
验证放款请求的完整性和合法性。

### 源码
```java
public void validate(DisburseRequest request) {
    // 验证逻辑...
}
```
```

#### 4.2 Enum 文件

```markdown
# LoanStatus

## 枚举定义
- **完整签名**: `public enum LoanStatus`
- **包名**: `com.autoloop.loan.enums`

## 业务描述
贷款申请流程中的各种状态流转定义。

## 字典映射

| 枚举值 | 编码 | 业务描述 |
|--------|------|---------|
| APPLIED | 1 | 已提交申请，等待初审 |
| REJECTED | 4 | 审核拒绝 |
| PASSED | 5 | 审批通过，待放款 |
| ACTIVE | 6 | 贷款生效中，正常还款 |
| CLOSED | 7 | 已结清 |
```

#### 4.3 MyBatis XML

```markdown
# CustomerMapper.xml

## 文件信息
- **路径**: `resources/mapper/CustomerMapper.xml`
- **类型**: MyBatis Mapper

## SQL 映射

### 操作: queryByPhone

**操作类型**: SELECT

**SQL 逻辑**:
```sql
SELECT * FROM t_customer
WHERE phone = #{phone}
  AND status = 1
```

**注释/描述**:
根据手机号精确查找客户信息，只返回有效客户。

---

## 操作: insertCustomer

**操作类型**: INSERT

**SQL 逻辑**:
```sql
INSERT INTO t_customer (id, phone, name, status)
VALUES (#{id}, #{phone}, #{name}, 1)
```

**注释/描述**:
新增客户信息。
```

## 实现架构

### 核心服务

```kotlin
/**
 * LLM 驱动的代码理解服务
 */
interface LlmCodeUnderstandingService {
    /**
     * 分析 Java 文件并生成 .md 文档
     */
    suspend fun analyzeJavaFile(javaFile: Path): String

    /**
     * 分析 Enum 文件并生成 .md 文档
     */
    suspend fun analyzeEnumFile(enumFile: Path): String

    /**
     * 分析 XML 文件并生成 .md 文档
     */
    suspend fun analyzeXmlFile(xmlFile: Path): String

    /**
     * 从 .md 文档解析向量化数据
     */
    suspend fun parseMarkdownToVectors(mdFile: Path): List<VectorFragment>
}

/**
 * 代码向量化协调器（核心）
 */
interface CodeVectorizationCoordinator {
    /**
     * 向量化项目中的所有文件
     */
    suspend fun vectorizeProject(projectPath: Path, projectKey: String): VectorizationResult

    /**
     * 向量化单个文件（如果 MD5 变化）
     */
    suspend fun vectorizeFileIfChanged(sourceFile: Path, projectKey: String): VectorizationResult
}
```

### 数据模型

```kotlin
/**
 * 文档向量化结果
 */
data class DocumentVectorizationResult(
    val file: Path,
    val md5: String,
    val vectors: List<VectorFragment>,
    val mdFilePath: Path
)

/**
 * 向量化统计
 */
data class VectorizationResult(
    val totalFiles: Int,
    val processedFiles: Int,
    val skippedFiles: Int,
    val totalVectors: Int,
    val errors: List<FileError>
)

/**
 * 文件错误
 */
data class FileError(
    val file: Path,
    val error: String
)
```

### 配置

```properties
# LLM 代码分析配置
llm.code.analysis.enabled=true
llm.code.analysis.model=glm-4-flash
llm.code.analysis.timeout=60000

# 向量化存储配置
vectorization.md.dir=~/.smanunion/md/{projectKey}
vectorization.cache.enabled=true
```

## 与现有架构的集成

### 1. 项目分析管道集成

```
ProjectAnalysisPipeline
  ├─ 1. ProjectStructureScanner
  ├─ 2. TechStackDetector
  ├─ 3. PsiAstScanner
  ├─ ...
  ├─ 10. CodeVectorizationService (新增)
  │    └─ LlmCodeUnderstandingService
  └─ 11. CodeWalkthroughGenerator
```

### 2. 向量数据库集成

```
TieredVectorStore
  ├─ L1: LRU Cache (100条)
  ├─ L2: JVector (全量)
  └─ L3: H2 Database (持久化)
```

### 3. MD5 变更检测

```kotlin
/**
 * 文件变更跟踪器
 */
interface FileChangeTracker {
    /**
     * 计算文件 MD5
     */
    fun calculateMd5(file: Path): String

    /**
     * 检查文件是否需要重新处理
     */
    fun needsProcessing(file: Path, projectKey: String): Boolean
}
```

## 实施计划

### 实施状态 (2026-02-03)

| Phase | 任务 | 状态 | 实现文件 |
|-------|------|------|----------|
| Phase 1 | LLM 代码分析服务 | ✅ 完成 | `LlmCodeUnderstandingService.kt` |
| Phase 1 | .md 文档解析为向量 | ✅ 完成 | `LlmCodeUnderstandingService.parseMarkdownToVectors()` |
| Phase 2 | 向量化协调器 | ✅ 完成 | `CodeVectorizationCoordinator.kt` |
| Phase 2 | 集成到 ProjectAnalysisPipeline | ✅ 完成 | `LlmCodeVectorizationStep.kt` + `ProjectAnalysisPipeline.kt` 修改 |
| Phase 3 | MD5 缓存机制 | ✅ 完成 | `Md5FileTracker.kt` (已存在) |
| Phase 4 | 生产级测试 | ✅ 完成 | `LlmCodeUnderstandingServiceTest.kt` (12 tests) + `CodeVectorizationCoordinatorTest.kt` (5 tests) |
| **新增** | 配置参数控制 | ✅ 完成 | `smanagent.properties` + `SmanAgentConfig.kt` |

### 已实现功能

1. **LlmCodeUnderstandingService** (`src/main/kotlin/com/smancode/smanagent/analysis/llm/`)
   - `analyzeJavaFile()`: 调用 LLM 分析 Java 文件，生成 .md 文档
   - `analyzeEnumFile()`: 调用 LLM 分析 Enum 文件，生成 .md 文档
   - `parseMarkdownToVectors()`: 从 .md 文档解析向量化数据

2. **CodeVectorizationCoordinator** (`src/main/kotlin/com/smancode/smanagent/analysis/coordination/`)
   - `vectorizeProject(forceUpdate)`: 批量向量化项目中的所有文件
   - `vectorizeFileIfChanged()`: 增量更新单个文件（如果 MD5 变化）
   - 自动保存 .md 文档到 `.smanunion/md/` 目录
   - MD5 缓存存储在 `.smanunion/cache/md5_cache.json`
   - **MD5 增量更新**: 只处理变化的文件，大幅提升性能

3. **LlmCodeVectorizationStep** (`src/main/kotlin/com/smancode/smanagent/analysis/step/`)
   - 新的分析步骤，集成到 `ProjectAnalysisPipeline`
   - 在所有标准分析步骤完成后自动执行
   - 基于配置参数自动启用/禁用

4. **ProjectAnalysisPipeline 集成**
   - 修改了 `executeAllSteps()` 方法
   - 在标准步骤完成后添加 LLM 代码向量化步骤
   - 自动检测配置，决定是否执行

5. **Md5FileTracker** (已存在)
   - 文件变更检测
   - MD5 缓存持久化

6. **配置参数控制** (新增)
   - `analysis.llm.vectorization.enabled`: 是否启用 LLM 代码向量化（默认 false）
   - `analysis.llm.vectorization.full.refresh`: 是否全量刷新，忽略 MD5 缓存（默认 false）

### 使用方式

#### 自动执行（推荐）

系统会自动在项目分析流程中执行 LLM 代码向量化：

1. 在 `smanagent.properties` 中启用：
   ```properties
   # 启用 LLM 代码向量化
   analysis.llm.vectorization.enabled=true

   # 是否全量刷新（通常保持 false）
   analysis.llm.vectorization.full.refresh=false
   ```

2. 在 IntelliJ IDEA 中：
   - 右键点击项目根目录
   - 选择 "SmanAgent" → "项目分析"
   - 等待分析完成

3. 首次运行会分析所有文件，后续只分析变化的文件（基于 MD5）

#### 手动调用（测试用）

```kotlin
val coordinator = CodeVectorizationCoordinator(
    projectKey = "autoloop",
    projectPath = Path.of("/path/to/project"),
    llmService = llmService,
    bgeEndpoint = "http://localhost:8000"
)

// 增量更新（默认）
val result = coordinator.vectorizeProject(forceUpdate = false)

// 全量刷新（慎用）
val result = coordinator.vectorizeProject(forceUpdate = true)
```

### 性能优化

| 场景 | behavior | 说明 |
|------|----------|------|
| 首次运行 | `forceUpdate=false` | 分析所有文件并生成向量 |
| 后续运行 | `forceUpdate=false` | 只处理 MD5 变化的文件 |
| 代码更新 | 自动检测 | 修改文件后自动重新分析该文件 |
| Prompt 更新 | `forceUpdate=true` | 手动全量刷新所有文件 |

```kotlin
// 创建协调器
val coordinator = CodeVectorizationCoordinator(
    projectKey = "autoloop",
    projectPath = Path.of("/path/to/project"),
    llmService = llmService,
    bgeEndpoint = "http://localhost:8000"
)

// 向量化整个项目
val result = coordinator.vectorizeProject()

// 或者增量向量化单个文件
val vectors = coordinator.vectorizeFileIfChanged(javaFile)
```

### 测试结果

- **LlmCodeUnderstandingServiceTest**: 12/12 通过
- **CodeVectorizationCoordinatorTest**: 5/5 通过
- **测试覆盖率**: 核心功能完全覆盖

### Phase 1: LLM 代码分析服务

| 任务 | 描述 | 优先级 |
|------|------|--------|
| 1.1 | 实现 `analyzeJavaFile()` - 调用 LLM 分析 Java 文件 | P0 |
| 1.2 | 实现 `analyzeEnumFile()` - 调用 LLM 分析 Enum 文件 | P0 |
| 1.3 | 实现 `analyzeXmlFile()` - 调用 LLM 分析 XML 文件 | P1 |
| 1.4 | 实现 `parseMarkdownToVectors()` - 从 .md 解析向量 | P0 |

### Phase 2: 向量化协调器

| 任务 | 描述 | 优先级 |
|------|------|--------|
| 2.1 | 实现 `vectorizeProject()` - 批量向量化 | P0 |
| 2.2 | 实现 `vectorizeFileIfChanged()` - 增量更新 | P0 |
| 2.3 | 集成到 `ProjectAnalysisPipeline` | P0 |
| 2.4 | MD5 缓存机制 | P1 |

### Phase 3: 向量化模型

| 任务 | 描述 | 优先级 |
|------|------|--------|
| 3.1 | 定义类向量化模型 | P0 |
| 3.2 | 定义方法向量化模型 | P0 |
| 3.3 | 定义 Enum 向量化模型 | P0 |
| 3.4 | 定义 XML 向量化模型 | P1 |

### Phase 4: 生产级测试

| 任务 | 描述 | 优先级 |
|------|------|--------|
| 4.1 | 单元测试（LLM 分析服务） | P0 |
| 4.2 | 单元测试（向量化协调器） | P0 |
| 4.3 | 集成测试（完整流程） | P0 |
| 4.4 | 性能测试（大型项目） | P1 |

## LLM Prompt 设计

### 类分析 Prompt

```markdown
你是一个代码分析专家。请分析以下 Java 类的业务功能。

## 类信息
类名: {className}
完整签名: {signature}
包名: {packageName}
注解: {annotations}

## 字段
{fields}

## 方法
{methods}

## 分析要求

请生成一个 Markdown 文档，包含以下部分：

### 1. 类信息
- 完整签名
- 包名
- 注解

### 2. 业务描述
用一句话描述这个类的业务功能（不超过50字）。

### 3. 核心数据模型
列出重要的字段及其类型和业务含义。

### 4. 包含功能
列出所有公共方法，每个方法用一句话描述功能。

### 5. 方法详细分析
对于每个方法，生成：
- 方法签名
- 参数列表
- 返回值
- 异常
- 业务描述
- 完整源码

## 输出格式（Markdown）

```markdown
# {className}

## 类信息
...

## 业务描述
...

## 核心数据模型
...

## 包含功能
...

---

## 方法：{methodName1}
...
```

注意：
- 业务描述必须用中文
- 必须保留完整的源代码
- 方法签名必须包含完整参数类型
```

### Enum 分析 Prompt

```markdown
你是一个代码分析专家。请分析以下枚举的业务字典含义。

## 枚举信息
枚举名: {enumName}
完整签名: {signature}
包名: {packageName}

## 枚举值
{values}

## 分析要求

请生成一个 Markdown 文档，包含：

1. 枚举定义
2. 业务描述
3. 字典映射表（枚举值 → 业务含义）

## 输出格式（Markdown）
```

## 方法向量化 Prompt

```markdown
你是一个代码分析专家。请分析以下方法的业务功能。

## 方法上下文
类名: {className}
类描述: {classDescription}

## 方法信息
方法名: {methodName}
签名: {signature}
参数: {parameters}
返回值: {returnType}
异常: {exceptions}

## 源代码
```java
{sourceCode}
```

## 分析要求

请生成：
1. 业务功能描述（一句话，不超过50字）
2. 业务领域（如：贷款、支付、风控）
3. 关键业务概念（3-5个中文关键词）

## 输出格式（JSON）
```json
{
  "description": "业务功能描述",
  "domain": "业务领域",
  "keywords": ["关键词1", "关键词2", "关键词3"]
}
```
```

## 测试用例

### TC1: DisburseHandler 向量化

**输入**:
- 文件: `autoloop/.../DisburseHandler.java`
- 类名: `DisburseHandler`

**预期输出**:
- `.md` 文件: 包含类描述 + 方法源码
- 向量: 1个类向量 + N 个方法向量
- 业务描述: "放款处理器，处理贷款发放业务"

**验证**:
- 查询"放款"能召回 `DisburseHandler`
- 查询"发放贷款"能召回 `DisburseHandler.execute()`

### TC2: LoanStatus Enum 向量化

**输入**:
- 文件: `autoloop/.../LoanStatus.java`
- 枚举名: `LoanStatus`

**预期输出**:
- `.md` 文件: 包含字典映射表
- 向量: 1个枚举向量
- 关键词: ["贷款状态", "申请", "审批", "放款", "还款", "结清"]

**验证**:
- 查询"贷款有哪些状态"能召回 `LoanStatus`

### TC3: MD5 变更检测

**输入**:
- 第一次: 文件 MD5 = A
- 第二次: 文件 MD5 = A（未修改）
- 第三次: 文件 MD5 = B（已修改）

**预期输出**:
- 第一次: 处理并生成向量
- 第二次: 跳过（使用缓存）
- 第三次: 重新处理并更新向量

## 性能指标

| 指标 | 目标 | 备注 |
|------|------|------|
| LLM 调用延迟 | < 5s | 单个文件分析 |
| 向量化速度 | > 100 文件/分钟 | 批量处理 |
| 内存占用 | < 1GB | 1000 个类的项目 |
| 准确率 | > 90% | 业务查询召回准确率 |

## 风险和缓解

| 风险 | 影响 | 缓解措施 |
|------|------|---------|
| LLM 调用失败 | 无法生成 .md | 降级到简单 AST 分析 |
| LLM 返回格式错误 | 解析失败 | 重试 + 格式验证 |
| 大型项目处理时间过长 | 用户体验差 | 增量处理 + 进度条 |
| 向量数据爆炸 | 存储和性能压力 | 分级缓存 + 定期清理 |

## 参考

- 设计文档: `16_代码向量化设计总结.md`
- LLM Prompt 规则: `~/.claude/rules/prompt_rules.md`
- 用户编码规则: `~/.claude/CODING_RULES.md`

## 更新日志

| 日期 | 版本 | 更新内容 |
|------|------|---------|
| 2026-02-03 | 1.0 | 初始版本 |
| 2026-02-03 | 1.1 | 修复 LLM 超时和端点池耗尽问题 |
