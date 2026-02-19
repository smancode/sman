package com.smancode.sman.architect

import com.smancode.sman.analysis.executor.AnalysisLoopExecutor
import com.smancode.sman.analysis.executor.AnalysisLoopResult
import com.smancode.sman.analysis.executor.AnalysisOutputValidator
import com.smancode.sman.analysis.model.AnalysisType
import com.smancode.sman.analysis.persistence.AnalysisStateRepository
import com.smancode.sman.architect.evaluator.CompletionEvaluator
import com.smancode.sman.architect.evaluator.ImpactAnalyzer
import com.smancode.sman.architect.model.*
import com.smancode.sman.architect.persistence.ArchitectStateEntity
import com.smancode.sman.architect.persistence.ArchitectStateRepository
import com.smancode.sman.architect.storage.MdFileService
import com.smancode.sman.config.SmanConfig
import com.smancode.sman.evolution.guard.DoomLoopGuard
import com.smancode.sman.ide.service.SmanService
import com.smancode.sman.model.part.Part
import com.smancode.sman.model.part.TextPart
import com.smancode.sman.model.part.ToolPart
import com.smancode.sman.smancode.llm.LlmService
import com.smancode.sman.tools.ToolExecutor
import com.smancode.sman.tools.ToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.time.Instant
import java.util.function.Consumer

/**
 * 架构师 Agent
 *
 * 独立的分析代理，通过调用 SmanLoop（ReAct 循环）实现项目分析
 *
 * 核心特性：
 * 1. 调用 SmanLoop（与用户从插件请求一样的方式）
 * 2. 小步快跑：每轮调用 → 收集回答 → 评估完成度
 * 3. 阶段性输出：完成时写入 MD 文件（带时间戳）+ 记录 TODO
 * 4. 增量更新：检测文件变更，判断是否需要更新 MD
 *
 * @property projectKey 项目标识
 * @property projectPath 项目路径
 * @property smanService Sman 服务（用于调用 SmanLoop）
 */
class ArchitectAgent(
    private val projectKey: String,
    private val projectPath: Path,
    private val smanService: SmanService
) {
    private val logger = LoggerFactory.getLogger(ArchitectAgent::class.java)

    // 协程作用域
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentJob: Job? = null

    // 控制标志
    @Volatile
    private var enabled = false

    // 当前阶段
    @Volatile
    private var currentPhase = ArchitectPhase.IDLE

    // 统计信息
    @Volatile
    private var totalIterations = 0L

    @Volatile
    private var successfulIterations = 0L

    // 停止原因
    @Volatile
    private var stopReason: ArchitectStopReason? = null

    // 当前目标
    @Volatile
    private var currentGoal: ArchitectGoal? = null

    @Volatile
    private var currentIterationCount = 0

    // 连续错误计数
    @Volatile
    private var consecutiveErrors = 0

    // 【关键】已处理的目标集合，防止死循环
    private val processedGoalsInCurrentRound = mutableSetOf<AnalysisType>()

    // 依赖组件
    private val mdFileService = MdFileService(projectPath)
    private val llmService: LlmService by lazy { SmanConfig.createLlmService() }
    private val completionEvaluator by lazy { CompletionEvaluator(llmService) }
    private val impactAnalyzer by lazy { ImpactAnalyzer(projectPath, llmService) }

    // 【修复】复用 smanService 的工具注册，而不是创建空的 ToolRegistry
    private val analysisValidator by lazy { AnalysisOutputValidator() }
    private val analysisDoomLoopGuard by lazy { DoomLoopGuard.createDefault() }
    private val analysisLoopExecutor: AnalysisLoopExecutor by lazy {
        AnalysisLoopExecutor(
            toolRegistry = smanService.getToolRegistry(),
            toolExecutor = smanService.getToolExecutor(),
            doomLoopGuard = analysisDoomLoopGuard,
            llmService = llmService,
            validator = analysisValidator,
            maxSteps = 15
        )
    }

    // 状态持久化（断点续传）
    private val stateRepository: ArchitectStateRepository by lazy {
        val vectorDbConfig = SmanConfig.createVectorDbConfig(projectKey, projectPath.toString())
        ArchitectStateRepository(vectorDbConfig)
    }

    // JSON 序列化器（用于持久化状态）
    private val json = Json { ignoreUnknownKeys = true }

    // 配置
    private val config: ArchitectConfig
        get() = ArchitectConfig.fromCurrentSettings()

    /**
     * 启动架构师 Agent
     */
    fun start() {
        if (enabled) {
            logger.warn("ArchitectAgent 已在运行中，忽略启动请求")
            return
        }

        if (!config.enabled) {
            logger.info("架构师 Agent 未启用，跳过启动")
            return
        }

        // 尝试恢复之前的状态（断点续传）
        val restored = tryRestoreState()

        if (!restored) {
            // 全新启动，重置状态
            processedGoalsInCurrentRound.clear()
            currentGoal = null
            currentIterationCount = 0
        }

        currentJob = scope.launch {
            runArchitectLoop()
        }

        logger.info("ArchitectAgent 启动: projectKey={}, 恢复状态={}", projectKey, restored)
    }

    /**
     * 尝试恢复之前的状态（断点续传）
     *
     * @return true-成功恢复，false-无状态或恢复失败
     */
    private fun tryRestoreState(): Boolean {
        return try {
            val stateEntity = runBlocking { stateRepository.loadState(projectKey) }

            if (stateEntity == null) {
                logger.info("无历史状态，全新启动")
                return false
            }

            // 检查是否有未完成的任务
            if (!runBlocking { stateRepository.hasUnfinishedTask(projectKey) }) {
                logger.info("上次任务已完成，全新启动")
                runBlocking { stateRepository.clearState(projectKey) }
                return false
            }

            // 恢复状态
            enabled = stateEntity.enabled
            currentPhase = stateEntity.currentPhase
            totalIterations = stateEntity.totalIterations
            successfulIterations = stateEntity.successfulIterations
            currentIterationCount = stateEntity.currentIterationCount
            stopReason = stateEntity.stopReason

            // 恢复当前目标
            if (stateEntity.currentGoalType != null) {
                val analysisType = try {
                    AnalysisType.fromKey(stateEntity.currentGoalType)
                } catch (e: Exception) {
                    logger.warn("无法解析目标类型: ${stateEntity.currentGoalType}")
                    null
                }

                if (analysisType != null) {
                    currentGoal = ArchitectGoal.fromType(analysisType, config.getCompletionThreshold())

                    // 恢复追问列表
                    if (!stateEntity.currentGoalTodos.isNullOrBlank()) {
                        try {
                            val todos = json.decodeFromString<List<String>>(stateEntity.currentGoalTodos)
                            currentGoal = currentGoal?.withFollowUp(todos)
                        } catch (e: Exception) {
                            logger.warn("解析追问列表失败", e)
                        }
                    }

                    logger.info("恢复当前目标: type={}, iteration={}", analysisType.key, currentIterationCount)
                }
            }

            // 恢复已处理目标集合
            // 【关键】只恢复那些 MD 文件实际存在且完成度达标的类型
            // 如果 MD 文件被删除了，不应该跳过重新分析
            if (!stateEntity.processedGoals.isNullOrBlank()) {
                try {
                    val goalKeys = json.decodeFromString<List<String>>(stateEntity.processedGoals)
                    goalKeys.forEach { key ->
                        try {
                            val analysisType = AnalysisType.fromKey(key)
                            if (analysisType != null) {
                                // 检查 MD 文件是否存在且完成度达标
                                val metadata = mdFileService.readMetadata(analysisType)
                                if (metadata != null && metadata.completeness >= config.getCompletionThreshold()) {
                                    processedGoalsInCurrentRound.add(analysisType)
                                    logger.debug("恢复已处理目标: {} (完成度: {})", key, metadata.completeness)
                                } else {
                                    logger.info("跳过恢复已处理目标: {} (MD不存在或未完成，需要重新分析)", key)
                                }
                            }
                        } catch (e: Exception) {
                            logger.warn("无法解析已处理目标: $key")
                        }
                    }
                    logger.info("恢复已处理目标: {} 个", processedGoalsInCurrentRound.size)
                } catch (e: Exception) {
                    logger.warn("解析已处理目标列表失败", e)
                }
            }

            logger.info("成功恢复状态: phase={}, goal={}, processedCount={}",
                currentPhase, currentGoal?.type?.key, processedGoalsInCurrentRound.size)

            true
        } catch (e: Exception) {
            logger.error("恢复状态失败", e)
            false
        }
    }

    /**
     * 停止架构师 Agent
     */
    fun stop(reason: ArchitectStopReason = ArchitectStopReason.USER_STOPPED) {
        enabled = false
        stopReason = reason
        currentJob?.cancel()
        currentJob = null

        // 清除持久化状态（用户主动停止）
        runBlocking {
            stateRepository.clearState(projectKey)
        }

        logger.info("ArchitectAgent 停止: projectKey={}, reason={}", projectKey, reason)
    }

    /**
     * 持久化当前状态（用于断点续传）
     *
     * 在关键节点调用，确保异常中断后可以恢复
     */
    private suspend fun persistState() {
        try {
            val stateEntity = ArchitectStateEntity(
                projectKey = projectKey,
                enabled = enabled,
                currentPhase = currentPhase,
                totalIterations = totalIterations,
                successfulIterations = successfulIterations,
                currentGoalType = currentGoal?.type?.key,
                currentIterationCount = currentIterationCount,
                currentGoalTodos = currentGoal?.followUpQuestions?.takeIf { it.isNotEmpty() }
                    ?.let { json.encodeToString(it) },
                processedGoals = processedGoalsInCurrentRound.takeIf { it.isNotEmpty() }
                    ?.map { it.key }
                    ?.let { json.encodeToString(it) },
                stopReason = stopReason,
                lastUpdatedAt = System.currentTimeMillis()
            )

            stateRepository.saveState(stateEntity)
            logger.debug("持久化状态: phase={}, goal={}, iteration={}",
                currentPhase, currentGoal?.type?.key, currentIterationCount)
        } catch (e: Exception) {
            logger.error("持久化状态失败", e)
        }
    }

    /**
     * 清除持久化状态（任务完成时调用）
     */
    private suspend fun clearPersistedState() {
        try {
            stateRepository.clearState(projectKey)
            logger.debug("清除持久化状态: projectKey={}", projectKey)
        } catch (e: Exception) {
            logger.error("清除持久化状态失败", e)
        }
    }

    /**
     * 获取当前状态
     */
    fun getStatus(): ArchitectStatus {
        return ArchitectStatus(
            projectKey = projectKey,
            enabled = enabled,
            currentPhase = currentPhase,
            totalIterations = totalIterations,
            successfulIterations = successfulIterations,
            currentGoal = currentGoal,
            currentIterationCount = currentIterationCount,
            stopReason = stopReason
        )
    }

    /**
     * 主循环
     */
    private suspend fun runArchitectLoop() {
        // 检查启动条件
        if (!shouldRun()) {
            return
        }

        enabled = true
        stopReason = null

        // 【断点续传】如果是恢复启动，且当前有未完成的目标，继续执行
        if (currentGoal != null && currentIterationCount > 0) {
            logger.info("恢复执行未完成的目标: {}, 已迭代 {} 次", currentGoal!!.type.key, currentIterationCount)
            executeGoalWithIterations(currentGoal!!)
        }

        while (enabled) {
            try {
                // 1. 选择下一个分析目标
                currentPhase = ArchitectPhase.SELECTING_GOAL
                val goal = selectNextGoal()

                if (goal == null) {
                    logger.info("本轮所有分析目标已完成，等待下一轮")
                    // 清空已处理集合，下一轮重新开始
                    processedGoalsInCurrentRound.clear()
                    // 【断点续传】本轮完成，清除持久化状态
                    clearPersistedState()
                    // 休眠后继续
                    delay(config.intervalMs)
                    continue
                }

                currentGoal = goal
                currentIterationCount = 0

                // 【断点续传】持久化状态（选中目标后）
                persistState()

                // 2. 检查增量更新
                if (config.incrementalCheckEnabled) {
                    currentPhase = ArchitectPhase.CHECKING_INCREMENTAL
                    val needsUpdate = needsReanalysis(goal)

                    if (!needsUpdate) {
                        logger.info("目标无需重新分析，标记为已处理: {}", goal.type.key)
                        // 【关键】标记为已处理，避免死循环
                        processedGoalsInCurrentRound.add(goal.type)
                        // 【断点续传】持久化状态（跳过目标后）
                        persistState()
                        continue
                    }
                }

                // 【关键】标记为正在处理
                processedGoalsInCurrentRound.add(goal.type)

                // 3. 执行分析（小步快跑）
                executeGoalWithIterations(goal)

            } catch (e: CancellationException) {
                logger.info("ArchitectAgent 被取消")
                break
            } catch (e: Exception) {
                logger.error("ArchitectAgent 迭代失败", e)
                handleIterationError(e)

                // 连续错误过多时，暂停更长时间
                if (consecutiveErrors >= 3) {
                    logger.warn("连续错误 ${consecutiveErrors} 次，暂停 60 秒")
                    delay(60000)
                    consecutiveErrors = 0
                }
            }
        }
    }

    /**
     * 检查是否应该运行
     */
    private fun shouldRun(): Boolean {
        return try {
            val apiKey = SmanConfig.llmApiKey
            if (apiKey.isBlank()) {
                logger.warn("LLM API Key 未配置")
                stopReason = ArchitectStopReason.API_KEY_NOT_CONFIGURED
                false
            } else {
                true
            }
        } catch (e: IllegalStateException) {
            logger.warn("LLM API Key 未配置: ${e.message}")
            stopReason = ArchitectStopReason.API_KEY_NOT_CONFIGURED
            false
        }
    }

    /**
     * 选择下一个分析目标
     *
     * 【关键修复】跳过本轮已处理的目标
     */
    private fun selectNextGoal(): ArchitectGoal? {
        val threshold = config.getCompletionThreshold()

        // 1. 优先选择未完成且未处理的目标
        val incompleteTypes = mdFileService.getIncompleteTypes(threshold)
            .filter { it !in processedGoalsInCurrentRound }

        if (incompleteTypes.isNotEmpty()) {
            // 按优先级排序：核心类型优先
            val sortedTypes = incompleteTypes.sortedBy { type ->
                when (type) {
                    AnalysisType.PROJECT_STRUCTURE -> 0
                    AnalysisType.TECH_STACK -> 1
                    else -> 2 + AnalysisType.standardTypes().indexOf(type)
                }
            }

            val selectedType = sortedTypes.first()
            logger.info("选择未完成的目标: {}", selectedType.key)

            // 检查是否有历史 TODO
            val metadata = mdFileService.readMetadata(selectedType)
            val followUpQuestions = metadata?.todos?.map { it.content } ?: emptyList()

            return ArchitectGoal.fromType(selectedType, threshold)
                .withFollowUp(followUpQuestions)
        }

        // 2. 检查是否有需要更新的目标（基于 TODO）
        val typesWithTodos = mdFileService.getTypesWithTodos()
            .filter { it.first !in processedGoalsInCurrentRound }

        if (typesWithTodos.isNotEmpty()) {
            val (type, todos) = typesWithTodos.first()
            logger.info("选择有 TODO 的目标: {}, todos={}", type.key, todos.size)
            return ArchitectGoal.fromType(type, threshold)
                .withFollowUp(todos)
        }

        // 3. 本轮所有目标已处理完成
        return null
    }

    /**
     * 检查是否需要重新分析
     */
    private fun needsReanalysis(goal: ArchitectGoal): Boolean {
        val metadata = mdFileService.readMetadata(goal.type)

        // 没有 MD 文件，需要分析
        if (metadata == null) {
            logger.debug("MD 文件不存在，需要分析: {}", goal.type.key)
            return true
        }

        // 完成度低于阈值，需要继续分析
        if (metadata.completeness < config.getCompletionThreshold()) {
            logger.debug("完成度 {} 低于阈值 {}，需要继续分析",
                metadata.completeness, config.getCompletionThreshold())
            return true
        }

        // 有未完成的 TODO，需要继续分析
        if (metadata.todos.isNotEmpty()) {
            logger.debug("有 {} 个 TODO，需要继续分析", metadata.todos.size)
            return true
        }

        // 已完成且没有 TODO，检查文件变更
        return try {
            val impact = impactAnalyzer.analyze(goal.type, metadata.lastModified)
            val needsUpdate = impact.impactLevel != FileChangeImpact.ImpactLevel.LOW

            if (needsUpdate) {
                logger.info("文件变更影响级别: {}, 需要更新", impact.impactLevel)
            } else {
                logger.debug("文件变更影响级别: LOW，无需更新")
            }

            needsUpdate
        } catch (e: Exception) {
            logger.error("分析文件变更影响失败，保守触发更新", e)
            true
        }
    }

    /**
     * 执行目标（多轮迭代）
     *
     * 【重构】使用 AnalysisLoopExecutor 替代 SmanService.processMessage()
     * 优势：
     * 1. 强制结构化输出
     * 2. 完整度验证
     * 3. 自动 TODO 生成
     */
    private suspend fun executeGoalWithIterations(goal: ArchitectGoal) {
        var currentGoal = goal
        val maxIterations = config.maxIterationsPerMd

        while (currentIterationCount < maxIterations && enabled) {
            currentIterationCount++
            totalIterations++

            logger.info("执行目标: {}, 迭代 {}/{}", goal.type.key, currentIterationCount, maxIterations)

            // 【断点续传】迭代开始前持久化状态
            persistState()

            try {
                // 1. 使用独立分析执行器执行分析
                currentPhase = ArchitectPhase.EXECUTING
                val result = executeWithAnalysisLoopExecutor(currentGoal)

                // 2. 使用验证器验证结果
                currentPhase = ArchitectPhase.EVALUATING
                val validation = analysisValidator.validate(result.content, goal.type)

                logger.info("验证结果: completeness={}, isValid={}, missing={}",
                    validation.completeness, validation.isValid, validation.missingSections)

                // 3. 转换为 EvaluationResult（兼容现有流程）
                val evaluation = EvaluationResult(
                    completeness = validation.completeness,
                    isComplete = validation.isValid,
                    summary = if (validation.isValid) "分析完成" else "分析未完成，缺少章节：${validation.missingSections.joinToString(", ")}",
                    todos = validation.missingSections.map { missing ->
                        TodoItem(content = "补充分析：$missing", priority = TodoPriority.HIGH)
                    },
                    followUpQuestions = validation.missingSections.map { "补充分析：$it" },
                    confidence = if (validation.completeness > 0) 0.8 else 0.3
                )

                // 4. 根据评估结果处理
                currentPhase = ArchitectPhase.PERSISTING

                when {
                    evaluation.isComplete -> {
                        // 完成，写入 MD（带自动生成的 TODO）
                        saveResultFromLoopResult(currentGoal, result, evaluation)
                        successfulIterations++
                        logger.info("目标已完成: {}, completeness={}", goal.type.key, validation.completeness)
                        clearPersistedState()
                        return
                    }
                    evaluation.confidence < 0.5 && currentIterationCount < maxIterations -> {
                        // 低置信度 = 内容质量差，继续迭代
                        logger.warn("评估置信度过低 ({})，内容质量可能有问题，继续迭代", evaluation.confidence)
                        currentGoal = currentGoal.withFollowUp(
                            listOf("上一次分析结果质量不佳，请重新执行${goal.type.displayName}任务。" +
                                   "确保：1) 使用工具扫描代码 2) 输出结构化的 Markdown 报告")
                        )
                        persistState()
                    }
                    evaluation.needsFollowUp && currentIterationCount < maxIterations -> {
                        // 需要追问，继续迭代
                        logger.info("需要补充: {} 个章节", evaluation.followUpQuestions.size)
                        currentGoal = currentGoal.withFollowUp(evaluation.followUpQuestions)
                        saveResultFromLoopResult(currentGoal, result, evaluation)
                        persistState()
                    }
                    else -> {
                        // 未完成，记录 TODO 并保存
                        saveResultFromLoopResult(currentGoal, result, evaluation)
                        if (currentIterationCount >= maxIterations) {
                            logger.warn("达到最大迭代次数: {}, 目标: {}", maxIterations, goal.type.key)
                        }
                        clearPersistedState()
                        return
                    }
                }
            } catch (e: Exception) {
                logger.error("执行迭代失败: {}, 迭代 {}", goal.type.key, currentIterationCount, e)
                persistState()
                return
            }
        }
    }

    /**
     * 使用 AnalysisLoopExecutor 执行分析
     *
     * 【核心修改】不再使用 SmanService.processMessage()
     * LLM 通过工具调用来探索项目，不需要提前注入文件内容
     */
    private suspend fun executeWithAnalysisLoopExecutor(goal: ArchitectGoal): AnalysisLoopResult {
        logger.info("使用独立分析执行器: type={}", goal.type.key)

        // 构建前置上下文
        val priorContext = buildString {
            if (goal.context.isNotEmpty()) {
                append("## 已有的分析结果\n\n")
                goal.context.forEach { (key, value) ->
                    append("### $key\n$value\n\n")
                }
            }
        }

        // 转换 TODO 为 AnalysisTodo 格式
        val existingTodos = goal.followUpQuestions.mapIndexed { index, question ->
            com.smancode.sman.analysis.model.AnalysisTodo(
                id = "todo-${goal.type.key}-$index",
                content = question,
                status = com.smancode.sman.analysis.model.TodoStatus.PENDING,
                priority = index + 1
            )
        }

        // 执行分析（LLM 会通过工具调用来探索项目）
        return analysisLoopExecutor.execute(
            type = goal.type,
            projectKey = projectKey,
            priorContext = priorContext,
            existingTodos = existingTodos
        )
    }

    /**
     * 从 AnalysisLoopResult 保存分析结果
     */
    private fun saveResultFromLoopResult(
        goal: ArchitectGoal,
        result: AnalysisLoopResult,
        evaluation: EvaluationResult
    ) {
        // 读取之前的元信息
        val previousMetadata = mdFileService.readMetadata(goal.type)

        // 保存 MD 文件（使用新验证器的内容）
        mdFileService.saveWithMetadata(goal.type, result.content, evaluation, previousMetadata)

        logger.info("保存分析结果: type={}, completeness={}, todos={}",
            goal.type.key, evaluation.completeness, result.todos.size)
    }

    /**
     * 执行单次分析（调用 SmanLoop）
     */
    private fun executeSingleIteration(goal: ArchitectGoal): com.smancode.sman.model.message.Message {
        // 1. 创建架构师 Session
        val session = smanService.createArchitectSession(goal.type)

        // 2. 构建用户输入
        val userInput = buildGoalPrompt(goal)

        // 3. 收集 Part
        val collectedParts = mutableListOf<Part>()
        val partPusher = Consumer<Part> { part ->
            collectedParts.add(part)
            logger.debug("收集 Part: type={}", part::class.simpleName)
        }

        return try {
            // 4. 调用 SmanLoop
            smanService.processMessage(session.id!!, userInput, partPusher)
        } finally {
            // 5. 清理 Session
            smanService.unloadSession(session.id!!)
        }
    }

    /**
     * 构建目标提示词
     *
     * 【重构】使用更强制性的执行协议，确保 LLM 必须先调用工具
     */
    private fun buildGoalPrompt(goal: ArchitectGoal): String {
        val promptBuilder = StringBuilder()

        // 【关键】在最开始添加强制执行协议（优先级最高）
        promptBuilder.append("""
# ⚠️ 强制执行协议（CRITICAL - 必须严格遵守）

**你是自动化分析架构师，这是无人值守的后台任务，没有用户交互。**

## 🚫 禁止行为（违反将导致任务失败）

```
❌ 你好，我是...
❌ 请问你想了解什么？
❌ 我可以帮你分析...
❌ 让我来为你...
❌ 我将按照以下步骤...
❌ 需要我做什么？
❌ 请告诉我你的需求
❌ 任何等待用户输入的内容
```

## ✅ 必须行为

```
步骤 1: 调用工具（read_file / find_file / grep_file）
步骤 2: 调用工具（继续扫描）
步骤 3: 调用工具（继续扫描）
...
步骤 N: 输出 Markdown 格式的分析报告
```

## 执行流程

**在输出任何文字之前，必须先完成工具调用！**

如果你现在还没有调用任何工具，请立即停止输出文字，先调用工具。

---

        """.trimIndent())

        // 加载基础提示词
        val basePrompt = loadAnalysisPrompt(goal.type)
        promptBuilder.append(basePrompt).append("\n\n")

        // 注入已有上下文
        if (goal.context.isNotEmpty()) {
            promptBuilder.append("## 已有的分析结果\n\n")
            goal.context.forEach { (key, value) ->
                promptBuilder.append("### $key\n$value\n\n")
            }
        }

        // 添加追问（如果有）
        if (goal.followUpQuestions.isNotEmpty()) {
            promptBuilder.append("## ⚠️ 上一轮分析的问题（必须解决）\n\n")
            promptBuilder.append("上一轮分析存在以下问题，本轮必须解决：\n\n")
            goal.followUpQuestions.forEachIndexed { index, q ->
                promptBuilder.append("${index + 1}. $q\n")
            }
            promptBuilder.append("\n**注意**：不要重复上一轮的错误！\n\n")
        }

        // 添加输出要求（强化版）
        promptBuilder.append("""
## 输出要求

分析结果必须以 **Markdown 格式** 输出，必须包含：

1. **标题**：使用 `##` 标题层级
2. **概述**：简要总结分析结果
3. **详细内容**：使用表格或列表展示关键信息
4. **如未完成**：在末尾标注 `TODO: 待完成事项`

当前时间: ${MdFileService.currentTimestamp()}

---

**再次提醒**：如果你现在还没有调用任何工具，请立即调用 `read_file` 或 `find_file` 扫描项目代码！
        """.trimIndent())

        return promptBuilder.toString()
    }

    /**
     * 加载分析提示词
     */
    private fun loadAnalysisPrompt(type: AnalysisType): String {
        return try {
            val resourcePath = "prompts/${type.getPromptPath()}"
            val stream = javaClass.getResourceAsStream("/$resourcePath")
            stream?.bufferedReader()?.readText() ?: generateDefaultPrompt(type)
        } catch (e: Exception) {
            logger.warn("加载提示词失败: {}", type.key, e)
            generateDefaultPrompt(type)
        }
    }

    /**
     * 生成默认提示词
     */
    private fun generateDefaultPrompt(type: AnalysisType): String {
        return """
# ${type.displayName}

请分析项目的${type.displayName}。

分析要点：
1. 全面扫描项目结构
2. 识别关键组件和模块
3. 总结分析结果

请以 Markdown 格式输出分析报告。
        """.trimIndent()
    }

    /**
     * 保存分析结果
     */
    private fun saveResult(
        goal: ArchitectGoal,
        response: com.smancode.sman.model.message.Message,
        evaluation: EvaluationResult
    ) {
        // 提取内容
        val rawContent = extractResponseContent(response)

        // 【防呆】清理内容（去除末尾问候语等）
        val content = cleanContent(rawContent)

        // 读取之前的元信息
        val previousMetadata = mdFileService.readMetadata(goal.type)

        // 保存 MD 文件
        mdFileService.saveWithMetadata(goal.type, content, evaluation, previousMetadata)

        logger.info("保存分析结果: type={}, completeness={}", goal.type.key, evaluation.completeness)
    }

    /**
     * 【防呆】清理内容
     *
     * 去除末尾的问候语、等待用户输入的提示等
     */
    private fun cleanContent(content: String): String {
        var cleaned = content.trim()

        // 问候语模式（通常是 LLM 在等待用户输入）
        // 注意：顺序很重要，分隔线模式要先匹配
        val greetingPatterns = listOf(
            // 分隔线后的问候语（优先匹配）
            Regex("""\n*---+\s*\n+\*\*请问.*$""", RegexOption.DOT_MATCHES_ALL),
            Regex("""\n*---+\s*\n+请问.*$""", RegexOption.DOT_MATCHES_ALL),
            // 分隔线后的列表选项（如：- 构建项目: ./gradlew build）
            Regex("""\n*---+\s*\n+(- [^\n]+\n?)+$"""),
            // 中文问候语
            Regex("""\n*\*\*请问[您你]想[做什么了解]*[^*]*\*\*.*$""", RegexOption.DOT_MATCHES_ALL),
            Regex("""\n*\*\*请告诉我[你的]*需求\*\*.*$""", RegexOption.DOT_MATCHES_ALL),
            Regex("""\n*请问[您你]想[做什么让我做什么了解]*.*$""", RegexOption.DOT_MATCHES_ALL),
            Regex("""\n*还是有其他需求.*$""", RegexOption.DOT_MATCHES_ALL)
        )

        for (pattern in greetingPatterns) {
            val newContent = pattern.replace(cleaned, "")
            if (newContent != cleaned) {
                logger.debug("清理问候语模式: {}", pattern)
                cleaned = newContent
            }
        }

        return cleaned.trim()
    }

    /**
     * 提取响应内容
     */
    private fun extractResponseContent(response: com.smancode.sman.model.message.Message): String {
        val textParts = response.parts.filterIsInstance<TextPart>()
        val toolParts = response.parts.filterIsInstance<ToolPart>()

        val sb = StringBuilder()

        // 添加文本内容
        textParts.forEach { part ->
            if (part.text.isNotEmpty()) {
                sb.append(part.text).append("\n\n")
            }
        }

        // 添加工具调用摘要
        if (toolParts.isNotEmpty()) {
            sb.append("## 工具调用记录\n\n")
            toolParts.forEach { part ->
                sb.append("- **${part.toolName}**: ${part.summary ?: "执行完成"}\n")
            }
        }

        return sb.toString().trim()
    }

    /**
     * 处理迭代错误
     */
    private fun handleIterationError(e: Exception) {
        consecutiveErrors++
        logger.error("迭代错误: {}, 连续错误次数: {}", e.message, consecutiveErrors)
    }

    /**
     * 单次执行（用于外部调用）
     */
    fun executeOnce(type: AnalysisType): EvaluationResult {
        if (!shouldRun()) {
            return EvaluationResult.failure("架构师 Agent 无法运行: ${stopReason}")
        }

        val goal = ArchitectGoal.fromType(type, config.getCompletionThreshold())

        return try {
            val response = executeSingleIteration(goal)
            val evaluation = completionEvaluator.evaluate(goal, response, config.getCompletionThreshold())

            // 保存结果
            saveResult(goal, response, evaluation)

            evaluation
        } catch (e: Exception) {
            logger.error("单次执行失败", e)
            EvaluationResult.failure("执行失败: ${e.message}")
        }
    }
}

/**
 * 架构师配置
 */
data class ArchitectConfig(
    val enabled: Boolean = false,
    val maxIterationsPerMd: Int = 5,
    val deepModeEnabled: Boolean = false,
    val completionThresholdDeep: Double = 0.9,
    val completionThresholdNormal: Double = 0.7,
    val incrementalCheckEnabled: Boolean = true,
    val intervalMs: Long = 300000L
) {
    fun getCompletionThreshold(): Double {
        return if (deepModeEnabled) completionThresholdDeep else completionThresholdNormal
    }

    companion object {
        fun fromCurrentSettings(): ArchitectConfig {
            return ArchitectConfig(
                enabled = SmanConfig.architectAgentEnabled,
                maxIterationsPerMd = SmanConfig.architectMaxIterationsPerMd,
                deepModeEnabled = SmanConfig.architectDeepModeEnabled,
                completionThresholdDeep = SmanConfig.architectCompletionThresholdDeep,
                completionThresholdNormal = SmanConfig.architectCompletionThresholdNormal,
                incrementalCheckEnabled = SmanConfig.architectIncrementalCheckEnabled,
                intervalMs = SmanConfig.architectIntervalMs
            )
        }
    }
}

/**
 * 架构师状态
 */
data class ArchitectStatus(
    val projectKey: String,
    val enabled: Boolean,
    val currentPhase: ArchitectPhase,
    val totalIterations: Long,
    val successfulIterations: Long,
    val currentGoal: ArchitectGoal?,
    val currentIterationCount: Int,
    val stopReason: ArchitectStopReason?
)
