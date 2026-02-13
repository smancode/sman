package com.smancode.sman.evolution.loop

import com.smancode.sman.evolution.generator.QuestionGenerator
import com.smancode.sman.evolution.guard.DoomLoopCheckResult
import com.smancode.sman.evolution.guard.DoomLoopGuard
import com.smancode.sman.evolution.memory.LearningRecordRepository
import com.smancode.sman.evolution.model.LearningRecord
import com.smancode.sman.evolution.model.ToolCallStep
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
    private val config: EvolutionConfig
) {
    private val logger = LoggerFactory.getLogger(SelfEvolutionLoop::class.java)

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

    /**
     * 启动后台循环
     *
     * 如果循环已在运行，则不做任何操作。
     */
    fun start() {
        if (enabled) {
            logger.warn("SelfEvolutionLoop 已在运行中，忽略启动请求")
            return
        }

        enabled = true
        currentJob = scope.launch {
            runEvolutionLoop()
        }

        logger.info("SelfEvolutionLoop 启动: projectKey={}", projectKey)
    }

    /**
     * 停止后台循环
     *
     * 设置停止标志并取消当前协程。
     */
    fun stop() {
        enabled = false
        currentJob?.cancel()
        currentJob = null

        logger.info("SelfEvolutionLoop 停止: projectKey={}", projectKey)
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
            quotaInfo = doomLoopGuard.getRemainingQuota(projectKey)
        )
    }

    /**
     * 主循环
     */
    private suspend fun runEvolutionLoop() {
        while (enabled) {
            try {
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

        // 0. 检查退避
        val checkResult = doomLoopGuard.shouldSkipQuestion(projectKey)
        if (checkResult.shouldSkip) {
            handleDoomLoopSkip(checkResult)
            return
        }

        // 1. 生成好问题
        currentPhase = EvolutionPhase.GENERATING_QUESTION
        val questions = generateQuestions()

        if (questions.isEmpty()) {
            logger.info("没有生成新问题，休眠")
            return
        }

        // 选择最高优先级问题
        val selectedQuestion = questions.maxByOrNull { it.priority }!!

        logger.info("选择问题: priority={}, question={}", selectedQuestion.priority, selectedQuestion.question)

        // 2. 探索（简化版：暂时返回模拟结果）
        currentPhase = EvolutionPhase.EXPLORING
        val explorationResult = explore(selectedQuestion)

        if (!explorationResult.success) {
            logger.error("探索失败: {}", explorationResult.error)
            doomLoopGuard.recordFailure(projectKey)
            return
        }

        // 3. 总结学习成果
        currentPhase = EvolutionPhase.SUMMARIZING
        val learningRecord = summarize(selectedQuestion, explorationResult)

        // 4. 持久化学习记录
        currentPhase = EvolutionPhase.PERSISTING
        persistLearningRecord(learningRecord)

        // 5. 记录成功
        doomLoopGuard.recordSuccess(projectKey)
        successfulIterations++

        currentPhase = EvolutionPhase.IDLE
        logger.info("学习完成: question={}", learningRecord.question)
    }

    /**
     * 生成好问题
     *
     * @return 生成的问题列表（按优先级降序排列）
     */
    private suspend fun generateQuestions(): List<GeneratedQuestion> {
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
                count = config.questionsPerIteration
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
     * @return 探索结果
     */
    private fun explore(question: GeneratedQuestion): ExplorationResult {
        logger.info("探索问题（简化版）: {}", question.question)

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
                val quotaInfo = doomLoopGuard.getRemainingQuota(projectKey)
                val waitTime = quotaInfo.resetsAt - System.currentTimeMillis()
                logger.info("已达每日配额，等待 {} ms 至明天重置", waitTime)
                // 最多等待到重置时间
                if (waitTime > 0 && waitTime < 24 * 60 * 60 * 1000) {
                    delay(waitTime)
                }
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
 */
data class EvolutionStatus(
    val projectKey: String,
    val enabled: Boolean,
    val currentPhase: EvolutionPhase,
    val totalIterations: Long,
    val successfulIterations: Long,
    val backoffState: com.smancode.sman.evolution.guard.BackoffState,
    val quotaInfo: com.smancode.sman.evolution.guard.QuotaInfo
)
