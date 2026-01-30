# 09 - XML代码扫描

## 目标

为 SmanAgent 项目添加 XML 配置扫描能力，自动识别项目中的各种 XML 配置文件，提取 XML 元数据，并分析 XML 的业务用途。最终将分析结果存入向量库，支持 BGE 召回 + 重排。

**核心价值**：
- 解决反射问题：理解 XML 配置背后的反射调用
- 业务逻辑收敛：识别 XML 中"收敛"的通用逻辑（如流程配置、动态 SQL）
- 快速复用：当有类似业务需求时，通过 BGE 召回直接复用 XML 配置

## 参考

- knowledge-graph-system 的 `XmlUtil.java`
- knowledge-graph-system 的 `XmlConfigReader.java`
- knowledge-graph-system 的 `FilterContextCollector.java`
- knowledge-graph-system 的 `MyBatisXmlReferenceAnalyzer.java`

## 核心功能

### 1. XML 类型识别

**目标**：识别 XML 配置的类型和用途

**识别规则**：

| XML 类型 | 文件名模式 | 根标签 | 用途 | 置信度 |
|---------|-----------|--------|------|--------|
| **流程配置** | `*-flow.xml`, `*-transaction.xml` | `<transaction>`, `<flow>` | 定义业务流程步骤 | 0.95 |
| **Spring Bean** | `applicationContext.xml`, `spring-*.xml` | `<beans>` | Bean 定义和依赖注入 | 0.95 |
| **MyBatis Mapper** | `*Mapper.xml`, `*-mapper.xml` | `<mapper>` | SQL 映射和动态 SQL | 0.95 |
| **MyBatis Config** | `mybatis-config.xml` | `<configuration>` | MyBatis 全局配置 | 0.90 |
| **持久化配置** | `persistence.xml` | `<persistence>` | JPA 持久化配置 | 0.90 |
| **日志配置** | `logback.xml`, `log4j2.xml` | `<configuration>` | 日志框架配置 | 0.95 |
| **Spring MVC** | `*-servlet.xml` | `<beans>` | MVC 视图解析器配置 | 0.85 |
| **Dubbo** | `*.dubbo.xml`, `dubbo-*.xml` | `<dubbo:service>` | RPC 服务配置 | 0.90 |

**识别流程**：
```kotlin
// 1. 扫描 resources 目录下的所有 .xml 文件
// 2. 读取 XML 根标签
// 3. 根据文件名和根标签匹配类型
// 4. 提取 XML 元数据
```

### 2. 流程配置拆解

**目标**：理解流程配置 XML 的业务逻辑

**支持的格式**：

#### 格式1：Procedure 标签格式（autoloop 项目）

```xml
<Procedure id="1" type="load" class="com.autoloop.loan.procedure.LoadLoanProcedure" />
<Procedure id="2" type="create" class="com.autoloop.loan.procedure.CreateDuebillProcedure">
    <Property name="group1"
              filter="'0'.equals(loan.getUnflag())"
              value="FVDrawdown01" />
</Procedure>
```

**提取信息**：
- 流程步骤：按 `id` 排序
- 反射目标：`class` 属性 → Java 类
- 调用方法：从 `type` 或类名推断（`LoadLoanProcedure` → `load`）
- 条件分支：`Property` 的 `filter` 属性 → 业务条件
- 参数注入：`Property` 的 `name` 和 `value` → 方法参数

#### 格式2：标准 Spring 流程

```xml
<transaction transCode="TL001" name="创建贷款">
    <steps>
        <step bean="loanInitService" method="init" />
        <step bean="loanCreateService" method="create" />
        <step ref="commonValidation" />
    </steps>
</transaction>
```

**提取信息**：
- 事务代码：`transCode`
- 步骤定义：`<step>` 子元素
- Bean 引用：`bean` 属性 → Spring Bean 名称
- 方法调用：`method` 属性
- 步骤引用：`ref` 属性 → 复用其他步骤

#### 格式3：简化格式

```xml
<flow id="loanFlow">init,load,execute,commit</flow>
```

**提取信息**：
- 逗号分隔的方法调用
- 推断 Bean 名称：`loanFlow` → `loanFlowService`

### 3. MyBatis Mapper 拆解

**目标**：理解 MyBatis Mapper XML 的 SQL 逻辑

**示例**：

```xml
<mapper namespace="com.smancode.mapper.LoanMapper">
    <select id="selectByStatus" resultType="Loan">
        SELECT * FROM ACCT_LOAN
        WHERE STATUS = #{status}
        <if test="startDate != null">
            AND CREATE_TIME >= #{startDate}
        </if>
    </select>

    <insert id="insert" parameterType="Loan">
        INSERT INTO ACCT_LOAN (LOAN_ID, AMOUNT, STATUS)
        VALUES (#{loanId}, #{amount}, #{status})
    </insert>

    <update id="updateStatus">
        UPDATE ACCT_LOAN
        SET STATUS = #{status}
        WHERE LOAN_ID = #{loanId}
    </update>
</mapper>
```

**提取信息**：
- Mapper 接口：`namespace` → Java 接口全限定名
- SQL 方法：`<select>`, `<insert>`, `<update>`, `<delete>` 的 `id`
- 参数类型：`parameterType` → Java 类型
- 返回类型：`resultType` → Java 类型
- 动态 SQL：`<if>`, `<choose>`, `<foreach>` → 业务条件逻辑
- 涉及表：从 SQL 中提取表名（`ACCT_LOAN`）
- 涉及字段：从 SQL 中提取字段名（`LOAN_ID`, `AMOUNT`, `STATUS`）

**动态 SQL 拆解**：

```xml
<if test="status != null and status != ''">
    AND STATUS = #{status}
</if>

<choose>
    <when test="type == 'PERSON'">AND TYPE = 'P'</when>
    <when test="type == 'COMPANY'">AND TYPE = 'C'</when>
    <otherwise>AND TYPE IS NULL</otherwise>
</choose>
```

**提取为可理解的描述**：
- "如果状态不为空，则过滤状态"
- "根据类型字段选择不同的条件：个人类型为 P，公司类型为 C"

### 4. Spring Bean 配置拆解

**目标**：理解 Spring Bean 的依赖注入配置

**示例**：

```xml
<bean id="loanService" class="com.smancode.service.LoanServiceImpl">
    <property name="loanMapper" ref="loanMapper" />
    <property name="interestCalculator" ref="interestCalculator" />
</bean>

<bean id="loanMapper" class="com.smancode.mapper.LoanMapperImpl" />
```

**提取信息**：
- Bean 定义：`id` → Bean 名称，`class` → Java 类
- 依赖关系：`<property ref="xxx">` → 依赖的其他 Bean
- 注入方式：`<property>` → Setter 注入，`<constructor-arg>` → 构造器注入
- 作用域：`scope` 属性（singleton, prototype）

### 5. Filter 表达式解析

**目标**：理解 Filter 表达式的业务含义

**示例**：

```xml
<Property name="group1"
          filter="'0'.equals(loan.getUnflag()) and '2010'.equals(loan.getBusinesstype())"
          value="FVDrawdown01" />
```

**解析流程**：
1. 提取字段引用：`loan.getUnflag()`, `loan.getBusinesstype()`
2. 从 DDL 查找字段业务含义：
   - `unflag` → "是否注销标志"
   - `businesstype` → "业务类型"
3. 生成可理解的描述：
   - "当贷款未注销且业务类型为 2010 时，使用 FVDrawdown01 流程组"

### 6. 反射调用链构建

**目标**：从 XML 配置构建完整的调用链

**示例**：

```xml
<transaction transCode="TL001">
    <steps>
        <step bean="loanInitService" method="init" />
        <step bean="loanCreateService" method="create" />
    </steps>
</transaction>
```

**构建调用链**：
1. 解析 Bean 名称：`loanInitService` → `com.smancode.service.LoanInitService`
2. 解析方法调用：`init(LoanRequest)`
3. 结合 Java 代码分析（通过 PSI）：
   - 方法签名：参数类型、返回类型
   - 调用的其他方法
   - 访问的数据库表
4. 生成完整的业务流程图：
   ```
   TL001: 创建贷款
   └─ loanInitService.init()
      ├─ 请求参数: LoanRequest
      ├─ 返回类型: LoanContext
      ├─ 调用方法: validateLoanRequest(), loadCustomerInfo()
      └─ 涉及表: ACCT_LOAN, ACCT_CUSTOMER
   └─ loanCreateService.create()
      ├─ 请求参数: LoanContext
      ├─ 返回类型: LoanResponse
      ├─ 调用方法: insertLoan(), createRepaymentPlan()
      └─ 涉及表: ACCT_LOAN, ACCT_REPAYMENT_PLAN
   ```

### 7. XML 业务功能分析

**目标**：使用 LLM 分析 XML 的业务功能

**分析输入**：
```yaml
XML类型: 流程配置
事务代码: TL001
配置名称: 创建贷款
步骤数量: 5
涉及Bean: loanInitService, loanCreateService, loanNotifierService
涉及方法: init, create, notify
涉及表: ACCT_LOAN, ACCT_CUSTOMER
技术栈: spring, mybatis
```

**LLM Prompt 模板**：
```yaml
你是代码分析专家。请分析以下 XML 配置的业务功能。

## XML 信息
XML类型: ${xmlType}
事务代码: ${transCode}
配置名称: ${configName}

## 步骤信息
${stepsDescription}

## 涉及的类和方法
${beansAndMethods}

## 涉及的数据表
${tables}

## 分析要求
请分析该 XML 配置的业务功能，输出以下内容：
1. 业务功能描述（一句话，不超过50字）
2. 业务领域（如：贷款、支付、风控）
3. 关键业务概念（3-5个关键词）

## 输出格式（JSON）
{
  "description": "业务功能描述",
  "domain": "业务领域",
  "keywords": ["关键词1", "关键词2", "关键词3"]
}
```

**分析结果存储**：
- `description`: 业务功能描述
- `domain`: 业务领域
- `keywords`: 关键词
- `confidence`: 分析置信度

## Kotlin 实现

### 文件位置

```
src/main/kotlin/com/smancode/smanagent/analyzer/
├── xml/
│   ├── XmlConfigScanner.kt              # XML 配置扫描器
│   ├── XmlTypeDetector.kt               # XML 类型检测器
│   ├── FlowConfigParser.kt              # 流程配置解析器
│   ├── MyBatisMapperParser.kt           # MyBatis Mapper 解析器
│   ├── SpringBeanParser.kt              # Spring Bean 解析器
│   ├── FilterExpressionParser.kt        # Filter 表达式解析器
│   ├── XmlBusinessAnalyzer.kt           # XML 业务功能分析器
│   └── model/
│       ├── XmlConfig.kt                 # XML 配置基类
│       ├── XmlType.kt                   # XML 类型枚举
│       ├── FlowConfig.kt                # 流程配置
│       ├── MyBatisMapper.kt             # MyBatis Mapper
│       ├── SpringBeanConfig.kt          # Spring Bean 配置
│       ├── FlowStep.kt                  # 流程步骤
│       ├── Property.kt                  # Property 配置
│       ├── FilterExpression.kt          # Filter 表达式
│       ├── FieldReference.kt            # 字段引用
│       ├── SqlStatement.kt              # SQL 语句
│       └── DynamicSql.kt                # 动态 SQL
```

### 核心接口

```kotlin
/**
 * XML 配置扫描器
 */
interface XmlConfigScanner {
    /**
     * 扫描项目中的所有 XML 配置
     * @param projectKey 项目标识
     * @return XML 配置列表
     */
    suspend fun scan(projectKey: String): List<XmlConfig>
}

/**
 * XML 类型检测器
 */
interface XmlTypeDetector {
    /**
     * 检测 XML 类型
     * @param xmlFile XML 文件
     * @return XML 类型
     */
    fun detectType(xmlFile: VirtualFile): XmlType

    /**
     * 检测 XML 类型
     * @param xmlContent XML 内容
     * @return XML 类型
     */
    fun detectType(xmlContent: String): XmlType
}

/**
 * XML 配置基类
 */
sealed class XmlConfig {
    abstract val filePath: String
    abstract val xmlType: XmlType
    abstract val configName: String?
    abstract val description: String?
    abstract val domain: String?
    abstract val keywords: List<String>
}

/**
 * XML 类型枚举
 */
enum class XmlType {
    FLOW_CONFIG,           // 流程配置（transaction, flow）
    SPRING_BEAN,           // Spring Bean 配置
    MYBATIS_MAPPER,        // MyBatis Mapper
    MYBATIS_CONFIG,        // MyBatis 全局配置
    PERSISTENCE,           // JPA 持久化配置
    LOG_CONFIG,            // 日志配置
    SPRING_MVC,            // Spring MVC 配置
    DUBBO,                 // Dubbo 配置
    UNKNOWN                // 未知类型
}

/**
 * 流程配置
 */
data class FlowConfig(
    override val filePath: String,
    override val xmlType: XmlType = XmlType.FLOW_CONFIG,
    override val configName: String?,
    override val description: String? = null,
    override val domain: String? = null,
    override val keywords: List<String> = emptyList(),
    val transCode: String?,
    val format: FlowFormat,
    val steps: List<FlowStep>,
    val properties: List<Property> = emptyList()
) : XmlConfig()

/**
 * 流程格式枚举
 */
enum class FlowFormat {
    PROCEDURE,     // Procedure 标签格式（autoloop 项目）
    STANDARD,      // 标准格式 <steps><step>
    DIRECT,        // 直接步骤子元素
    SIMPLIFIED,    // 简化格式（逗号分隔）
    NESTED,        // 嵌套引用格式
    UNKNOWN        // 未知格式
}

/**
 * 流程步骤
 */
data class FlowStep(
    val order: Int,
    val beanName: String?,              // Bean 名称
    val className: String?,             // Java 类全限定名
    val methodName: String?,            // 方法名
    val inferredClassName: String?,     // 推断的类名
    val inferredClassPath: String?,     // 推断的类路径
    val stepReference: String?,         // 步骤引用（ref）
    val type: String?,                  // 步骤类型（Procedure 格式）
    val stepId: String?,                // 步骤 ID（Procedure 格式）
    val properties: List<Property> = emptyList()
) {
    companion object {
        fun createNormalStep(bean: String, method: String, order: Int): FlowStep {
            return FlowStep(
                order = order,
                beanName = bean,
                methodName = method,
                className = null,
                inferredClassName = null,
                inferredClassPath = null,
                stepReference = null,
                type = null,
                stepId = null
            )
        }

        fun createReferenceStep(ref: String, order: Int): FlowStep {
            return FlowStep(
                order = order,
                beanName = null,
                methodName = null,
                className = null,
                inferredClassName = null,
                inferredClassPath = null,
                stepReference = ref,
                type = null,
                stepId = null
            )
        }
    }

    fun isReference(): Boolean = stepReference != null
}

/**
 * Property 配置
 */
data class Property(
    val name: String,
    val filter: String?,
    val value: String?
) {
    companion object {
        fun of(name: String, filter: String?, value: String?): Property {
            return Property(name, filter, value)
        }
    }
}

/**
 * MyBatis Mapper 配置
 */
data class MyBatisMapper(
    override val filePath: String,
    override val xmlType: XmlType = XmlType.MYBATIS_MAPPER,
    override val configName: String?,
    override val description: String? = null,
    override val domain: String? = null,
    override val keywords: List<String> = emptyList(),
    val namespace: String,               // Mapper 接口全限定名
    val statements: List<SqlStatement>
) : XmlConfig()

/**
 * SQL 语句
 */
data class SqlStatement(
    val id: String,                      // 方法 ID
    val sqlType: SqlType,                // SQL 类型（select, insert, update, delete）
    val parameterType: String?,          // 参数类型
    val resultType: String?,             // 返回类型
    val sqlContent: String,              // SQL 内容
    val dynamicSql: List<DynamicSql>,    // 动态 SQL
    val tables: List<String>,            // 涉及的表
    val fields: List<String>             // 涉及的字段
)

/**
 * SQL 类型枚举
 */
enum class SqlType {
    SELECT, INSERT, UPDATE, DELETE, UNKNOWN
}

/**
 * 动态 SQL
 */
data class DynamicSql(
    val type: DynamicSqlType,            // 类型（if, choose, foreach）
    val testCondition: String?,          // 测试条件
    val content: String                  // 内容
)

/**
 * 动态 SQL 类型枚举
 */
enum class DynamicSqlType {
    IF, CHOOSE, FOREACH, WHEN, OTHERWISE, UNKNOWN
}

/**
 * Spring Bean 配置
 */
data class SpringBeanConfig(
    override val filePath: String,
    override val xmlType: XmlType = XmlType.SPRING_BEAN,
    override val configName: String?,
    override val description: String? = null,
    override val domain: String? = null,
    override val keywords: List<String> = emptyList(),
    val beans: List<SpringBean>
) : XmlConfig()

/**
 * Spring Bean
 */
data class SpringBean(
    val id: String,                      // Bean 名称
    val className: String,               // Java 类全限定名
    val scope: String?,                  // 作用域
    val dependencies: List<BeanDependency>  // 依赖的其他 Bean
)

/**
 * Bean 依赖
 */
data class BeanDependency(
    val propertyName: String,            // 属性名
    val refBean: String                  // 引用的 Bean 名称
)

/**
 * Filter 表达式
 */
data class FilterExpression(
    val originalExpression: String,      // 原始表达式
    val fieldReferences: List<FieldReference>,
    val fieldMeanings: Map<String, FieldMeaning>
) {
    /**
     * 生成 LLM 理解的上下文描述
     */
    fun toLLMContext(): String {
        if (fieldMeanings.isEmpty()) {
            return "Filter 表达式: $originalExpression\n(未找到字段含义)"
        }

        val sb = StringBuilder()
        sb.append("Filter 表达式: $originalExpression\n\n")
        sb.append("涉及字段的业务含义:\n")

        for ((key, meaning) in fieldMeanings) {
            val comment = meaning.comment ?: meaning.businessName
            sb.append("  - $key: $comment\n")
        }

        return sb.toString()
    }
}

/**
 * 字段引用
 */
data class FieldReference(
    val entityName: String,              // 实体名（如 loan）
    val fieldName: String,               // 字段名（如 unflag）
    val originalExpression: String       // 原始表达式（如 loan.getUnflag()）
)

/**
 * 字段业务含义
 */
data class FieldMeaning(
    val tableName: String,               // 表名
    val fieldName: String,               // 字段名
    val businessName: String,            // 业务名称
    val comment: String?                 // DDL 注释
)
```

### 核心实现

```kotlin
/**
 * XML 类型检测器
 */
class XmlTypeDetectorImpl : XmlTypeDetector {

    companion object {
        // XML 类型模式
        private val TYPE_PATTERNS = mapOf(
            XmlType.FLOW_CONFIG to setOf(
                "flow", "transaction", "process", "procedure"
            ),
            XmlType.SPRING_BEAN to setOf(
                "beans", "bean"
            ),
            XmlType.MYBATIS_MAPPER to setOf(
                "mapper"
            ),
            XmlType.MYBATIS_CONFIG to setOf(
                "configuration"
            ),
            XmlType.PERSISTENCE to setOf(
                "persistence"
            ),
            XmlType.LOG_CONFIG to setOf(
                "configuration", "logback", "log4j"
            ),
            XmlType.DUBBO to setOf(
                "dubbo:service", "dubbo:reference", "dubbo:application"
            )
        )
    }

    /**
     * 检测 XML 类型
     */
    override fun detectType(xmlFile: VirtualFile): XmlType {
        val content = xmlFile.inputStream.use { it.bufferedReader().readText() }
        return detectType(content)
    }

    /**
     * 检测 XML 类型
     */
    override fun detectType(xmlContent: String): XmlType {
        // 1. 提取根标签
        val rootTag = extractRootTag(xmlContent) ?: return XmlType.UNKNOWN

        // 2. 根据根标签匹配类型
        for ((type, patterns) in TYPE_PATTERNS) {
            if (patterns.any { rootTag.equals(it, ignoreCase = true) }) {
                return type
            }
        }

        // 3. 检查特殊子标签
        return detectByChildTags(xmlContent)
    }

    /**
     * 提取根标签
     */
    private fun extractRootTag(xmlContent: String): String? {
        // 匹配 <rootTag> 或 <rootTag xmlns="...">
        val pattern = Regex("<([a-zA-Z0-9:]+)(?:\\s+[^>]*)?>")
        val match = pattern.find(xmlContent.trim()) ?: return null
        return match.groupValues[1]
    }

    /**
     * 根据子标签检测类型
     */
    private fun detectByChildTags(xmlContent: String): XmlType {
        return when {
            xmlContent.contains("<transaction ") || xmlContent.contains("<flow ") -> XmlType.FLOW_CONFIG
            xmlContent.contains("<bean ") -> XmlType.SPRING_BEAN
            xmlContent.contains("<mapper ") -> XmlType.MYBATIS_MAPPER
            xmlContent.contains("<settings>") -> XmlType.MYBATIS_CONFIG
            xmlContent.contains("<persistence-unit>") -> XmlType.PERSISTENCE
            else -> XmlType.UNKNOWN
        }
    }
}

/**
 * 流程配置解析器
 */
class FlowConfigParser(
    private val project: Project,
    private val psiManager: PsiManager
) {

    /**
     * 解析流程配置
     */
    suspend fun parse(xmlFile: VirtualFile): FlowConfig? {
        val content = xmlFile.inputStream.use { it.bufferedReader().readText() }

        // 1. 提取事务元素
        val transactionElement = extractTransactionElement(content) ?: return null

        // 2. 提取元数据
        val transCode = extractTransCode(transactionElement)
        val configName = extractConfigName(transactionElement)

        // 3. 检测格式
        val format = detectFormat(transactionElement)

        // 4. 解析步骤
        val steps = parseSteps(transactionElement, format)

        // 5. 解析 Property
        val properties = parseProperties(transactionElement)

        // 6. 推断 Bean 类信息
        inferBeanClasses(steps, xmlFile)

        return FlowConfig(
            filePath = xmlFile.path,
            configName = configName,
            transCode = transCode,
            format = format,
            steps = steps,
            properties = properties
        )
    }

    /**
     * 提取事务元素
     */
    private fun extractTransactionElement(xmlContent: String): Element? {
        val doc = Jsoup.parse(xmlContent)
        val transactionTags = listOf("transaction", "flow", "process", "Transaction", "Flow", "Process")

        for (tag in transactionTags) {
            val element = doc.selectFirst(tag) ?: continue
            if (element.hasAttr("transCode") || element.hasAttr("id")) {
                return element
            }
        }

        return null
    }

    /**
     * 提取事务代码
     */
    private fun extractTransCode(element: Element): String? {
        val attributes = listOf("transCode", "transcode", "trans_code", "code", "id")
        for (attr in attributes) {
            val value = element.attr(attr)
            if (value.isNotEmpty()) {
                return value
            }
        }
        return null
    }

    /**
     * 检测流程格式
     */
    private fun detectFormat(element: Element): FlowFormat {
        return when {
            !element.select("Procedure,procedure").isEmpty() -> FlowFormat.PROCEDURE
            !element.select("steps > step").isEmpty() -> FlowFormat.STANDARD
            !element.select("> step").isEmpty() -> FlowFormat.DIRECT
            element.text().trim().matches(Regex("^[a-zA-Z]+(,[a-zA-Z]+)*$")) -> FlowFormat.SIMPLIFIED
            !element.select("[ref]").isEmpty() -> FlowFormat.NESTED
            else -> FlowFormat.UNKNOWN
        }
    }

    /**
     * 解析步骤
     */
    private fun parseSteps(element: Element, format: FlowFormat): List<FlowStep> {
        return when (format) {
            FlowFormat.PROCEDURE -> parseProcedureSteps(element)
            FlowFormat.STANDARD -> parseStandardSteps(element)
            FlowFormat.DIRECT -> parseDirectSteps(element)
            FlowFormat.SIMPLIFIED -> parseSimplifiedSteps(element)
            else -> emptyList()
        }
    }

    /**
     * 解析 Procedure 格式步骤
     */
    private fun parseProcedureSteps(element: Element): List<FlowStep> {
        val procedureElements = element.select("Procedure,procedure")
        val steps = mutableListOf<FlowStep>()

        // 按 id 排序
        procedureElements.sortedBy { it.attr("id").toIntOrNull() ?: 0 }
            .forEachIndexed { index, procElement ->
                val id = procElement.attr("id")
                val type = procElement.attr("type")
                val className = procElement.attr("class")

                if (className.isEmpty()) {
                    return@forEachIndexed
                }

                // 从类名推断方法名
                val method = inferMethodFromClassAndType(className, type)

                val step = FlowStep(
                    order = index + 1,
                    beanName = null,
                    methodName = method,
                    className = className,
                    type = type,
                    stepId = id
                )

                // 解析 Property
                val properties = parseProperties(procElement)
                step.properties.addAll(properties)

                steps.add(step)
            }

        return steps
    }

    /**
     * 从类名和类型推断方法名
     */
    private fun inferMethodFromClassAndType(className: String, type: String): String {
        // 如果 type 是 load/create，直接使用
        if (type == "load" || type == "create") {
            return type
        }

        val simpleName = className.substringAfterLast('.')

        // Procedure 后缀 -> 从类名提取方法
        if (simpleName.endsWith("Procedure")) {
            val baseName = simpleName.removeSuffix("Procedure")
            if (baseName.isNotEmpty()) {
                return baseName[0].lowercaseChar() + baseName.substring(1)
            }
        }

        // Executor 后缀 -> 统一使用 run
        if (simpleName.endsWith("Executor")) {
            return "run"
        }

        // 默认返回 type 或 run
        return type.ifEmpty { "run" }
    }

    /**
     * 解析 Property
     */
    private fun parseProperties(element: Element): List<Property> {
        val propertyElements = element.select("Property,property")
        return propertyElements.map { propElement ->
            val name = propElement.attr("name")
            val filter = propElement.attr("filter")
            val value = propElement.attr("value")
            Property.of(name, filter, value)
        }
    }

    /**
     * 推断 Bean 类信息
     */
    private fun inferBeanClasses(steps: List<FlowStep>, xmlFile: VirtualFile) {
        steps.forEach { step ->
            if (step.isReference() || step.className == null) {
                return@forEach
            }

            // 通过 PSI 查找类
            val psiClass = psiManager.findClass(step.className, GlobalSearchScope.allScope(project))
            if (psiClass != null) {
                step.inferredClassName = psiClass.name
                step.inferredClassPath = psiClass.qualifiedName
            }
        }
    }
}

/**
 * MyBatis Mapper 解析器
 */
class MyBatisMapperParser {

    /**
     * 解析 MyBatis Mapper
     */
    suspend fun parse(xmlFile: VirtualFile): MyBatisMapper? {
        val content = xmlFile.inputStream.use { it.bufferedReader().readText() }
        val doc = Jsoup.parse(content)

        val mapper = doc.selectFirst("mapper") ?: return null

        val namespace = mapper.attr("namespace")
        val configName = xmlFile.nameWithoutExtension

        val statements = parseStatements(mapper)

        return MyBatisMapper(
            filePath = xmlFile.path,
            configName = configName,
            namespace = namespace,
            statements = statements
        )
    }

    /**
     * 解析 SQL 语句
     */
    private fun parseStatements(mapper: Element): List<SqlStatement> {
        val statements = mutableListOf<SqlStatement>()

        val sqlTypes = listOf(
            "select" to SqlType.SELECT,
            "insert" to SqlType.INSERT,
            "update" to SqlType.UPDATE,
            "delete" to SqlType.DELETE
        )

        for ((tagName, sqlType) in sqlTypes) {
            val elements = mapper.select(tagName)
            elements.forEach { element ->
                val id = element.attr("id")
                val parameterType = element.attr("parameterType").takeIf { it.isNotEmpty() }
                val resultType = element.attr("resultType").takeIf { it.isNotEmpty() }
                val sqlContent = element.html()

                val dynamicSql = parseDynamicSql(element)
                val tables = extractTables(sqlContent)
                val fields = extractFields(sqlContent)

                statements.add(
                    SqlStatement(
                        id = id,
                        sqlType = sqlType,
                        parameterType = parameterType,
                        resultType = resultType,
                        sqlContent = sqlContent,
                        dynamicSql = dynamicSql,
                        tables = tables,
                        fields = fields
                    )
                )
            }
        }

        return statements
    }

    /**
     * 解析动态 SQL
     */
    private fun parseDynamicSql(element: Element): List<DynamicSql> {
        val dynamicSqlList = mutableListOf<DynamicSql>()

        // 解析 <if> 标签
        element.select("if").forEach { ifElement ->
            dynamicSqlList.add(
                DynamicSql(
                    type = DynamicSqlType.IF,
                    testCondition = ifElement.attr("test"),
                    content = ifElement.html()
                )
            )
        }

        // 解析 <choose> 标签
        element.select("choose").forEach { chooseElement ->
            dynamicSqlList.add(
                DynamicSql(
                    type = DynamicSqlType.CHOOSE,
                    testCondition = null,
                    content = chooseElement.html()
                )
            )

            // 解析 <when> 和 <otherwise>
            chooseElement.select("when").forEach { whenElement ->
                dynamicSqlList.add(
                    DynamicSql(
                        type = DynamicSqlType.WHEN,
                        testCondition = whenElement.attr("test"),
                        content = whenElement.html()
                    )
                )
            }

            chooseElement.select("otherwise").forEach { otherwiseElement ->
                dynamicSqlList.add(
                    DynamicSql(
                        type = DynamicSqlType.OTHERWISE,
                        testCondition = null,
                        content = otherwiseElement.html()
                    )
                )
            }
        }

        return dynamicSqlList
    }

    /**
     * 提取表名
     */
    private fun extractTables(sqlContent: String): List<String> {
        val pattern = Regex("(?:FROM|JOIN|UPDATE|INSERT\\s+INTO|DELETE\\s+FROM)\\s+([a-zA-Z_][a-zA-Z0-9_]*)", RegexOption.IGNORE_CASE)
        val matches = pattern.findAll(sqlContent)
        return matches.map { it.groupValues[1].lowercase() }.distinct().toList()
    }

    /**
     * 提取字段名
     */
    private fun extractFields(sqlContent: String): List<String> {
        val pattern = Regex("[a-zA-Z_][a-zA-Z0-9_]*\\s*[=,]", RegexOption.IGNORE_CASE)
        val matches = pattern.findAll(sqlContent)
        return matches.map { it.groupValues[1].removeSuffix("=").removeSuffix(",").lowercase() }
            .filter { it.length > 2 }  // 过滤掉明显的关键字
            .distinct()
            .toList()
    }
}

/**
 * Filter 表达式解析器
 */
class FilterExpressionParser(
    private val ddlScanner: DdlScanner  // DDL 扫描器，用于查找字段含义
) {

    companion object {
        // 提取方法调用模式：xxx.getYyy()
        private val METHOD_CALL_PATTERN = Regex("([a-zA-Z_][a-zA-Z0-9_]*)\\.get([A-Z][a-zA-Z0-9]*)\\(\\)")
    }

    /**
     * 解析 Filter 表达式
     */
    suspend fun parse(filterExpression: String, projectKey: String): FilterExpression {
        // 1. 提取字段引用
        val fieldReferences = extractFieldReferences(filterExpression)

        // 2. 从 DDL 中查找字段的业务含义
        val fieldMeanings = lookupFieldMeaningsFromDDL(fieldReferences, projectKey)

        return FilterExpression(
            originalExpression = filterExpression,
            fieldReferences = fieldReferences,
            fieldMeanings = fieldMeanings
        )
    }

    /**
     * 提取字段引用
     */
    private fun extractFieldReferences(filterExpression: String): List<FieldReference> {
        val references = mutableListOf<FieldReference>()

        METHOD_CALL_PATTERN.findAll(filterExpression).forEach { match ->
            val entity = match.groupValues[1]      // loan
            val field = match.groupValues[2]       // Unflag

            // 转换为小写字段名：Unflag → unflag
            val fieldName = field[0].lowercaseChar() + field.substring(1)

            references.add(
                FieldReference(
                    entityName = entity,
                    fieldName = fieldName,
                    originalExpression = match.groupValues[0]  // loan.getUnflag()
                )
            )
        }

        return references
    }

    /**
     * 从 DDL 中查找字段的业务含义
     */
    private suspend fun lookupFieldMeaningsFromDDL(
        fieldReferences: List<FieldReference>,
        projectKey: String
    ): Map<String, FieldMeaning> {
        val meanings = mutableMapOf<String, FieldMeaning>()

        // 获取 DDL 业务实体
        val entities = ddlScanner.scan(projectKey)

        fieldReferences.forEach { ref ->
            // 推断表名
            val tableName = inferTableName(ref.entityName)

            // 查找表
            val entity = entities.find { it.tableName.equals(tableName, ignoreCase = true) }
            if (entity == null) {
                return@forEach
            }

            // 查找字段
            val column = entity.columns.find { it.name.equals(ref.fieldName, ignoreCase = true) }
            if (column != null) {
                val key = "${ref.entityName}.${ref.fieldName}"
                meanings[key] = FieldMeaning(
                    tableName = entity.tableName,
                    fieldName = ref.fieldName,
                    businessName = column.businessName,
                    comment = column.comment
                )
            }
        }

        return meanings
    }

    /**
     * 推断表名
     */
    private fun inferTableName(entityName: String): String {
        // 驼峰转下划线
        val tableName = entityName.replace(Regex("([a-z])([A-Z])"), "$1_$2").uppercase()

        // 尝试添加常见前缀
        return when {
            tableName.startsWith("LOAN") -> "ACCT_LOAN"
            else -> tableName
        }
    }
}
```

## 与 knowledge-graph-system 的差异

### 可以借鉴的部分

1. **多格式解析**
   - Procedure 标签格式（autoloop 项目）
   - 标准 Spring 流程格式
   - 简化格式（逗号分隔）
   - MyBatis Mapper 格式

2. **Filter 表达式解析**
   - 正则表达式提取字段引用
   - 从 DDL 查找字段业务含义
   - 生成 LLM 理解的上下文描述

3. **Bean 类推断**
   - 从类名推断方法名
   - 从 Bean 名称推断类名
   - PSI 查找验证类存在

4. **动态 SQL 解析**
   - 解析 `<if>`, `<choose>`, `<foreach>` 标签
   - 提取测试条件和内容
   - 转换为可理解的描述

### 需要调整的部分

1. **XML 解析方式**
   - knowledge-graph-system 使用 Jsoup
   - SmanAgent 也可以使用 Jsoup（轻量级 HTML/XML 解析库）

2. **DDL 扫描集成**
   - knowledge-graph-system 有独立的 DDL 扫描器
   - SmanAgent 需要集成 DDL 扫描结果（第 4 节已设计）

3. **存储方式**
   - knowledge-graph-system 存入文件系统
   - SmanAgent 存入向量库（待第 11 节设计）

4. **PSI 集成**
   - knowledge-graph-system 不使用 PSI
   - SmanAgent 利用 PSI 验证类存在、查找方法签名

## 专家知识库

### 关键问题

1. **如何解决反射问题？**
   - 理解 XML 配置的作用：流程配置、Bean 定义、SQL 映射
   - 提取反射目标：类名、方法名、参数
   - 构建"配置 → 代码"的映射关系
   - 结合 PSI 验证反射目标存在

2. **如何理解 Filter 表达式？**
   - 使用正则表达式提取字段引用：`loan.getUnflag()` → `loan.unflag`
   - 从 DDL 查找字段业务含义
   - 生成可理解的描述："当贷款未注销时"

3. **如何推断方法名？**
   - 从 `type` 属性推断：`type="load"` → 方法名 `load`
   - 从类名推断：`LoadLoanProcedure` → 方法名 `load`
   - 从类后缀推断：`*Executor` → 方法名 `run`

4. **如何处理动态 SQL？**
   - 解析 `<if test="xxx">` → "如果 xxx 条件满足"
   - 解析 `<choose><when test="xxx">` → "根据 xxx 选择"
   - 转换为业务逻辑描述

5. **如何构建调用链？**
   - 解析流程配置的步骤顺序
   - 结合 PSI 查找方法签名
   - 分析方法调用关系
   - 生成完整的业务流程图

### 最佳实践

1. **XML 类型识别**
   - 先根据文件名模式快速匹配
   - 再读取根标签精确识别
   - 最后检查特殊子标签兜底

2. **Bean 类推断**
   - 优先使用 PSI 查找验证
   - 回退到命名规则推断
   - 记录置信度

3. **Filter 表达式解析**
   - 使用正则表达式提取字段引用
   - 从 DDL 查找字段含义
   - 生成结构化的上下文描述

4. **错误处理**
   - XML 解析失败：记录日志，跳过该文件
   - Bean 类不存在：标记为推断，降低置信度
   - DDL 字段缺失：使用字段名作为业务名称

## 待解决的问题

1. **复杂 Filter 表达式**
   - 如何处理嵌套的逻辑运算（and, or）？
   - 如何处理方法调用参数（`loan.getStatus() == 'ACTIVE'`）？

2. **多文件配置**
   - 如何处理跨文件的配置引用（`<import resource="xxx.xml">`）？
   - 如何处理配置继承（`<bean parent="xxx">`）？

3. **动态配置**
   - 如何处理占位符（`${property.name}`）？
   - 如何解析属性文件（`application.properties`）？

4. **向量库存储**
   - 如何设计 XML 配置的向量 Schema？
   - 如何生成 XML 配置的 embedding？
   - 如何支持 BGE 召回 + 重排？

5. **性能优化**
   - 大量 XML 文件的扫描速度
   - DDL 字段含义查找的缓存
   - PSI 类查找的性能优化

## 下一步

- [ ] 实现基础的 XML 类型检测
- [ ] 实现流程配置解析
- [ ] 实现 MyBatis Mapper 解析
- [ ] 实现 Filter 表达式解析
- [ ] 实现 Spring Bean 配置解析
- [ ] 集成 PSI 进行 Bean 类验证
- [ ] 集成 DDL 扫描结果
- [ ] 实现 LLM 业务功能分析
- [x] 设计向量库存储格式（第 11 节）
- [x] 编写单元测试
- [ ] 性能优化和缓存策略

---

## ✅ 实现完成 (2026-01-30)

### 实现文件

- **位置**: `src/main/kotlin/com/smancode/smanagent/analysis/xml/XmlCodeScanner.kt`
- **测试**: 所有测试通过 (196 tests passed)

### 实现功能

1. ✅ **MyBatis Mapper 扫描** - 识别和解析 Mapper XML
2. ✅ **Spring 配置扫描** - 识别 application/context 配置
3. ✅ **表名提取** - 从 SQL 语句提取表名
4. ✅ **元数据提取** - ID、NAME、DESC 等属性
5. ✅ **引用提取** - class、resultMap 等引用关系

### 验证状态

```bash
./gradlew test
# BUILD SUCCESSFUL - 196 tests completed
```
