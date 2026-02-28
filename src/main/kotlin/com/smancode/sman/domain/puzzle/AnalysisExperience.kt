package com.smancode.sman.domain.puzzle

import org.slf4j.LoggerFactory
import java.io.File
import java.time.Instant

/**
 * 分析经验
 *
 * 记录在分析"屎山代码"过程中积累的经验，
 * 这些经验可以复用，提高后续分析的准确性
 */
data class AnalysisExperience(
    /** 经验 ID */
    val id: String,
    /** 经验类型 */
    val type: ExperienceType,
    /** 经验来源 */
    val source: ExperienceSource,
    /** 适用场景（描述） */
    val scenario: String,
    /** 具体经验内容 */
    val pattern: String,
    /** 解决方案 */
    val solution: String,
    /** 成功次数 */
    val successCount: Int = 0,
    /** 失败次数 */
    val failureCount: Int = 0,
    /** 置信度 0.0-1.0 */
    val confidence: Double = 0.5,
    /** 创建时间 */
    val createdAt: Instant = Instant.now(),
    /** 最后更新时间 */
    val updatedAt: Instant = Instant.now(),
    /** 示例 */
    val examples: List<String> = emptyList()
)

/**
 * 经验类型
 */
enum class ExperienceType {
    /** 配置关联：代码调用看起来没下文，实际在配置文件里 */
    CONFIG_LINK,
    /** 框架模式：特定框架的特有模式 */
    FRAMEWORK_PATTERN,
    /** 命名约定：通过命名推断意图 */
    NAMING_CONVENTION,
    /** 隐式调用：动态代理、反射、AOP 等 */
    IMPLICIT_CALL,
    /** 业务规则：从代码中推断的业务规则 */
    BUSINESS_RULE
}

/**
 * 经验来源
 */
enum class ExperienceSource {
    /** 用户提示 */
    USER_HINT,
    /** 系统自动发现 */
    SELF_DISCOVERY,
    /** 测试验证 */
    TEST_VERIFIED
}

/**
 * 经验库
 *
 * 管理分析经验的存储、检索和应用
 */
class ExperienceStore(
    private val projectPath: String = System.getProperty("user.dir")
) {
    private val logger = LoggerFactory.getLogger(ExperienceStore::class.java)

    private val experiences = mutableListOf<AnalysisExperience>()

    companion object {
        // 内置经验：从用户提示中获得
        val BUILTIN_EXPERIENCES = listOf(
            AnalysisExperience(
                id = "exp-001",
                type = ExperienceType.CONFIG_LINK,
                source = ExperienceSource.USER_HINT,
                scenario = "当代码调用看起来没有完成（没有显式的后续处理），很可能走了配置文件",
                pattern = "Service.*execute\\(.*\\)|.*Executor.*|transactionService\\.\\w+",
                solution = "搜索项目中的 XML/YAML/JSON 配置文件，查找 transactionCode、transCode 等关键词",
                successCount = 10,
                confidence = 0.9,
                examples = listOf(
                    "transactionService.execute(\"2001\", context) → transaction.xml",
                    "ruleEngine.fire(\"loan_approve\") → rules/loan_approve.xml"
                )
            ),
            AnalysisExperience(
                id = "exp-002",
                type = ExperienceType.FRAMEWORK_PATTERN,
                source = ExperienceSource.USER_HINT,
                scenario = "MyBatis Mapper 接口只有方法签名，实际 SQL 在 XML 里",
                pattern = "@Mapper|interface.*Mapper|extends BaseMapper",
                solution = "在 resources/mapper 目录查找对应的 XML 文件",
                successCount = 50,
                confidence = 0.95,
                examples = listOf(
                    "UserMapper.java → UserMapper.xml",
                    "AcctTransactionMapper.java → mapper/AcctTransactionMapper.xml"
                )
            ),
            AnalysisExperience(
                id = "exp-003",
                type = ExperienceType.IMPLICIT_CALL,
                source = ExperienceSource.USER_HINT,
                scenario = "Spring @Transactional 注解的方法，有隐式的事务边界",
                pattern = "@Transactional",
                solution = "识别事务边界，考虑 AOP 拦截的提交/回滚逻辑",
                successCount = 30,
                confidence = 0.85
            ),
            AnalysisExperience(
                id = "exp-004",
                type = ExperienceType.NAMING_CONVENTION,
                source = ExperienceSource.SELF_DISCOVERY,
                scenario = "方法名包含 Action/Procedure/Handler，很可能是被配置文件引用的类",
                pattern = "\\w+Action|\\w+Procedure|\\w+Handler",
                solution = "在 XML 配置中搜索类名，查找引用关系",
                successCount = 20,
                confidence = 0.8
            ),
            AnalysisExperience(
                id = "exp-005",
                type = ExperienceType.CONFIG_LINK,
                source = ExperienceSource.SELF_DISCOVERY,
                scenario = "注释中出现的数字（如 2001、1001）很可能是 TransactionCode",
                pattern = "//.*?(\\d{4})|transCode.*?(\\d{4})",
                solution = "在 XML 中搜索 TransactionCode=\"2001\" 进行关联",
                successCount = 5,
                confidence = 0.75
            )
        )
    }

    init {
        // 加载内置经验
        experiences.addAll(BUILTIN_EXPERIENCES)
        // 尝试加载持久化的经验
        loadPersisted()
    }

    /**
     * 根据场景查找适用的经验
     */
    fun findApplicable(codePattern: String, context: String): List<AnalysisExperience> {
        return experiences
            .filter { exp ->
                // 检查模式是否匹配
                try {
                    Regex(exp.pattern, RegexOption.IGNORE_CASE).containsMatchIn(codePattern) ||
                    context.contains(exp.scenario, ignoreCase = true)
                } catch (e: Exception) {
                    false
                }
            }
            .sortedByDescending { it.confidence * (1 + it.successCount * 0.1) }
    }

    /**
     * 根据经验 ID 查找
     */
    fun findById(id: String): AnalysisExperience? {
        return experiences.find { it.id == id }
    }

    /**
     * 添加新经验
     */
    fun addExperience(experience: AnalysisExperience) {
        // 检查是否已存在类似经验
        val existing = experiences.find {
            it.type == experience.type && it.scenario == experience.scenario
        }

        if (existing != null) {
            // 更新现有经验
            val updated = existing.copy(
                successCount = existing.successCount + 1,
                confidence = (existing.confidence + 0.1).coerceAtMost(1.0),
                updatedAt = Instant.now()
            )
            experiences.remove(existing)
            experiences.add(updated)
            logger.info("更新经验: {} -> 置信度 {}", existing.id, updated.confidence)
        } else {
            experiences.add(experience)
            logger.info("添加新经验: {} - {}", experience.type, experience.scenario)
        }
    }

    /**
     * 记录经验应用成功
     */
    fun recordSuccess(experienceId: String) {
        val exp = experiences.find { it.id == experienceId }
        if (exp != null) {
            val updated = exp.copy(
                successCount = exp.successCount + 1,
                confidence = (exp.confidence + 0.05).coerceAtMost(1.0),
                updatedAt = Instant.now()
            )
            experiences.remove(exp)
            experiences.add(updated)
        }
    }

    /**
     * 记录经验应用失败
     */
    fun recordFailure(experienceId: String) {
        val exp = experiences.find { it.id == experienceId }
        if (exp != null) {
            val updated = exp.copy(
                failureCount = exp.failureCount + 1,
                confidence = (exp.confidence - 0.1).coerceAtLeast(0.1),
                updatedAt = Instant.now()
            )
            experiences.remove(exp)
            experiences.add(updated)
            logger.warn("经验应用失败: {} -> 置信度降低至 {}", exp.id, updated.confidence)
        }
    }

    /**
     * 获取所有经验
     */
    fun getAll(): List<AnalysisExperience> = experiences.toList()

    /**
     * 格式化为 Prompt 可用的文本
     */
    fun formatForPrompt(): String {
        if (experiences.isEmpty()) {
            return "（暂无分析经验）"
        }

        return experiences
            .sortedByDescending { it.confidence }
            .take(10)
            .joinToString("\n\n") { exp ->
                """
### 经验: ${exp.id} [${exp.type}] (置信度: ${(exp.confidence * 100).toInt()}%)
- 场景: ${exp.scenario}
- 模式: `${exp.pattern}`
- 解决方案: ${exp.solution}
- 成功次数: ${exp.successCount}
${if (exp.examples.isNotEmpty()) "- 示例:\n" + exp.examples.joinToString("\n") { "  - $it" } else ""}
                """.trimIndent()
            }
    }

    /**
     * 持久化到文件
     */
    fun persist() {
        val storePath = File(projectPath, ".sman/experiences.json")
        storePath.parentFile.mkdirs()

        // 简单 JSON 序列化
        val content = buildString {
            appendLine("{")
            appendLine("  \"experiences\": [")
            experiences.forEachIndexed { index, exp ->
                append("    {")
                append("\"id\":\"${exp.id}\",")
                append("\"type\":\"${exp.type}\",")
                append("\"source\":\"${exp.source}\",")
                append("\"scenario\":\"${exp.scenario.replace("\"", "\\\"")}\",")
                append("\"pattern\":\"${exp.pattern.replace("\"", "\\\"")}\",")
                append("\"solution\":\"${exp.solution.replace("\"", "\\\"")}\",")
                append("\"successCount\":${exp.successCount},")
                append("\"failureCount\":${exp.failureCount},")
                append("\"confidence\":${exp.confidence}")
                append("}")
                if (index < experiences.size - 1) appendLine(",")
                else appendLine()
            }
            appendLine("  ]")
            append("}")
        }

        storePath.writeText(content)
        logger.info("经验已持久化到 {}", storePath.path)
    }

    private fun loadPersisted() {
        val storePath = File(projectPath, ".sman/experiences.json")
        if (!storePath.exists()) return

        try {
            // 简单 JSON 反序列化（仅提取关键字段）
            val content = storePath.readText()
            val experienceRegex = Regex(
                """\{"id":"([^"]+)","type":"([^"]+)".*?"scenario":"([^"]+)".*?"pattern":"([^"]+)".*?"solution":"([^"]+)".*?"successCount":(\d+).*?"confidence":([\d.]+)\}"""
            )

            experienceRegex.findAll(content).forEach { match ->
                val (id, type, scenario, pattern, solution, successCount, confidence) = match.destructured

                // 只添加不在内置列表中的经验
                if (experiences.none { it.id == id }) {
                    experiences.add(AnalysisExperience(
                        id = id,
                        type = ExperienceType.valueOf(type),
                        source = ExperienceSource.SELF_DISCOVERY,
                        scenario = scenario.replace("\\\"", "\""),
                        pattern = pattern.replace("\\\"", "\""),
                        solution = solution.replace("\\\"", "\""),
                        successCount = successCount.toInt(),
                        confidence = confidence.toDouble()
                    ))
                }
            }

            logger.info("从 {} 加载了 {} 条经验", storePath.path, experiences.size - BUILTIN_EXPERIENCES.size)
        } catch (e: Exception) {
            logger.warn("加载经验失败: {}", e.message)
        }
    }
}
