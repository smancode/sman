package com.smancode.sman.evolution.loop

import com.smancode.sman.analysis.coordination.CodeVectorizationCoordinator
import com.smancode.sman.analysis.model.ProjectMapManager
import com.smancode.sman.analysis.util.ProjectHashCalculator
import com.smancode.sman.config.SmanConfig
import com.smancode.sman.evolution.generator.QuestionGenerator
import com.smancode.sman.evolution.guard.DoomLoopCheckResult
import com.smancode.sman.evolution.guard.DoomLoopGuard
import com.smancode.sman.evolution.memory.LearningRecordRepository
import com.smancode.sman.evolution.model.LearningRecord
import com.smancode.sman.evolution.model.ToolCallStep
import com.smancode.sman.evolution.persistence.EvolutionStateRepository
import com.smancode.sman.evolution.persistence.LoopStateEntity
import com.smancode.sman.evolution.recorder.ExplorationContext
import com.smancode.sman.evolution.recorder.ExplorationResult
import com.smancode.sman.evolution.recorder.GeneratedQuestion
import com.smancode.sman.evolution.recorder.LearningRecorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * 进化阶段枚举
 *
 * 标识当前自进化循环所处的执行阶段
 */
enum class EvolutionPhase {
    /** 空闲状态 */
    IDLE,
    /** 检查退避状态 */
    CHECKING_BACKOFF,
    /** 生成问题 */
    GENERATING_QUESTION,
    /** 探索中 */
    EXPLORING,
    /** 总结学习中 */
    SUMMARIZING,
    /** 持久化中 */
    PERSISTING
}

/**
 * 进化停止原因枚举
 *
 * 标识自进化循环停止的具体原因
 */
enum class EvolutionStopReason {
    /** 用户手动停止 */
    USER_STOPPED,
    /** API Key 未配置 */
    API_KEY_NOT_CONFIGURED,
    /** 项目已学完 */
    PROJECT_FULLY_LEARNED,
    /** 连续重复问题 */
    CONSECUTIVE_DUPLICATE_QUESTIONS,
    /** 配额用完 */
    QUOTA_EXHAUSTED,
    /** 项目未变化 */
    PROJECT_UNCHANGED,
    /** 未知错误 */
    UNKNOWN_ERROR
}

/**
 * 进化启动检查结果
 *
 * @param canRun 是否可以运行
 * @param reason 不能运行的原因
 */
data class EvolutionStartCheck(
    val canRun: Boolean,
    val reason: EvolutionStopReason? = null,
    val message: String? = null
) {
    companion object {
        fun pass(): EvolutionStartCheck = EvolutionStartCheck(canRun = true)

        fun fail(reason: EvolutionStopReason, message: String): EvolutionStartCheck =
            EvolutionStartCheck(canRun = false, reason = reason, message = message)
    }
}

/**
 * 项目变化检查结果
 *
 * @param changed 项目是否发生变化
 * @param currentMd5 当前 MD5 值
 * @param previousMd5 之前记录的 MD5 值
 */
data class ProjectChangeResult(
    val changed: Boolean,
    val currentMd5: String,
    val previousMd5: String?
)

/**
 * 自进化循环 - 后台主循环
 *
 * 核心职责：
 * 1. 持续在后台运行，不断生成好问题并探索学习
 * 2. 与 DoomLoopGuard 协作，避免死循环
 * 3. 通过 LearningRecorder 持久化学习成果
 *
 * 设计要点：
 * - 使用协程实现非阻塞后台运行
 * - 支持优雅启停
 * - 完善的异常处理和退避机制
 *
 * @property projectKey 项目标识
 * @property projectPath 项目路径
 * @property questionGenerator 问题生成器
 * @property learningRecorder 学习记录器
 * @property doomLoopGuard 死循环防护器
 * @property repository 学习记录仓储
 * @property config 进化配置
 */
class SelfEvolutionLoop(
    private val projectKey: String,
    private val projectPath: Path,
    private val questionGenerator: QuestionGenerator,
    private val learningRecorder: LearningRecorder,
    private val doomLoopGuard: DoomLoopGuard,
    private val repository: LearningRecordRepository,
    private val stateRepository: EvolutionStateRepository,
    private val config: EvolutionConfig
) {
    private val logger = LoggerFactory.getLogger(SelfEvolutionLoop::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    // 协程作用域
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentJob: Job? = null

    // 控制标志
    @Volatile
    private var enabled = false

    // 当前阶段
    @Volatile
    private var currentPhase = EvolutionPhase.IDLE

    // 统计信息
    @Volatile
    private var totalIterations = 0L

    @Volatile
    private var successfulIterations = 0L

    // 智能控制状态
    @Volatile
    private var consecutiveDuplicateCount = 0

    @Volatile
    private var lastGeneratedQuestionHash: String? = null

    @Volatile
    private var lastProjectMd5: String? = null

    @Volatile
    private var stopReason: EvolutionStopReason? = null

    // ING 状态（当前正在进行的操作）
    @Volatile
    private var currentQuestion: GeneratedQuestion? = null

    @Volatile
    private var currentQuestionHash: String? = null

    @Volatile
    private var explorationProgress: Int = 0

    /**
     * 启动后台循环
     *
     * 如果循环已在运行，则不做任何操作。
     * 启动前会检查运行条件（API Key 等）。
     * 支持从中断状态恢复。
     */
    fun start() {
        if (enabled) {
            logger.warn("SelfEvolutionLoop 已在运行中，忽略启动请求")
            return
        }

        // 启动协程来检查状态并恢复
        currentJob = scope.launch {
            // 1. 尝试从数据库恢复状态
            val savedState = stateRepository.getLoopState(projectKey)
            if (savedState != null && savedState.enabled) {
                logger.info("发现保存的状态，尝试恢复: phase={}, totalIterations={}, successfulIterations={}",
                    savedState.currentPhase, savedState.totalIterations, savedState.successfulIterations)

                // 恢复统计信息
                totalIterations = savedState.totalIterations
                successfulIterations = savedState.successfulIterations
                consecutiveDuplicateCount = savedState.consecutiveDuplicateCount
                lastGeneratedQuestionHash = savedState.lastGeneratedQuestionHash
                lastProjectMd5 = savedState.lastProjectMd5

                // 恢复 ING 状态
                if (savedState.currentPhase != EvolutionPhase.IDLE &&
                    savedState.currentPhase != EvolutionPhase.CHECKING_BACKOFF) {
                    logger.info("从中断处恢复: phase={}, question={}",
                        savedState.currentPhase, savedState.currentQuestion)

                    // 检查启动条件
                    val startCheck = shouldRun()
                    if (!startCheck.canRun) {
                        logger.warn("SelfEvolutionLoop 无法启动: reason={}, message={}",
                            startCheck.reason, startCheck.message)
                        stopReason = startCheck.reason
                        return@launch
                    }

                    enabled = true
                    resumeFromIngState(savedState)
                    return@launch
                }
            }

            // 2. 正常启动
            runEvolutionLoop()
        }

        logger.info("SelfEvolutionLoop 启动: projectKey={}", projectKey)
    }

    /**
     * 正常启动循环
     */
    private suspend fun startLoop() {
        // 检查启动条件
        val startCheck = shouldRun()
        if (!startCheck.canRun) {
            logger.warn("SelfEvolutionLoop 无法启动: reason={}, message={}",
                startCheck.reason, startCheck.message)
            stopReason = startCheck.reason
            return
        }

        enabled = true
        stopReason = null

        logger.info("SelfEvolutionLoop 启动: projectKey={}", projectKey)
    }

    /**
     * 从 ING 状态恢复
     */
    private suspend fun resumeFromIngState(savedState: LoopStateEntity) {
        logger.info("从中断处恢复: phase={}, question={}",
            savedState.currentPhase, savedState.currentQuestion)

        when (savedState.currentPhase) {
            EvolutionPhase.EXPLORING -> {
                // 恢复探索
                if (savedState.currentQuestion != null) {
                    val question = recreateQuestion(savedState)
                    val maxExplorationSteps = if (config.deepAnalysisEnabled) 15 else config.maxExplorationSteps
                    continueExploration(question, savedState.explorationProgress, maxExplorationSteps)
                } else {
                    logger.warn("恢复探索失败：问题为空，开始正常循环")
                    runEvolutionLoop()
                }
            }
            EvolutionPhase.SUMMARIZING -> {
                // 恢复总结
                if (savedState.currentQuestion != null) {
                    val question = recreateQuestion(savedState)
                    val explorationResult = recreateExplorationResult(savedState)
                    if (explorationResult != null) {
                        summarizeAndPersist(question, explorationResult)
                    } else {
                        logger.warn("恢复总结失败：探索结果为空，开始正常循环")
                        runEvolutionLoop()
                    }
                } else {
                    logger.warn("恢复总结失败：问题为空，开始正常循环")
                    runEvolutionLoop()
                }
            }
            EvolutionPhase.PERSISTING -> {
                // 恢复持久化 - 需要重新构建学习记录
                if (savedState.currentQuestion != null) {
                    val question = recreateQuestion(savedState)
                    val explorationResult = recreateExplorationResult(savedState)
                    if (explorationResult != null) {
                        val record = learningRecorder.summarize(question, explorationResult)
                        persistLearningRecord(record)
                        clearIngState()
                        logger.info("恢复持久化完成")
                    }
                }
                // 继续正常循环
                runEvolutionLoop()
            }
            else -> {
                // 其他阶段，正常启动
                runEvolutionLoop()
            }
        }
    }

    /**
     * 重建问题对象
     */
    private fun recreateQuestion(savedState: LoopStateEntity): GeneratedQuestion {
        return GeneratedQuestion(
            question = savedState.currentQuestion!!,
            type = com.smancode.sman.evolution.model.QuestionType.CODE_STRUCTURE,
            priority = 1,
            reason = "从中断状态恢复",
            suggestedTools = emptyList(),
            expectedOutcome = "从中断状态恢复"
        )
    }

    /**
     * 重建探索结果
     */
    private fun recreateExplorationResult(savedState: LoopStateEntity): ExplorationResult? {
        // 从 partialSteps 重建
        val stepsJson = savedState.partialSteps
        if (stepsJson.isNullOrBlank()) {
            // 如果没有部分步骤，创建一个空的
            val question = recreateQuestion(savedState)
            return ExplorationResult(
                question = question,
                steps = emptyList(),
                success = true,
                error = null,
                finalContext = ExplorationContext(
                    question = question,
                    previousSteps = emptyList(),
                    accumulatedKnowledge = "从中断状态恢复"
                )
            )
        }

        return try {
            val steps: List<ToolCallStep> = json.decodeFromString(stepsJson)
            val question = recreateQuestion(savedState)
            ExplorationResult(
                question = question,
                steps = steps,
                success = true,
                error = null,
                finalContext = ExplorationContext(
                    question = question,
                    previousSteps = steps,
                    accumulatedKnowledge = "从中断状态恢复"
                )
            )
        } catch (e: Exception) {
            logger.error("重建探索结果失败", e)
            null
        }
    }

    /**
     * 继续探索（从中断点）
     */
    private suspend fun continueExploration(
        question: GeneratedQuestion,
        startStep: Int,
        maxSteps: Int
    ) {
        logger.info("继续探索: question={}, startStep={}, maxSteps={}", question.question, startStep, maxSteps)

        currentPhase = EvolutionPhase.EXPLORING
        currentQuestion = question

        // 简化版：直接完成探索
        val explorationResult = explore(question, maxSteps)

        if (!explorationResult.success) {
            logger.error("探索失败: {}", explorationResult.error)
            doomLoopGuard.recordFailure(projectKey)
            persistIngState()
            runEvolutionLoop()
            return
        }

        // 继续总结
        summarizeAndPersist(question, explorationResult)
    }

    /**
     * 总结并持久化
     */
    private suspend fun summarizeAndPersist(
        question: GeneratedQuestion,
        explorationResult: ExplorationResult
    ) {
        currentPhase = EvolutionPhase.SUMMARIZING

        // 总结学习成果
        val learningRecord = summarize(question, explorationResult)

        // 持久化
        currentPhase = EvolutionPhase.PERSISTING
        persistLearningRecord(learningRecord)

        // 深度分析模式：触发代码向量化
        if (config.deepAnalysisEnabled) {
            triggerCodeVectorization()
        }

        // 记录成功
        doomLoopGuard.recordSuccess(projectKey)
        successfulIterations++

        // 清除 ING 状态
        clearIngState()

        // 继续循环
        currentPhase = EvolutionPhase.IDLE
        logger.info("学习完成: question={}", learningRecord.question)

        runEvolutionLoop()
    }

    /**
     * 停止后台循环
     *
     * 设置停止标志并取消当前协程。
     *
     * @param reason 停止原因，默认为 USER_STOPPED
     */
    fun stop(reason: EvolutionStopReason = EvolutionStopReason.USER_STOPPED) {
        enabled = false
        stopReason = reason
        currentJob?.cancel()
        currentJob = null

        logger.info("SelfEvolutionLoop 停止: projectKey={}, reason={}", projectKey, reason)
    }

    /**
     * 获取当前状态
     *
     * @return 当前进化状态信息
     */
    fun getStatus(): EvolutionStatus {
        return EvolutionStatus(
            projectKey = projectKey,
            enabled = enabled,
            currentPhase = currentPhase,
            totalIterations = totalIterations,
            successfulIterations = successfulIterations,
            backoffState = doomLoopGuard.getBackoffState(projectKey),
            quotaInfo = doomLoopGuard.getRemainingQuota(projectKey),
            stopReason = stopReason
        )
    }

    // ==================== 智能控制方法 ====================

    /**
     * 检查是否应该运行
     *
     * 启动条件检查：
     * 1. API Key 必须配置
     *
     * @return 启动检查结果
     */
    private fun shouldRun(): EvolutionStartCheck {
        // 检查 API Key 是否配置
        return try {
            val apiKey = SmanConfig.llmApiKey
            if (apiKey.isBlank()) {
                EvolutionStartCheck.fail(
                    reason = EvolutionStopReason.API_KEY_NOT_CONFIGURED,
                    message = "LLM API Key 未配置，请在设置中配置 API Key"
                )
            } else {
                EvolutionStartCheck.pass()
            }
        } catch (e: IllegalStateException) {
            EvolutionStartCheck.fail(
                reason = EvolutionStopReason.API_KEY_NOT_CONFIGURED,
                message = "LLM API Key 未配置: ${e.message}"
            )
        }
    }

    /**
     * 检查项目是否发生变化
     *
     * 通过 MD5 比对检测项目内容是否变化
     *
     * @return 项目变化检查结果
     */
    private fun checkProjectChanged(): ProjectChangeResult {
        val currentMd5 = try {
            ProjectHashCalculator.calculateDirectoryHash(projectPath)
        } catch (e: Exception) {
            logger.warn("计算项目 MD5 失败", e)
            return ProjectChangeResult(changed = true, currentMd5 = "", previousMd5 = lastProjectMd5)
        }

        // 首次运行，记录 MD5
        if (lastProjectMd5 == null) {
            lastProjectMd5 = currentMd5
            logger.info("首次记录项目 MD5: {}", currentMd5)
            return ProjectChangeResult(changed = true, currentMd5 = currentMd5, previousMd5 = null)
        }

        val changed = currentMd5 != lastProjectMd5
        if (changed) {
            logger.info("项目内容已变化: previous={}, current={}", lastProjectMd5, currentMd5)
            lastProjectMd5 = currentMd5
        }

        return ProjectChangeResult(
            changed = changed,
            currentMd5 = currentMd5,
            previousMd5 = lastProjectMd5
        )
    }

    /**
     * 检查项目是否已学完
     *
     * 基于学习记录数量判断
     *
     * @return true 如果项目已学完
     */
    private suspend fun isProjectFullyLearned(): Boolean {
        val recordCount = repository.countByProjectKey(projectKey)
        val fullyLearned = recordCount >= config.projectFullyLearnedThreshold

        if (fullyLearned) {
            logger.info("项目已学完: recordCount={}, threshold={}",
                recordCount, config.projectFullyLearnedThreshold)
        }

        return fullyLearned
    }

    /**
     * 检查是否存在连续重复问题
     *
     * @param questionHash 当前问题的哈希值
     * @return true 如果存在连续重复
     */
    private fun hasConsecutiveDuplicateQuestions(questionHash: String): Boolean {
        if (lastGeneratedQuestionHash == null) {
            lastGeneratedQuestionHash = questionHash
            consecutiveDuplicateCount = 0
            return false
        }

        if (questionHash == lastGeneratedQuestionHash) {
            consecutiveDuplicateCount++
            logger.debug("检测到重复问题: consecutiveCount={}", consecutiveDuplicateCount)

            if (consecutiveDuplicateCount >= config.maxConsecutiveDuplicateQuestions) {
                logger.warn("连续重复问题达到阈值: count={}, threshold={}",
                    consecutiveDuplicateCount, config.maxConsecutiveDuplicateQuestions)
                return true
            }
        } else {
            // 问题不同，重置计数
            consecutiveDuplicateCount = 0
            lastGeneratedQuestionHash = questionHash
        }

        return false
    }

    /**
     * 计算问题的哈希值
     *
     * @param question 问题文本
     * @return 哈希值
     */
    private fun calculateQuestionHash(question: String): String {
        return question.trim().lowercase().hashCode().toString(16)
    }

    /**
     * 获取停止原因
     *
     * @return 停止原因，如果正在运行则返回 null
     */
    fun getStopReason(): EvolutionStopReason? = stopReason

    /**
     * 主循环
     */
    private suspend fun runEvolutionLoop() {
        while (enabled) {
            try {
                // 1. 检查项目是否已学完
                if (isProjectFullyLearned()) {
                    logger.info("项目已学完，停止自进化循环")
                    stop(EvolutionStopReason.PROJECT_FULLY_LEARNED)
                    break
                }

                // 2. 检查项目是否变化（增量触发）
                val projectChange = checkProjectChanged()
                if (!projectChange.changed) {
                    logger.debug("项目未变化，跳过本次迭代")
                    delay(config.projectUnchangedSkipIntervalMs)
                    continue
                }

                // 3. 执行单次迭代
                runSingleIteration()

            } catch (e: CancellationException) {
                logger.info("SelfEvolutionLoop 被取消")
                break
            } catch (e: Exception) {
                logger.error("SelfEvolutionLoop 迭代失败", e)
                handleIterationError(e)
            }

            // 休眠后继续
            delay(config.intervalMs)
        }
    }

    /**
     * 单次迭代
     */
    private suspend fun runSingleIteration() {
        totalIterations++
        currentPhase = EvolutionPhase.CHECKING_BACKOFF

        // 保存状态
        saveLoopState()

        // 0. 检查退避和配额
        val checkResult = doomLoopGuard.shouldSkipQuestion(projectKey)
        if (checkResult.shouldSkip) {
            handleDoomLoopSkip(checkResult)
            return
        }

        // 深度分析模式：根据配置调整问题数量和探索深度
        val questionCount = if (config.deepAnalysisEnabled) {
            logger.debug("深度分析模式：问题数量 = 5")
            5
        } else {
            config.questionsPerIteration
        }

        val maxExplorationSteps = if (config.deepAnalysisEnabled) {
            logger.debug("深度分析模式：探索步数 = 15")
            15
        } else {
            config.maxExplorationSteps
        }

        // 1. 生成好问题
        currentPhase = EvolutionPhase.GENERATING_QUESTION
        val questions = generateQuestions(questionCount)

        if (questions.isEmpty()) {
            logger.info("没有生成新问题，休眠")
            return
        }

        // 选择最高优先级问题
        val selectedQuestion = questions.maxByOrNull { it.priority }!!

        // 2. 检查是否连续重复问题
        val questionHash = calculateQuestionHash(selectedQuestion.question)
        if (hasConsecutiveDuplicateQuestions(questionHash)) {
            logger.warn("连续重复问题达到阈值，停止自进化循环")
            stop(EvolutionStopReason.CONSECUTIVE_DUPLICATE_QUESTIONS)
            return
        }

        logger.info("选择问题: priority={}, question={}", selectedQuestion.priority, selectedQuestion.question)

        // 设置当前问题（ING 状态）
        currentQuestion = selectedQuestion
        currentQuestionHash = questionHash
        explorationProgress = 0

        // 3. 探索（简化版：暂时返回模拟结果）
        currentPhase = EvolutionPhase.EXPLORING
        persistIngState() // 保存 ING 状态

        val explorationResult = explore(selectedQuestion, maxExplorationSteps)

        if (!explorationResult.success) {
            logger.error("探索失败: {}", explorationResult.error)
            doomLoopGuard.recordFailure(projectKey)
            persistIngState() // 保存失败状态
            return
        }

        // 3. 总结学习成果
        currentPhase = EvolutionPhase.SUMMARIZING
        persistIngState() // 保存 ING 状态
        val learningRecord = summarize(selectedQuestion, explorationResult)

        // 4. 持久化学习记录
        currentPhase = EvolutionPhase.PERSISTING
        persistLearningRecord(learningRecord)

        // 5. 深度分析模式：触发代码向量化
        if (config.deepAnalysisEnabled) {
            triggerCodeVectorization()
        }

        // 6. 记录成功
        doomLoopGuard.recordSuccess(projectKey)
        successfulIterations++

        // 7. 清除 ING 状态
        clearIngState()

        currentPhase = EvolutionPhase.IDLE
        saveLoopState() // 保存最终状态
        logger.info("学习完成: question={}", learningRecord.question)
    }

    /**
     * 生成好问题
     *
     * @param count 生成问题数量
     * @return 生成的问题列表（按优先级降序排列）
     */
    private suspend fun generateQuestions(count: Int = config.questionsPerIteration): List<GeneratedQuestion> {
        return try {
            // 获取最近的问题用于去重
            val recentRecords = repository.findByProjectKey(projectKey)
            val recentQuestions = recentRecords.take(20).map { it.question }

            val request = QuestionGenerator.QuestionGenerationRequest(
                projectKey = projectKey,
                techStack = "Kotlin",
                domains = emptyList(),
                recentQuestions = recentQuestions,
                knowledgeGaps = emptyList(),
                count = count
            )

            // 将 generator.GeneratedQuestion 转换为 recorder.GeneratedQuestion
            questionGenerator.generate(request).map { genQ ->
                GeneratedQuestion(
                    question = genQ.question,
                    type = genQ.type,
                    priority = genQ.priority,
                    reason = genQ.reason,
                    suggestedTools = genQ.suggestedTools,
                    expectedOutcome = genQ.expectedOutcome
                )
            }
        } catch (e: Exception) {
            logger.error("问题生成失败", e)
            emptyList()
        }
    }

    /**
     * 探索问题（简化版）
     *
     * 当前实现返回模拟结果，后续将由 ToolExplorer 实现。
     *
     * @param question 要探索的问题
     * @param maxSteps 最大探索步数
     * @return 探索结果
     */
    private fun explore(question: GeneratedQuestion, maxSteps: Int = config.maxExplorationSteps): ExplorationResult {
        logger.info("探索问题（简化版）: {}, maxSteps={}", question.question, maxSteps)

        // 简化版：创建一个模拟的探索结果
        // 后续将由 ToolExplorer 实现真正的工具探索
        val mockSteps = listOf(
            ToolCallStep(
                toolName = "expert_consult",
                parameters = mapOf("query" to question.question),
                resultSummary = "模拟探索结果：${question.expectedOutcome}",
                timestamp = System.currentTimeMillis()
            )
        )

        return ExplorationResult(
            question = question,
            steps = mockSteps,
            success = true,
            error = null,
            finalContext = ExplorationContext(
                question = question,
                previousSteps = mockSteps,
                accumulatedKnowledge = "模拟累积知识：${question.expectedOutcome}"
            )
        )
    }

    /**
     * 总结学习成果
     *
     * @param question 探索的问题
     * @param explorationResult 探索结果
     * @return 学习记录
     */
    private fun summarize(
        question: GeneratedQuestion,
        explorationResult: ExplorationResult
    ): LearningRecord {
        return learningRecorder.summarize(question, explorationResult)
    }

    /**
     * 触发代码向量化
     *
     * 深度分析模式下，每轮迭代会触发代码向量化，
     * 确保项目代码的语义信息被持续更新。
     */
    private suspend fun triggerCodeVectorization() {
        try {
            logger.info("深度分析模式：触发代码向量化")

            val bgeConfig = SmanConfig.bgeM3Config
            if (bgeConfig == null) {
                logger.warn("BGE-M3 配置未找到，跳过代码向量化")
                return
            }

            if (bgeConfig.endpoint.isBlank()) {
                logger.warn("BGE 端点为空，跳过代码向量化")
                return
            }

            val coordinator = CodeVectorizationCoordinator(
                projectKey = projectKey,
                projectPath = projectPath,
                llmService = SmanConfig.createLlmService(),
                bgeEndpoint = bgeConfig.endpoint
            )

            val result = coordinator.vectorizeProject(forceUpdate = false)
            logger.info("代码向量化完成: 处理={}, 跳过={}, 向量数={}",
                result.processedFiles, result.skippedFiles, result.totalVectors)

            coordinator.close()
        } catch (e: Exception) {
            logger.error("代码向量化失败: ${e.message}", e)
        }
    }

    /**
     * 持久化学习记录
     *
     * @param record 学习记录
     */
    private suspend fun persistLearningRecord(record: LearningRecord) {
        learningRecorder.save(record)
    }

    /**
     * 处理 DoomLoop 跳过
     *
     * @param checkResult 检查结果
     */
    private suspend fun handleDoomLoopSkip(checkResult: DoomLoopCheckResult) {
        when {
            checkResult.remainingBackoff != null && checkResult.remainingBackoff > 0 -> {
                logger.info("在退避期中，剩余 {} ms", checkResult.remainingBackoff)
                delay(checkResult.remainingBackoff)
            }
            checkResult.reason == "已达每日配额" -> {
                logger.warn("已达每日配额，停止自进化循环")
                stop(EvolutionStopReason.QUOTA_EXHAUSTED)
            }
            else -> {
                logger.warn("DoomLoop 跳过: {}", checkResult.reason)
            }
        }
    }

    /**
     * 处理迭代错误
     *
     * @param e 异常
     */
    private fun handleIterationError(e: Exception) {
        // 记录失败以触发退避
        doomLoopGuard.recordFailure(projectKey)

        val backoffState = doomLoopGuard.getBackoffState(projectKey)
        logger.warn(
            "迭代错误已记录，连续错误次数: {}, 退避状态: {}",
            backoffState.consecutiveErrors,
            backoffState.backoffUntil
        )

        // 如果连续错误次数超过阈值，考虑延长等待
        if (backoffState.consecutiveErrors >= config.maxConsecutiveErrors) {
            logger.error("连续错误次数达到阈值 {}，建议检查系统状态", config.maxConsecutiveErrors)
        }
    }

    // ========== 状态持久化方法 ==========

    /**
     * 保存 ING 状态到数据库
     *
     * 在进入新阶段时调用，记录当前正在进行的操作
     */
    private suspend fun persistIngState() {
        try {
            val partialStepsJson = if (currentQuestion != null && explorationProgress > 0) {
                // 保存已完成的步骤
                json.encodeToString<List<ToolCallStep>>(emptyList()) // 简化版：暂无步骤
            } else null

            val state = LoopStateEntity(
                projectKey = projectKey,
                enabled = enabled,
                currentPhase = currentPhase,
                totalIterations = totalIterations,
                successfulIterations = successfulIterations,
                consecutiveDuplicateCount = consecutiveDuplicateCount,
                currentQuestion = currentQuestion?.question,
                currentQuestionHash = currentQuestionHash,
                explorationProgress = explorationProgress,
                partialSteps = partialStepsJson,
                startedAt = System.currentTimeMillis(),
                lastGeneratedQuestionHash = lastGeneratedQuestionHash,
                lastProjectMd5 = lastProjectMd5,
                stopReason = stopReason,
                lastUpdatedAt = System.currentTimeMillis()
            )
            stateRepository.saveLoopState(state)
            logger.debug("保存 ING 状态: phase={}, question={}", currentPhase, currentQuestion?.question)
        } catch (e: Exception) {
            logger.error("保存 ING 状态失败", e)
        }
    }

    /**
     * 清除 ING 状态
     *
     * 在操作完成后调用，清除当前正在进行的操作状态
     */
    private suspend fun clearIngState() {
        try {
            stateRepository.clearIngState(projectKey)
            currentQuestion = null
            currentQuestionHash = null
            explorationProgress = 0
            logger.debug("清除 ING 状态完成")
        } catch (e: Exception) {
            logger.error("清除 ING 状态失败", e)
        }
    }

    /**
     * 保存循环状态
     *
     * 在每次迭代前后保存状态
     */
    private suspend fun saveLoopState() {
        try {
            val state = LoopStateEntity(
                projectKey = projectKey,
                enabled = enabled,
                currentPhase = currentPhase,
                totalIterations = totalIterations,
                successfulIterations = successfulIterations,
                consecutiveDuplicateCount = consecutiveDuplicateCount,
                currentQuestion = currentQuestion?.question,
                currentQuestionHash = currentQuestionHash,
                explorationProgress = explorationProgress,
                partialSteps = null,
                startedAt = null,
                lastGeneratedQuestionHash = lastGeneratedQuestionHash,
                lastProjectMd5 = lastProjectMd5,
                stopReason = stopReason,
                lastUpdatedAt = System.currentTimeMillis()
            )
            stateRepository.saveLoopState(state)
        } catch (e: Exception) {
            logger.error("保存循环状态失败", e)
        }
    }
}

/**
 * 进化状态
 *
 * @property projectKey 项目标识
 * @property enabled 是否启用
 * @property currentPhase 当前阶段
 * @property totalIterations 总迭代次数
 * @property successfulIterations 成功迭代次数
 * @property backoffState 退避状态
 * @property quotaInfo 配额信息
 * @property stopReason 停止原因，如果正在运行则为 null
 */
data class EvolutionStatus(
    val projectKey: String,
    val enabled: Boolean,
    val currentPhase: EvolutionPhase,
    val totalIterations: Long,
    val successfulIterations: Long,
    val backoffState: com.smancode.sman.evolution.guard.BackoffState,
    val quotaInfo: com.smancode.sman.evolution.guard.QuotaInfo,
    val stopReason: EvolutionStopReason? = null
)
