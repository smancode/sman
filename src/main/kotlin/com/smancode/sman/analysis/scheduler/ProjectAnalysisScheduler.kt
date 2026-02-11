package com.smancode.sman.analysis.scheduler

import com.intellij.openapi.project.Project
import com.smancode.sman.analysis.executor.AnalysisTaskExecutor
import com.smancode.sman.analysis.model.AnalysisType
import com.smancode.sman.analysis.model.ProjectMapManager
import com.smancode.sman.analysis.model.StepState
import com.smancode.sman.analysis.util.ProjectHashCalculator
import com.smancode.sman.ide.service.SmanService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 项目分析调度器
 *
 * 后台定期检查项目状态并执行分析任务
 */
class ProjectAnalysisScheduler(
    private val project: Project,
    private val intervalMs: Long = 300_000  // 默认 5 分钟
) {
    private val logger = LoggerFactory.getLogger(ProjectAnalysisScheduler::class.java)

    // 协程作用域
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 当前作业
    private var currentJob: Job? = null

    // 启用状态
    @Volatile
    private var enabled = true

    // 单次执行标志（防止重复执行）
    private val isExecuting = AtomicBoolean(false)

    // SmanService（延迟初始化）
    private var _smanService: SmanService? = null
    private val smanService: SmanService
        get() {
            if (_smanService == null) {
                _smanService = SmanService.getInstance(project)
            }
            return _smanService!!
        }

    // BGE 端点
    private val bgeEndpoint: String
        get() {
            val storageService = project.getService(com.smancode.sman.ide.service.StorageService::class.java)
            return storageService?.bgeEndpoint ?: ""
        }

    /**
     * 启动后台调度器
     */
    fun start() {
        logger.info("启动项目分析调度器: project={}, interval={}ms", project.name, intervalMs)

        currentJob = scope.launch {
            while (enabled) {
                try {
                    // 检查并执行分析
                    checkAndExecuteAnalysis()

                    // 等待下次检查
                    delay(intervalMs)

                } catch (e: Exception) {
                    logger.error("后台分析循环出错", e)
                    // 出错后等待一段时间再继续
                    delay(60000)  // 1 分钟
                }
            }
        }

        logger.info("项目分析调度器已启动")
    }

    /**
     * 停止后台调度器
     */
    fun stop() {
        logger.info("停止项目分析调度器")
        enabled = false
        currentJob?.cancel()
        currentJob = null
    }

    /**
     * 切换启用状态
     */
    fun toggle() {
        enabled = !enabled
        logger.info("自动分析已{}", if (enabled) "启用" else "禁用")

        if (enabled && currentJob == null) {
            start()
        } else if (!enabled) {
            stop()
        }
    }

    /**
     * 检查是否启用
     */
    fun isEnabled(): Boolean = enabled

    /**
     * 检查并执行分析
     */
    private suspend fun checkAndExecuteAnalysis() {
        // 防止并发执行
        if (!isExecuting.compareAndSet(false, true)) {
            logger.debug("已有分析任务在执行，跳过本次检查")
            return
        }

        try {
            // 获取项目信息
            val projectKey = project.name
            val projectBasePath = project.basePath?.let { Paths.get(it) }
                ?: run {
                    logger.warn("项目基础路径为空")
                    return
                }

            // 计算当前项目 MD5
            val currentMd5 = try {
                ProjectHashCalculator.calculate(project)
            } catch (e: Exception) {
                logger.warn("计算项目 MD5 失败", e)
                return
            }

            // 检查项目是否已注册
            if (!ProjectMapManager.isProjectRegistered(projectKey)) {
                logger.info("首次发现项目，注册并开始分析: {}", projectKey)

                // 注册项目
                ProjectMapManager.registerProject(projectKey, projectBasePath.toString(), currentMd5)

                // 执行完整分析
                executeFullAnalysis(projectKey, projectBasePath)
                return
            }

            // 检查项目是否发生变化
            val entry = ProjectMapManager.getProjectEntry(projectKey)
            if (entry != null && entry.projectMd5 != currentMd5) {
                logger.info("项目内容已变化，更新 MD5: {}", projectKey)
                ProjectMapManager.updateProjectMd5(projectKey, currentMd5)

                // 重置所有分析状态为 PENDING
                resetAllAnalysisStatus(projectKey)
            }

            // 查找需要执行的分析
            val pendingTypes = findPendingAnalysisTypes(projectKey)

            if (pendingTypes.isNotEmpty()) {
                logger.info("发现 {} 个待执行的分析任务: {}", pendingTypes.size, pendingTypes.map { it.key })

                // 创建执行器
                val executor = AnalysisTaskExecutor(
                    projectKey = projectKey,
                    projectBasePath = projectBasePath,
                    smanService = smanService,
                    bgeEndpoint = bgeEndpoint
                )

                // 执行待分析的任务
                for (type in pendingTypes) {
                    if (!enabled) break

                    try {
                        executor.execute(type)
                    } catch (e: Exception) {
                        logger.error("执行分析失败: type={}", type.key, e)
                    }

                    // 每个任务之间稍作等待
                    delay(1000)
                }
            } else {
                logger.debug("没有待执行的分析任务")
            }

        } finally {
            isExecuting.set(false)
        }
    }

    /**
     * 查找待执行的分析类型
     *
     * @param projectKey 项目标识符
     * @return 待执行的分析类型列表
     */
    private fun findPendingAnalysisTypes(projectKey: String): List<AnalysisType> {
        val entry = ProjectMapManager.getProjectEntry(projectKey)
            ?: return AnalysisType.values().toList()

        val pendingTypes = mutableListOf<AnalysisType>()

        for (type in AnalysisType.values()) {
            if (!entry.isAnalysisComplete(type)) {
                pendingTypes.add(type)
            }
        }

        return pendingTypes
    }

    /**
     * 重置所有分析状态为 PENDING
     *
     * @param projectKey 项目标识符
     */
    private fun resetAllAnalysisStatus(projectKey: String) {
        val entry = ProjectMapManager.getProjectEntry(projectKey) ?: return

        // 重置所有未完成的步骤
        val newStatus = com.smancode.sman.analysis.model.AnalysisStatus(
            projectStructure = if (entry.analysisStatus.projectStructure == StepState.COMPLETED) StepState.COMPLETED else StepState.PENDING,
            techStack = if (entry.analysisStatus.techStack == StepState.COMPLETED) StepState.COMPLETED else StepState.PENDING,
            apiEntries = if (entry.analysisStatus.apiEntries == StepState.COMPLETED) StepState.COMPLETED else StepState.PENDING,
            dbEntities = if (entry.analysisStatus.dbEntities == StepState.COMPLETED) StepState.COMPLETED else StepState.PENDING,
            enums = if (entry.analysisStatus.enums == StepState.COMPLETED) StepState.COMPLETED else StepState.PENDING,
            configFiles = if (entry.analysisStatus.configFiles == StepState.COMPLETED) StepState.COMPLETED else StepState.PENDING
        )

        val updatedEntry = entry.copy(analysisStatus = newStatus)
        val map = com.smancode.sman.analysis.model.ProjectMapManager.loadProjectMap()
        val updatedMap = map.copy(
            projects = map.projects + (projectKey to updatedEntry)
        )

        com.smancode.sman.analysis.model.ProjectMapManager.saveProjectMap(updatedMap)
        logger.info("已重置分析状态: {}", projectKey)
    }

    /**
     * 执行完整的项目分析
     *
     * @param projectKey 项目标识符
     * @param projectBasePath 项目基础路径
     */
    private suspend fun executeFullAnalysis(projectKey: String, projectBasePath: Path) {
        val executor = AnalysisTaskExecutor(
            projectKey = projectKey,
            projectBasePath = projectBasePath,
            smanService = smanService,
            bgeEndpoint = bgeEndpoint
        )

        try {
            // 先执行核心分析
            executor.executeCoreAnalysis()

            // 再执行标准分析
            executor.executeStandardAnalysis()

            logger.info("完整分析已完成: {}", projectKey)

        } catch (e: Exception) {
            logger.error("执行完整分析失败: {}", projectKey, e)
        }
    }

    /**
     * 手动触发分析（供外部调用）
     *
     * @param type 分析类型，null 表示执行所有待分析任务
     */
    suspend fun triggerAnalysis(type: AnalysisType? = null) {
        logger.info("手动触发分析: type={}", type?.key ?: "ALL")

        if (type != null) {
            // 执行单个分析
            val projectKey = project.name
            val projectBasePath = project.basePath?.let { Paths.get(it) }
                ?: run {
                    logger.warn("项目基础路径为空")
                    return
                }

            val executor = AnalysisTaskExecutor(
                projectKey = projectKey,
                projectBasePath = projectBasePath,
                smanService = smanService,
                bgeEndpoint = bgeEndpoint
            )

            executor.execute(type)
        } else {
            // 执行所有待分析任务
            checkAndExecuteAnalysis()
        }
    }

    companion object {
        /**
         * 创建调度器实例
         *
         * @param project IntelliJ 项目对象
         * @param intervalMs 检查间隔（毫秒）
         */
        fun create(
            project: Project,
            intervalMs: Long = 300_000
        ): ProjectAnalysisScheduler {
            return ProjectAnalysisScheduler(
                project = project,
                intervalMs = intervalMs
            )
        }
    }
}
