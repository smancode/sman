package com.smancode.smanagent.analysis.service

import com.intellij.openapi.project.Project
import com.smancode.smanagent.analysis.config.VectorDatabaseConfig
import com.smancode.smanagent.analysis.config.VectorDbType
import com.smancode.smanagent.analysis.config.JVectorConfig
import com.smancode.smanagent.analysis.model.ProjectAnalysisResult
import com.smancode.smanagent.analysis.pipeline.ProjectAnalysisPipeline
import com.smancode.smanagent.analysis.repository.ProjectAnalysisRepository
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

        return ProjectAnalysisPipeline(repository, progressCallback)
            .execute(project.name, project)
            .also { cacheResult(it) }
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
            repository.loadAnalysisResult(project.name)?.also { resultCache[project.name] = it }
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
