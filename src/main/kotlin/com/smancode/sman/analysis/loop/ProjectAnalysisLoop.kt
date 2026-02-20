package com.smancode.sman.analysis.loop

import com.smancode.sman.analysis.executor.AnalysisLoopExecutor
import com.smancode.sman.analysis.executor.AnalysisLoopResult
import com.smancode.sman.analysis.model.*
import com.smancode.sman.analysis.persistence.AnalysisStateRepository
import com.smancode.sman.analysis.util.Md5FileTracker
import com.smancode.sman.analysis.guard.DoomLoopGuard
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries

/**
 * 分析循环配置
 */
data class AnalysisLoopConfig(
    val enabled: Boolean = true,
    val analysisIntervalMs: Long = 60000,
    val unchangedSkipIntervalMs: Long = 300000,
    val maxStepsPerAnalysis: Int = 15
)

/**
 * 分析循环状态
 */
data class AnalysisLoopStatus(
    val enabled: Boolean,
    val currentPhase: AnalysisPhase,
    val currentAnalysisType: AnalysisType?,
    val totalAnalyses: Int,
    val successfulAnalyses: Int
)

/**
 * 项目分析主循环
 *
 * 负责项目分析的整体调度，包括：
 * - 检测文件变更
 * - 执行分析任务
 * - 管理分析状态
 * - 支持断点续传
 */
class ProjectAnalysisLoop(
    private val projectKey: String,
    private val projectPath: Path,
    private val analysisExecutor: AnalysisLoopExecutor,
    private val stateRepository: AnalysisStateRepository,
    private val md5FileTracker: Md5FileTracker,
    private val doomLoopGuard: DoomLoopGuard,
    private val config: AnalysisLoopConfig
) {
    private val logger = LoggerFactory.getLogger(ProjectAnalysisLoop::class.java)

    private var running = false
    private var loopJob: Job? = null
    private var currentState: AnalysisLoopState = AnalysisLoopState(projectKey = projectKey)

    /**
     * 启动分析循环
     */
    suspend fun start() {
        if (running) {
            logger.warn("分析循环已在运行中")
            return
        }

        logger.info("启动项目分析循环: projectKey={}", projectKey)
        running = true

        loadState()

        currentState = currentState.copy(enabled = true)
        stateRepository.saveLoopState(currentState)

        resumeFromInterruptedState()

        loopJob = CoroutineScope(Dispatchers.Default).launch {
            runAnalysisLoop()
        }
    }

    /**
     * 停止分析循环
     */
    fun stop() {
        logger.info("停止项目分析循环: projectKey={}", projectKey)
        running = false
        loopJob?.cancel()
        loopJob = null

        currentState = currentState.copy(enabled = false)
        runBlocking {
            stateRepository.saveLoopState(currentState)
        }
    }

    /**
     * 获取当前状态
     */
    fun getStatus(): AnalysisLoopStatus {
        return AnalysisLoopStatus(
            enabled = currentState.enabled,
            currentPhase = currentState.currentPhase,
            currentAnalysisType = currentState.currentAnalysisType,
            totalAnalyses = currentState.totalAnalyses,
            successfulAnalyses = currentState.successfulAnalyses
        )
    }

    private suspend fun loadState() {
        val savedState = stateRepository.getLoopState(projectKey)
        if (savedState != null) {
            currentState = savedState
            logger.debug("加载保存的状态: phase={}", savedState.currentPhase)
        }
    }

    private suspend fun resumeFromInterruptedState() {
        val doingTasks = stateRepository.getDoingTasks(projectKey)

        if (doingTasks.isNotEmpty()) {
            logger.info("发现 {} 个未完成的分析任务，准备恢复", doingTasks.size)
            stateRepository.resetDoingTasksToPending(projectKey)

            for (task in doingTasks) {
                logger.info("重新执行中断的任务: type={}", task.analysisType)
                executeAnalysisTask(task.analysisType)
            }
        }
    }

    private suspend fun runAnalysisLoop() {
        logger.info("分析循环开始运行")

        while (running && currentState.enabled) {
            try {
                currentState = currentState.copy(currentPhase = AnalysisPhase.CHECKING_CHANGES)
                stateRepository.saveLoopState(currentState)

                val changedFiles = detectChanges()

                if (changedFiles.isEmpty()) {
                    logger.debug("项目未变更，跳过分析")
                    delay(config.unchangedSkipIntervalMs)
                    continue
                }

                logger.info("检测到 {} 个文件变更", changedFiles.size)

                val affectedTypes = analyzeChangeImpact(changedFiles)

                for (type in affectedTypes) {
                    if (!running) break

                    val checkResult = doomLoopGuard.shouldSkipQuestion(projectKey)
                    if (checkResult.shouldSkip) {
                        logger.warn("分析被跳过: reason={}", checkResult.reason)
                        delay(1000)
                        continue
                    }

                    executeAnalysisTask(type)
                }

                saveMd5Cache()
                delay(config.analysisIntervalMs)

            } catch (e: CancellationException) {
                logger.info("分析循环被取消")
                break
            } catch (e: Exception) {
                logger.error("分析循环出错", e)
                doomLoopGuard.recordFailure(projectKey)
                currentState = currentState.markAsError(e.message ?: "未知错误")
                stateRepository.saveLoopState(currentState)
                delay(30000)
            }
        }

        logger.info("分析循环结束")
    }

    private suspend fun detectChanges(): List<FileSnapshot> {
        if (!projectPath.exists() || !projectPath.isDirectory()) {
            return emptyList()
        }

        val sourceFiles = getAllSourceFiles()
        return md5FileTracker.getChangedFiles(sourceFiles)
    }

    private fun getAllSourceFiles(): List<Path> {
        val sourceDirs = listOf(
            projectPath.resolve("src/main/kotlin"),
            projectPath.resolve("src/test/kotlin"),
            projectPath.resolve("src/main/java"),
            projectPath.resolve("src/test/java")
        )

        val files = mutableListOf<Path>()

        for (dir in sourceDirs) {
            if (dir.exists() && dir.isDirectory()) {
                files.addAll(dir.listDirectoryEntries("**/*.kt"))
                files.addAll(dir.listDirectoryEntries("**/*.java"))
            }
        }

        val configFiles = listOf(
            "build.gradle.kts", "build.gradle",
            "settings.gradle.kts", "settings.gradle",
            "pom.xml"
        )

        for (fileName in configFiles) {
            val file = projectPath.resolve(fileName)
            if (file.exists()) {
                files.add(file)
            }
        }

        return files
    }

    private fun analyzeChangeImpact(changedFiles: List<FileSnapshot>): Set<AnalysisType> {
        val affectedTypes = mutableSetOf<AnalysisType>()
        affectedTypes.addAll(AnalysisType.coreTypes())

        for (file in changedFiles) {
            when {
                file.relativePath.contains("build.gradle") || file.relativePath.contains("pom.xml") -> {
                    affectedTypes.add(AnalysisType.PROJECT_STRUCTURE)
                    affectedTypes.add(AnalysisType.TECH_STACK)
                }
                file.relativePath.contains("Controller") || file.relativePath.contains("controller/") -> {
                    affectedTypes.add(AnalysisType.API_ENTRIES)
                }
                file.relativePath.contains("Entity") || file.relativePath.contains("entity/") -> {
                    affectedTypes.add(AnalysisType.DB_ENTITIES)
                }
                file.fileName.contains("Enum") || file.fileName.contains("Type") -> {
                    affectedTypes.add(AnalysisType.ENUMS)
                }
                file.relativePath.contains("application") || file.relativePath.contains("config") -> {
                    affectedTypes.add(AnalysisType.CONFIG_FILES)
                }
            }
        }

        return affectedTypes
    }

    private suspend fun executeAnalysisTask(type: AnalysisType) {
        logger.info("开始执行分析任务: type={}", type)

        currentState = currentState.startAnalyzing(type)
        stateRepository.saveLoopState(currentState)

        val existingResult = stateRepository.getAnalysisResult(projectKey, type)
        val existingTodos = existingResult?.analysisTodos ?: emptyList()

        val taskEntity = AnalysisResultEntity(
            projectKey = projectKey,
            analysisType = type,
            taskStatus = TaskStatus.DOING,
            mdFilePath = type.getMdFilePath()
        )
        stateRepository.saveAnalysisResult(taskEntity)

        try {
            val result = analysisExecutor.execute(
                type = type,
                projectKey = projectKey,
                existingTodos = existingTodos
            )

            saveAnalysisResult(result)

            currentState = currentState.finishAnalyzing(success = result.completeness >= COMPLETENESS_THRESHOLD)
            stateRepository.saveLoopState(currentState)

            stateRepository.updateTaskStatus(projectKey, type, TaskStatus.COMPLETED)
            stateRepository.updateCompleteness(projectKey, type, result.completeness)

            logger.info("分析任务完成: type={}, completeness={}", type, result.completeness)

        } catch (e: Exception) {
            logger.error("分析任务失败: type={}", type, e)
            stateRepository.updateTaskStatus(projectKey, type, TaskStatus.FAILED)
            doomLoopGuard.recordFailure(projectKey)
            throw e
        }
    }

    private suspend fun saveAnalysisResult(result: AnalysisLoopResult) {
        val mdFilePath = projectPath.resolve(".sman/base/${result.type.getMdFilePath()}")
        saveMarkdownFile(mdFilePath, result.content)

        val entity = AnalysisResultEntity(
            projectKey = projectKey,
            analysisType = result.type,
            mdFilePath = mdFilePath.toString(),
            completeness = result.completeness,
            missingSections = result.missingSections,
            analysisTodos = result.todos,
            taskStatus = TaskStatus.COMPLETED,
            toolCallHistory = result.toolCallHistory
        )
        stateRepository.saveAnalysisResult(entity)
    }

    private fun saveMarkdownFile(path: Path, content: String) {
        try {
            val parent = path.parent
            if (!parent.exists()) {
                Files.createDirectories(parent)
            }
            Files.writeString(path, content)
            logger.debug("保存 Markdown 文件: path={}", path)
        } catch (e: Exception) {
            logger.error("保存 Markdown 文件失败: path={}", path, e)
        }
    }

    private fun saveMd5Cache() {
        try {
            val cachePath = projectPath.resolve(".sman/cache/md5_cache.json")
            md5FileTracker.saveCache(cachePath)
        } catch (e: Exception) {
            logger.warn("保存 MD5 缓存失败", e)
        }
    }

    companion object {
        private const val COMPLETENESS_THRESHOLD = 0.8
    }
}
