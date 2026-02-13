package com.smancode.sman.evolution.context

import com.smancode.sman.analysis.config.VectorDatabaseConfig
import com.smancode.sman.analysis.database.TieredVectorStore
import com.smancode.sman.analysis.vectorization.BgeM3Client
import com.smancode.sman.analysis.vectorization.RerankerClient
import com.smancode.sman.evolution.memory.LearningRecordRepository
import com.smancode.sman.evolution.recall.*
import com.smancode.sman.model.session.ProjectInfo
import com.smancode.sman.model.session.Session
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

/**
 * 增强上下文
 *
 * 注入到 SmanLoop 的 System Prompt
 *
 * @property projectInfo 项目基础信息
 * @property learningRecords 相关学习记录（带分数）
 * @property domainKnowledge 领域知识列表
 * @property codeFragments 相关代码片段（带分数）
 * @property userRequest 用户请求
 */
data class EnhancedContext(
    val projectInfo: ProjectInfo?,
    val learningRecords: List<LearningRecordWithScore> = emptyList(),
    val domainKnowledge: List<DomainKnowledge> = emptyList(),
    val codeFragments: List<CodeFragmentWithScore> = emptyList(),
    val userRequest: String
) {
    /**
     * Token 预算控制器参数
     */
    companion object {
        // 最大 Token 预算（用于上下文注入）
        private const val MAX_TOKENS = 4000

        // 学习记录最大显示数量
        private const val MAX_LEARNING_RECORDS = 5

        // 代码片段最大显示数量
        private const val MAX_CODE_FRAGMENTS = 3

        // 答案内容最大长度
        private const val MAX_ANSWER_LENGTH = 500

        // 代码片段最大长度
        private const val MAX_CODE_LENGTH = 500
    }

    /**
     * 生成 System Prompt 补充内容
     *
     * 输出格式：
     * - 使用中文标题（视觉锚定）
     * - 保留技术术语为英文
     *
     * @return System Prompt 补充内容
     */
    fun toSystemPromptSection(): String {
        val sb = StringBuilder()

        // 项目背景
        if (projectInfo != null) {
            sb.appendLine("## 项目背景")
            sb.appendLine("- 项目路径: ${projectInfo.projectPath ?: "未知"}")
            if (!projectInfo.description.isNullOrBlank()) {
                sb.appendLine("- 项目描述: ${projectInfo.description}")
            }
            sb.appendLine()
        }

        // 领域知识
        if (domainKnowledge.isNotEmpty()) {
            sb.appendLine("## 相关领域知识 (Domain Knowledge)")
            domainKnowledge.take(3).forEach { dk ->
                sb.appendLine("### ${dk.domain}")
                sb.appendLine(dk.summary)
                if (dk.keyFiles.isNotEmpty()) {
                    sb.appendLine("关键文件: ${dk.keyFiles.take(5).joinToString(", ")}")
                }
                sb.appendLine()
            }
        }

        // 学习记录（后台已学到的知识）
        if (learningRecords.isNotEmpty()) {
            sb.appendLine("## 相关学习记录 (Learning Records - 后台已学到的知识)")
            learningRecords.take(MAX_LEARNING_RECORDS).forEachIndexed { index, record ->
                val scorePercent = (record.score * 100).toInt()
                sb.appendLine("### 知识 ${index + 1} (相关度: ${scorePercent}%)")
                sb.appendLine("问题: ${record.record.question}")
                sb.appendLine("答案: ${record.record.answer.take(MAX_ANSWER_LENGTH)}")
                if (record.record.sourceFiles.isNotEmpty()) {
                    sb.appendLine("涉及文件: ${record.record.sourceFiles.take(5).joinToString(", ")}")
                }
                sb.appendLine()
            }
        }

        // 代码片段
        if (codeFragments.isNotEmpty()) {
            sb.appendLine("## 相关代码片段 (Code Fragments)")
            codeFragments.take(MAX_CODE_FRAGMENTS).forEach { fragment ->
                val filePath = fragment.fragment.metadata["filePath"] ?: "未知文件"
                sb.appendLine("### 文件: $filePath")
                sb.appendLine("```")
                sb.appendLine(fragment.fragment.content.take(MAX_CODE_LENGTH))
                if (fragment.fragment.content.length > MAX_CODE_LENGTH) {
                    sb.appendLine("... (已截断)")
                }
                sb.appendLine("```")
                sb.appendLine()
            }
        }

        return sb.toString()
    }

    /**
     * 估算 Token 数量（简化实现：按字符数估算）
     *
     * @return 估算的 Token 数量
     */
    fun estimateTokens(): Int {
        return toSystemPromptSection().length / 2  // 简化估算：2 字符约等于 1 Token
    }

    /**
     * 裁剪到 Token 预算内
     *
     * @param maxTokens 最大 Token 数量
     * @return 裁剪后的上下文
     */
    fun trimToBudget(maxTokens: Int = MAX_TOKENS): EnhancedContext {
        var currentTokens = estimateTokens()
        if (currentTokens <= maxTokens) {
            return this
        }

        // 逐步裁剪
        var trimmedRecords = learningRecords
        var trimmedFragments = codeFragments

        // 先裁剪代码片段
        while (currentTokens > maxTokens && trimmedFragments.isNotEmpty()) {
            trimmedFragments = trimmedFragments.dropLast(1)
            currentTokens = this.copy(codeFragments = trimmedFragments).estimateTokens()
        }

        // 再裁剪学习记录
        while (currentTokens > maxTokens && trimmedRecords.isNotEmpty()) {
            trimmedRecords = trimmedRecords.dropLast(1)
            currentTokens = this.copy(learningRecords = trimmedRecords).estimateTokens()
        }

        return this.copy(
            learningRecords = trimmedRecords,
            codeFragments = trimmedFragments
        )
    }
}

/**
 * 增强上下文注入器
 *
 * 职责：
 * - 为用户请求构建增强上下文
 * - 调用 MultiPathRecaller 进行多路召回
 * - 将增强上下文注入到 SmanLoop
 *
 * 简化实现：
 * - 使用已有的服务组件
 * - 支持同步和异步调用
 */
class EnhancedContextInjector(
    private val multiPathRecaller: MultiPathRecaller
) {
    private val logger = LoggerFactory.getLogger(EnhancedContextInjector::class.java)

    companion object {
        // 默认召回数量
        private const val DEFAULT_TOP_K = 10

        // 最小查询长度（小于此长度不进行召回）
        private const val MIN_QUERY_LENGTH = 3

        /**
         * 创建默认的 EnhancedContextInjector
         *
         * @param bgeM3Client BGE-M3 客户端
         * @param vectorStore 向量存储
         * @param recordRepository 学习记录仓库
         * @param rerankerClient Reranker 客户端（可选）
         * @param config 向量数据库配置
         * @return EnhancedContextInjector 实例
         */
        fun create(
            bgeM3Client: BgeM3Client,
            vectorStore: TieredVectorStore,
            recordRepository: LearningRecordRepository,
            rerankerClient: RerankerClient?,
            config: VectorDatabaseConfig
        ): EnhancedContextInjector {
            val multiPathRecaller = MultiPathRecaller(
                bgeM3Client = bgeM3Client,
                vectorStore = vectorStore,
                recordRepository = recordRepository,
                rerankerClient = rerankerClient,
                config = config
            )

            return EnhancedContextInjector(multiPathRecaller)
        }
    }

    /**
     * 为用户请求构建增强上下文（异步版本）
     *
     * @param session 会话
     * @param userRequest 用户请求
     * @param projectKey 项目标识
     * @param topK 召回数量
     * @return 增强上下文
     */
    suspend fun injectAsync(
        session: Session,
        userRequest: String,
        projectKey: String,
        topK: Int = DEFAULT_TOP_K
    ): EnhancedContext {
        require(userRequest.isNotBlank()) { "userRequest 不能为空" }
        require(projectKey.isNotBlank()) { "projectKey 不能为空" }
        require(topK > 0) { "topK 必须大于 0" }

        logger.info("开始构建增强上下文: projectKey={}, userRequest={}",
            projectKey, userRequest.take(50))

        // 短查询不进行召回
        if (userRequest.length < MIN_QUERY_LENGTH) {
            logger.debug("查询过短，跳过召回: length={}", userRequest.length)
            return EnhancedContext(
                projectInfo = session.projectInfo,
                userRequest = userRequest
            )
        }

        return try {
            // 1. 构建用户意图（简化实现：直接使用用户查询）
            val intent = buildUserIntent(userRequest)

            // 2. 多路召回
            val recallResult = multiPathRecaller.recall(
                projectKey = projectKey,
                intent = intent,
                topK = topK
            )

            logger.info("召回完成: learningRecords={}, domainKnowledge={}, codeFragments={}",
                recallResult.learningRecords.size,
                recallResult.domainKnowledge.size,
                recallResult.codeFragments.size)

            // 3. 构建增强上下文
            val context = EnhancedContext(
                projectInfo = session.projectInfo,
                learningRecords = recallResult.learningRecords,
                domainKnowledge = recallResult.domainKnowledge,
                codeFragments = recallResult.codeFragments,
                userRequest = userRequest
            )

            // 4. 裁剪到 Token 预算内
            context.trimToBudget()

        } catch (e: Exception) {
            logger.error("构建增强上下文失败: {}", e.message, e)
            EnhancedContext(
                projectInfo = session.projectInfo,
                userRequest = userRequest
            )
        }
    }

    /**
     * 为用户请求构建增强上下文（同步版本）
     *
     * @param session 会话
     * @param userRequest 用户请求
     * @param projectKey 项目标识
     * @param topK 召回数量
     * @return 增强上下文
     */
    fun inject(
        session: Session,
        userRequest: String,
        projectKey: String,
        topK: Int = DEFAULT_TOP_K
    ): EnhancedContext {
        return runBlocking {
            injectAsync(session, userRequest, projectKey, topK)
        }
    }

    /**
     * 构建增强的 System Prompt
     *
     * 将原始 System Prompt 与增强上下文组合
     *
     * @param originalPrompt 原始 System Prompt
     * @param session 会话
     * @param userRequest 用户请求
     * @param projectKey 项目标识
     * @return 增强后的 System Prompt
     */
    fun buildEnhancedSystemPrompt(
        originalPrompt: String,
        session: Session,
        userRequest: String,
        projectKey: String
    ): String {
        // 短查询不进行增强
        if (userRequest.length < MIN_QUERY_LENGTH) {
            return originalPrompt
        }

        return try {
            val enhancedContext = inject(session, userRequest, projectKey)
            val contextSection = enhancedContext.toSystemPromptSection()

            if (contextSection.isNotBlank()) {
                """
                $originalPrompt

                ---

                $contextSection
                """.trimIndent()
            } else {
                originalPrompt
            }
        } catch (e: Exception) {
            logger.error("构建增强 System Prompt 失败: {}", e.message)
            originalPrompt
        }
    }

    /**
     * 构建用户意图（简化实现）
     *
     * 简化版本：直接使用用户查询作为意图
     * 后续可以集成 LLM 进行意图分析
     *
     * @param userRequest 用户请求
     * @return 用户意图
     */
    private fun buildUserIntent(userRequest: String): UserIntent {
        // 简化实现：从查询中提取关键词
        val keywords = extractKeywords(userRequest)

        return UserIntent(
            originalQuery = userRequest,
            domains = emptyList(),  // 暂不进行领域识别
            keywords = keywords
        )
    }

    /**
     * 从查询中提取关键词（简化实现）
     *
     * @param query 查询文本
     * @return 关键词列表
     */
    private fun extractKeywords(query: String): List<String> {
        // 简化实现：分词并过滤停用词
        val stopWords = setOf(
            "的", "是", "在", "和", "了", "有", "我", "你", "他", "她",
            "这", "那", "个", "们", "吗", "呢", "吧", "啊", "哦",
            "the", "a", "an", "is", "are", "was", "were", "be", "been",
            "have", "has", "had", "do", "does", "did", "will", "would",
            "could", "should", "may", "might", "must", "can", "to", "of",
            "in", "for", "on", "with", "at", "by", "from", "as", "into"
        )

        return query
            .split(Regex("[\\s,，。！？、；：\"'（）\\[\\]{}]+"))
            .filter { it.isNotBlank() && it.length >= 2 && it.lowercase() !in stopWords }
            .take(5)  // 最多取 5 个关键词
    }
}
