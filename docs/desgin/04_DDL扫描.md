# 04 - DDL 扫描

## 目标

为 SmanAgent 项目添加 DDL（数据定义语言）扫描能力，通过分析项目中的数据库定义文件、实体类注解和 MyBatis Mapper XML，提取完整的数据库结构信息，包括表结构、字段信息、关联关系和 SQL 语句，为后续的 BGE 召回+重排提供基础数据。

## 参考

- knowledge-graph-system 的 `DDLBusinessEntityExtractor.java`
- knowledge-graph-system 的 `DDLAnalyzer.java`
- knowledge-graph-system 的 `MyBatisXmlReferenceAnalyzer.java`
- knowledge-graph-system 的 `BusinessEntityInferenceService.java`
- knowledge-graph-system 的 `BusinessConceptMappings.java`

## 核心功能

### 1. 数据源扫描（多源融合）

**目标**：从多个数据源收集数据库定义信息

**数据源优先级**：
```
1. DDL 文件（.sql, .ddl）
   ↓
2. JPA 实体类（@Entity, @Table）
   ↓
3. MyBatis Mapper XML（<resultMap>）
   ↓
4. MyBatis Mapper 接口（@Mapper）
```

**扫描规则**：

#### 1.1 DDL 文件扫描

```kotlin
// 扫描路径
- src/main/resources/db/migration/
- src/main/resources/
- src/main/resources/sql/

// 文件类型
- *.sql
- *.ddl

// 排除目录
- /test/
- /build/
- /target/
```

**解析策略**：
- 使用正则表达式匹配 `CREATE TABLE` 语句
- 支持嵌套括号（处理字段默认值中的函数）
- 提取表名、字段定义、主键、索引、约束

```kotlin
// 正则表达式（支持嵌套括号）
private val CREATE_TABLE_PATTERN = Pattern.compile(
    "CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?([\\w_]+)\\s*\\((.*)\\)\\s*(?:ENGINE|COMMENT)",
    Pattern.CASE_INSENSITIVE or Pattern.DOTALL
)

// 字段定义正则
private val COLUMN_PATTERN = Pattern.compile(
    "^([\\w_]+)\\s+(\\w+(?:\\([^)]+\\))?)",
    Pattern.CASE_INSENSITIVE
)
```

#### 1.2 JPA 实体类扫描

**扫描规则**：
```kotlin
// 识别注解
@Entity → 实体类
@Table(name = "table_name") → 表名映射
@Column(name = "column_name") → 字段映射
@Id → 主键
@GeneratedValue → 自增主键
@OneToMany / @ManyToOne / @OneToOne / @ManyToMany → 关联关系
@JoinColumn → 外键关系
@Index → 索引定义
```

**PSI 解析策略**：
```kotlin
// 1. 查找所有 @Entity 注解的类
val entityClasses = project.allFiles()
    .filter { it.extension == "java" || it.extension == "kt" }
    .mapNotNull { psiManager.findFile(it) }
    .filter { it.hasAnnotation("javax.persistence.Entity") }

// 2. 提取表名（优先 @Table，否则类名）
val tableName = entity.getAnnotationAttributeValue("Table", "name")
    ?: entity.name.decapitalize()

// 3. 提取字段映射
entity.fields.forEach { field ->
    val columnName = field.getAnnotationAttributeValue("Column", "name")
        ?: field.name
    val isPrimaryKey = field.hasAnnotation("Id")
    // ...
}
```

#### 1.3 MyBatis Mapper XML 扫描

**扫描路径**：
```kotlin
- src/main/resources/mapper/
- src/main/resources/dao/
- src/main/resources/mybatis/
```

**解析策略**：
```kotlin
// 1. 解析 <resultMap> 获取表结构
<resultMap id="BaseResultMap" type="com.example.User">
    <id column="id" property="id" />
    <result column="user_name" property="userName" />
</resultMap>

// 2. 解析 SQL 语句
<select id="selectById" resultType="User">
    SELECT id, user_name FROM t_user WHERE id = #{id}
</select>

// 3. 统计表引用次数（用于置信度调整）
val TABLE_REFERENCE_PATTERN = Pattern.compile(
    "(?:FROM|JOIN|UPDATE|INSERT\\s+INTO|DELETE\\s+FROM)\\s+([a-zA-Z_][a-zA-Z0-9_]*)",
    Pattern.CASE_INSENSITIVE
)
```

#### 1.4 MyBatis Mapper 接口扫描

**识别注解**：
```kotlin
@Mapper → MyBatis Mapper
@Repository → DAO 层
@Select / @Insert / @Update / @Delete → SQL 注解
```

### 2. 表结构信息提取

**目标**：从多个数据源融合表结构信息

**输出模型**：
```kotlin
data class TableInfo(
    // 基本信息
    val tableName: String,                    // 表名
    val entityName: String?,                  // 对应实体类名
    val businessName: String,                 // 业务名称（从主键推断）
    val comment: String?,                     // 表注释

    // 字段信息
    val columns: List<ColumnInfo>,
    val primaryKeys: List<String>,            // 主键列表

    // 约束和索引
    val indexes: List<IndexInfo>,
    val foreignKeys: List<ForeignKeyInfo>,

    // 关联关系
    val relations: List<RelationInfo>,

    // 数据源置信度
    val sourceType: SourceType,               // 数据源类型
    val confidence: Double,                   // 置信度（0-1）

    // MyBatis 引用统计
    val xmlReferenceCount: Int = 0,           // XML 中被引用次数
)

enum class SourceType {
    DDL_FILE,        // DDL 文件
    JPA_ENTITY,      // JPA 实体类
    MYBATIS_XML,     // MyBatis XML
    MYBATIS_ANNOTATION, // MyBatis 注解
    MULTI_SOURCE     // 多源融合
}
```

### 3. 字段信息提取

**字段模型**：
```kotlin
data class ColumnInfo(
    val columnName: String,                   // 字段名
    val fieldName: String?,                   // Java 字段名（从实体类）
    val businessName: String,                 // 业务名称
    val dataType: String,                     // 数据类型（VARCHAR(255)）
    val javaType: String?,                    // Java 类型（String）
    val nullable: Boolean = true,             // 是否可空
    val primaryKey: Boolean = false,          // 是否主键
    val autoIncrement: Boolean = false,       // 是否自增
    val defaultValue: String?,                // 默认值
    val comment: String?                      // 字段注释
)
```

**业务名称推断**（复用 knowledge-graph-system 的逻辑）：
```kotlin
// 1. 精确匹配（优先）
val FIELD_NAME_TO_BUSINESS_NAME = mapOf(
    "duebill_id" to "借据号",
    "loan_id" to "贷款编号",
    "customer_id" to "客户编号",
    "amount" to "金额",
    "balance" to "余额",
    "status" to "状态",
    "create_time" to "创建时间"
)

// 2. 模糊匹配（降级）
if (fieldName.contains("date")) return "日期"
if (fieldName.contains("name")) return "名称"
if (fieldName.contains("id")) return "编号"
```

### 4. 业务实体推断（核心逻辑）

**目标**：从数据库表结构推断业务实体，**从主键字段语义推断，而非表名**

**推断规则**（复用 knowledge-graph-system 的映射表）：
```kotlin
// 从主键前缀到业务概念的映射表
val KEY_PREFIX_TO_CONCEPT = mapOf(
    // 借据相关
    "loan" to "借据",
    "duebill" to "借据",
    "loan_contract" to "借据",

    // 客户相关
    "customer" to "客户",
    "cust" to "客户",
    "client" to "客户",

    // 账户相关
    "acct" to "账户",
    "account" to "账户",

    // 合同相关
    "contract" to "合同",
    "agreement" to "协议",

    // 还款相关
    "repayment" to "还款",
    "schedule" to "还款计划",
    "installment" to "分期",

    // 担保相关
    "guarantee" to "担保",
    "guarantor" to "担保人",
    "collateral" to "抵押物"
)
```

**推断算法**：
```kotlin
fun inferBusinessName(tableName: String, primaryKey: String?): String {
    // 1. 优先使用主键推断（核心逻辑）
    if (primaryKey != null) {
        val prefix = extractKeyPrefix(primaryKey) // loan_id → "loan"
        val concept = KEY_PREFIX_TO_CONCEPT[prefix]
        if (concept != null) {
            logger.info("主键推断成功: table={}, primaryKey={} → concept={}",
                tableName, primaryKey, concept)
            return concept
        }
    }

    // 2. 降级：使用表名推断（仅用于日志记录）
    val fallbackConcept = inferFromTableName(tableName)
    logger.warn("主键推断失败，降级使用表名推断: table={}, fallbackConcept={} (可能不准确)",
        tableName, fallbackConcept)

    return fallbackConcept
}

private fun extractKeyPrefix(primaryKey: String): String {
    return primaryKey.lowercase()
        .replace("(_id|_no|_code|_num|_number)$".toRegex(), "")
}
```

**示例对比**：
```
错误方式（当前）:
  表名: acct_loan → 去前缀 → loan → 硬编码映射 → "贷款" ❌

正确方式:
  主键: loan_id → 提取前缀 "loan" → 语义映射 → "借据" ✅
  字段: customer_id, amount, due_date → 语义分析 → "借据" ✅
```

### 5. 关联关系提取

**目标**：建立表之间的关联关系图

**关系类型**：
```kotlin
enum class RelationType {
    ONE_TO_ONE,      // 一对一（@OneToOne）
    ONE_TO_MANY,     // 一对多（@OneToMany）
    MANY_TO_ONE,     // 多对一（@ManyToOne）
    MANY_TO_MANY,    // 多对多（@ManyToMany）
    FOREIGN_KEY      // 外键关系
}

data class RelationInfo(
    val fromTable: String,              // 源表
    val fromColumn: String,             // 源字段
    val toTable: String,                // 目标表
    val toColumn: String,               // 目标字段
    val relationType: RelationType,     // 关系类型
    val cascadeType: String?            // 级联类型（CASCADE, SET_NULL）
)
```

**提取规则**：
```kotlin
// 1. JPA 注解提取
@OneToMany(mappedBy = "customer", cascade = CascadeType.ALL)
private List<Order> orders;

// → RelationInfo(fromTable="customer", toTable="order",
//                relationType=ONE_TO_MANY, cascadeType="ALL")

// 2. 外键提取
FOREIGN KEY (customer_id) REFERENCES customer(id)

// → RelationInfo(fromTable="order", fromColumn="customer_id",
//                toTable="customer", toColumn="id",
//                relationType=FOREIGN_KEY)

// 3. 命名约定推断
customer_id → customer 表的 id 字段
```

### 6. SQL 语句提取（MyBatis）

**目标**：从 MyBatis Mapper XML 提取所有 SQL 语句

**SQL 类型分类**：
```kotlin
enum class SqlType {
    SELECT,         // 查询
    INSERT,         // 插入
    UPDATE,         // 更新
    DELETE,         // 删除
    DYNAMIC_SQL     // 动态 SQL（if/choose/foreach）
}

data class SqlStatement(
    val sqlId: String,                    // SQL ID（方法名）
    val sqlType: SqlType,                 // SQL 类型
    val sqlText: String,                  // SQL 文本
    val tableName: String,                // 主表
    val involvedTables: Set<String>,      // 涉及的所有表
    val hasDynamicSql: Boolean,           // 是否包含动态 SQL
    val parameters: List<ParameterInfo>   // 参数信息
)
```

**解析规则**：
```kotlin
// 1. 解析 <select>, <insert>, <update>, <delete> 标签
val SQL_TAG_PATTERN = Pattern.compile(
    "<(select|insert|update|delete)\\s+id=\"([^\"]+)\"[^>]*>(.*?)</\\1>",
    Pattern.DOTALL
)

// 2. 检测动态 SQL
val DYNAMIC_SQL_PATTERNS = listOf(
    "<if\\s+test=", "<choose>", "<foreach\\s+"
)

// 3. 提取表引用
val TABLE_REFERENCE_PATTERN = Pattern.compile(
    "(?:FROM|JOIN|UPDATE|INSERT\\s+INTO|DELETE\\s+FROM)\\s+([a-zA-Z_][a-zA-Z0-9_]*)",
    Pattern.CASE_INSENSITIVE
)

// 4. 提取参数（#{param}, ${param}）
val PARAMETER_PATTERN = Pattern.compile("[#$]\\{([^}]+)\\}")
```

### 7. 置信度计算（多源融合）

**目标**：根据数据源质量和一致性计算置信度

**置信度计算公式**：
```kotlin
// 基础置信度（数据源优先级）
val BASE_CONFIDENCE = when (sourceType) {
    SourceType.DDL_FILE -> 0.95           // DDL 文件最准确
    SourceType.JPA_ENTITY -> 0.90         // JPA 实体类次之
    SourceType.MYBATIS_XML -> 0.80        // MyBatis XML 第三
    SourceType.MYBATIS_ANNOTATION -> 0.70 // 注解最后
    SourceType.MULTI_SOURCE -> 0.98       // 多源融合最高
}

// MyBatis 引用提升（来自 knowledge-graph-system）
val xmlReferenceBoost = when {
    xmlReferenceCount == 0 -> 0.7         // 无引用：降低 30%
    xmlReferenceCount >= p90 -> 1.15      // 高引用：提升 15%
    xmlReferenceCount >= p75 -> 1.10      // 中高引用：提升 10%
    xmlReferenceCount >= p50 -> 1.05      // 中引用：提升 5%
    else -> 0.9                           // 低引用：降低 10%
}

// 多源一致性提升
val consistencyBoost = if (allSourcesAgree) 1.05 else 1.0

// 最终置信度
val finalConfidence = BASE_CONFIDENCE * xmlReferenceBoost * consistencyBoost
```

**分位数计算**（参考 knowledge-graph-system）：
```kotlin
// 计算所有表的引用次数分位数
val counts = allTables.map { it.xmlReferenceCount }.sorted()
val p50 = counts[(counts.size * 0.5).toInt()]   // 中位数
val p75 = counts[(counts.size * 0.75).toInt()]  // 75 分位数
val p90 = counts[(counts.size * 0.9).toInt()]   // 90 分位数
```

### 8. 建立业务实体词典

**目标**：维护项目级别的业务实体映射关系

**词典结构**：
```kotlin
data class BusinessEntityDictionary(
    val projectKey: String,

    // 表名 → 业务概念映射
    val tableToConcept: Map<String, String>,

    // 主键前缀 → 业务概念映射
    val keyPrefixToConcept: Map<String, String>,

    // 业务概念 → 同义词集合映射
    val conceptSynonyms: Map<String, Set<String>>
)
```

**词典构建**：
```kotlin
fun buildDictionary(tables: List<TableInfo>, projectKey: String): BusinessEntityDictionary {
    val dictionary = BusinessEntityDictionary(projectKey)

    tables.forEach { table ->
        val concept = table.businessName

        // 1. 建立表名→业务概念映射
        dictionary.tableToConcept[table.tableName] = concept

        // 2. 建立主键前缀→业务概念映射
        table.primaryKeys.forEach { primaryKey ->
            val prefix = extractKeyPrefix(primaryKey)
            dictionary.keyPrefixToConcept[prefix] = concept
        }

        // 3. 添加同义词（可选）
        dictionary.addSynonym(concept, table.tableName)
        dictionary.addSynonym(concept, table.entityName ?: "")
    }

    return dictionary
}
```

## Kotlin 实现

### 文件位置

```
src/main/kotlin/com/smancode/smanagent/analyzer/
├── ddl/
│   ├── DdlScanner.kt                     # DDL 扫描器（主入口）
│   ├── DdlFileParser.kt                  # DDL 文件解析器
│   ├── JpaEntityAnalyzer.kt              # JPA 实体类分析器
│   ├── MyBatisXmlAnalyzer.kt             # MyBatis XML 分析器
│   ├── BusinessEntityInferenceService.kt # 业务实体推断服务
│   ├── BusinessConceptMappings.kt        # 业务概念映射配置
│   └── model/
│       ├── TableInfo.kt                  # 表信息模型
│       ├── ColumnInfo.kt                 # 字段信息模型
│       ├── RelationInfo.kt               # 关联关系模型
│       ├── SqlStatement.kt               # SQL 语句模型
│       └── BusinessEntityDictionary.kt   # 业务实体词典
```

### 核心接口

```kotlin
/**
 * DDL 扫描器接口
 */
interface DdlScanner {
    /**
     * 扫描项目的数据库定义信息
     * @param project 项目实例
     * @return 表信息列表
     */
    fun scan(project: Project): List<TableInfo>

    /**
     * 获取业务实体词典
     * @param projectKey 项目标识
     * @return 业务实体词典
     */
    fun getDictionary(projectKey: String): BusinessEntityDictionary?
}

/**
 * 表信息模型
 */
data class TableInfo(
    // 基本信息
    val tableName: String,
    val entityName: String?,
    val businessName: String,
    val comment: String?,

    // 字段信息
    val columns: List<ColumnInfo>,
    val primaryKeys: List<String>,

    // 约束和索引
    val indexes: List<IndexInfo> = emptyList(),
    val foreignKeys: List<ForeignKeyInfo> = emptyList(),

    // 关联关系
    val relations: List<RelationInfo> = emptyList(),

    // 数据源和置信度
    val sourceType: SourceType,
    val confidence: Double = 0.0,
    val sourceFiles: List<String> = emptyList(), // 来源文件列表

    // MyBatis 引用统计
    val xmlReferenceCount: Int = 0
)

/**
 * 字段信息模型
 */
data class ColumnInfo(
    val columnName: String,
    val fieldName: String?,
    val businessName: String,
    val dataType: String,
    val javaType: String?,
    val nullable: Boolean = true,
    val primaryKey: Boolean = false,
    val autoIncrement: Boolean = false,
    val defaultValue: String?,
    val comment: String?
)

/**
 * SQL 语句模型
 */
data class SqlStatement(
    val mapperId: String,                  // Mapper 方法名
    val sqlType: SqlType,
    val sqlText: String,
    val tableName: String,
    val involvedTables: Set<String>,
    val hasDynamicSql: Boolean,
    val parameters: List<String>
)

enum class SourceType {
    DDL_FILE,
    JPA_ENTITY,
    MYBATIS_XML,
    MYBATIS_ANNOTATION,
    MULTI_SOURCE
}

enum class SqlType {
    SELECT, INSERT, UPDATE, DELETE, DYNAMIC_SQL
}
```

### 实现示例

#### 1. DDL 文件解析器

```kotlin
/**
 * DDL 文件解析器
 */
class DdlFileParser(private val project: Project) {

    private val logger = Logger.getInstance(DdlFileParser::class.java)

    /**
     * 扫描并解析 DDL 文件
     */
    fun parseDdlFiles(): List<TableInfo> {
        val tables = mutableListOf<TableInfo>()

        // 1. 查找所有 DDL 文件
        val ddlFiles = findDdlFiles()
        logger.info("找到 ${ddlFiles.size} 个 DDL 文件")

        // 2. 解析每个文件
        ddlFiles.forEach { file ->
            try {
                val fileTables = parseFile(file)
                tables.addAll(fileTables)
            } catch (e: Exception) {
                logger.warn("解析 DDL 文件失败: ${file.path} - ${e.message}")
            }
        }

        return tables
    }

    /**
     * 查找 DDL 文件
     */
    private fun findDdlFiles(): List<VirtualFile> {
        val ddlFiles = mutableListOf<VirtualFile>()

        // 扫描路径
        val scanPaths = listOf(
            "src/main/resources/db/migration",
            "src/main/resources",
            "src/main/resources/sql"
        )

        scanPaths.forEach { path ->
            val dir = project.baseDir.findFileByRelativePath(path)
            if (dir != null && dir.exists()) {
                dir.children.forEach { file ->
                    if (file.extension == "sql" || file.extension == "ddl") {
                        ddlFiles.add(file)
                    }
                }
            }
        }

        return ddlFiles
    }

    /**
     * 解析单个 DDL 文件
     */
    private fun parseFile(file: VirtualFile): List<TableInfo> {
        val content = String(file.contentsToByteArray(), Charset.defaultCharset())
        val tables = mutableListOf<TableInfo>()

        // 匹配 CREATE TABLE 语句
        val matcher = CREATE_TABLE_PATTERN.matcher(content)

        while (matcher.find()) {
            val tableName = matcher.group(1)
            val tableBody = matcher.group(2)

            val table = parseTableDefinition(tableName, tableBody, file.path)
            if (table != null) {
                tables.add(table)
            }
        }

        return tables
    }

    /**
     * 解析表定义
     */
    private fun parseTableDefinition(
        tableName: String,
        tableBody: String,
        sourceFile: String
    ): TableInfo? {
        try {
            val columns = mutableListOf<ColumnInfo>()
            val primaryKeys = mutableListOf<String>()

            // 按行解析字段
            val lines = tableBody.split("\r?\n".toRegex())
            lines.forEach { line ->
                val trimmed = line.trim()

                // 跳过空行、注释、约束
                if (trimmed.isEmpty() ||
                    trimmed.startsWith("--") ||
                    trimmed.startsWith("PRIMARY KEY") ||
                    trimmed.startsWith("FOREIGN KEY") ||
                    trimmed.startsWith("CONSTRAINT")) {
                    return@forEach
                }

                // 解析字段
                val column = parseColumnLine(trimmed)
                if (column != null) {
                    columns.add(column)
                    if (column.primaryKey) {
                        primaryKeys.add(column.columnName)
                    }
                }
            }

            // 推断业务名称
            val primaryKey = primaryKeys.firstOrNull()
            val businessName = BusinessConceptMappings.inferFromPrimaryKey(primaryKey)

            return TableInfo(
                tableName = tableName,
                entityName = null,
                businessName = businessName,
                comment = null,
                columns = columns,
                primaryKeys = primaryKeys,
                sourceType = SourceType.DDL_FILE,
                confidence = 0.95,
                sourceFiles = listOf(sourceFile)
            )

        } catch (e: Exception) {
            logger.warn("解析表定义失败: $tableName - ${e.message}")
            return null
        }
    }

    /**
     * 解析字段行
     */
    private fun parseColumnLine(line: String): ColumnInfo? {
        // 移除行尾逗号
        val cleaned = line.replace(",\\s*$".toRegex(), "").trim()

        // 匹配字段名和类型
        val matcher = COLUMN_PATTERN.matcher(cleaned)
        if (!matcher.find()) {
            return null
        }

        val columnName = matcher.group(1)
        val dataType = matcher.group(2)

        // 检查 NOT NULL
        val nullable = !cleaned.uppercase().contains("NOT NULL")

        // 检查 PRIMARY KEY
        val primaryKey = cleaned.uppercase().contains("PRIMARY KEY")

        // 检查 AUTO_INCREMENT
        val autoIncrement = cleaned.uppercase().contains("AUTO_INCREMENT")

        // 提取 COMMENT
        val comment = extractComment(cleaned)

        // 提取 DEFAULT
        val defaultValue = extractDefault(cleaned)

        return ColumnInfo(
            columnName = columnName,
            fieldName = null,
            businessName = BusinessConceptMappings.inferFieldName(columnName),
            dataType = dataType,
            javaType = null,
            nullable = nullable,
            primaryKey = primaryKey,
            autoIncrement = autoIncrement,
            defaultValue = defaultValue,
            comment = comment
        )
    }

    companion object {
        private val CREATE_TABLE_PATTERN = Pattern.compile(
            "CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?([\\w_]+)\\s*\\((.*)\\)\\s*(?:ENGINE|COMMENT)",
            Pattern.CASE_INSENSITIVE or Pattern.DOTALL
        )

        private val COLUMN_PATTERN = Pattern.compile(
            "^([\\w_]+)\\s+(\\w+(?:\\([^)]+\\))?)",
            Pattern.CASE_INSENSITIVE
        )
    }
}
```

#### 2. JPA 实体类分析器

```kotlin
/**
 * JPA 实体类分析器
 */
class JpaEntityAnalyzer(private val project: Project) {

    private val logger = Logger.getInstance(JpaEntityAnalyzer::class.java)

    /**
     * 分析所有 JPA 实体类
     */
    fun analyzeEntities(): List<TableInfo> {
        val tables = mutableListOf<TableInfo>()

        // 1. 查找所有实体类
        val entityClasses = findEntityClasses()
        logger.info("找到 ${entityClasses.size} 个实体类")

        // 2. 解析每个实体类
        entityClasses.forEach { psiClass ->
            try {
                val table = parseEntityClass(psiClass)
                if (table != null) {
                    tables.add(table)
                }
            } catch (e: Exception) {
                logger.warn("解析实体类失败: ${psiClass.qualifiedName} - ${e.message}")
            }
        }

        return tables
    }

    /**
     * 查找实体类
     */
    private fun findEntityClasses(): List<PsiClass> {
        val entityClasses = mutableListOf<PsiClass>()

        // 扫描所有 Java/Kotlin 文件
        val scope = GlobalSearchScope.projectScope(project)
        val psiClasses = PsiShortNamesCache.getInstance(project)
            .getAllClasses(scope)

        psiClasses.forEach { psiClass ->
            // 检查 @Entity 注解
            if (hasAnnotation(psiClass, "javax.persistence.Entity") ||
                hasAnnotation(psiClass, "jakarta.persistence.Entity")) {
                entityClasses.add(psiClass)
            }
        }

        return entityClasses
    }

    /**
     * 解析实体类
     */
    private fun parseEntityClass(psiClass: PsiClass): TableInfo? {
        // 1. 提取表名
        val tableName = extractTableName(psiClass)

        // 2. 解析字段
        val columns = mutableListOf<ColumnInfo>()
        val primaryKeys = mutableListOf<String>()
        val relations = mutableListOf<RelationInfo>()

        psiClass.allFields.forEach { psiField ->
            if (psiField.hasAnnotation("javax.persistence.Transient") ||
                psiField.hasAnnotation("jakarta.persistence.Transient")) {
                return@forEach // 跳过 @Transient 字段
            }

            val column = parseField(psiField)
            if (column != null) {
                columns.add(column)
                if (column.primaryKey) {
                    primaryKeys.add(column.columnName)
                }
            }

            // 解析关联关系
            val relation = parseRelation(psiField, tableName)
            if (relation != null) {
                relations.add(relation)
            }
        }

        // 3. 提取表注释
        val comment = extractTableComment(psiClass)

        // 4. 推断业务名称
        val primaryKey = primaryKeys.firstOrNull()
        val businessName = BusinessConceptMappings.inferFromPrimaryKey(primaryKey)

        return TableInfo(
            tableName = tableName,
            entityName = psiClass.name,
            businessName = businessName,
            comment = comment,
            columns = columns,
            primaryKeys = primaryKeys,
            relations = relations,
            sourceType = SourceType.JPA_ENTITY,
            confidence = 0.90,
            sourceFiles = listOf(psiClass.containingFile.virtualFile.path)
        )
    }

    /**
     * 提取表名
     */
    private fun extractTableName(psiClass: PsiClass): String {
        // 优先使用 @Table 注解的 name 属性
        val tableAnnotation = psiClass.getAnnotation("javax.persistence.Table")
            ?: psiClass.getAnnotation("jakarta.persistence.Table")

        if (tableAnnotation != null) {
            val nameAttribute = tableAnnotation.findAttributeValue("name")
            if (nameAttribute is PsiLiteralExpression) {
                return nameAttribute.value as? String ?: psiClass.name.decapitalize()
            }
        }

        // 降级：使用类名转下划线
        return camelToSnake(psiClass.name)
    }

    /**
     * 解析字段
     */
    private fun parseField(psiField: PsiField): ColumnInfo? {
        // 1. 提取列名
        val columnAnnotation = psiField.getAnnotation("javax.persistence.Column")
            ?: psiField.getAnnotation("jakarta.persistence.Column")

        val columnName = if (columnAnnotation != null) {
            val nameAttr = columnAnnotation.findAttributeValue("name")
            (nameAttr as? PsiLiteralExpression)?.value as? String
                ?: camelToSnake(psiField.name)
        } else {
            camelToSnake(psiField.name)
        }

        // 2. 检查主键
        val primaryKey = hasAnnotation(psiField, "javax.persistence.Id") ||
            hasAnnotation(psiField, "jakarta.persistence.Id")

        // 3. 检查可空
        val nullable = columnAnnotation?.findAttributeValue("nullable") as? PsiLiteralExpression
        val isNullable = if (nullable != null) {
            nullable.value as? Boolean ?: true
        } else {
            !primaryKey // 主键默认不可空
        }

        // 4. 提取 Java 类型
        val javaType = psiField.type.canonicalText

        // 5. 提取注释
        val comment = columnAnnotation?.findAttributeValue("columnDefinition") as? PsiLiteralExpression
            ?.value as? String

        return ColumnInfo(
            columnName = columnName,
            fieldName = psiField.name,
            businessName = BusinessConceptMappings.inferFieldName(columnName),
            dataType = mapJavaTypeToSqlType(javaType),
            javaType = javaType,
            nullable = isNullable,
            primaryKey = primaryKey,
            autoIncrement = hasAnnotation(psiField, "javax.persistence.GeneratedValue") ||
                hasAnnotation(psiField, "jakarta.persistence.GeneratedValue"),
            defaultValue = null,
            comment = comment
        )
    }

    /**
     * 解析关联关系
     */
    private fun parseRelation(psiField: PsiField, fromTable: String): RelationInfo? {
        val relationType = when {
            hasAnnotation(psiField, "javax.persistence.OneToMany") ||
            hasAnnotation(psiField, "jakarta.persistence.OneToMany") ->
                RelationType.ONE_TO_MANY

            hasAnnotation(psiField, "javax.persistence.ManyToOne") ||
            hasAnnotation(psiField, "jakarta.persistence.ManyToOne") ->
                RelationType.MANY_TO_ONE

            hasAnnotation(psiField, "javax.persistence.OneToOne") ||
            hasAnnotation(psiField, "jakarta.persistence.OneToOne") ->
                RelationType.ONE_TO_ONE

            hasAnnotation(psiField, "javax.persistence.ManyToMany") ||
            hasAnnotation(psiField, "jakarta.persistence.ManyToMany") ->
                RelationType.MANY_TO_MANY

            else -> null
        }

        if (relationType == null) return null

        // 提取 @JoinColumn 信息
        val joinColumnAnnotation = psiField.getAnnotation("javax.persistence.JoinColumn")
            ?: psiField.getAnnotation("jakarta.persistence.JoinColumn")

        if (joinColumnAnnotation != null) {
            val nameAttr = joinColumnAnnotation.findAttributeValue("name")
            val refAttr = joinColumnAnnotation.findAttributeValue("referencedColumnName")

            val fromColumn = (nameAttr as? PsiLiteralExpression)?.value as? String
                ?: camelToSnake(psiField.name) + "_id"
            val toColumn = (refAttr as? PsiLiteralExpression)?.value as? String ?: "id"

            // 推断目标表名
            val fieldTypeName = (psiField.type as? PsiClassType)?.resolve()?.name
            val toTable = fieldTypeName?.let { camelToSnake(it) } ?: ""

            return RelationInfo(
                fromTable = fromTable,
                fromColumn = fromColumn,
                toTable = toTable,
                toColumn = toColumn,
                relationType = relationType,
                cascadeType = null
            )
        }

        return null
    }

    /**
     * 检查是否有指定注解
     */
    private fun hasAnnotation(psiElement: PsiElement, qualifiedName: String): Boolean {
        if (psiElement is PsiModifierOwner) {
            return psiElement.modifierList?.findAnnotation(qualifiedName) != null
        }
        return false
    }

    /**
     * 驼峰转下划线
     */
    private fun camelToSnake(name: String): String {
        return name.replace("([A-Z])".toRegex(), "_$1").lowercase().dropWhile { it == '_' }
    }

    /**
     * Java 类型映射到 SQL 类型
     */
    private fun mapJavaTypeToSqlType(javaType: String): String {
        return when {
            javaType == "java.lang.String" -> "VARCHAR"
            javaType == "java.lang.Integer" || javaType == "int" -> "INT"
            javaType == "java.lang.Long" || javaType == "long" -> "BIGINT"
            javaType == "java.math.BigDecimal" -> "DECIMAL"
            javaType == "java.time.LocalDate" -> "DATE"
            javaType == "java.time.LocalDateTime" -> "DATETIME"
            javaType == "boolean" || javaType == "java.lang.Boolean" -> "TINYINT"
            else -> "VARCHAR"
        }
    }
}
```

#### 3. MyBatis XML 分析器

```kotlin
/**
 * MyBatis XML 分析器
 */
class MyBatisXmlAnalyzer(private val project: Project) {

    private val logger = Logger.getInstance(MyBatisXmlAnalyzer::class.java)

    /**
     * 分析所有 MyBatis XML 文件
     */
    fun analyzeXmlFiles(): List<SqlStatement> {
        val statements = mutableListOf<SqlStatement>()

        // 1. 查找所有 XML 文件
        val xmlFiles = findXmlFiles()
        logger.info("找到 ${xmlFiles.size} 个 MyBatis XML 文件")

        // 2. 解析每个文件
        xmlFiles.forEach { file ->
            try {
                val fileStatements = parseXmlFile(file)
                statements.addAll(fileStatements)
            } catch (e: Exception) {
                logger.warn("解析 XML 文件失败: ${file.path} - ${e.message}")
            }
        }

        return statements
    }

    /**
     * 统计表引用次数
     */
    fun countTableReferences(): Map<String, Int> {
        val referenceCounts = mutableMapOf<String, Int>()

        val xmlFiles = findXmlFiles()
        xmlFiles.forEach { file ->
            try {
                val content = String(file.contentsToByteArray(), Charset.defaultCharset())
                val matcher = TABLE_REFERENCE_PATTERN.matcher(content)

                while (matcher.find()) {
                    val tableName = matcher.group(1).lowercase()
                    if (isLikelyTableName(tableName)) {
                        referenceCounts.merge(tableName, 1, Int::plus)
                    }
                }
            } catch (e: Exception) {
                logger.warn("读取 XML 文件失败: ${file.path}")
            }
        }

        return referenceCounts
    }

    /**
     * 查找 MyBatis XML 文件
     */
    private fun findXmlFiles(): List<VirtualFile> {
        val xmlFiles = mutableListOf<VirtualFile>()

        val scanPaths = listOf(
            "src/main/resources/mapper",
            "src/main/resources/dao",
            "src/main/resources/mybatis"
        )

        scanPaths.forEach { path ->
            val dir = project.baseDir.findFileByRelativePath(path)
            if (dir != null && dir.exists()) {
                dir.children.forEach { file ->
                    if (file.extension == "xml") {
                        xmlFiles.add(file)
                    }
                }
            }
        }

        return xmlFiles
    }

    /**
     * 解析 XML 文件
     */
    private fun parseXmlFile(file: VirtualFile): List<SqlStatement> {
        val statements = mutableListOf<SqlStatement>()
        val content = String(file.contentsToByteArray(), Charset.defaultCharset())

        // 解析 <select>, <insert>, <update>, <delete> 标签
        val matcher = SQL_TAG_PATTERN.matcher(content)

        while (matcher.find()) {
            val sqlTypeStr = matcher.group(1).uppercase()
            val sqlId = matcher.group(2)
            val sqlText = matcher.group(3).trim()

            val sqlType = SqlType.valueOf(sqlTypeStr)
            val tableName = extractMainTable(sqlText)
            val involvedTables = extractInvolvedTables(sqlText)
            val hasDynamicSql = hasDynamicSql(sqlText)
            val parameters = extractParameters(sqlText)

            statements.add(
                SqlStatement(
                    mapperId = sqlId,
                    sqlType = sqlType,
                    sqlText = sqlText,
                    tableName = tableName,
                    involvedTables = involvedTables,
                    hasDynamicSql = hasDynamicSql,
                    parameters = parameters
                )
            )
        }

        return statements
    }

    /**
     * 提取主表
     */
    private fun extractMainTable(sql: String): String {
        val matcher = Pattern.compile(
            "FROM\\s+([a-zA-Z_][a-zA-Z0-9_]*)",
            Pattern.CASE_INSENSITIVE
        ).matcher(sql)

        return if (matcher.find()) {
            matcher.group(1).lowercase()
        } else {
            ""
        }
    }

    /**
     * 提取涉及的所有表
     */
    private fun extractInvolvedTables(sql: String): Set<String> {
        val tables = mutableSetOf<String>()
        val matcher = TABLE_REFERENCE_PATTERN.matcher(sql)

        while (matcher.find()) {
            val tableName = matcher.group(1).lowercase()
            if (isLikelyTableName(tableName)) {
                tables.add(tableName)
            }
        }

        return tables
    }

    /**
     * 检查是否包含动态 SQL
     */
    private fun hasDynamicSql(sql: String): Boolean {
        return DYNAMIC_SQL_PATTERNS.any { pattern ->
            Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(sql).find()
        }
    }

    /**
     * 提取参数
     */
    private fun extractParameters(sql: String): List<String> {
        val parameters = mutableSetOf<String>()
        val matcher = PARAMETER_PATTERN.matcher(sql)

        while (matcher.find()) {
            parameters.add(matcher.group(1))
        }

        return parameters.toList()
    }

    /**
     * 判断是否可能是表名
     */
    private fun isLikelyTableName(name: String): Boolean {
        // 过滤系统表
        if (name.startsWith("sys_") ||
            name.startsWith("hibernate_") ||
            name.startsWith("flyway_")) {
            return false
        }

        // 过滤 Oracle 的 dual 表
        if (name == "dual") {
            return false
        }

        return true
    }

    companion object {
        private val SQL_TAG_PATTERN = Pattern.compile(
            "<(select|insert|update|delete)\\s+id=\"([^\"]+)\"[^>]*>(.*?)</\\1>",
            Pattern.DOTALL or Pattern.CASE_INSENSITIVE
        )

        private val TABLE_REFERENCE_PATTERN = Pattern.compile(
            "(?:FROM|JOIN|UPDATE|INSERT\\s+INTO|DELETE\\s+FROM)\\s+([a-zA-Z_][a-zA-Z0-9_]*)",
            Pattern.CASE_INSENSITIVE
        )

        private val PARAMETER_PATTERN = Pattern.compile("[#$]\\{([^}]+)\\}")

        private val DYNAMIC_SQL_PATTERNS = listOf(
            "<if\\s+test=",
            "<choose>",
            "<foreach\\s+",
            "<when\\s+test=",
            "<otherwise>"
        )
    }
}
```

## 与 knowledge-graph-system 的差异

### 可以借鉴的部分

1. **多源融合策略**
   - DDL 文件 → JPA 实体类 → MyBatis XML
   - 逐步补充和验证信息
   - 提高数据准确性和完整性

2. **业务实体推断逻辑**
   - 从主键字段语义推断业务概念（而非表名）
   - 使用 `KEY_PREFIX_TO_CONCEPT` 映射表
   - 主键优先，表名降级

3. **置信度计算**
   - 基于数据源类型的基础置信度
   - MyBatis XML 引用次数提升/降低
   - 多源一致性验证

4. **正则表达式解析**
   - `CREATE TABLE` 语句解析（支持嵌套括号）
   - MyBatis SQL 标签解析
   - 表引用统计

### 需要调整的部分

1. **IntelliJ PSI 集成**
   - knowledge-graph-system：文件扫描
   - SmanAgent：使用 PSI 获取更准确的注解信息
   - 支持增量更新（PSI 变更监听）

2. **数据模型简化**
   - knowledge-graph-system：QueenTable（业务表识别）
   - SmanAgent：TableInfo（完整表结构）
   - 增加字段、索引、关联关系等详细信息

3. **多源融合算法**
   - knowledge-graph-system：阶段式处理（阶段1 → 阶段2 → 阶段3）
   - SmanAgent：并行扫描 + 后期融合
   - 支持不同 ORM 框架（JPA、MyBatis、MyBatis-Plus）

4. **向量化准备**
   - knowledge-graph-system：用于知识图谱构建
   - SmanAgent：用于 BGE 召回+重排
   - 需要生成结构化的向量文本

## 专家知识库

### 关键问题

#### 1. 如何处理表名不一致？

**问题**：同一张表在不同数据源中有不同的名称：
- DDL 文件：`t_user`
- JPA 实体：`@Table(name = "sys_user")`
- MyBatis XML：`FROM user`

**解决方案**：
```kotlin
// 1. 建立表名别名映射
val tableAliases = mutableMapOf<String, MutableSet<String>>()

// 2. 归一化表名（移除常见前缀）
fun normalizeTableName(tableName: String): String {
    return tableName.replace("^(t_|tb_|tbl_|sys_|acct_)".toRegex(), "")
}

// 3. 检查是否为同一张表
fun isSameTable(table1: String, table2: String): Boolean {
    val norm1 = normalizeTableName(table1)
    val norm2 = normalizeTableName(table2)
    return norm1.equals(norm2, ignoreCase = true)
}

// 4. 合并信息
fun mergeTableInfo(info1: TableInfo, info2: TableInfo): TableInfo {
    // 优先使用 DDL 文件的信息（置信度更高）
    val base = if (info1.sourceType == SourceType.DDL_FILE) info1 else info2

    // 补充缺失信息
    val mergedColumns = mergeColumns(info1.columns, info2.columns)
    val mergedRelations = mergeRelations(info1.relations, info2.relations)

    return base.copy(
        columns = mergedColumns,
        relations = mergedRelations,
        sourceType = SourceType.MULTI_SOURCE,
        confidence = (info1.confidence + info2.confidence) / 2.0,
        sourceFiles = info1.sourceFiles + info2.sourceFiles
    )
}
```

#### 2. 如何识别关联关系？

**问题**：如何准确识别表之间的关联关系？

**解决方案**：
```kotlin
// 1. 从 JPA 注解提取（最准确）
@OneToMany(mappedBy = "customer")
private List<Order> orders;

// 2. 从外键约束提取
FOREIGN KEY (customer_id) REFERENCES customer(id)

// 3. 从字段命名约定推断
customer_id → customer 表的 id 字段
order_id → order 表的 id 字段

// 4. 从 MyBatis ResultMap 提取
<resultMap id="orderWithCustomer" type="Order">
    <association property="customer" column="customer_id"
                 javaType="Customer" select="selectCustomer"/>
</resultMap>
```

**推断规则**：
```kotlin
fun inferRelations(
    table: TableInfo,
    allTables: List<TableInfo>
): List<RelationInfo> {
    val relations = mutableListOf<RelationInfo>()

    table.columns.forEach { column ->
        // 1. 检查外键字段（以 _id, _no 结尾）
        if (column.columnName.endsWith("_id") ||
            column.columnName.endsWith("_no")) {

            val targetTableName = column.columnName
                .replace("_id$".toRegex(), "")
                .replace("_no$".toRegex(), "")

            // 2. 查找目标表
            val targetTable = allTables.find {
                normalizeTableName(it.tableName) == targetTableName
            }

            if (targetTable != null) {
                relations.add(
                    RelationInfo(
                        fromTable = table.tableName,
                        fromColumn = column.columnName,
                        toTable = targetTable.tableName,
                        toColumn = targetTable.primaryKeys.firstOrNull() ?: "id",
                        relationType = RelationType.MANY_TO_ONE,
                        cascadeType = null
                    )
                )
            }
        }
    }

    return relations
}
```

#### 3. 如何处理复杂的动态 SQL？

**问题**：MyBatis 动态 SQL 包含 `<if>`, `<choose>`, `<foreach>` 等标签，如何提取表信息？

**解决方案**：
```kotlin
// 1. 检测动态 SQL
val hasDynamicSql = sql.contains("<if") ||
                    sql.contains("<choose>") ||
                    sql.contains("<foreach")

// 2. 清理动态标签（提取静态部分）
fun cleanDynamicSql(sql: String): String {
    return sql
        .replace("<if[^>]*>.*?</if>".toRegex(RegexOption.DOTALL), "")
        .replace("<choose>.*?</choose>".toRegex(RegexOption.DOTALL), "")
        .replace("<foreach[^>]*>.*?</foreach>".toRegex(RegexOption.DOTALL), "")
        .replace("<when[^>]*>.*?</when>".toRegex(RegexOption.DOTALL), "")
        .replace("<otherwise>.*?</otherwise>".toRegex(RegexOption.DOTALL), "")
        .trim()
}

// 3. 从清理后的 SQL 提取表信息
val cleanedSql = cleanDynamicSql(originalSql)
val tables = extractInvolvedTables(cleanedSql)

// 4. 记录动态 SQL 标记
SqlStatement(
    // ...
    hasDynamicSql = true,
    dynamicElements = extractDynamicElements(originalSql)
)
```

#### 4. 如何提高业务概念推断的准确率？

**问题**：如何从数据库结构准确推断业务概念？

**解决方案**：
```kotlin
// 1. 主键推断（优先）
val concept = inferFromPrimaryKey(primaryKey)

// 2. 字段集合推断（增强）
val concept = inferFromColumns(table.columns)

// 3. 关联关系推断（补充）
val concept = inferFromRelations(table.relations)

// 4. 多源验证
val jpaConcept = extractFromJpaAnnotations(entityClass)
val ddlConcept = extractFromDdlComment(ddlFile)
val mybatisConcept = extractFromMyBatisResultMap(xmlFile)

// 5. 投票机制
val concepts = listOf(jpaConcept, ddlConcept, mybatisConcept)
val finalConcept = concepts.groupingBy { it }.eachCount()
    .maxByOrNull { it.value }?.key
```

**字段集合推断**：
```kotlin
fun inferFromColumns(columns: List<ColumnInfo>): String? {
    val columnNames = columns.map { it.columnName.lowercase() }

    // 借据特征字段
    if (columnNames.contains("loan_id") &&
        columnNames.contains("amount") &&
        columnNames.contains("interest_rate")) {
        return "借据"
    }

    // 客户特征字段
    if (columnNames.contains("customer_id") &&
        columnNames.contains("customer_name") &&
        columnNames.contains("phone")) {
        return "客户"
    }

    return null
}
```

### 待解决的问题

1. **增量更新**
   - 当 DDL 文件、实体类、Mapper XML 修改时，如何增量更新？
   - PSI 监听机制？
   - 文件修改时间检测？

2. **性能优化**
   - 大型项目表数量多，如何优化扫描速度？
   - 并发扫描？
   - 缓存策略？

3. **错误处理**
   - DDL 文件格式错误？
   - 实体类注解不规范？
   - Mapper XML 格式错误？

4. **多数据库支持**
   - MySQL、PostgreSQL、Oracle、SQL Server
   - 不同数据库的数据类型映射
   - 不同数据库的 DDL 语法差异

5. **MyBatis-Plus 支持**
   - `@TableName` 注解
   - `@TableId` 注解
   - `@TableField` 注解
   - 通用 CRUD 方法的 SQL 提取

## 下一步

- [ ] 实现 DDL 文件解析器
- [ ] 实现 JPA 实体类分析器
- [ ] 实现 MyBatis XML 分析器
- [ ] 实现业务实体推断服务
- [ ] 实现多源融合算法
- [ ] 设计向量化文本生成策略
- [ ] 编写单元测试
- [ ] 性能测试和优化

---

**文档版本**: v1.0
**最后更新**: 2026-01-29
**状态**: 设计完成，待实施
