package com.smancode.sman.domain.puzzle

import com.smancode.sman.infra.storage.PuzzleStore
import com.smancode.sman.smancode.llm.LlmService
import org.slf4j.LoggerFactory

/**
 * PuzzleCoordinator 工厂
 *
 * 负责创建和配置 PuzzleCoordinator 及其依赖
 */
object PuzzleCoordinatorFactory {

    private val logger = LoggerFactory.getLogger(PuzzleCoordinatorFactory::class.java)

    /**
     * 创建 PuzzleCoordinator
     *
     * @param projectPath 项目路径
     * @param llmService LLM 服务
     * @return 配置好的 PuzzleCoordinator
     * @throws IllegalArgumentException 如果 projectPath 为空
     */
    fun create(
        projectPath: String,
        llmService: LlmService
    ): PuzzleCoordinator {
        require(projectPath.isNotBlank()) { "projectPath 不能为空" }

        logger.info("创建 PuzzleCoordinator: projectPath={}", projectPath)

        // 创建依赖组件
        val puzzleStore = PuzzleStore(projectPath)
        val taskQueueStore = TaskQueueStore(projectPath)
        val gapDetector = GapDetector()
        val taskScheduler = TaskScheduler()
        val doomLoopGuard = DoomLoopGuard(taskQueueStore)
        val recoveryService = RecoveryService(taskQueueStore)

        // 创建 TaskExecutor 依赖
        val checksumCalculator = ChecksumCalculator()
        val llmAnalyzer = DefaultLlmAnalyzer(llmService)
        val fileReader = DefaultFileReader(projectPath)

        // 创建 TaskExecutor
        val taskExecutor = TaskExecutor(
            taskQueueStore = taskQueueStore,
            puzzleStore = puzzleStore,
            checksumCalculator = checksumCalculator,
            llmAnalyzer = llmAnalyzer,
            fileReader = fileReader
        )

        return PuzzleCoordinator(
            puzzleStore = puzzleStore,
            taskQueueStore = taskQueueStore,
            gapDetector = gapDetector,
            taskScheduler = taskScheduler,
            taskExecutor = taskExecutor,
            recoveryService = recoveryService,
            doomLoopGuard = doomLoopGuard
        )
    }

    /**
     * 创建 KnowledgeEvolutionLoop
     *
     * @param projectPath 项目路径
     * @param llmService LLM 服务
     * @return 配置好的 KnowledgeEvolutionLoop
     * @throws IllegalArgumentException 如果 projectPath 为空
     */
    fun createEvolutionLoop(
        projectPath: String,
        llmService: LlmService
    ): KnowledgeEvolutionLoop {
        require(projectPath.isNotBlank()) { "projectPath 不能为空" }

        logger.info("创建 KnowledgeEvolutionLoop: projectPath={}", projectPath)

        val puzzleStore = PuzzleStore(projectPath)

        return KnowledgeEvolutionLoop(
            puzzleStore = puzzleStore,
            llmService = llmService,
            projectPath = projectPath
        )
    }

    /**
     * 创建完整的调度器组件
     *
     * @param projectPath 项目路径
     * @param llmService LLM 服务
     * @param intervalMs 调度间隔（毫秒）
     * @return 配置好的 PuzzleScheduler
     * @throws IllegalArgumentException 如果 projectPath 为空
     */
    fun createScheduler(
        projectPath: String,
        llmService: LlmService,
        intervalMs: Long = 300_000
    ): PuzzleScheduler {
        require(projectPath.isNotBlank()) { "projectPath 不能为空" }

        logger.info("创建 PuzzleScheduler: projectPath={}, intervalMs={}", projectPath, intervalMs)

        val puzzleStore = PuzzleStore(projectPath)
        val versionStore = KnowledgeBaseVersionStore(projectPath)
        val evolutionLoop = createEvolutionLoop(projectPath, llmService)

        return PuzzleScheduler(
            projectPath = projectPath,
            puzzleStore = puzzleStore,
            versionStore = versionStore,
            evolutionLoop = evolutionLoop,
            intervalMs = intervalMs
        )
    }
}
