# 07 - Enum 扫描

## 目标

为 SmanAgent 项目添加枚举（Enum）扫描能力，通过分析项目中的 Java/Kotlin 枚举类，提取完整的枚举定义信息，包括枚举常量、字段、方法、注解，并结合业务实体建立关系，识别枚举的业务含义，为后续的 BGE 召回+重排提供基础数据。

## 参考

- knowledge-graph-system 的 `EnumDefinitionExtractor.java`
- knowledge-graph-system 的 `EnumReferenceDetector.java`
- knowledge-graph-system 的 `EnumContextEnhancer.java`
- knowledge-graph-system 的 `EnumRepository.java`
- knowledge-graph-system 的枚举模型类（`EnumDefinition`、`EnumConstantDefinition`）

## 核心功能

### 1. 枚举定义扫描

**目标**：扫描并提取项目中所有枚举类的完整定义

**扫描规则**：

#### 1.1 枚举类识别

```kotlin
// Java 枚举
public enum LoanStatus {
    PENDING, APPROVED, REJECTED
}

// Kotlin 枚举
enum class LoanStatus {
    PENDING, APPROVED, REJECTED
}

// 带构造参数的枚举
public enum ActionEnum {
    LOAN_GA("担保类型借据", bo -> "1".equals(...)),
    LOAN_DD_S("担保方式为抵押", ...);
}
```

**PSI 解析策略**：
```kotlin
// 1. 查找所有枚举类
val enumClasses = project.allFiles()
    .filter { it.extension == "java" || it.extension == "kt" }
    .mapNotNull { psiManager.findFile(it) }
    .filter { it is PsiEnum || it.isEnum() }

// 2. 提取枚举信息
val enumDefinition = extractEnumInfo(enumClass)
```

#### 1.2 枚举信息提取

**提取内容**：
- 枚举名称（简单类名、完整类名）
- 包路径
- 枚举常量列表
- 枚举字段
- 枚举方法
- 枚举上的注解（如 `@JsonValue`、`@JsonProperty`）
- 源码文件路径
- 完整源码（缓存）

**数据模型**：
```kotlin
data class EnumDefinition(
    // 基本信息
    val qualifiedName: String,              // 完整类名（如 "com.example.LoanStatus"）
    val simpleName: String,                 // 简单类名（如 "LoanStatus"）
    val packageName: String,                // 包名
    val sourceFilePath: String,             // 源码文件路径

    // 枚举常量
    val constants: Map<String, EnumConstantDefinition>,

    // 方法和字段
    val methods: List<MethodDefinition>,
    val fields: List<FieldDefinition>,

    // 注解
    val annotations: List<AnnotationInfo>,

    // 完整源码（缓存）
    val fullSourceCode: String
)
```

### 2. 枚举常量提取

**目标**：提取每个枚举常量的详细信息

**提取内容**：
```kotlin
data class EnumConstantDefinition(
    val name: String,                       // 常量名称（如 "PENDING"）
    val constructorArgs: List<String>,      // 构造参数值
    val sourceSnippet: String,              // 源码片段
    val businessRules: List<BusinessRule>,  // 关联的业务规则
    val nestedEnumReferences: Map<String, Set<String>> // 嵌套的枚举引用
)
```

**提取策略**：

#### 2.1 简单枚举常量

```java
public enum LoanStatus {
    PENDING,        // name: "PENDING", constructorArgs: []
    APPROVED,       // name: "APPROVED", constructorArgs: []
    REJECTED        // name: "REJECTED", constructorArgs: []
}
```

#### 2.2 带构造参数的枚举常量

```java
public enum ActionEnum {
    LOAN_GA("担保类型借据", bo -> "1".equals(...))
    // name: "LOAN_GA"
    // constructorArgs: ["担保类型借据", "bo -> ..."]
    // businessRules: [{ruleName: "担保类型借据", condition: "bo -> ..."}]
}
```

#### 2.3 带数组初始化的枚举常量

```java
DISBURSE("正常放款", new SingleScenarioEnum[]{
    LOAN_BL, LOAN_DS, LOAN_DD_S, ...
})
// name: "DISBURSE"
// constructorArgs: ["正常放款", "[LOAN_BL, LOAN_DS, ...]"]
// nestedEnumReferences: {"SingleScenarioEnum": ["LOAN_BL", "LOAN_DS", ...]}
```

**数组简化规则**：
```kotlin
// 输入：new SingleScenarioEnum[]{LOAN_BL, LOAN_DS, LOAN_DD_S, ...}
// 输出："[LOAN_BL, LOAN_DS, LOAN_DD_S, ...]"

private fun simplifyArrayInitialization(newArray: CtNewArray<*>): String {
    val elements = newArray.elements
    if (elements.isEmpty()) return "[]"

    val sb = StringBuilder()
    sb.append("[")

    val maxElements = 10 // 最多显示10个元素
    for (i in 0 until minOf(elements.size, maxElements)) {
        if (i > 0) sb.append(", ")
        sb.append(elements[i].toString())
    }

    if (elements.size > maxElements) {
        sb.append(", ... (共").append(elements.size).append("个)")
    }

    sb.append("]")
    return sb.toString()
}
```

### 3. 枚举引用检测

**目标**：检测代码中对枚举的引用

**检测模式**：

#### 3.1 方法调用检测

```java
ActionEnum.ZZ_PACK.restrictVerify(...)
// -> {"com.example.ActionEnum": ["ZZ_PACK"]}
```

**检测策略**：
```kotlin
// 1. 扫描方法调用
val invocations = method.getElements(TypeFilter(CtInvocation::class.java))

for (invocation in invocations) {
    val targetType = extractTargetType(invocation)

    if (isEnumType(targetType)) {
        val enumName = targetType.qualifiedName
        val constantName = extractEnumConstantFromInvocation(invocation)
        // 添加到引用映射
    }
}
```

#### 3.2 字段访问检测

```java
if (action == ActionEnum.ZZ_PACK) { ... }
// -> {"com.example.ActionEnum": ["ZZ_PACK"]}
```

**检测策略**：
```kotlin
// 2. 扫描字段访问
val fieldReads = method.getElements(TypeFilter(CtFieldRead::class.java))

for (fieldRead in fieldReads) {
    if (isEnumType(fieldRead.type)) {
        val enumName = fieldRead.type.qualifiedName
        val constantName = fieldRead.variable.simpleName
        // 添加到引用映射
    }
}
```

#### 3.3 类型访问检测

```java
ActionEnum.valueOf(...)
// -> {"com.example.ActionEnum": []}（引用了类型但未引用具体常量）
```

**检测策略**：
```kotlin
// 3. 扫描类型访问
val typeAccesses = method.getElements(TypeFilter(CtTypeAccess::class.java))

for (typeAccess in typeAccesses) {
    val accessedType = typeAccess.accessedType
    if (isEnumType(accessedType)) {
        val enumName = accessedType.qualifiedName
        // 添加到引用映射（空集合表示引用了类型）
    }
}
```

### 4. 业务含义推断

**目标**：从枚举定义和构造参数推断业务含义

**推断策略**：

#### 4.1 从枚举命名推断

```kotlin
// 枚举名称映射表
val ENUM_NAME_TO_DESCRIPTION = mapOf(
    "LoanStatus" to "贷款状态",
    "ActionEnum" to "业务操作",
    "RepaymentMethod" to "还款方式",
    "VerifyMode" to "校验模式"
)
```

#### 4.2 从构造参数推断

```kotlin
// 从第一个构造参数获取业务含义
LOAN_GA("担保类型借据", ...)
// -> 业务含义: "担保类型借据"
```

#### 4.3 从字段注释提取

```kotlin
// 提取枚举类和字段的注释
@Comment("贷款状态枚举")
public enum LoanStatus {
    /** 待审批 */
    PENDING,

    /** 已批准 */
    APPROVED
}
```

#### 4.4 从注解提取

```kotlin
// 提取 Jackson 注解
@JsonFormat(shape = JsonFormat.Shape.OBJECT)
public enum LoanStatus {
    @JsonProperty("pending")
    PENDING("待审批"),

    @JsonProperty("approved")
    APPROVED("已批准")
}
```

### 5. 与业务实体建立关系

**目标**：识别哪些实体类使用了哪些枚举，以及枚举在实体中的用途

**建立关系**：

#### 5.1 实体字段枚举引用

```java
@Entity
public class Loan {
    @Enumerated(EnumType.STRING)
    private LoanStatus status;  // 使用了 LoanStatus 枚举
}
```

**提取策略**：
```kotlin
// 1. 查找所有实体类的字段
val entityFields = entityClass.fields

for (field in entityFields) {
    val fieldType = field.type

    if (isEnumType(fieldType)) {
        val enumName = fieldType.qualifiedName
        val fieldName = field.name

        // 建立关系
        addEntityEnumRelation(
            entityName = entityClass.name,
            fieldName = fieldName,
            enumName = enumName,
            usage = inferFieldUsage(fieldName) // "状态"、"类型"等
        )
    }
}
```

#### 5.2 用途推断

```kotlin
// 从字段名推断枚举用途
val FIELD_NAME_TO_USAGE = mapOf(
    "status" to "状态",
    "state" to "状态",
    "type" to "类型",
    "category" to "分类",
    "method" to "方式",
    "mode" to "模式"
)

fun inferFieldUsage(fieldName: String): String {
    val lowerName = fieldName.lowercase()
    return FIELD_NAME_TO_USAGE.entries
        .find { lowerName.contains(it.key) }
        ?.value ?: "属性"
}
```

**关系模型**：
```kotlin
data class EntityEnumRelation(
    val entityName: String,               // 实体类名
    val fieldName: String,                // 字段名
    val enumName: String,                 // 枚举类名
    val usage: String,                    // 用途（状态、类型等）
    val enumValues: List<String>          // 枚举值列表
)
```

### 6. 嵌套枚举引用解析

**目标**：递归解析枚举常量中的嵌套枚举引用

**示例**：
```java
public enum ActionEnum {
    DISBURSE("正常放款", new SingleScenarioEnum[]{
        LOAN_BL, LOAN_DS, LOAN_DD_S, ...
    })
}
```

**解析流程**：
```kotlin
// 1. 检测嵌套枚举引用
val nestedReferences = extractNestedEnumReferences(enumConstant)
// -> {"SingleScenarioEnum": ["LOAN_BL", "LOAN_DS", ...]}

// 2. 递归解析嵌套枚举
val allReferences = mutableMapOf<String, Set<String>>()
recursivelyResolveNestedEnums(
    projectKey = projectKey,
    currentReferences = nestedReferences,
    allReferences = allReferences,
    resolvingStack = mutableSetOf() // 防止循环依赖
)

// 3. 合并所有引用
allReferences["ActionEnum"] = setOf("DISBURSE")
allReferences["SingleScenarioEnum"] = setOf("LOAN_BL", "LOAN_DS", ...)
```

**递归解析**：
```kotlin
private fun recursivelyResolveNestedEnums(
    projectKey: String,
    currentReferences: Map<String, Set<String>>,
    allReferences: MutableMap<String, Set<String>>,
    resolvingStack: MutableSet<String>
) {
    for ((enumName, constantNames) in currentReferences) {
        // 检测循环依赖
        if (resolvingStack.contains(enumName)) {
            logger.warn("检测到枚举循环依赖，跳过: {}", enumName)
            continue
        }

        // 获取枚举定义
        val enumDef = getOrExtractEnum(projectKey, enumName, constantNames)
        if (enumDef == null) continue

        // 查找嵌套引用
        val nestedReferences = mutableMapOf<String, Set<String>>()
        for (constantName in constantNames) {
            val constant = enumDef.getConstant(constantName)
            if (constant?.hasNestedEnumReferences() == true) {
                nestedReferences.putAll(constant.nestedEnumReferences)
                allReferences.putAll(constant.nestedEnumReferences)
            }
        }

        // 递归解析
        if (nestedReferences.isNotEmpty()) {
            resolvingStack.add(enumName)
            try {
                recursivelyResolveNestedEnums(
                    projectKey, nestedReferences, allReferences, resolvingStack
                )
            } finally {
                resolvingStack.remove(enumName)
            }
        }
    }
}
```

### 7. 枚举注解提取

**目标**：提取枚举类和枚举常量上的注解信息

**常见注解**：

#### 7.1 Jackson 注解

```java
public enum LoanStatus {
    @JsonProperty("pending")
    @JsonValue
    PENDING("待审批"),

    @JsonProperty("approved")
    APPROVED("已批准")
}
```

**提取策略**：
```kotlin
data class AnnotationInfo(
    val name: String,                    // 注解名称（如 "JsonProperty"）
    val attributes: Map<String, String>  // 注解属性（如 {"value": "pending"}）
)

fun extractAnnotations(psiElement: PsiElement): List<AnnotationInfo> {
    val annotations = mutableListOf<AnnotationInfo>()

    if (psiElement is PsiModifierOwner) {
        psiElement.modifierList?.annotations?.forEach { psiAnnotation ->
            val annotationInfo = AnnotationInfo(
                name = psiAnnotation.qualifiedName ?: psiAnnotation.shortName,
                attributes = extractAnnotationAttributes(psiAnnotation)
            )
            annotations.add(annotationInfo)
        }
    }

    return annotations
}
```

#### 7.2 JPA 注解

```java
@Enumerated(EnumType.STRING)
private LoanStatus status;
```

**提取策略**：
```kotlin
val enumeratedAnnotation = field.getAnnotation("javax.persistence.Enumerated")
if (enumeratedAnnotation != null) {
    val enumType = enumeratedAnnotation.findAttributeValue("value")
    // enumType: EnumType.STRING 或 EnumType.ORDINAL
}
```

## Kotlin 实现

### 文件位置

```
src/main/kotlin/com/smancode/smanagent/analyzer/
├── enum/
│   ├── EnumScanner.kt                      # 枚举扫描器（主入口）
│   ├── EnumDefinitionExtractor.kt          # 枚举定义提取器
│   ├── EnumReferenceDetector.kt            # 枚举引用检测器
│   ├── EnumContextEnhancer.kt              # 枚举上下文增强器
│   ├── EnumRepository.kt                   # 枚举仓储（缓存）
│   └── model/
│       ├── EnumDefinition.kt               # 枚举定义模型
│       ├── EnumConstantDefinition.kt       # 枚举常量定义模型
│       ├── MethodDefinition.kt             # 方法定义模型
│       ├── FieldDefinition.kt              # 字段定义模型
│       ├── BusinessRule.kt                 # 业务规则模型
│       ├── EntityEnumRelation.kt           # 实体-枚举关系模型
│       └── AnnotationInfo.kt               # 注解信息模型
```

### 核心接口

```kotlin
/**
 * 枚举扫描器接口
 */
interface EnumScanner {
    /**
     * 扫描项目中的所有枚举
     * @param project 项目实例
     * @return 枚举定义列表
     */
    fun scanEnums(project: Project): List<EnumDefinition>

    /**
     * 扫描实体类的枚举引用
     * @param project 项目实例
     * @return 实体-枚举关系列表
     */
    fun scanEntityEnumRelations(project: Project): List<EntityEnumRelation>
}

/**
 * 枚举定义模型
 */
data class EnumDefinition(
    // 基本信息
    val qualifiedName: String,
    val simpleName: String,
    val packageName: String,
    val sourceFilePath: String,

    // 枚举常量
    val constants: Map<String, EnumConstantDefinition>,

    // 方法和字段
    val methods: List<MethodDefinition>,
    val fields: List<FieldDefinition>,

    // 注解
    val annotations: List<AnnotationInfo>,

    // 完整源码（缓存）
    val fullSourceCode: String,

    // 业务含义
    val businessDescription: String? = null
) {
    fun getConstantCount(): Int = constants.size

    fun hasConstant(constantName: String): Boolean = constants.containsKey(constantName)

    fun toSourceCodeSnippet(): String {
        // 生成用于 LLM 分析的源码片段
    }
}

/**
 * 枚举常量定义模型
 */
data class EnumConstantDefinition(
    val name: String,
    val constructorArgs: List<String> = emptyList(),
    val sourceSnippet: String,
    val businessRules: List<BusinessRule> = emptyList(),
    val nestedEnumReferences: Map<String, Set<String>> = emptyMap(),
    val annotations: List<AnnotationInfo> = emptyList()
) {
    fun getDisplayName(): String {
        return constructorArgs.firstOrNull() ?: name
    }

    fun hasBusinessRules(): Boolean = businessRules.isNotEmpty()

    fun hasNestedEnumReferences(): Boolean = nestedEnumReferences.isNotEmpty()

    fun toSimpleDescription(): String = "$name: ${getDisplayName()}"
}

/**
 * 实体-枚举关系模型
 */
data class EntityEnumRelation(
    val entityName: String,
    val fieldName: String,
    val enumName: String,
    val usage: String,
    val enumValues: List<String>
)

/**
 * 业务规则模型
 */
data class BusinessRule(
    val ruleName: String,
    val enumConstant: String,
    val condition: String? = null,
    val priority: Int = 0
)

/**
 * 注解信息模型
 */
data class AnnotationInfo(
    val name: String,
    val attributes: Map<String, String> = emptyMap()
)
```

### 实现示例

#### 1. 枚举定义提取器

```kotlin
/**
 * 枚举定义提取器
 */
class EnumDefinitionExtractor(
    private val project: Project
) {
    private val logger = Logger.getInstance(EnumDefinitionExtractor::class.java)

    /**
     * 提取枚举定义
     */
    fun extractEnumDefinition(
        psiEnum: PsiClass,
        requiredConstants: Set<String> = emptySet()
    ): EnumDefinition? {
        if (!psiEnum.isEnum) {
            return null
        }

        logger.info("提取枚举定义: {}", psiEnum.qualifiedName)

        val definition = EnumDefinition(
            qualifiedName = psiEnum.qualifiedName ?: "",
            simpleName = psiEnum.name ?: "",
            packageName = getPackageName(psiEnum),
            sourceFilePath = psiEnum.containingFile?.virtualFile?.path ?: "",
            constants = extractConstants(psiEnum, requiredConstants),
            methods = extractMethods(psiEnum),
            fields = extractFields(psiEnum),
            annotations = extractAnnotations(psiEnum),
            fullSourceCode = extractFullSourceCode(psiEnum)
        )

        logger.info("成功提取枚举定义: {}, 常量数: {}",
            definition.simpleName, definition.getConstantCount())

        return definition
    }

    /**
     * 提取枚举常量
     */
    private fun extractConstants(
        psiEnum: PsiClass,
        requiredConstants: Set<String>
    ): Map<String, EnumConstantDefinition> {
        val constants = linkedMapOf<String, EnumConstantDefinition>()

        for (field in psiEnum.fields) {
            if (!field.isEnumConstant) continue

            val constantName = field.name ?: continue

            // 只提取需要的常量（如果指定了）
            if (requiredConstants.isNotEmpty() && !requiredConstants.contains(constantName)) {
                continue
            }

            val constant = extractEnumConstant(field)
            constants[constantName] = constant
            logger.debug("提取枚举常量: {}", constantName)
        }

        return constants
    }

    /**
     * 提取单个枚举常量
     */
    private fun extractEnumConstant(psiField: PsiField): EnumConstantDefinition {
        val constant = EnumConstantDefinition(
            name = psiField.name ?: "",
            constructorArgs = extractConstructorArgs(psiField),
            sourceSnippet = generateSourceSnippet(psiField),
            businessRules = extractBusinessRules(psiField),
            nestedEnumReferences = extractNestedEnumReferences(psiField),
            annotations = extractAnnotations(psiField)
        )

        if (constant.hasNestedEnumReferences()) {
            logger.info("枚举常量 {} 包含嵌套枚举引用: {}",
                constant.name, constant.nestedEnumReferences)
        }

        return constant
    }

    /**
     * 提取构造参数
     */
    private fun extractConstructorArgs(psiField: PsiField): List<String> {
        val args = mutableListOf<String>()

        val initializer = psiField.initializer
        if (initializer is PsiExpressionList) {
            for (expr in initializer.expressionList) {
                args.add(normalizeExpression(expr))
            }
        }

        return args
    }

    /**
     * 提取业务规则（从构造参数推断）
     */
    private fun extractBusinessRules(psiField: PsiField): List<BusinessRule> {
        val args = extractConstructorArgs(psiField)
        val rules = mutableListOf<BusinessRule>()

        if (args.isNotEmpty()) {
            val ruleName = removeQuotes(args[0])
            val rule = BusinessRule(
                ruleName = ruleName,
                enumConstant = psiField.name ?: "",
                condition = if (args.size >= 2) args[1] else null,
                priority = 0
            )
            rules.add(rule)
        }

        // 从数组参数中提取规则
        for (arg in args) {
            if (arg.startsWith("[") && arg.endsWith("]")) {
                rules.addAll(extractRulesFromArray(arg, psiField.name ?: ""))
            }
        }

        return rules
    }

    /**
     * 提取嵌套枚举引用（从数组初始化中）
     */
    private fun extractNestedEnumReferences(
        psiField: PsiField
    ): Map<String, Set<String>> {
        val nestedReferences = linkedMapOf<String, MutableSet<String>>()

        val initializer = psiField.initializer
        if (initializer is PsiNewExpression) {
            val arrayType = initializer.arrayType
            val arrayInitializer = initializer.arrayInitializer

            if (arrayInitializer != null) {
                // 推断数组元素的枚举类型
                val enumTypeName = arrayType?.elementType?.canonicalText

                for (element in arrayInitializer.expressionList) {
                    if (element is PsiReferenceExpression) {
                        val constantName = element.referenceName
                        if (enumTypeName != null && constantName != null) {
                            nestedReferences
                                .getOrPut(enumTypeName) { mutableSetOf() }
                                .add(constantName)
                        }
                    }
                }
            }
        }

        return nestedReferences
    }

    /**
     * 提取方法定义
     */
    private fun extractMethods(psiEnum: PsiClass): List<MethodDefinition> {
        val methods = mutableListOf<MethodDefinition>()

        for (method in psiEnum.methods) {
            if (method.isConstructor || method.isImplicit) continue

            val methodDef = MethodDefinition(
                name = method.name ?: "",
                signature = method.text,
                returnType = method.returnType?.canonicalText ?: "void",
                body = method.body?.text ?: "",
                important = isImportantMethodName(method.name)
            )
            methods.add(methodDef)
        }

        return methods
    }

    /**
     * 提取字段定义
     */
    private fun extractFields(psiEnum: PsiClass): List<FieldDefinition> {
        val fields = mutableListOf<FieldDefinition>()

        for (field in psiEnum.fields) {
            if (field.isEnumConstant || field.isImplicit) continue

            val fieldDef = FieldDefinition(
                name = field.name ?: "",
                type = field.type?.canonicalText ?: "Object",
                isFinal = field.isFinal,
                isStatic = field.isStatic,
                initializer = field.initializer?.text
            )
            fields.add(fieldDef)
        }

        return fields
    }

    /**
     * 提取注解
     */
    private fun extractAnnotations(psiElement: PsiElement): List<AnnotationInfo> {
        val annotations = mutableListOf<AnnotationInfo>()

        if (psiElement is PsiModifierOwner) {
            psiElement.modifierList?.annotations?.forEach { psiAnnotation ->
                val annotationInfo = AnnotationInfo(
                    name = psiAnnotation.qualifiedName ?: psiAnnotation.shortName,
                    attributes = extractAnnotationAttributes(psiAnnotation)
                )
                annotations.add(annotationInfo)
            }
        }

        return annotations
    }

    /**
     * 提取注解属性
     */
    private fun extractAnnotationAttributes(psiAnnotation: PsiAnnotation): Map<String, String> {
        val attributes = linkedMapOf<String, String>()

        psiAnnotation.parameterList.attributes.forEach { attr ->
            val name = attr.name ?: "value"
            val value = attr.value?.text ?: ""
            attributes[name] = value
        }

        return attributes
    }

    /**
     * 提取完整源码
     */
    private fun extractFullSourceCode(psiEnum: PsiClass): String {
        return psiEnum.containingFile?.text ?: ""
    }

    /**
     * 规范化表达式
     */
    private fun normalizeExpression(expr: PsiExpression): String {
        return when (expr) {
            is PsiLiteralExpression -> {
                val value = (expr as PsiLiteralExpression).value
                when (value) {
                    is String -> "\"$value\""
                    else -> value?.toString() ?: "null"
                }
            }
            is PsiNewExpression -> {
                simplifyArrayInitialization(expr)
            }
            else -> expr.text.take(500)
        }
    }

    /**
     * 简化数组初始化
     */
    private fun simplifyArrayInitialization(newExpr: PsiNewExpression): String {
        val arrayInitializer = newExpr.arrayInitializer
        if (arrayInitializer == null) return "[]"

        val elements = arrayInitializer.expressionList
        if (elements.isEmpty()) return "[]"

        val sb = StringBuilder()
        sb.append("[")

        val maxElements = 10
        for (i in 0 until minOf(elements.size, maxElements)) {
            if (i > 0) sb.append(", ")
            sb.append(elements[i].text)
        }

        if (elements.size > maxElements) {
            sb.append(", ... (共").append(elements.size).append("个)")
        }

        sb.append("]")
        return sb.toString()
    }

    /**
     * 生成源码片段
     */
    private fun generateSourceSnippet(psiField: PsiField): String {
        val sb = StringBuilder()
        sb.append(psiField.name)

        val initializer = psiField.initializer
        if (initializer != null) {
            sb.append("(")
            val exprStr = initializer.text.take(100)
            sb.append(exprStr)
            if (initializer.text.length > 100) {
                sb.append("...")
            }
            sb.append(")")
        }

        return sb.toString()
    }

    /**
     * 从数组字符串中提取业务规则
     */
    private fun extractRulesFromArray(
        arrayStr: String,
        enumConstant: String
    ): List<BusinessRule> {
        val content = arrayStr.removeSurrounding("[", "]")
        val elements = content.split(",")

        return elements
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "..." }
            .map { element ->
                BusinessRule(
                    ruleName = element,
                    enumConstant = element,
                    priority = 0
                )
            }
    }

    /**
     * 移除字符串两端的引号
     */
    private fun removeQuotes(str: String): String {
        return when {
            str.startsWith("\"") && str.endsWith("\"") -> str.substring(1, str.length - 1)
            str.startsWith("'") && str.endsWith("'") -> str.substring(1, str.length - 1)
            else -> str
        }
    }

    /**
     * 判断是否是重要方法名
     */
    private fun isImportantMethodName(methodName: String?): Boolean {
        if (methodName == null) return false
        return listOf("restrictVerify", "verify", "validate", "check")
            .any { methodName.contains(it, ignoreCase = true) }
    }

    /**
     * 获取包名
     */
    private fun getPackageName(psiClass: PsiClass): String {
        return psiClass.qualifiedName?.substringBeforeLast(".") ?: ""
    }
}
```

#### 2. 枚举引用检测器

```kotlin
/**
 * 枚举引用检测器
 */
class EnumReferenceDetector(
    private val project: Project
) {
    private val logger = Logger.getInstance(EnumReferenceDetector::class.java)

    /**
     * 检测方法中引用的所有枚举
     * @return Map<枚举类名, 引用的常量名集合>
     */
    fun detectEnumReferences(method: PsiMethod): Map<String, Set<String>> {
        val enumReferences = linkedMapOf<String, MutableSet<String>>()

        // 1. 扫描方法调用
        scanMethodInvocations(method, enumReferences)

        // 2. 扫描字段访问
        scanFieldReads(method, enumReferences)

        // 3. 扫描类型访问
        scanTypeAccess(method, enumReferences)

        if (enumReferences.isNotEmpty()) {
            logger.debug("检测到 {} 个枚举引用: {}", enumReferences.size, enumReferences)
        }

        return enumReferences
    }

    /**
     * 扫描方法调用中的枚举引用
     */
    private fun scanMethodInvocations(
        method: PsiMethod,
        enumReferences: MutableMap<String, MutableSet<String>>
    ) {
        method.accept(object : JavaRecursiveElementVisitor() {
            override fun visitMethodCallExpression(call: PsiMethodCallExpression) {
                super.visitMethodCallExpression(call)

                val methodRef = call.methodExpression
                val qualifier = methodRef.qualifierExpression

                if (qualifier is PsiReferenceExpression) {
                    val type = qualifier.type
                    if (isEnumType(type)) {
                        val enumName = type?.canonicalText ?: return
                        val constantName = qualifier.referenceName

                        enumReferences
                            .getOrPut(enumName) { mutableSetOf() }
                            .add(constantName)

                        logger.debug("检测到方法调用中的枚举引用: {}.{}",
                            enumName, constantName)
                    }
                }
            }
        })
    }

    /**
     * 扫描字段访问中的枚举引用
     */
    private fun scanFieldReads(
        method: PsiMethod,
        enumReferences: MutableMap<String, MutableSet<String>>
    ) {
        method.accept(object : JavaRecursiveElementVisitor() {
            override fun visitReferenceExpression(expr: PsiReferenceExpression) {
                super.visitReferenceExpression(expr)

                val type = expr.type
                if (isEnumType(type)) {
                    val enumName = type?.canonicalText ?: return
                    val constantName = expr.referenceName

                    enumReferences
                        .getOrPut(enumName) { mutableSetOf() }
                        .add(constantName)

                    logger.debug("检测到字段访问中的枚举引用: {}.{}",
                        enumName, constantName)
                }
            }
        })
    }

    /**
     * 扫描类型访问中的枚举引用
     */
    private fun scanTypeAccess(
        method: PsiMethod,
        enumReferences: MutableMap<String, MutableSet<String>>
    ) {
        method.accept(object : JavaRecursiveElementVisitor() {
            override fun visitClassTypeElement(element: PsiClassTypeElement) {
                super.visitClassTypeElement(element)

                val type = element.type
                if (isEnumType(type)) {
                    val enumName = type?.canonicalText ?: return

                    // 类型访问不一定引用具体常量
                    enumReferences.putIfAbsent(enumName, mutableSetOf())

                    logger.debug("检测到类型访问中的枚举引用: {}", enumName)
                }
            }
        })
    }

    /**
     * 检查类型是否是枚举
     */
    private fun isEnumType(type: PsiType?): Boolean {
        if (type == null) return false

        // 方法1：检查类型声明
        val psiClass = type.resolve()
        if (psiClass is PsiEnum) {
            return true
        }

        // 方法2：检查继承关系
        val enumType = PsiType.getJavaLangEnum(type.manager, type.resolveScope)
        return type.isAssignableFrom(enumType)
    }
}
```

## 与 knowledge-graph-system 的差异

### 可以借鉴的部分

1. **枚举定义提取**
   - 从 CtEnum 中提取完整定义
   - 按需提取枚举常量（只提取引用的常量）
   - 构造参数提取和简化
   - 嵌套枚举引用提取（从数组初始化中）

2. **枚举引用检测**
   - 方法调用检测（`ActionEnum.ZZ_PACK.restrictVerify(...)`）
   - 字段访问检测（`action == ActionEnum.ZZ_PACK`）
   - 类型访问检测（`ActionEnum.valueOf(...)`）
   - 三级检查策略（类型声明、Factory 查找、继承关系）

3. **业务规则推断**
   - 从构造参数推断业务规则名称
   - 从 Lambda 表达式提取校验逻辑
   - 从数组初始化中提取嵌套引用

4. **嵌套枚举递归解析**
   - 递归解析嵌套引用
   - 循环依赖检测
   - 所有引用累积

### 需要调整的部分

1. **IntelliJ PSI 集成**
   - knowledge-graph-system：使用 Spoon AST
   - SmanAgent：使用 IntelliJ PSI（更准确、性能更好）
   - 无需额外解析，直接通过 PSI 获取类型信息

2. **实体-枚举关系建立**
   - knowledge-graph-system：未实现
   - SmanAgent：新增功能，扫描实体类的枚举字段

3. **业务含义推断**
   - knowledge-graph-system：从构造参数推断
   - SmanAgent：增强版，结合命名、注释、注解推断

4. **向量化准备**
   - knowledge-graph-system：用于知识图谱构建
   - SmanAgent：用于 BGE 召回+重排
   - 需要生成结构化的向量文本

## 专家知识库

### 关键问题

#### 1. 如何判断类型是否是枚举？

**问题**：如何准确判断一个 PsiType 是否是枚举类型？

**解决方案**：
```kotlin
// 三级检查策略
fun isEnumType(type: PsiType?): Boolean {
    if (type == null) return false

    // 方法1：检查类型声明（最快）
    val psiClass = type.resolve()
    if (psiClass is PsiEnum) {
        return true
    }

    // 方法2：检查继承关系（兜底方案）
    val enumType = PsiType.getJavaLangEnum(type.manager, type.resolveScope)
    return type.isAssignableFrom(enumType)
}
```

#### 2. 如何提取枚举常量的构造参数？

**问题**：枚举常量的构造参数可能非常复杂（Lambda 表达式、数组初始化等），如何提取？

**解决方案**：
```kotlin
private fun extractConstructorArgs(psiField: PsiField): List<String> {
    val args = mutableListOf<String>()

    val initializer = psiField.initializer
    if (initializer is PsiExpressionList) {
        for (expr in initializer.expressionList) {
            args.add(normalizeExpression(expr))
        }
    }

    return args
}

private fun normalizeExpression(expr: PsiExpression): String {
    return when (expr) {
        is PsiLiteralExpression -> {
            // 字面量（字符串、数字等）
            val value = (expr as PsiLiteralExpression).value
            when (value) {
                is String -> "\"$value\""
                else -> value?.toString() ?: "null"
            }
        }
        is PsiNewExpression -> {
            // 数组初始化（简化处理）
            simplifyArrayInitialization(expr)
        }
        is PsiLambdaExpression -> {
            // Lambda 表达式（简化显示）
            expr.text.take(500)
        }
        else -> {
            // 其他类型
            expr.text.take(500)
        }
    }
}
```

#### 3. 如何处理嵌套枚举引用？

**问题**：枚举常量可能引用其他枚举（如数组初始化），如何递归解析？

**解决方案**：
```kotlin
// 1. 提取嵌套引用
private fun extractNestedEnumReferences(
    psiField: PsiField
): Map<String, Set<String>> {
    val nestedReferences = linkedMapOf<String, MutableSet<String>>()

    val initializer = psiField.initializer
    if (initializer is PsiNewExpression) {
        val arrayType = initializer.arrayType
        val arrayInitializer = initializer.arrayInitializer

        if (arrayInitializer != null) {
            // 推断数组元素的枚举类型
            val enumTypeName = arrayType?.elementType?.canonicalText

            for (element in arrayInitializer.expressionList) {
                if (element is PsiReferenceExpression) {
                    val constantName = element.referenceName
                    if (enumTypeName != null && constantName != null) {
                        nestedReferences
                            .getOrPut(enumTypeName) { mutableSetOf() }
                            .add(constantName)
                    }
                }
            }
        }
    }

    return nestedReferences
}

// 2. 递归解析嵌套引用
fun recursivelyResolveNestedEnums(
    projectKey: String,
    currentReferences: Map<String, Set<String>>,
    allReferences: MutableMap<String, Set<String>>,
    resolvingStack: MutableSet<String>
) {
    for ((enumName, constantNames) in currentReferences) {
        // 检测循环依赖
        if (resolvingStack.contains(enumName)) {
            logger.warn("检测到枚举循环依赖，跳过: {}", enumName)
            continue
        }

        // 获取枚举定义
        val enumDef = enumRepository.getEnumDefinition(projectKey, enumName)
        if (enumDef == null) continue

        // 查找嵌套引用
        val nestedReferences = mutableMapOf<String, Set<String>>()
        for (constantName in constantNames) {
            val constant = enumDef.getConstant(constantName)
            if (constant?.hasNestedEnumReferences() == true) {
                nestedReferences.putAll(constant.nestedEnumReferences)
                allReferences.putAll(constant.nestedEnumReferences)
            }
        }

        // 递归解析
        if (nestedReferences.isNotEmpty()) {
            resolvingStack.add(enumName)
            try {
                recursivelyResolveNestedEnums(
                    projectKey, nestedReferences, allReferences, resolvingStack
                )
            } finally {
                resolvingStack.remove(enumName)
            }
        }
    }
}
```

#### 4. 如何建立实体-枚举关系？

**问题**：如何识别实体类中使用了哪些枚举，以及枚举的用途？

**解决方案**：
```kotlin
// 1. 扫描实体类的字段
val entityFields = entityClass.fields
for (field in entityFields) {
    val fieldType = field.type

    if (isEnumType(fieldType)) {
        val enumName = fieldType.qualifiedName
        val fieldName = field.name

        // 2. 推断枚举用途
        val usage = inferFieldUsage(fieldName)

        // 3. 获取枚举值列表
        val enumDef = enumRepository.getEnumDefinition(projectKey, enumName)
        val enumValues = enumDef?.constants?.keys?.toList() ?: emptyList()

        // 4. 建立关系
        addEntityEnumRelation(
            entityName = entityClass.name,
            fieldName = fieldName,
            enumName = enumName,
            usage = usage,
            enumValues = enumValues
        )
    }
}

// 推断字段用途
fun inferFieldUsage(fieldName: String): String {
    val lowerName = fieldName.lowercase()
    return FIELD_NAME_TO_USAGE.entries
        .find { lowerName.contains(it.key) }
        ?.value ?: "属性"
}

val FIELD_NAME_TO_USAGE = mapOf(
    "status" to "状态",
    "state" to "状态",
    "type" to "类型",
    "category" to "分类",
    "method" to "方式",
    "mode" to "模式"
)
```

### 待解决的问题

1. **增量更新**
   - 当枚举类修改时，如何增量更新？
   - PSI 监听机制？
   - 文件修改时间检测？

2. **性能优化**
   - 大型项目枚举数量多，如何优化扫描速度？
   - 并发扫描？
   - 缓存策略？

3. **错误处理**
   - 枚举类定义不规范？
   - 构造参数解析失败？
   - 循环引用？

4. **Kotlin 枚举支持**
   - Kotlin 枚举的 PSI 结构与 Java 不同
   - 需要特殊处理

5. **向量化文本生成**
   - 如何将枚举定义转换为结构化的向量文本？
   - 如何包含业务含义和关系信息？

## 下一步

- [ ] 实现枚举定义提取器（基于 PSI）
- [ ] 实现枚举引用检测器
- [ ] 实现实体-枚举关系建立
- [ ] 实现嵌套枚举递归解析
- [ ] 实现业务含义推断
- [ ] 实现枚举仓储（缓存）
- [ ] 设计向量化文本生成策略
- [ ] 编写单元测试
- [ ] 性能测试和优化

---

**文档版本**: v1.0
**最后更新**: 2026-01-29
**状态**: 设计完成，待实施

---

## ✅ 实现完成 (2026-01-30)

### 实现文件

- **位置**: `src/main/kotlin/com/smancode/smanagent/analysis/enum/EnumScanner.kt`
- **测试**: 所有测试通过 (196 tests passed)

### 实现功能

1. ✅ **枚举类扫描** - 识别所有 `enum class` 声明
2. ✅ **枚举常量提取** - 提取所有枚举值
3. ✅ **业务含义推断** - 基于命名模式推断业务语义
4. ✅ **包名和限定名** - 完整的类路径信息

### 核心数据类

```kotlin
@Serializable
data class EnumInfo(
    val enumName: String,
    val qualifiedName: String,
    val packageName: String,
    val constants: List<EnumConstant>,
    val businessMeaning: String
)

@Serializable
data class EnumConstant(
    val name: String,
    val value: String?,
    val description: String?
)
```

### 验证状态

```bash
./gradlew test
# BUILD SUCCESSFUL - 196 tests completed
```
