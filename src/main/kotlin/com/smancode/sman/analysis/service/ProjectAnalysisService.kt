package com.smancode.sman.analysis.service

import com.intellij.openapi.project.Project
import com.smancode.sman.analysis.config.VectorDatabaseConfig
import com.smancode.sman.analysis.config.VectorDbType
import com.smancode.sman.analysis.config.JVectorConfig
import com.smancode.sman.analysis.model.ProjectAnalysisResult
import com.smancode.sman.analysis.pipeline.ProjectAnalysisPipeline
import com.smancode.sman.analysis.repository.ProjectAnalysisRepository
import com.smancode.sman.analysis.util.ProjectHashCalculator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * 项目分析服务
 *
 * 负责：
 * - 对外提供分析接口
 * - 管理 Pipeline 生命周期
 * - 缓存分析结果
 */
class ProjectAnalysisService(
    private val project: Project
) {

    private val logger = LoggerFactory.getLogger(ProjectAnalysisService::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val resultCache = ConcurrentHashMap<String, ProjectAnalysisResult>()

    private val jdbcUrl: String
        get() = buildJdbcUrl(project.name)

    private val repository by lazy { ProjectAnalysisRepository(jdbcUrl) }

    private fun buildJdbcUrl(projectKey: String): String {
        val config = VectorDatabaseConfig.create(
            projectKey = projectKey,
            type = VectorDbType.JVECTOR,
            jvector = JVectorConfig()
        )
        return "jdbc:h2:${config.databasePath};MODE=PostgreSQL;AUTO_SERVER=TRUE"
    }

    private suspend fun shouldSkipAnalysis(): ProjectAnalysisResult? {
        val currentMd5 = calculateProjectMd5() ?: return null
        val cached = getAnalysisResult(forceReload = false) ?: return null
        return cached.takeIf { it.projectMd5 == currentMd5 }
    }

    /**
     * 初始化服务
     */
    suspend fun init() {
        try {
            repository.initTables()
            loadAndCacheResult()
            logger.info("项目分析服务初始化完成")
        } catch (e: Exception) {
            logger.error("项目分析服务初始化失败", e)
            throw e
        }
    }

    private suspend fun loadAndCacheResult() {
        repository.loadAnalysisResult(project.name)?.let { result ->
            resultCache[project.name] = result
            logger.info("加载已有分析结果: projectKey={}, status={}", project.name, result.status)
        }
    }

    /**
     * 执行项目分析
     *
     * @param progressCallback 进度回调
     * @return 分析结果
     */
    suspend fun executeAnalysis(
        progressCallback: ProjectAnalysisPipeline.ProgressCallback? = null
    ): ProjectAnalysisResult {
        logger.info("开始项目分析: projectKey={}", project.name)

        // 检查是否可以使用缓存
        shouldSkipAnalysis()?.let { cached ->
            logger.info("项目未变化，跳过分析: projectKey={}", project.name)
            return cached
        }

        // 执行分析并保存结果
        val result = executeAnalysisPipeline(progressCallback, coreOnly = false)
            .also { it ->
                calculateProjectMd5()?.let { md5 -> it.copy(projectMd5 = md5) } ?: it
            }
            .also { updatedResult ->
                repository.saveAnalysisResult(updatedResult)
                cacheResult(updatedResult)
            }

        return result
    }

    /**
     * 执行核心步骤分析（仅 project_structure + tech_stack_detection）
     *
     * 用于首次配置后的快速初始化
     *
     * @param progressCallback 进度回调
     * @return 核心步骤的分析结果
     */
    suspend fun executeCoreSteps(
        progressCallback: ProjectAnalysisPipeline.ProgressCallback? = null
    ): ProjectAnalysisResult {
        logger.info("开始核心步骤分析: projectKey={}", project.name)

        // 检查核心步骤是否已完成
        val cached = getAnalysisResult(forceReload = false)
        val coreStepsCompleted = cached?.steps?.all { (stepName, stepResult) ->
            // 核心步骤：project_structure, tech_stack_detection
            val isCoreStep = stepName == "project_structure" || stepName == "tech_stack_detection"
            !isCoreStep || stepResult.status == com.smancode.sman.analysis.model.StepStatus.COMPLETED
        } ?: false

        if (coreStepsCompleted) {
            logger.info("核心步骤已完成，跳过分析: projectKey={}", project.name)
            return cached!!
        }

        // 执行核心步骤分析
        val result = executeAnalysisPipeline(progressCallback, coreOnly = true)

        // 保存结果（不标记为完成，因为还有其他步骤未执行）
        repository.saveAnalysisResult(result)
        cacheResult(result)

        return result
    }

    private suspend fun executeAnalysisPipeline(
        progressCallback: ProjectAnalysisPipeline.ProgressCallback? = null,
        coreOnly: Boolean = false
    ): ProjectAnalysisResult {
        return ProjectAnalysisPipeline(
            repository = repository,
            progressCallback = progressCallback,
            projectKey = project.name,
            project = project,
            coreOnly = coreOnly
        ).execute(project.name, project)
    }

    private fun calculateProjectMd5(): String? {
        return try {
            ProjectHashCalculator.calculate(project)
        } catch (e: Exception) {
            logger.warn("计算项目 MD5 失败，将执行分析", e)
            null
        }
    }

    private fun cacheResult(result: ProjectAnalysisResult) {
        resultCache[project.name] = result
    }

    /**
     * 获取分析结果
     *
     * @param forceReload 是否强制从数据库重新加载
     * @return 分析结果，如果不存在返回 null
     */
    suspend fun getAnalysisResult(forceReload: Boolean = false): ProjectAnalysisResult? {
        return if (forceReload) {
            repository.loadAnalysisResult(project.name)?.also { result ->
                resultCache[project.name] = result
            }
        } else {
            resultCache[project.name]
        }
    }

    /**
     * 异步执行分析（不阻塞调用方）
     *
     * @param progressCallback 进度回调
     */
    fun executeAnalysisAsync(progressCallback: ProjectAnalysisPipeline.ProgressCallback? = null) {
        scope.launch {
            try {
                executeAnalysis(progressCallback)
            } catch (e: Exception) {
                logger.error("异步项目分析失败", e)
            }
        }
    }

    /**
     * 获取分析状态
     */
    suspend fun getAnalysisStatus() = getAnalysisResult()?.status

    /**
     * 删除分析结果
     */
    suspend fun deleteAnalysisResult() {
        repository.deleteAnalysisResult(project.name)
        resultCache.remove(project.name)
        logger.info("删除分析结果: projectKey={}", project.name)
    }

    /**
     * 关闭服务
     */
    fun close() {
        logger.info("关闭项目分析服务")
    }
}
