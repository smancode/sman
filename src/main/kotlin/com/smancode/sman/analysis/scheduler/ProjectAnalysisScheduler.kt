package com.smancode.sman.analysis.scheduler

import com.intellij.openapi.project.Project
import com.smancode.sman.analysis.coordination.CodeVectorizationCoordinator
import com.smancode.sman.analysis.coordination.VectorizationResult
import com.smancode.sman.config.SmanConfig
import com.smancode.sman.ide.service.storageService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.nio.file.Paths

/**
 * 项目分析调度器
 *
 * 后台定期检查项目状态并执行分析任务
 *
 * 核心职责：
 * 代码向量化（扫描 .java → 生成 MD → 向量化）- 通过 CodeVectorizationCoordinator
 */
class ProjectAnalysisScheduler(
    private val project: Project,
    private val intervalMs: Long = 300000
) {
    private val logger = LoggerFactory.getLogger(ProjectAnalysisScheduler::class.java)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var currentJob: Job? = null

    // BGE 端点配置
    private val bgeEndpoint: String = project.storageService().bgeEndpoint

    @Volatile
    private var enabled: Boolean = true

    fun start() {
        logger.info("启动项目分析调度器: project={}, interval={}ms", project.name, intervalMs)

        currentJob = scope.launch {
            while (enabled) {
                try {
                    checkAndExecuteAnalysis()
                    delay(intervalMs)
                } catch (e: Exception) {
                    logger.error("后台分析循环出错", e)
                    delay(60000)
                }
            }
        }

        logger.info("项目分析调度器已启动")
    }

    fun stop() {
        logger.info("停止项目分析调度器")
        enabled = false
        currentJob?.cancel()
        currentJob = null
    }

    fun toggle() {
        enabled = !enabled
        logger.info("自动分析已{}", if (enabled) "启用" else "禁用")

        if (enabled && currentJob == null) {
            start()
        } else if (!enabled) {
            stop()
        }
    }

    private suspend fun checkAndExecuteAnalysis() {
        // 执行代码向量化（核心！扫描 .java → 生成 MD → 向量化）
        if (enabled) {
            val projectKey = project.name
            val projectBasePath = project.basePath?.let { Paths.get(it) }
            if (projectBasePath != null) {
                executeCodeVectorization(projectKey, projectBasePath)
            }
        }
    }

    /**
     * 执行代码向量化（核心能力）
     *
     * 流程：
     * 1. 扫描所有 .java 文件
     * 2. 检查 MD5 缓存，识别变化的文件
     * 3. 对变化的文件：LLM 分析 → 生成 MD → 向量化 → 更新 MD5 缓存
     * 4. 对已有 MD 文件：直接向量化（跳过 LLM）
     */
    private suspend fun executeCodeVectorization(projectKey: String, projectBasePath: Path) {
        logger.info("开始执行代码向量化: projectKey={}", projectKey)

        try {
            val llmService = SmanConfig.createLlmService()
            val coordinator = CodeVectorizationCoordinator(
                projectKey = projectKey,
                projectPath = projectBasePath,
                llmService = llmService,
                bgeEndpoint = bgeEndpoint
            )

            val result: VectorizationResult = coordinator.vectorizeProject(forceUpdate = false)

            logger.info("代码向量化完成: projectKey={}, 处理={}, 跳过={}, 向量数={}, 错误={}, 耗时={}ms",
                projectKey,
                result.processedFiles,
                result.skippedFiles,
                result.totalVectors,
                result.errors.size,
                result.elapsedTimeMs)

            // 打印错误详情
            if (result.errors.isNotEmpty()) {
                result.errors.forEach { error ->
                    logger.warn("向量化错误: file={}, error={}", error.file.fileName, error.error)
                }
            }

            coordinator.close()

        } catch (e: Exception) {
            logger.error("代码向量化失败: projectKey={}", projectKey, e)
        }
    }

    /**
     * 立即触发分析（供外部调用）
     *
     * 用于配置保存后立即开始分析，无需等待调度器轮询
     */
    fun triggerImmediateAnalysis() {
        logger.info("立即触发项目分析: project={}", project.name)

        scope.launch {
            try {
                checkAndExecuteAnalysis()
            } catch (e: Exception) {
                logger.error("立即分析执行失败", e)
            }
        }
    }

    /**
     * 强制重新向量化所有代码（忽略 MD5 缓存）
     *
     * 用于手动触发全量更新
     */
    fun forceRevectorize() {
        logger.info("强制重新向量化所有代码: project={}", project.name)

        scope.launch {
            try {
                val projectKey = project.name
                val projectBasePath = project.basePath?.let { Paths.get(it) }
                    ?: run {
                        logger.warn("项目基础路径为空")
                        return@launch
                    }

                val llmService = SmanConfig.createLlmService()
                val coordinator = CodeVectorizationCoordinator(
                    projectKey = projectKey,
                    projectPath = projectBasePath,
                    llmService = llmService,
                    bgeEndpoint = bgeEndpoint
                )

                val result = coordinator.vectorizeProject(forceUpdate = true)

                logger.info("强制向量化完成: projectKey={}, 处理={}, 向量数={}",
                    projectKey, result.processedFiles, result.totalVectors)

                coordinator.close()

            } catch (e: Exception) {
                logger.error("强制向量化失败", e)
            }
        }
    }

    /**
     * 仅从已有 MD 文件向量化（不调用 LLM）
     *
     * 用于修复向量库数据，直接读取 .sman/md/classes/ 下的 MD 文件
     */
    fun revectorizeFromExistingMd() {
        logger.info("从已有 MD 文件重新向量化: project={}", project.name)

        scope.launch {
            try {
                val projectKey = project.name
                val projectBasePath = project.basePath?.let { Paths.get(it) }
                    ?: run {
                        logger.warn("项目基础路径为空")
                        return@launch
                    }

                val llmService = SmanConfig.createLlmService()
                val coordinator = CodeVectorizationCoordinator(
                    projectKey = projectKey,
                    projectPath = projectBasePath,
                    llmService = llmService,
                    bgeEndpoint = bgeEndpoint
                )

                val result = coordinator.vectorizeFromExistingMd()

                logger.info("从 MD 文件向量化完成: projectKey={}, 处理={}, 向量数={}",
                    projectKey, result.processedFiles, result.totalVectors)

                coordinator.close()

            } catch (e: Exception) {
                logger.error("从 MD 文件向量化失败", e)
            }
        }
    }

    companion object {
        fun create(
            project: Project,
            intervalMs: Long = 300000
        ): ProjectAnalysisScheduler {
            return ProjectAnalysisScheduler(
                project = project,
                intervalMs = intervalMs
            )
        }
    }
}
