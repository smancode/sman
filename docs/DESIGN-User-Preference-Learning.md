# 设计方案：用户习惯学习系统

> 版本：1.0
> 日期：2026-02-23

---

## 一、设计目标

实现用户习惯的自动学习和应用：

1. **隐式反馈收集**：自动分析用户对代码的修改
2. **显式反馈收集**：用户主动评价和指导
3. **偏好提取**：从反馈中提取可复用的偏好
4. **知识应用**：下次交互时自动应用学到的偏好

---

## 二、学习闭环架构

```
┌──────────────────────────────────────────────────────────────────┐
│                     用户习惯学习闭环                               │
├──────────────────────────────────────────────────────────────────┤
│                                                                  │
│   ┌──────────┐    ┌──────────────┐    ┌──────────────────┐      │
│   │ 用户交互  │───>│ 反馈收集器   │───>│ 习惯提取器       │      │
│   └──────────┘    └──────────────┘    └──────────────────┘      │
│        │                 │                     │                │
│        │                 │                     ▼                │
│        │                 │          ┌──────────────────┐        │
│        │                 │          │ 偏好存储（向量） │        │
│        │                 │          └──────────────────┘        │
│        │                 │                     │                │
│        ▼                 ▼                     ▼                │
│   ┌──────────────────────────────────────────────────────┐      │
│   │              知识应用器（下次交互时注入）               │      │
│   └──────────────────────────────────────────────────────┘      │
│                                                                  │
└──────────────────────────────────────────────────────────────────┘
```

---

## 三、偏好分类体系

```kotlin
/**
 * 偏好类别
 */
enum class PreferenceCategory(
    val displayName: String,
    val description: String
) {
    // 代码风格
    NAMING_STYLE("命名风格", "变量、方法、类的命名习惯"),
    CODE_FORMATTING("代码格式化", "缩进、空行、括号风格"),
    COMMENT_STYLE("注释风格", "KDoc/JavaDoc、注释密度"),

    // 架构偏好
    ARCHITECTURE_PATTERN("架构模式", "MVC/Clean/DDD 等架构偏好"),
    ERROR_HANDLING("错误处理", "异常/Result/Option 等错误处理方式"),
    DEPENDENCY_INJECTION("依赖注入", "Koin/Dagger/Hilt 等注入方式"),

    // 技术选型
    LIBRARY_PREFERENCE("库偏好", "同类库的选择偏好"),
    TESTING_FRAMEWORK("测试框架", "JUnit/TestNG/Kotest 等"),
    BUILD_TOOL("构建工具", "Gradle/Maven 等"),

    // 业务规则
    BUSINESS_RULES("业务规则", "项目特定的业务规则"),
    DOMAIN_TERMS("领域术语", "业务术语与代码映射"),

    // 交互偏好
    OUTPUT_DETAIL("输出详细程度", "Agent 回复的详细程度"),
    LANGUAGE_PREFERENCE("语言偏好", "中文/英文输出"),
    CONFIRMATION_LEVEL("确认频率", "执行前的确认频率")
}
```

---

## 四、反馈收集设计

### 4.1 隐式反馈收集器

```kotlin
/**
 * 隐式反馈收集器
 *
 * 自动分析用户行为，提取偏好
 */
class ImplicitFeedbackCollector(
    private val project: Project,
    private val preferenceStore: PreferenceStore
) {
    companion object {
        private val logger = Logger.getInstance(ImplicitFeedbackCollector::class.java)
    }

    /**
     * 分析用户对生成代码的修改
     */
    fun analyzeCodeModification(
        original: String,
        modified: String,
        context: ModificationContext
    ): List<UserPreference> {
        val preferences = mutableListOf<UserPreference>()

        // 1. 命名风格分析
        val namingPrefs = analyzeNamingDifference(original, modified)
        preferences.addAll(namingPrefs)

        // 2. 代码结构分析
        val structurePrefs = analyzeStructureDifference(original, modified)
        preferences.addAll(structurePrefs)

        // 3. 注释风格分析
        val commentPrefs = analyzeCommentDifference(original, modified)
        preferences.addAll(commentPrefs)

        // 4. 错误处理分析
        val errorHandlingPrefs = analyzeErrorHandlingDifference(original, modified)
        preferences.addAll(errorHandlingPrefs)

        // 记录学习日志
        if (preferences.isNotEmpty()) {
            logger.info("从代码修改中学习到 ${preferences.size} 个偏好")
            preferences.forEach { pref ->
                logger.debug("  - ${pref.category}: ${pref.content}")
            }
        }

        return preferences
    }

    /**
     * 分析命名差异
     */
    private fun analyzeNamingDifference(original: String, modified: String): List<UserPreference> {
        val preferences = mutableListOf<UserPreference>()

        // 提取变量名
        val originalNames = extractVariableNames(original)
        val modifiedNames = extractVariableNames(modified)

        // 分析命名风格变化
        val styleChanges = mutableMapOf<String, Int>()

        originalNames.forEach { (name, style) ->
            val newStyle = modifiedNames[name]
            if (newStyle != null && newStyle != style) {
                styleChanges[newStyle] = (styleChanges[newStyle] ?: 0) + 1
            }
        }

        // 如果某种风格变化超过阈值，记录为偏好
        styleChanges.forEach { (style, count) ->
            if (count >= 2) {
                preferences.add(UserPreference(
                    projectKey = project.name,
                    category = PreferenceCategory.NAMING_STYLE,
                    content = "偏好使用 ${getNamingStyleName(style)} 命名",
                    confidence = minOf(0.9, 0.5 + count * 0.1),
                    frequency = count,
                    source = PreferenceSource.IMPLICIT_CODE_MODIFICATION,
                    examples = findExamples(modified, style)
                ))
            }
        }

        return preferences
    }

    /**
     * 分析结构差异
     */
    private fun analyzeStructureDifference(original: String, modified: String): List<UserPreference> {
        val preferences = mutableListOf<UserPreference>()

        // 检测是否添加了特定结构
        val addedPatterns = mutableListOf<String>()

        // 检测 data class vs class
        if (!original.contains("data class") && modified.contains("data class")) {
            addedPatterns.add("偏好使用 data class")
        }

        // 检测表达式体
        val originalExpressionBodies = countExpressionBodies(original)
        val modifiedExpressionBodies = countExpressionBodies(modified)
        if (modifiedExpressionBodies > originalExpressionBodies + 1) {
            addedPatterns.add("偏好使用表达式体")
        }

        // 检测 when 表达式
        if (modified.count { it == 'w' && modified.substringAfter(it).startsWith("hen") } >
            original.count { it == 'w' && original.substringAfter(it).startsWith("hen") }) {
            addedPatterns.add("偏好使用 when 表达式")
        }

        addedPatterns.forEach { pattern ->
            preferences.add(UserPreference(
                projectKey = project.name,
                category = PreferenceCategory.CODE_STRUCTURE,
                content = pattern,
                confidence = 0.7,
                frequency = 1,
                source = PreferenceSource.IMPLICIT_CODE_MODIFICATION
            ))
        }

        return preferences
    }

    /**
     * 分析注释差异
     */
    private fun analyzeCommentDifference(original: String, modified: String): List<UserPreference> {
        val preferences = mutableListOf<UserPreference>()

        // 检测 KDoc vs JavaDoc
        val originalKdoc = original.count { it == '/' && modified.substringAfter(it).startsWith("*") }
        val modifiedKdoc = modified.count { it == '/' && modified.substringAfter(it).startsWith("*") }

        if (modifiedKdoc > originalKdoc) {
            preferences.add(UserPreference(
                projectKey = project.name,
                category = PreferenceCategory.COMMENT_STYLE,
                content = "偏好使用 KDoc 注释",
                confidence = 0.8,
                frequency = modifiedKdoc - originalKdoc,
                source = PreferenceSource.IMPLICIT_CODE_MODIFICATION
            ))
        }

        return preferences
    }

    /**
     * 分析错误处理差异
     */
    private fun analyzeErrorHandlingDifference(original: String, modified: String): List<UserPreference> {
        val preferences = mutableListOf<UserPreference>()

        // 检测 Result 模式
        if (!original.contains("Result<") && modified.contains("Result<")) {
            preferences.add(UserPreference(
                projectKey = project.name,
                category = PreferenceCategory.ERROR_HANDLING,
                content = "偏好使用 Result 模式处理错误",
                confidence = 0.8,
                frequency = 1,
                source = PreferenceSource.IMPLICIT_CODE_MODIFICATION
            ))
        }

        // 检测异常处理
        val originalTryCatch = countTryCatch(original)
        val modifiedTryCatch = countTryCatch(modified)
        if (modifiedTryCatch > originalTryCatch) {
            preferences.add(UserPreference(
                projectKey = project.name,
                category = PreferenceCategory.ERROR_HANDLING,
                content = "偏好显式异常处理",
                confidence = 0.7,
                frequency = modifiedTryCatch - originalTryCatch,
                source = PreferenceSource.IMPLICIT_CODE_MODIFICATION
            ))
        }

        return preferences
    }

    // 辅助方法
    private fun extractVariableNames(code: String): Map<String, String> {
        // 使用正则提取变量名和命名风格
        val result = mutableMapOf<String, String>()
        val variablePattern = Regex("""(val|var)\s+(\w+)""")

        variablePattern.findAll(code).forEach { match ->
            val name = match.groupValues[2]
            val style = detectNamingStyle(name)
            result[name] = style
        }

        return result
    }

    private fun detectNamingStyle(name: String): String {
        return when {
            name.matches(Regex("[a-z][a-z0-9]*")) -> "lowercase"
            name.matches(Regex("[a-z][a-zA-Z0-9]*")) -> "camelCase"
            name.matches(Regex("[a-z][a-z0-9_]*")) -> "snake_case"
            name.matches(Regex("[A-Z][a-zA-Z0-9]*")) -> "PascalCase"
            name.matches(Regex("[A-Z][A-Z0-9_]*")) -> "UPPER_SNAKE_CASE"
            else -> "unknown"
        }
    }

    private fun getNamingStyleName(style: String): String {
        return when (style) {
            "lowercase" -> "纯小写"
            "camelCase" -> "驼峰"
            "snake_case" -> "下划线"
            "PascalCase" -> "帕斯卡"
            "UPPER_SNAKE_CASE" -> "全大写下划线"
            else -> style
        }
    }

    data class ModificationContext(
        val fileName: String,
        val modificationType: ModificationType,
        val timestamp: Instant
    )

    enum class ModificationType {
        FILE_EDIT,       // 文件编辑
        ACCEPT_SUGGESTION,  // 接受建议
        REJECT_SUGGESTION   // 拒绝建议
    }
}
```

### 4.2 显式反馈收集器

```kotlin
/**
 * 显式反馈收集器
 *
 * 收集用户主动提供的反馈
 */
class ExplicitFeedbackCollector(
    private val preferenceStore: PreferenceStore
) {
    /**
     * 记录用户评分
     */
    fun recordRating(
        sessionId: String,
        messageId: String,
        rating: Rating,
        comment: String? = null
    ) {
        val preference = when (rating) {
            Rating.GOOD -> null  // 好评不产生偏好
            Rating.BAD -> UserPreference(
                projectKey = getCurrentProjectKey(),
                category = PreferenceCategory.OUTPUT_DETAIL,
                content = "上次输出不满意: ${comment ?: "未提供原因"}",
                confidence = 0.5,
                frequency = 1,
                source = PreferenceSource.EXPLICIT_RATING
            )
        }

        if (preference != null) {
            preferenceStore.store(preference)
        }
    }

    /**
     * 记录用户命令反馈
     *
     * 示例: "/learn 记住我喜欢使用驼峰命名"
     */
    fun recordCommandFeedback(
        command: String,
        content: String
    ): UserPreference? {
        // 解析命令格式: /learn <内容>
        val pattern = Regex("""/learn\s+(.+)""")
        val match = pattern.matchEntire(command) ?: return null

        val learningContent = match.groupValues[1]

        // 尝试解析偏好类别
        val (category, extractedContent) = parseLearningContent(learningContent)

        return UserPreference(
            projectKey = getCurrentProjectKey(),
            category = category,
            content = extractedContent,
            confidence = 1.0,  // 显式反馈置信度最高
            frequency = 1,
            source = PreferenceSource.EXPLICIT_COMMAND
        )
    }

    /**
     * 解析学习内容
     */
    private fun parseLearningContent(content: String): Pair<PreferenceCategory, String> {
        // 简单的关键词匹配
        return when {
            content.contains("命名") || content.contains("name") ->
                PreferenceCategory.NAMING_STYLE to content

            content.contains("格式") || content.contains("format") ->
                PreferenceCategory.CODE_FORMATTING to content

            content.contains("注释") || content.contains("comment") ->
                PreferenceCategory.COMMENT_STYLE to content

            content.contains("架构") || content.contains("architecture") ->
                PreferenceCategory.ARCHITECTURE_PATTERN to content

            content.contains("错误") || content.contains("error") ->
                PreferenceCategory.ERROR_HANDLING to content

            else ->
                PreferenceCategory.BUSINESS_RULES to content
        }
    }

    enum class Rating {
        GOOD, BAD
    }
}
```

---

## 五、记忆刷新机制

### 5.1 记忆刷新服务

```kotlin
/**
 * 记忆刷新服务
 *
 * 在上下文压缩前自动触发，将学习到的偏好持久化
 *
 * 参考 OpenClaw 的设计
 */
class MemoryFlushService(
    private val project: Project,
    private val preferenceStore: PreferenceStore,
    private val memoryService: MemoryService,
    private val contextCompactor: ContextCompactor
) {
    companion object {
        private val logger = Logger.getInstance(MemoryFlushService::class.java)
        private const val SOFT_THRESHOLD_TOKENS = 4000
    }

    /**
     * 检查是否需要刷新记忆
     */
    fun needsFlush(session: Session): Boolean {
        // 检查是否已经刷新过
        if (session.metadata["memoryFlushed"] == true) {
            return false
        }

        // 检查 Token 是否接近压缩阈值
        val currentTokens = contextCompactor.estimateSessionTokens(session)
        val maxTokens = contextCompactor.getMaxContextTokens()
        val reserveTokens = contextCompactor.getReserveTokensFloor()

        return currentTokens > (maxTokens - reserveTokens - SOFT_THRESHOLD_TOKENS)
    }

    /**
     * 执行记忆刷新（在压缩前调用）
     */
    suspend fun flushMemory(session: Session): FlushResult {
        logger.info("开始记忆刷新: sessionId=${session.id}")

        // 1. 提取本会话的偏好
        val preferences = extractPreferencesFromSession(session)

        // 2. 合并到长期存储
        val merged = preferenceStore.mergePreferences(preferences)

        // 3. 更新 MEMORY.md
        updateMemoryFile(preferences)

        // 4. 生成刷新报告
        val report = generateFlushReport(preferences, merged)

        // 5. 标记已刷新（避免重复）
        session.metadata["memoryFlushed"] = true

        logger.info("记忆刷新完成: extracted=${preferences.size}, merged=${merged.size}")

        return FlushResult(
            preferencesExtracted = preferences.size,
            preferencesMerged = merged.size,
            report = report
        )
    }

    /**
     * 从会话中提取偏好
     */
    private suspend fun extractPreferencesFromSession(
        session: Session
    ): List<UserPreference> {
        val preferences = mutableListOf<UserPreference>()

        // 遍历所有消息，分析用户修正
        for (message in session.messages) {
            // 查找工具调用后的用户修改
            if (message.role == Message.Role.USER) {
                val prevAssistantMessage = findPrevAssistantMessage(session, message)
                if (prevAssistantMessage != null) {
                    val toolCalls = extractToolCalls(prevAssistantMessage)

                    // 对于 apply_change 工具，检查用户是否有后续修改
                    toolCalls.filter { it.toolName == "apply_change" }.forEach { toolCall ->
                        val pref = analyzeApplyChangeFeedback(toolCall, message)
                        if (pref != null) {
                            preferences.add(pref)
                        }
                    }
                }
            }
        }

        return preferences
    }

    /**
     * 更新 MEMORY.md 文件
     */
    private fun updateMemoryFile(preferences: List<UserPreference>) {
        if (preferences.isEmpty()) return

        val memory = memoryService.loadMemory()

        // 合并偏好
        preferences.forEach { newPref ->
            val existing = memory.preferences.find {
                it.category == newPref.category && it.content == newPref.content
            }

            if (existing != null) {
                // 更新频率和置信度
                memory.preferences.remove(existing)
                memory.preferences.add(existing.copy(
                    frequency = existing.frequency + 1,
                    confidence = minOf(1.0, existing.confidence + 0.1),
                    updatedAt = Instant.now()
                ))
            } else {
                memory.preferences.add(newPref)
            }
        }

        memoryService.saveMemory(memory)
    }

    /**
     * 生成刷新报告
     */
    private fun generateFlushReport(
        preferences: List<UserPreference>,
        merged: List<UserPreference>
    ): String {
        return buildString {
            append("# 记忆刷新报告\n\n")
            append("时间: ${Instant.now()}\n\n")

            if (preferences.isEmpty()) {
                append("本会话未检测到新的偏好。\n")
            } else {
                append("## 新学习的偏好 (${preferences.size})\n\n")

                preferences.groupBy { it.category }.forEach { (category, prefs) ->
                    append("### ${category.displayName}\n\n")
                    prefs.forEach { pref ->
                        append("- ${pref.content}")
                        append(" (置信度: ${(pref.confidence * 100).toInt()}%)\n")
                    }
                    append("\n")
                }
            }

            if (merged.isNotEmpty()) {
                append("## 合并后的偏好 (${merged.size})\n\n")
                merged.take(5).forEach { pref ->
                    append("- [${pref.category.displayName}] ${pref.content}")
                    append(" (频率: ${pref.frequency})\n")
                }
            }
        }
    }

    data class FlushResult(
        val preferencesExtracted: Int,
        val preferencesMerged: Int,
        val report: String
    )
}
```

### 5.2 在 SmanLoop 中集成

```kotlin
// 在 SmanLoop.processWithLLM() 方法中添加

if (contextCompactor.needsCompaction(session)) {

    // 【新增】先执行记忆刷新
    if (memoryFlushService.needsFlush(session)) {
        logger.info("触发记忆刷新: sessionId=${session.id}")

        try {
            val flushResult = memoryFlushService.flushMemory(session)
            logger.info("记忆刷新完成: extracted=${flushResult.preferencesExtracted}, merged=${flushResult.preferencesMerged}")

            // 可选：向用户展示学习内容
            if (flushResult.preferencesExtracted > 0) {
                partPusher.accept(TextPart(
                    "\n\n---\n*本会话学习了 ${flushResult.preferencesExtracted} 个偏好，已记录到项目记忆。*\n"
                ))
            }
        } catch (e: Exception) {
            logger.error("记忆刷新失败", e)
        }
    }

    // 然后执行压缩
    logger.info("触发上下文压缩: sessionId=${session.id}")
    contextCompactor.prune(session)
    // ... 原有压缩逻辑
}
```

---

## 六、知识应用设计

### 6.1 偏好注入器

```kotlin
/**
 * 偏好注入器
 *
 * 在 LLM 调用前注入用户偏好
 */
class PreferenceInjector(
    private val preferenceStore: PreferenceStore,
    private val memoryService: MemoryService
) {
    /**
     * 构建偏好提示词
     */
    fun buildPreferencePrompt(projectKey: String): String {
        // 1. 从向量存储获取相关偏好
        val preferences = preferenceStore.getTopPreferences(projectKey, limit = 15)

        if (preferences.isEmpty()) {
            return ""
        }

        // 2. 从 MEMORY.md 获取项目特定知识
        val memory = memoryService.loadMemory()

        // 3. 构建提示词
        return buildString {
            append("## 用户偏好（已学习）\n\n")
            append("以下是从此项目中学到的用户偏好，请在生成代码时遵循：\n\n")

            // 按类别分组
            preferences.groupBy { it.category }
                .toSortedMap(compareBy { it.ordinal })
                .forEach { (category, prefs) ->
                    append("### ${category.displayName}\n\n")

                    prefs.sortedByDescending { it.confidence * it.frequency }
                        .take(5)
                        .forEach { pref ->
                            append("- ${pref.content}")

                            // 添加示例（如果有）
                            if (pref.examples.isNotEmpty()) {
                                append("（示例：`${pref.examples.first()}`）")
                            }

                            append("\n")
                        }
                    append("\n")
                }

            // 添加业务规则（如果有）
            if (memory.businessRules.isNotEmpty()) {
                append("### 业务规则\n\n")
                memory.businessRules.forEach { rule ->
                    append("- $rule\n")
                }
                append("\n")
            }

            // 添加领域术语（如果有）
            if (memory.domainTerms.isNotEmpty()) {
                append("### 领域术语\n\n")
                append("| 术语 | 含义 | 代码映射 |\n")
                append("|------|------|----------|\n")
                memory.domainTerms.forEach { term ->
                    append("| ${term.name} | ${term.meaning} | ${term.codeMapping} |\n")
                }
                append("\n")
            }
        }
    }

    /**
     * 根据当前任务获取相关偏好
     */
    suspend fun getRelevantPreferences(
        projectKey: String,
        currentTask: String,
        limit: Int = 10
    ): List<UserPreference> {
        // 使用向量搜索找到语义相关的偏好
        return preferenceStore.getRelevantPreferences(projectKey, currentTask, limit)
    }
}
```

### 6.2 在 DynamicPromptInjector 中集成

```kotlin
// 扩展 DynamicPromptInjector

class DynamicPromptInjector(
    private val promptLoader: PromptLoaderService,
    private val projectPath: Path,
    private val preferenceInjector: PreferenceInjector  // 新增
) {
    fun detectAndInject(sessionKey: String, projectKey: String?): InjectResult {
        val result = InjectResult()

        // ... 原有逻辑 ...

        // 【新增】注入用户偏好
        if (!projectKey.isNullOrEmpty()) {
            val preferencePrompt = preferenceInjector.buildPreferencePrompt(projectKey)
            if (preferencePrompt.isNotEmpty()) {
                result.userPreferences = preferencePrompt
                result.needUserPreferences = true
                logger.info("会话 {} 已加载用户偏好", sessionKey)
            }
        }

        return result
    }

    data class InjectResult(
        var userPreferences: String = "",
        var needUserPreferences: Boolean = false,
        // ... 其他字段 ...
    )
}
```

---

## 七、数据持久化

### 7.1 用户偏好数据结构

```kotlin
/**
 * 用户偏好
 */
data class UserPreference(
    val id: String = UUID.randomUUID().toString(),
    val projectKey: String,
    val category: PreferenceCategory,
    val content: String,
    val examples: List<String> = emptyList(),
    val confidence: Double,      // 置信度 (0.0-1.0)
    val frequency: Int,          // 出现频率
    val lastApplied: Instant? = null,
    val source: PreferenceSource,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
)

enum class PreferenceSource {
    IMPLICIT_CODE_MODIFICATION,   // 代码修改分析
    IMPLICIT_REJECTION,           // 方案拒绝分析
    EXPLICIT_RATING,              // 显式评分
    EXPLICIT_COMMAND,             // 命令输入
    IMPORTED_FROM_PROJECT         // 从项目配置导入
}
```

### 7.2 偏好存储服务

```kotlin
/**
 * 偏好存储服务
 *
 * 结合 Markdown 文件和向量存储
 */
class PreferenceStore(
    private val projectPath: Path,
    private val vectorStore: VectorStoreService
) {
    private val preferencesFile = projectPath.resolve(".sman/preferences.json")

    /**
     * 获取项目偏好（按置信度排序）
     */
    fun getTopPreferences(projectKey: String, limit: Int = 10): List<UserPreference> {
        val allPreferences = loadPreferences()
        return allPreferences
            .filter { it.projectKey == projectKey }
            .sortedByDescending { it.confidence * it.frequency }
            .take(limit)
    }

    /**
     * 语义搜索相关偏好
     */
    suspend fun getRelevantPreferences(
        projectKey: String,
        query: String,
        limit: Int = 10
    ): List<UserPreference> {
        // 使用向量搜索
        val results = vectorStore.semanticSearch(
            projectKey = projectKey,
            query = query,
            topK = limit * 2,  // 多获取一些，后面再过滤
            filter = mapOf("type" to "preference")
        )

        return results.mapNotNull { result ->
            parsePreferenceFromSearchResult(result)
        }.take(limit)
    }

    /**
     * 存储新偏好
     */
    suspend fun store(preference: UserPreference) {
        // 1. 存储到 JSON 文件
        val preferences = loadPreferences().toMutableList()

        // 检查是否已存在相同偏好
        val existingIndex = preferences.indexOfFirst {
            it.projectKey == preference.projectKey &&
            it.category == preference.category &&
            it.content == preference.content
        }

        if (existingIndex >= 0) {
            // 更新现有偏好
            preferences[existingIndex] = preference.copy(
                frequency = preferences[existingIndex].frequency + 1,
                confidence = minOf(1.0, preferences[existingIndex].confidence + 0.1),
                updatedAt = Instant.now()
            )
        } else {
            preferences.add(preference)
        }

        savePreferences(preferences)

        // 2. 向量化并存储到向量库
        vectorStore.store(
            id = preference.id,
            content = preference.content,
            embedding = vectorizationService.embed(preference.content),
            metadata = mapOf(
                "type" to "preference",
                "category" to preference.category.name,
                "projectKey" to preference.projectKey,
                "confidence" to preference.confidence.toString(),
                "frequency" to preference.frequency.toString()
            )
        )
    }

    /**
     * 合并偏好列表
     */
    fun mergePreferences(newPreferences: List<UserPreference>): List<UserPreference> {
        val existing = loadPreferences().toMutableList()

        newPreferences.forEach { newPref ->
            val matchIndex = existing.indexOfFirst {
                it.projectKey == newPref.projectKey &&
                it.category == newPref.category &&
                it.content == newPref.content
            }

            if (matchIndex >= 0) {
                // 合并
                existing[matchIndex] = existing[matchIndex].copy(
                    frequency = existing[matchIndex].frequency + newPref.frequency,
                    confidence = minOf(1.0, existing[matchIndex].confidence + 0.05),
                    updatedAt = Instant.now()
                )
            } else {
                existing.add(newPref)
            }
        }

        savePreferences(existing)
        return existing
    }

    private fun loadPreferences(): List<UserPreference> {
        if (!preferencesFile.exists()) {
            return emptyList()
        }

        return try {
            Json.decodeFromString<List<UserPreference>>(preferencesFile.readText())
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun savePreferences(preferences: List<UserPreference>) {
        preferencesFile.parent.createDirectories()
        preferencesFile.writeText(Json.encodeToString(preferences))
    }
}
```

---

## 八、UI 集成

### 8.1 偏好确认对话框

当检测到新的偏好时，弹出确认对话框：

```kotlin
/**
 * 偏好确认对话框
 */
class PreferenceConfirmationDialog(
    private val project: Project,
    private val preferences: List<UserPreference>
) : DialogWrapper(project) {

    init {
        title = "学习确认"
        setOKButtonText("记住这些偏好")
        setCancelButtonText("这是特殊情况")
    }

    override fun createCenterPanel(): JComponent {
        return panel {
            row {
                label("我注意到您对生成的代码做了以下调整：")
            }

            preferences.forEach { pref ->
                row {
                    checkBox(
                        "${pref.category.displayName}: ${pref.content}",
                        true
                    )
                }
            }

            row {
                button("编辑偏好") {
                    // 打开编辑对话框
                }
            }
        }
    }
}
```

### 8.2 偏好管理面板

```kotlin
/**
 * 偏好管理面板
 */
class PreferenceManagerPanel(
    private val project: Project,
    private val preferenceStore: PreferenceStore
) : JPanel() {

    init {
        layout = BorderLayout()

        // 顶部：工具栏
        add(createToolBar(), BorderLayout.NORTH)

        // 中间：偏好列表
        add(createPreferenceList(), BorderLayout.CENTER)

        // 底部：操作按钮
        add(createButtonPanel(), BorderLayout.SOUTH)

        // 加载数据
        loadPreferences()
    }

    private fun createToolBar(): JComponent {
        return panel {
            row {
                textField("搜索偏好...", 30)
                    .component.addKeyListener(object : KeyAdapter() {
                        override fun keyReleased(e: KeyEvent) {
                            filterPreferences((e.source as JTextField).text)
                        }
                    })

                comboBox(listOf("全部", "代码风格", "架构偏好", "技术选型"))
                    .component.addItemListener { e ->
                        if (e.stateChange == ItemEvent.SELECTED) {
                            filterByCategory(e.item as String)
                        }
                    }
            }
        }
    }

    private fun createPreferenceList(): JComponent {
        return JBList<UserPreference>().apply {
            cellRenderer = PreferenceListCellRenderer()

            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount == 2) {
                        val selected = selectedValue
                        // 编辑偏好
                        editPreference(selected)
                    }
                }
            })
        }
    }

    private fun createButtonPanel(): JComponent {
        return panel {
            row {
                button("删除选中") {
                    deleteSelectedPreferences()
                }

                button("导出") {
                    exportPreferences()
                }

                button("重置为默认") {
                    resetToDefault()
                }
            }
        }
    }
}
```

---

## 九、实施路线

| 阶段 | 内容 | 时间 |
|------|------|------|
| Phase 1 | 隐式反馈收集（代码修改分析） | 1-2 周 |
| Phase 2 | 偏好存储（JSON + 向量化） | 1 周 |
| Phase 3 | 记忆刷新机制 | 1 周 |
| Phase 4 | 偏好注入（Prompt 集成） | 1 周 |
| Phase 5 | 显式反馈收集（UI） | 1-2 周 |
| Phase 6 | 偏好管理面板 | 1 周 |

---

## 十、总结

用户习惯学习系统的核心价值：

1. **减少重复调整**：自动应用学到的偏好
2. **个性化体验**：Agent 越用越懂用户
3. **知识沉淀**：项目特定的偏好可共享
4. **透明可控**：用户可见可编辑偏好

关键设计决策：

- **双向存储**：JSON 文件（用户可见）+ 向量索引（语义搜索）
- **隐式优先**：自动分析，减少用户负担
- **置信度机制**：避免错误偏好被过度应用
- **记忆刷新**：压缩前自动持久化，不丢失学习成果
