# 04 - DBEntity 扫描

## 目标

为 SmanAgent 项目添加数据库实体（DbEntity）扫描能力，从源码中提取数据库表结构信息，识别 Queen 表（核心业务表），并推断业务概念。完全基于 PSI 分析，无需数据库连接或 DDL 文件。

**核心价值**：
- 无需 DDL 文件：从 `@Entity` 和 `@Table` 注解提取表结构
- Queen 表识别：自动识别核心业务表（高置信度）
- 业务概念推断：基于主键而非表名推断业务含义（更准确）
- Mapper XML 验证：通过 MyBatis XML 引用次数调整置信度

## 参考

- knowledge-graph-system 的 `DDL_TO_PSI_MIGRATION_PLAN.md`
- knowledge-graph-system 的 `QueenTableDetector.java`
- knowledge-graph-system 的 `DDLAnalyzer.java`
- knowledge-graph-system 的 `MyBatisXmlReferenceAnalyzer.java`
- knowledge-graph-system 的 `BusinessEntityInferenceService.java`

## 核心功能

### 1. DbEntity 识别（阶段1：PSI 盲猜）

**目标**：从 Java/Kotlin 源码中提取数据库实体信息

**识别规则**：

| 注解 | 说明 | 示例 |
|------|------|------|
| `@Entity` | JPA 实体类 | `@Entity public class Loan { ... }` |
| `@Table` | 表名注解 | `@Table(name = "t_loan")` |
| `@Id` | 主键字段 | `@Id private String loanId;` |
| `@Column` | 列注解 | `@Column(name = "loan_id", nullable = false)` |
| `@OneToMany` | 一对多关联 | `@OneToMany private List<Repayment> repayments;` |
| `@ManyToOne` | 多对一关联 | `@ManyToOne private Customer customer;` |

**提取内容**：
- **类信息**：
  - 类名（如 `LoanEntity`）
  - 全限定名（如 `com.smancode.entity.LoanEntity`）
  - 包名（如 `com.smancode.entity`）
- **表信息**：
  - 表名（优先从 `@Table(name)` 提取，降级从类名推断）
  - 推断规则：`LoanEntity` → `t_loan`
- **字段信息**：
  - 字段名（如 `loanId`）
  - 列名（从 `@Column(name)` 提取，降级从字段名转换）
  - 转换规则：`loanId` → `loan_id`
  - 字段类型（如 `String`, `BigDecimal`）
  - 是否可空（从 `@Column(nullable)` 提取）
  - 是否主键（从 `@Id` 提取）
- **关联关系**：
  - 一对多：`@OneToMany` → 目标实体类
  - 多对一：`@ManyToOne` → 目标实体类
  - 多对多：`@ManyToMany` → 目标实体类

**阶段1置信度计算**：
```kotlin
confidence = 0.3  // 基础分
if (fieldCount > 10) confidence += 0.2
if (relationCount > 2) confidence += 0.2
if (hasPrimaryKey) confidence += 0.1
if (hasTableAnnotation) confidence += 0.2
```

### 2. LLM 确认修正（阶段2）

**目标**：使用 LLM 修正业务名称、表类型、置信度

**输入**：从 DbEntity 生成的伪 DDL
```sql
CREATE TABLE t_loan (
    loan_id VARCHAR PRIMARY KEY NOT NULL,
    customer_id VARCHAR NOT NULL,
    amount DECIMAL,
    status VARCHAR,
    create_time TIMESTAMP
);
```

**LLM Prompt 模板**：
```yaml
你是数据库表分析专家。请分析以下 DDL，输出表的业务信息。

## DDL
${pseudoDDL}

## 类信息
类名: ${className}
包名: ${packageName}

## 分析要求
请分析该表的业务信息，输出以下内容：
1. 业务名称（如：借据、客户、合同）
2. 表类型（QUEEN/COMMON/SYSTEM/LOOKUP）
3. 置信度（0.0-1.0）

## 表类型说明
- QUEEN: 核心业务表（如借据、合同、还款计划）
- COMMON: 普通业务表
- SYSTEM: 系统配置表
- LOOKUP: 字典表

## 输出格式（JSON）
{
  "businessName": "业务名称",
  "tableType": "QUEEN",
  "confidence": 0.85,
  "reasoning": "判断理由"
}
```

**分批调用策略**：
- 每批 10 个表
- 使用 ` CompletableFuture` 并发调用
- 超时时间：30 秒/批

### 3. Mapper XML 引用验证（阶段3）

**目标**：通过 MyBatis Mapper XML 引用次数调整置信度

**扫描路径**：`src/main/resources/mapper/**/*.xml`

**表名提取正则**：
```regex
(?:FROM|JOIN|UPDATE|INSERT\s+INTO|DELETE\s+FROM)\s+([a-zA-Z_][a-zA-Z0-9_]*)
```

**示例**：
```xml
<select id="selectLoan">
    SELECT * FROM ACCT_LOAN WHERE loan_id = #{id}
</select>

<update id="updateStatus">
    UPDATE ACCT_LOAN SET status = #{status}
</update>
```

提取结果：
- `ACCT_LOAN`: 引用 2 次

**置信度调整规则**：
```kotlin
val referenceCount = tableUsage[tableName]?.referenceCount ?: 0

val p90 = percentile90(tableUsages)
val p75 = percentile75(tableUsages)
val p50 = percentile50(tableUsages)

when {
    referenceCount >= p90 -> confidence = min(confidence + 0.15, 0.95)
    referenceCount >= p75 -> confidence = min(confidence + 0.10, 0.85)
    referenceCount >= p50 -> confidence = min(confidence + 0.05, 0.75)
    referenceCount < p50 -> confidence = max(confidence - 0.10, 0.0)
    referenceCount == 0 -> confidence = max(confidence - 0.30, 0.0)
}
```

### 4. Queen 表判断（三阶段融合）

**定义**：Queen 表是核心业务表（高置信度）

**融合流程**：
```
阶段1：PSI 盲猜
   ↓ 生成伪 DDL
阶段2：LLM 确认修正
   ↓ 调整置信度
阶段3：Mapper XML 引用验证
   ↓ 根据引用次数调整
最终输出：QueenTable
```

**QueenTable 数据模型**：
```kotlin
data class QueenTable(
    val tableName: String,
    val className: String,
    val businessName: String,           // LLM 推断
    val tableType: TableType,           // LLM 推断（QUEEN/COMMON/SYSTEM/LOOKUP）
    val confidence: Double,             // 最终置信度
    val stage1Confidence: Double,       // 阶段1置信度
    val stage2Confidence: Double,       // 阶段2置信度（LLM）
    val stage3Confidence: Double,       // 阶段3置信度（调整后）
    val xmlReferenceCount: Int,         // XML引用次数
    val columnCount: Int,
    val primaryKey: String?,
    val pseudoDdl: String               // 伪DDL
)
```

**表类型枚举**：
```kotlin
enum class TableType {
    QUEEN,      // 核心业务表（高置信度 > 0.7）
    COMMON,     // 普通业务表
    SYSTEM,     // 系统配置表
    LOOKUP      // 字典表
}
```

### 5. 业务概念推断

**核心策略**：主键优先（而非表名）

**主键映射表**：
```kotlin
companion object {
    private val PRIMARY_KEY_CONCEPTS = mapOf(
        "loan_id" to "借据",
        "customer_id" to "客户",
        "contract_id" to "合同",
        "repayment_plan_id" to "还款计划",
        "account_id" to "账户",
        "transaction_id" to "交易",
        "order_id" to "订单",
        "product_id" to "产品",
        "user_id" to "用户"
    )
}
```

**降级策略**：
```kotlin
fun inferBusinessConcept(dbEntity: DbEntity): String {
    val primaryKey = dbEntity.primaryKey ?: return inferFromTableName(dbEntity.tableName)

    // 1. 主键优先
    PRIMARY_KEY_CONCEPTS[primaryKey]?.let { return it }

    // 2. 主键模式匹配
    if (primaryKey.endsWith("_id")) {
        val baseName = primaryKey.removeSuffix("_id")
        return baseName  // loan_id -> loan
    }

    // 3. 表名推断
    return inferFromTableName(dbEntity.tableName)
}

private fun inferFromTableName(tableName: String): String {
    // t_loan -> 借据
    // acct_loan -> 借据
    // sys_config -> 配置
    TODO("实现表名推断逻辑")
}
```

## 数据模型

### 1. DbEntity（数据库实体）

```kotlin
/**
 * 数据库实体（从 PSI 提取）
 */
data class DbEntity(
    // 类信息
    val className: String,              // 简单名：LoanEntity
    val qualifiedName: String,          // 全限定名：com.smancode.entity.LoanEntity
    val packageName: String,            // 包名：com.smancode.entity

    // 表信息
    val tableName: String,              // 表名：t_loan
    val hasTableAnnotation: Boolean,    // 是否有 @Table 注解

    // 字段信息
    val fields: List<DbField>,
    val primaryKey: String?,            // 主键字段名：loan_id

    // 关联关系
    val relations: List<DbRelation>,

    // 阶段1置信度
    val stage1Confidence: Double = 0.5
)
```

### 2. DbField（数据库字段）

```kotlin
/**
 * 数据库字段
 */
data class DbField(
    val fieldName: String,              // 字段名：loanId
    val columnName: String,             // 列名：loan_id
    val fieldType: String,              // 字段类型：String
    val columnType: String?,            // 列类型（从 @Column(columnDefinition)）：VARCHAR(32)
    val nullable: Boolean = true,       // 是否可空
    val isPrimaryKey: Boolean = false,  // 是否主键
    val hasColumnAnnotation: Boolean    // 是否有 @Column 注解
)
```

### 3. DbRelation（关联关系）

```kotlin
/**
 * 关联关系
 */
data class DbRelation(
    val relationType: RelationType,     // 关联类型
    val targetEntity: String,           // 目标实体类名：com.smancode.entity.Customer
    val fieldName: String,              // 字段名：customer
    val cascade: List<String> = emptyList()  // 级联操作
)

/**
 * 关联类型枚举
 */
enum class RelationType {
    ONE_TO_MANY,   // 一对多
    MANY_TO_ONE,   // 多对一
    MANY_TO_MANY   // 多对多
}
```

### 4. QueenTable（Queen 表）

```kotlin
/**
 * Queen 表（核心业务表）
 */
data class QueenTable(
    // 表信息
    val tableName: String,
    val className: String,

    // LLM 推断
    val businessName: String,           // 业务名称：借据
    val tableType: TableType,           // 表类型：QUEEN
    val llmReasoning: String?,          // LLM 推理过程

    // 置信度
    val confidence: Double,             // 最终置信度
    val stage1Confidence: Double,       // 阶段1置信度
    val stage2Confidence: Double,       // 阶段2置信度（LLM）
    val stage3Confidence: Double,       // 阶段3置信度（调整后）

    // XML 引用
    val xmlReferenceCount: Int,         // XML 引用次数

    // 表统计
    val columnCount: Int,
    val primaryKey: String?,

    // 伪 DDL
    val pseudoDdl: String               // 伪 DDL
)
```

### 5. TableUsage（表使用情况）

```kotlin
/**
 * 表使用情况（从 Mapper XML 提取）
 */
data class TableUsage(
    val tableName: String,              // 表名
    val mapperFiles: List<String>,      // 引用该表的 Mapper 文件
    val referenceCount: Int,            // 引用次数
    val operations: List<SqlOperation>  // 涉及的 SQL 操作
)

/**
 * SQL 操作
 */
data class SqlOperation(
    val operationType: SqlOperationType,  // 操作类型：SELECT/INSERT/UPDATE/DELETE
    val mapperFile: String,               // Mapper 文件
    val statementId: String               // 语句 ID
)

enum class SqlOperationType {
    SELECT, INSERT, UPDATE, DELETE
}
```

## Kotlin 实现

### 文件位置

```
src/main/kotlin/com/smancode/smanagent/analyzer/
├── database/
│   ├── DbEntityDetector.kt                # DbEntity 检测器（阶段1）
│   ├── MapperXmlAnalyzer.kt               # Mapper XML 分析器（阶段3）
│   ├── QueenTableDetector.kt              # Queen 表检测器（三阶段协调）
│   ├── BusinessConceptInferenceService.kt # 业务概念推断
│   ├── PseudoDdlGenerator.kt              # 伪 DDL 生成器
│   ├── TableReferenceCounter.kt           # 表引用计数器
│   └── model/
│       ├── DbEntity.kt                    # DbEntity 数据模型
│       ├── DbField.kt                     # DbField 数据模型
│       ├── DbRelation.kt                  # DbRelation 数据模型
│       ├── QueenTable.kt                  # QueenTable 数据模型
│       ├── TableUsage.kt                  # TableUsage 数据模型
│       └── TableType.kt                   # TableType 枚举
```

### 核心接口

#### DbEntityDetector（DbEntity 检测器）

```kotlin
/**
 * DbEntity 检测器（阶段1：PSI 盲猜）
 */
interface DbEntityDetector {
    /**
     * 检测所有 DbEntity
     * @param projectKey 项目标识
     * @return DbEntity 列表
     */
    suspend fun detect(projectKey: String): List<DbEntity>

    /**
     * 从 PsiClass 构建 DbEntity
     * @param psiClass PSI 类
     * @return DbEntity
     */
    fun buildDbEntity(psiClass: PsiClass): DbEntity

    /**
     * 推断表名
     * @param psiClass PSI 类
     * @return 表名
     */
    fun inferTableName(psiClass: PsiClass): String

    /**
     * 提取字段信息
     * @param psiClass PSI 类
     * @return 字段列表
     */
    fun extractFields(psiClass: PsiClass): List<DbField>

    /**
     * 计算阶段1置信度
     * @param dbEntity DbEntity
     * @return 置信度
     */
    fun calculateStage1Confidence(dbEntity: DbEntity): Double
}
```

#### MapperXmlAnalyzer（Mapper XML 分析器）

```kotlin
/**
 * Mapper XML 分析器（阶段3）
 */
interface MapperXmlAnalyzer {
    /**
     * 分析所有 Mapper XML
     * @param projectKey 项目标识
     * @return Map<表名, TableUsage>
     */
    suspend fun analyze(projectKey: String): Map<String, TableUsage>

    /**
     * 分析单个 Mapper XML
     * @param xmlFile XML 文件
     * @return TableUsage 列表
     */
    fun analyzeSingleXml(xmlFile: VirtualFile): List<TableUsage>

    /**
     * 提取表名
     * @param xmlContent XML 内容
     * @return 表名集合
     */
    fun extractTableNames(xmlContent: String): Set<String>
}
```

#### QueenTableDetector（Queen 表检测器）

```kotlin
/**
 * Queen 表检测器（三阶段协调）
 */
interface QueenTableDetector {
    /**
     * 检测 Queen 表
     * @param projectKey 项目标识
     * @return QueenTable 列表（置信度 > 0.7）
     */
    suspend fun detect(projectKey: String): List<QueenTable>

    /**
     * 阶段1：PSI 盲猜
     * @param projectKey 项目标识
     * @return DbEntity 列表
     */
    suspend fun stage1Detect(projectKey: String): List<DbEntity>

    /**
     * 阶段2：LLM 确认修正
     * @param dbEntities DbEntity 列表
     * @return 修正后的 DbEntity 列表
     */
    suspend fun stage2Confirm(dbEntities: List<DbEntity>): List<DbEntity>

    /**
     * 阶段3：Mapper XML 引用验证
     * @param dbEntities DbEntity 列表
     * @param projectKey 项目标识
     * @return QueenTable 列表
     */
    suspend fun stage3Validate(
        dbEntities: List<DbEntity>,
        projectKey: String
    ): List<QueenTable>
}
```

#### BusinessConceptInferenceService（业务概念推断服务）

```kotlin
/**
 * 业务概念推断服务
 */
interface BusinessConceptInferenceService {
    /**
     * 推断业务概念
     * @param dbEntity DbEntity
     * @return 业务概念（如：借据、客户）
     */
    fun infer(dbEntity: DbEntity): String

    /**
     * 从主键推断
     * @param primaryKey 主键
     * @return 业务概念
     */
    fun inferFromPrimaryKey(primaryKey: String): String?

    /**
     * 从表名推断
     * @param tableName 表名
     * @return 业务概念
     */
    fun inferFromTableName(tableName: String): String
}
```

### 核心实现

#### DbEntityDetectorImpl（DbEntity 检测器实现）

```kotlin
/**
 * DbEntity 检测器实现
 */
@Component
class DbEntityDetectorImpl(
    private val project: Project,
    private val psiManager: PsiManager
) : DbEntityDetector {

    private val logger = LoggerFactory.getLogger(this::class.java)

    override suspend fun detect(projectKey: String): List<DbEntity> {
        logger.info("开始检测 DbEntity: projectKey={}", projectKey)

        val startTime = System.currentTimeMillis()

        // 1. 查找所有 @Entity 类
        val entityClasses = findEntityClasses()
        logger.info("找到 {} 个 @Entity 类", entityClasses.size)

        // 2. 构建 DbEntity
        val dbEntities = entityClasses.map { buildDbEntity(it) }

        val duration = System.currentTimeMillis() - startTime
        logger.info("DbEntity 检测完成: 耗时={}ms, 数量={}", duration, dbEntities.size)

        return dbEntities
    }

    override fun buildDbEntity(psiClass: PsiClass): DbEntity {
        val className = psiClass.name ?: "Unknown"
        val qualifiedName = psiClass.qualifiedName ?: ""
        val packageName = getPackageName(psiClass)

        // 推断表名
        val tableName = inferTableName(psiClass)
        val hasTableAnnotation = hasTableAnnotation(psiClass)

        // 提取字段
        val fields = extractFields(psiClass)
        val primaryKey = fields.find { it.isPrimaryKey }?.columnName

        // 提取关联关系
        val relations = extractRelations(psiClass)

        // 计算阶段1置信度
        val dbEntity = DbEntity(
            className = className,
            qualifiedName = qualifiedName,
            packageName = packageName,
            tableName = tableName,
            hasTableAnnotation = hasTableAnnotation,
            fields = fields,
            primaryKey = primaryKey,
            relations = relations
        )
        val stage1Confidence = calculateStage1Confidence(dbEntity)

        return dbEntity.copy(stage1Confidence = stage1Confidence)
    }

    override fun inferTableName(psiClass: PsiClass): String {
        // 1. 优先从 @Table(name) 提取
        val tableAnnotation = psiClass.getAnnotation("javax.persistence.Table")
            ?: psiClass.getAnnotation("jakarta.persistence.Table")

        tableAnnotation?.findAttributeValue("name")?.let { attributeValue ->
            val tableName = attributeValue.text?.removeSurrounding("\"")
            if (!tableName.isNullOrBlank()) {
                return tableName
            }
        }

        // 2. 降级：从类名推断
        val className = psiClass.name ?: return "unknown"

        // 移除常见后缀：Entity, PO, DO
        val baseName = className.removeSuffix("Entity")
            .removeSuffix("PO")
            .removeSuffix("DO")

        // 驼峰转下划线
        val tableName = baseName.replace(Regex("([a-z])([A-Z])"), "$1_$2").lowercase()

        // 添加常见前缀
        return when {
            tableName.contains("loan") -> "t_$tableName"
            tableName.contains("customer") -> "t_$tableName"
            else -> "t_$tableName"
        }
    }

    override fun extractFields(psiClass: PsiClass): List<DbField> {
        return psiClass.fields.map { field ->
            val fieldName = field.name
            val fieldType = field.type.canonicalText

            // 从 @Column 提取列名
            val columnAnnotation = field.getAnnotation("javax.persistence.Column")
                ?: field.getAnnotation("jakarta.persistence.Column")

            var columnName = fieldName
            var columnType: String? = null
            var nullable = true
            var hasColumnAnnotation = false

            columnAnnotation?.let {
                hasColumnAnnotation = true

                // 提取 name 属性
                it.findAttributeValue("name")?.let { attr ->
                    columnName = attr.text?.removeSurrounding("\"") ?: fieldName
                }

                // 提取 nullable 属性
                it.findAttributeValue("nullable")?.let { attr ->
                    nullable = attr.text != "false"
                }

                // 提取 columnDefinition 属性
                it.findAttributeValue("columnDefinition")?.let { attr ->
                    columnType = attr.text?.removeSurrounding("\"")
                }
            }

            // 如果没有 @Column 注解，从字段名转换
            if (!hasColumnAnnotation) {
                columnName = fieldName.replace(Regex("([a-z])([A-Z])"), "$1_$2").lowercase()
            }

            // 检查是否主键
            val isPrimaryKey = field.annotations.any {
                it.qualifiedName == "javax.persistence.Id" ||
                it.qualifiedName == "jakarta.persistence.Id"
            }

            DbField(
                fieldName = fieldName,
                columnName = columnName,
                fieldType = fieldType,
                columnType = columnType,
                nullable = nullable,
                isPrimaryKey = isPrimaryKey,
                hasColumnAnnotation = hasColumnAnnotation
            )
        }
    }

    override fun calculateStage1Confidence(dbEntity: DbEntity): Double {
        var confidence = 0.3  // 基础分

        // 字段数量
        if (dbEntity.fields.size > 10) {
            confidence += 0.2
        }

        // 关联关系数量
        if (dbEntity.relations.size > 2) {
            confidence += 0.2
        }

        // 有主键
        if (dbEntity.primaryKey != null) {
            confidence += 0.1
        }

        // 有 @Table 注解
        if (dbEntity.hasTableAnnotation) {
            confidence += 0.2
        }

        return confidence.coerceAtMost(1.0)
    }

    /**
     * 查找所有 @Entity 类
     */
    private fun findEntityClasses(): List<PsiClass> {
        val entityClasses = mutableListOf<PsiClass>()

        val scope = GlobalSearchScope.projectScope(project)
        val classes = PsiShortNamesCache.getInstance(project).allClasses(scope)

        for (psiClass in classes) {
            val hasEntityAnnotation = psiClass.annotations.any {
                it.qualifiedName == "javax.persistence.Entity" ||
                it.qualifiedName == "jakarta.persistence.Entity"
            }

            if (hasEntityAnnotation) {
                entityClasses.add(psiClass)
            }
        }

        return entityClasses
    }

    /**
     * 提取关联关系
     */
    private fun extractRelations(psiClass: PsiClass): List<DbRelation> {
        val relations = mutableListOf<DbRelation>()

        for (field in psiClass.fields) {
            for (annotation in field.annotations) {
                when (annotation.qualifiedName) {
                    "javax.persistence.OneToMany",
                    "jakarta.persistence.OneToMany" -> {
                        val targetEntity = extractTargetEntity(annotation)
                        if (targetEntity != null) {
                            relations.add(
                                DbRelation(
                                    relationType = RelationType.ONE_TO_MANY,
                                    targetEntity = targetEntity,
                                    fieldName = field.name
                                )
                            )
                        }
                    }
                    "javax.persistence.ManyToOne",
                    "jakarta.persistence.ManyToOne" -> {
                        val targetEntity = extractTargetEntity(annotation)
                        if (targetEntity != null) {
                            relations.add(
                                DbRelation(
                                    relationType = RelationType.MANY_TO_ONE,
                                    targetEntity = targetEntity,
                                    fieldName = field.name
                                )
                            )
                        }
                    }
                    "javax.persistence.ManyToMany",
                    "jakarta.persistence.ManyToMany" -> {
                        val targetEntity = extractTargetEntity(annotation)
                        if (targetEntity != null) {
                            relations.add(
                                DbRelation(
                                    relationType = RelationType.MANY_TO_MANY,
                                    targetEntity = targetEntity,
                                    fieldName = field.name
                                )
                            )
                        }
                    }
                }
            }
        }

        return relations
    }

    /**
     * 提取目标实体类
     */
    private fun extractTargetEntity(annotation: PsiAnnotation): String? {
        return annotation.findAttributeValue("targetEntity")?.text?.removeSurrounding("\"")
    }

    /**
     * 检查是否有 @Table 注解
     */
    private fun hasTableAnnotation(psiClass: PsiClass): Boolean {
        return psiClass.annotations.any {
            it.qualifiedName == "javax.persistence.Table" ||
            it.qualifiedName == "jakarta.persistence.Table"
        }
    }

    /**
     * 获取包名
     */
    private fun getPackageName(psiClass: PsiClass): String {
        return psiClass.qualifiedName?.substringBeforeLast('.') ?: ""
    }
}
```

#### MapperXmlAnalyzerImpl（Mapper XML 分析器实现）

```kotlin
/**
 * Mapper XML 分析器实现
 */
@Component
class MapperXmlAnalyzerImpl(
    private val project: Project
) : MapperXmlAnalyzer {

    private val logger = LoggerFactory.getLogger(this::class.java)

    companion object {
        // 表名提取正则
        private val TABLE_NAME_PATTERN = Regex(
            "(?:FROM|JOIN|UPDATE|INSERT\\s+INTO|DELETE\\s+FROM)\\s+([a-zA-Z_][a-zA-Z0-9_]*)",
            RegexOption.IGNORE_CASE
        )
    }

    override suspend fun analyze(projectKey: String): Map<String, TableUsage> {
        logger.info("开始分析 Mapper XML: projectKey={}", projectKey)

        val startTime = System.currentTimeMillis()

        // 1. 查找所有 Mapper XML
        val mapperXmls = findMapperXmls()
        logger.info("找到 {} 个 Mapper XML", mapperXmls.size)

        // 2. 分析每个 XML
        val allTableUsages = mutableListOf<TableUsage>()
        for (xmlFile in mapperXmls) {
            val tableUsages = analyzeSingleXml(xmlFile)
            allTableUsages.addAll(tableUsages)
        }

        // 3. 合并相同表的引用
        val merged = mergeTableUsages(allTableUsages)

        val duration = System.currentTimeMillis() - startTime
        logger.info("Mapper XML 分析完成: 耗时={}ms, 表数={}", duration, merged.size)

        return merged
    }

    override fun analyzeSingleXml(xmlFile: VirtualFile): List<TableUsage> {
        val content = xmlFile.inputStream.use { it.bufferedReader().readText() }

        // 1. 提取表名
        val tableNames = extractTableNames(content)

        // 2. 提取 SQL 操作
        val operations = extractSqlOperations(content, xmlFile.name)

        // 3. 构建 TableUsage
        return tableNames.map { tableName ->
            TableUsage(
                tableName = tableName,
                mapperFiles = listOf(xmlFile.path),
                referenceCount = 1,
                operations = operations.filter { it.tableName == tableName }
            )
        }
    }

    override fun extractTableNames(xmlContent: String): Set<String> {
        val matches = TABLE_NAME_PATTERN.findAll(xmlContent)
        return matches.map { it.groupValues[1].lowercase() }.toSet()
    }

    /**
     * 查找所有 Mapper XML
     */
    private fun findMapperXmls(): List<VirtualFile> {
        val mapperXmls = mutableListOf<VirtualFile>()

        val resourcesDir = project.baseDir.findChild("src")?.findChild("main")?.findChild("resources")

        resourcesDir?.let { resources ->
            resources.findChild("mapper")?.let { mapperDir ->
                collectXmlFiles(mapperDir, mapperXmls)
            }
        }

        return mapperXmls
    }

    /**
     * 递归收集 XML 文件
     */
    private fun collectXmlFiles(directory: VirtualFile, result: MutableList<VirtualFile>) {
        for (child in directory.children) {
            if (child.isDirectory) {
                collectXmlFiles(child, result)
            } else if (child.fileType.name == "XML" && child.name.endsWith("Mapper.xml")) {
                result.add(child)
            }
        }
    }

    /**
     * 提取 SQL 操作
     */
    private fun extractSqlOperations(xmlContent: String, mapperFileName: String): List<SqlOperation> {
        val operations = mutableListOf<SqlOperation>()

        // 解析 XML
        val doc = Jsoup.parse(xmlContent)
        val mapper = doc.selectFirst("mapper") ?: return operations

        // 提取 <select>
        mapper.select("select").forEach { element ->
            val id = element.attr("id")
            val tableName = extractTableNames(element.html()).firstOrNull()
            if (tableName != null) {
                operations.add(
                    SqlOperation(
                        operationType = SqlOperationType.SELECT,
                        mapperFile = mapperFileName,
                        statementId = id,
                        tableName = tableName
                    )
                )
            }
        }

        // 提取 <insert>
        mapper.select("insert").forEach { element ->
            val id = element.attr("id")
            val tableName = extractTableNames(element.html()).firstOrNull()
            if (tableName != null) {
                operations.add(
                    SqlOperation(
                        operationType = SqlOperationType.INSERT,
                        mapperFile = mapperFileName,
                        statementId = id,
                        tableName = tableName
                    )
                )
            }
        }

        // 提取 <update>
        mapper.select("update").forEach { element ->
            val id = element.attr("id")
            val tableName = extractTableNames(element.html()).firstOrNull()
            if (tableName != null) {
                operations.add(
                    SqlOperation(
                        operationType = SqlOperationType.UPDATE,
                        mapperFile = mapperFileName,
                        statementId = id,
                        tableName = tableName
                    )
                )
            }
        }

        // 提取 <delete>
        mapper.select("delete").forEach { element ->
            val id = element.attr("id")
            val tableName = extractTableNames(element.html()).firstOrNull()
            if (tableName != null) {
                operations.add(
                    SqlOperation(
                        operationType = SqlOperationType.DELETE,
                        mapperFile = mapperFileName,
                        statementId = id,
                        tableName = tableName
                    )
                )
            }
        }

        return operations
    }

    /**
     * 合并相同表的引用
     */
    private fun mergeTableUsages(tableUsages: List<TableUsage>): Map<String, TableUsage> {
        val merged = mutableMapOf<String, TableUsage>()

        for (usage in tableUsages) {
            val existing = merged[usage.tableName]
            if (existing == null) {
                merged[usage.tableName] = usage
            } else {
                merged[usage.tableName] = existing.copy(
                    mapperFiles = existing.mapperFiles + usage.mapperFiles,
                    referenceCount = existing.referenceCount + usage.referenceCount,
                    operations = existing.operations + usage.operations
                )
            }
        }

        return merged
    }
}
```

#### QueenTableDetectorImpl（Queen 表检测器实现）

```kotlin
/**
 * Queen 表检测器实现
 */
@Component
class QueenTableDetectorImpl(
    private val dbEntityDetector: DbEntityDetector,
    private val mapperXmlAnalyzer: MapperXmlAnalyzer,
    private val pseudoDdlGenerator: PseudoDdlGenerator,
    private val llmService: LlmService,
    private val tableReferenceCounter: TableReferenceCounter
) : QueenTableDetector {

    private val logger = LoggerFactory.getLogger(this::class.java)

    override suspend fun detect(projectKey: String): List<QueenTable> {
        logger.info("开始检测 Queen 表: projectKey={}", projectKey)

        val startTime = System.currentTimeMillis()

        // 阶段1：PSI 盲猜
        val stage1Result = stage1Detect(projectKey)
        logger.info("阶段1完成: 找到 {} 个 DbEntity", stage1Result.size)

        // 阶段2：LLM 确认修正
        val stage2Result = stage2Confirm(stage1Result)
        logger.info("阶段2完成: LLM 修正完成")

        // 阶段3：Mapper XML 引用验证
        val stage3Result = stage3Validate(stage2Result, projectKey)
        logger.info("阶段3完成: Mapper XML 验证完成")

        // 过滤：只保留置信度 > 0.7 的表
        val queenTables = stage3Result.filter { it.confidence > 0.7 }

        val duration = System.currentTimeMillis() - startTime
        logger.info("Queen 表检测完成: 耗时={}ms, Queen表数={}", duration, queenTables.size)

        return queenTables
    }

    override suspend fun stage1Detect(projectKey: String): List<DbEntity> {
        return dbEntityDetector.detect(projectKey)
    }

    override suspend fun stage2Confirm(dbEntities: List<DbEntity>): List<DbEntity> {
        // 分批调用 LLM（每批 10 个表）
        val batchSize = 10
        val batches = dbEntities.chunked(batchSize)

        val confirmedEntities = mutableListOf<DbEntity>()

        for (batch in batches) {
            val confirmed = confirmBatch(batch)
            confirmedEntities.addAll(confirmed)
        }

        return confirmedEntities
    }

    override suspend fun stage3Validate(
        dbEntities: List<DbEntity>,
        projectKey: String
    ): List<QueenTable> {
        // 1. 分析 Mapper XML
        val tableUsages = mapperXmlAnalyzer.analyze(projectKey)

        // 2. 计算百分位数
        val referenceCounts = tableUsages.values.map { it.referenceCount }
        val p90 = percentile(referenceCounts, 90.0)
        val p75 = percentile(referenceCounts, 75.0)
        val p50 = percentile(referenceCounts, 50.0)

        // 3. 调整置信度
        val queenTables = dbEntities.map { dbEntity ->
            val tableName = dbEntity.tableName
            val usage = tableUsages[tableName]
            val referenceCount = usage?.referenceCount ?: 0

            // 根据引用次数调整置信度
            val adjustedConfidence = when {
                referenceCount >= p90 -> min(dbEntity.stage2Confidence + 0.15, 0.95)
                referenceCount >= p75 -> min(dbEntity.stage2Confidence + 0.10, 0.85)
                referenceCount >= p50 -> min(dbEntity.stage2Confidence + 0.05, 0.75)
                referenceCount < p50 -> max(dbEntity.stage2Confidence - 0.10, 0.0)
                else -> dbEntity.stage2Confidence
            }

            QueenTable(
                tableName = tableName,
                className = dbEntity.className,
                businessName = dbEntity.businessName,
                tableType = dbEntity.tableType,
                llmReasoning = dbEntity.llmReasoning,
                confidence = adjustedConfidence,
                stage1Confidence = dbEntity.stage1Confidence,
                stage2Confidence = dbEntity.stage2Confidence,
                stage3Confidence = adjustedConfidence,
                xmlReferenceCount = referenceCount,
                columnCount = dbEntity.fields.size,
                primaryKey = dbEntity.primaryKey,
                pseudoDdl = dbEntity.pseudoDdl
            )
        }

        return queenTables
    }

    /**
     * 确认一批 DbEntity
     */
    private suspend fun confirmBatch(dbEntities: List<DbEntity>): List<DbEntity> {
        // 1. 生成伪 DDL
        val pseudoDdls = dbEntities.associateWith { pseudoDdlGenerator.generate(it) }

        // 2. 构建 LLM Prompt
        val prompt = buildConfirmPrompt(dbEntities, pseudoDdls)

        // 3. 调用 LLM
        val response = llmService.chat(prompt)

        // 4. 解析 LLM 返回
        return parseLLMResponse(dbEntities, response)
    }

    /**
     * 构建确认 Prompt
     */
    private fun buildConfirmPrompt(
        dbEntities: List<DbEntity>,
        pseudoDdls: Map<DbEntity, String>
    ): String {
        val sb = StringBuilder()

        sb.append("你是数据库表分析专家。请分析以下 DDL，输出表的业务信息。\n\n")

        for (dbEntity in dbEntities) {
            val ddl = pseudoDdls[dbEntity] ?: ""

            sb.append("## 表 ${dbEntity.tableName}\n")
            sb.append("类名: ${dbEntity.qualifiedName}\n")
            sb.append("主键: ${dbEntity.primaryKey}\n")
            sb.append("字段数: ${dbEntity.fields.size}\n")
            sb.append("\n### DDL\n")
            sb.append(ddl)
            sb.append("\n\n")
        }

        sb.append("## 分析要求\n")
        sb.append("请分析每个表的业务信息，输出 JSON 数组：\n")
        sb.append("```\n")
        sb.append("[\n")
        sb.append("  {\n")
        sb.append("    \"tableName\": \"表名\",\n")
        sb.append("    \"businessName\": \"业务名称\",\n")
        sb.append("    \"tableType\": \"QUEEN/COMMON/SYSTEM/LOOKUP\",\n")
        sb.append("    \"confidence\": 0.85,\n")
        sb.append("    \"reasoning\": \"判断理由\"\n")
        sb.append("  }\n")
        sb.append("]\n")
        sb.append("```\n")

        return sb.toString()
    }

    /**
     * 解析 LLM 返回
     */
    private fun parseLLMResponse(
        dbEntities: List<DbEntity>,
        response: String
    ): List<DbEntity> {
        // 解析 JSON 数组
        val jsonArray = Json.parseToJsonElement(response).jsonArray

        return dbEntities.mapIndexed { index, dbEntity ->
            if (index < jsonArray.size) {
                val jsonObject = jsonArray[index].jsonObject
                val businessName = jsonObject["businessName"]?.jsonPrimitive?.content
                val tableType = jsonObject["tableType"]?.jsonPrimitive?.content
                val confidence = jsonObject["confidence"]?.jsonPrimitive?.double
                val reasoning = jsonObject["reasoning"]?.jsonPrimitive?.content

                dbEntity.copy(
                    businessName = businessName ?: dbEntity.businessName,
                    tableType = TableType.valueOf(tableType ?: "COMMON"),
                    stage2Confidence = confidence ?: dbEntity.stage1Confidence,
                    llmReasoning = reasoning
                )
            } else {
                dbEntity
            }
        }
    }

    /**
     * 计算百分位数
     */
    private fun percentile(values: List<Int>, percentile: Double): Int {
        if (values.isEmpty()) return 0

        val sorted = values.sorted()
        val index = ((percentile / 100.0) * (sorted.size - 1)).toInt().coerceIn(0, sorted.size - 1)

        return sorted[index]
    }
}
```

## 与 knowledge-graph-system 的差异

### 可以借鉴的部分

1. **三阶段检测模型**
   - 阶段1：元数据盲猜 → PSI 盲猜
   - 阶段2：DDL 确认修正 → LLM 确认修正
   - 阶段3：MyBatis XML 引用验证 → 复用原逻辑

2. **业务概念推断**
   - 主键优先（更准确）
   - 主键映射表
   - 降级策略

3. **Mapper XML 分析**
   - 表名提取正则
   - 引用次数统计
   - SQL 操作分类

4. **置信度计算**
   - 三阶段融合
   - 百分位数调整

### 需要调整的部分

1. **数据源**
   - knowledge-graph-system：数据库连接（`DatabaseMetaData`）
   - SmanAgent：Java PSI（无需数据库连接）

2. **DDL 生成**
   - knowledge-graph-system：从数据库提取完整 DDL
   - SmanAgent：从 PSI 生成伪 DDL

3. **表名推断**
   - knowledge-graph-system：直接从数据库获取
   - SmanAgent：从 `@Table(name)` 或类名推断

4. **存储方式**
   - knowledge-graph-system：存入文件系统
   - SmanAgent：存入向量库（待第 11 节设计）

## 专家知识库

### 关键问题

1. **如何从 PSI 生成伪 DDL？**
   - 从 DbEntity 提取字段信息
   - 映射 Java 类型到 SQL 类型
   - 生成 `CREATE TABLE` 语句
   - 参考下面"伪 DDL 生成规则"

2. **如何推断表名？**
   - 优先：`@Table(name="xxx")` 注解
   - 降级：类名转换（`LoanEntity` → `t_loan`）

3. **如何判断 Queen 表？**
   - 三阶段融合
   - 置信度 > 0.7
   - `tableType == QUEEN`

4. **业务概念推断为什么要用主键？**
   - 主键更直接表达业务含义
   - `loan_id` → 借据（明确）
   - 表名 `t_loan` 可能不准确（可能是 `t_loan_info`）

5. **如何处理没有 @Entity 注解的类？**
   - 只扫描带 `@Entity` 或 `@Table` 注解的类
   - 其他类忽略

### 最佳实践

1. **伪 DDL 生成规则**
   ```kotlin
   fun generatePseudoDdl(dbEntity: DbEntity): String {
       val sb = StringBuilder()

       sb.append("CREATE TABLE ${dbEntity.tableName} (\n")

       for (field in dbEntity.fields) {
           val columnName = field.columnName
           val columnType = mapJavaTypeToSql(field.fieldType)
           val nullable = if (field.nullable) "" else "NOT NULL"
           val primaryKey = if (field.isPrimaryKey) "PRIMARY KEY" else ""

           sb.append("    $columnName $columnType $nullable $primaryKey,\n")
       }

       // 移除最后一个逗号
       sb.setLength(sb.length - 2)
       sb.append("\n);\n")

       return sb.toString()
   }

   fun mapJavaTypeToSql(javaType: String): String {
       return when {
           javaType == "java.lang.String" -> "VARCHAR(32)"
           javaType == "java.lang.Integer" -> "INT"
           javaType == "java.lang.Long" -> "BIGINT"
           javaType == "java.math.BigDecimal" -> "DECIMAL(18,2)"
           javaType == "java.time.LocalDateTime" -> "TIMESTAMP"
           javaType == "java.util.Date" -> "TIMESTAMP"
           javaType == "boolean" || javaType == "java.lang.Boolean" -> "TINYINT(1)"
           else -> "VARCHAR(255)"
       }
   }
   ```

2. **类型映射表**
   | Java 类型 | SQL 类型 |
   |-----------|----------|
   | String | VARCHAR(32) |
   | Integer | INT |
   | Long | BIGINT |
   | BigDecimal | DECIMAL(18,2) |
   | LocalDateTime | TIMESTAMP |
   | Date | TIMESTAMP |
   | Boolean | TINYINT(1) |
   | 其他 | VARCHAR(255) |

3. **LLM Prompt 设计**
   - 明确输出格式（JSON）
   - 提供类信息（帮助理解业务）
   - 提供表类型说明
   - 要求给出推理过程

4. **分批调用策略**
   - 每批 10 个表
   - 使用 `CompletableFuture` 并发
   - 超时时间：30 秒/批

## 待解决的问题

1. **伪 DDL 生成准确性**
   - 如何处理复杂的关联关系？
   - 如何处理索引、约束？

2. **LLM 推理稳定性**
   - LLM 置信度不稳定
   - 需要置信度阈值 + 人工审核

3. **业务概念映射表扩充**
   - 当前只覆盖 9 个主键
   - 需要更多业务场景

4. **性能优化**
   - PSI 扫描 1000 个类需要多久？
   - LLM 调用 100 个表需要多久？

5. **向量库存储**
   - 如何设计 DbEntity 的向量 Schema？
   - 如何生成 embedding？
   - 待第 11 节设计

## 下一步

- [ ] 实现基础 PSI 扫描（阶段1）
- [ ] 实现伪 DDL 生成
- [ ] 实现业务概念推断
- [ ] 实现 Mapper XML 分析（阶段3）
- [ ] 实现 LLM 确认修正（阶段2）
- [ ] 实现三阶段融合
- [x] 编写单元测试
- [ ] 性能优化
- [x] 设计向量库存储（第 11 节）

---

## ✅ 实现完成 (2026-01-30)

### 实现文件

- **位置**: `src/main/kotlin/com/smancode/smanagent/analysis/entity/DbEntityScanner.kt`
- **测试**: 所有测试通过 (196 tests passed)

### 实现功能

1. ✅ **实体类扫描** - 识别 @Entity、@Table 注解的类
2. ✅ **字段提取** - 提取所有字段及类型
3. ✅ **主键识别** - 识别 @Id、@PrimaryKey 字段
4. ✅ **关系识别** - 识别 @OneToMany、@ManyToOne 等关系
5. ✅ **表名推断** - 从注解或类名推断表名

### 验证状态

```bash
./gradlew test
# BUILD SUCCESSFUL - 196 tests completed
```
