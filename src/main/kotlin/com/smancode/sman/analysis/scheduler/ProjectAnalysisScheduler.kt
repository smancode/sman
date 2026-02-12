package com.smancode.sman.analysis.scheduler

import com.intellij.openapi.project.Project
import com.smancode.sman.analysis.executor.AnalysisTaskExecutor
import com.smancode.sman.analysis.model.AnalysisType
import com.smancode.sman.analysis.model.ProjectMapManager
import com.smancode.sman.analysis.model.StepState
import com.smancode.sman.analysis.util.ProjectHashCalculator
import com.smancode.sman.ide.service.SmanService
import com.smancode.sman.ide.service.StorageService
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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 项目分析调度器
 *
 * 后台定期检查项目状态并执行分析任务
 */
class ProjectAnalysisScheduler(
    private val project: Project,
    private val intervalMs: Long = 300000
) {
    private val logger = LoggerFactory.getLogger(ProjectAnalysisScheduler::class.java)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var currentJob: Job? = null

    // Sman 服务实例（用于执行分析任务）
    private val smanService: SmanService = SmanService.getInstance(project)

    // BGE 端点配置
    private val bgeEndpoint: String = project.storageService().bgeEndpoint

    @Volatile
    private var enabled: Boolean = true
        get() {
            return enabled
        }
        private set(value: Boolean) {
            field = value
            logger.info("自动分析已{}", if (value) "启用" else "禁用")
        }

    private val isExecuting = AtomicBoolean(false)

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
        if (!isExecuting.compareAndSet(false, true)) {
            logger.debug("已有分析任务在执行，跳过本次检查")
            return
        }

        try {
            val projectKey = project.name
            val projectBasePath = project.basePath?.let { Paths.get(it) }
                ?: run {
                    logger.warn("项目基础路径为空")
                    return
                }

            val currentMd5 = try {
                ProjectHashCalculator.calculate(project)
            } catch (e: Exception) {
                logger.warn("计算项目 MD5 失败", e)
                return
            }

            if (!ProjectMapManager.isProjectRegistered(projectKey)) {
                logger.info("首次发现项目，注册并开始分析: {}", projectKey)
                ProjectMapManager.registerProject(projectKey, projectBasePath.toString(), currentMd5)
                executeFullAnalysis(projectKey, projectBasePath)
                return
            }

            val entry = ProjectMapManager.getProjectEntry(projectKey)
            if (entry != null && entry.projectMd5 != currentMd5) {
                logger.info("项目内容已变化，更新 MD5: {}", projectKey)
                ProjectMapManager.updateProjectMd5(projectKey, currentMd5)
                resetAllAnalysisStatus(projectKey)
            }

            val pendingTypes = findPendingAnalysisTypes(projectKey)

            if (pendingTypes.isNotEmpty()) {
                logger.info("发现 {} 个待执行的分析任务: {}", pendingTypes.size, pendingTypes.map { it.key })
                val executor = AnalysisTaskExecutor(
                    projectKey = projectKey,
                    projectBasePath = projectBasePath,
                    smanService = smanService,
                    bgeEndpoint = bgeEndpoint
                )

                for (type in pendingTypes) {
                    if (!enabled) break
                    try {
                        executor.execute(type)
                    } catch (e: Exception) {
                        logger.error("执行分析失败: type={}", type.key, e)
                    }
                    delay(1000)
                }
            } else {
                logger.debug("没有待执行的分析任务")
            }
        } finally {
            isExecuting.set(false)
        }
    }

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

    private fun resetAllAnalysisStatus(projectKey: String) {
        val entry = ProjectMapManager.getProjectEntry(projectKey) ?: return

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

    private suspend fun executeFullAnalysis(projectKey: String, projectBasePath: Path) {
        val executor = AnalysisTaskExecutor(
            projectKey = projectKey,
            projectBasePath = projectBasePath,
            smanService = smanService,
            bgeEndpoint = bgeEndpoint
        )

        try {
            executor.executeCoreAnalysis()
            executor.executeStandardAnalysis()
            logger.info("完整分析已完成: {}", projectKey)
        } catch (e: Exception) {
            logger.error("执行完整分析失败: {}", projectKey, e)
        }
    }

    suspend fun triggerAnalysis(type: AnalysisType? = null) {
        logger.info("手动触发分析: type={}", type?.key ?: "ALL")

        if (type != null) {
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
            checkAndExecuteAnalysis()
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
