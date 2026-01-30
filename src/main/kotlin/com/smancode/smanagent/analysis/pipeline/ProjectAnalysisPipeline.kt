package com.smancode.smanagent.analysis.pipeline

import com.smancode.smanagent.analysis.config.VectorDatabaseConfig
import com.smancode.smanagent.analysis.config.VectorDbType
import com.smancode.smanagent.analysis.config.JVectorConfig
import com.smancode.smanagent.analysis.database.JVectorStore
import com.smancode.smanagent.analysis.model.ProjectAnalysisResult
import com.smancode.smanagent.analysis.model.StepStatus
import com.smancode.smanagent.analysis.repository.ProjectAnalysisRepository
import com.smancode.smanagent.analysis.step.AnalysisContext
import com.smancode.smanagent.analysis.step.AnalysisStep
import com.smancode.smanagent.analysis.step.ApiEntryScanningStep
import com.smancode.smanagent.analysis.step.ASTScanningStep
import com.smancode.smanagent.analysis.step.CommonClassScanningStep
import com.smancode.smanagent.analysis.step.DbEntityDetectionStep
import com.smancode.smanagent.analysis.step.EnumScanningStep
import com.smancode.smanagent.analysis.step.ProjectStructureStep
import com.smancode.smanagent.analysis.step.TechStackDetectionStep
import com.smancode.smanagent.analysis.step.XmlCodeScanningStep
import com.smancode.smanagent.analysis.vectorization.ProjectInfoVectorizationService
import com.smancode.smanagent.ide.service.storageService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/**
 * 项目分析 Pipeline
 *
 * 负责编排各个分析步骤的执行，并向量化结果
 */
class ProjectAnalysisPipeline(
    private val repository: ProjectAnalysisRepository,
    private val progressCallback: ProgressCallback? = null,
    private val projectKey: String = "",
    private val project: com.intellij.openapi.project.Project? = null
) {

    private val logger = LoggerFactory.getLogger(ProjectAnalysisPipeline::class.java)

    // 向量化服务（懒加载）
    private val vectorizationService by lazy {
        project?.let { proj ->
            val storage = proj.storageService()
            val config = VectorDatabaseConfig.create(
                projectKey = projectKey,
                type = VectorDbType.JVECTOR,
                jvector = JVectorConfig()
            )
            val vectorStore = JVectorStore(config)
            ProjectInfoVectorizationService(
                projectKey = projectKey,
                vectorStore = vectorStore,
                bgeEndpoint = storage.bgeEndpoint
            )
        }
    }

    // 分析步骤列表（按执行顺序）
    private val steps: List<AnalysisStep> = listOf(
        ProjectStructureStep(),
        TechStackDetectionStep(),
        ASTScanningStep(),
        DbEntityDetectionStep(),
        ApiEntryScanningStep(),
        EnumScanningStep(),
        CommonClassScanningStep(),
        XmlCodeScanningStep()
    )

    /**
     * 执行完整的项目分析
     *
     * @param projectKey 项目标识符
     * @param project IntelliJ 项目对象
     * @return 分析结果
     */
    suspend fun execute(projectKey: String, project: com.intellij.openapi.project.Project): ProjectAnalysisResult {
        return withContext(Dispatchers.IO) {
            logger.info("开始项目分析: projectKey={}", projectKey)

            val context = AnalysisContext(projectKey, project)
            var result = ProjectAnalysisResult.create(projectKey).markStarted()

            repository.saveAnalysisResult(result)

            result = try {
                executeAllSteps(result, context)
            } catch (e: Exception) {
                logger.error("项目分析失败: projectKey={}", projectKey, e)
                result.markFailed()
            }.also { repository.saveAnalysisResult(it) }

            logger.info("项目分析完成: projectKey={}, steps={}", projectKey, result.steps.size)
            result
        }
    }

    private suspend fun executeAllSteps(result: ProjectAnalysisResult, context: AnalysisContext): ProjectAnalysisResult {
        var currentResult = result
        for (step in steps) {
            currentResult = if (step.canExecute(context)) {
                executeAndVectorizeStep(currentResult, context, step)
            } else {
                logger.info("跳过步骤: {}", step.name)
                currentResult
            }
        }
        return currentResult.markCompleted()
    }

    private suspend fun executeAndVectorizeStep(result: ProjectAnalysisResult, context: AnalysisContext, step: AnalysisStep): ProjectAnalysisResult {
        val updatedResult = executeStep(result, context, step)
        repository.saveAnalysisResult(updatedResult)

        // 向量化步骤结果（AST步骤除外，因为太大）
        if (step.name != "ast_scanning" && updatedResult.steps[step.name]?.status == StepStatus.COMPLETED) {
            vectorizeStepData(step.name, updatedResult.steps[step.name]?.data)
        }

        return updatedResult
    }

    /**
     * 向量化步骤数据
     */
    private suspend fun vectorizeStepData(stepName: String, data: String?) {
        if (data == null) return

        try {
            val service = vectorizationService ?: run {
                logger.warn("向量化服务未初始化，跳过向量化: step={}", stepName)
                return
            }

            when (stepName) {
                "project_structure" -> service.vectorizeProjectStructure(data)
                "tech_stack_detection" -> service.vectorizeTechStack(data)
                "db_entity_detection" -> service.vectorizeDbEntities(data)
                "api_entry_scanning" -> service.vectorizeApiEntries(data)
                "enum_scanning" -> service.vectorizeEnums(data)
                "common_class_scanning" -> service.vectorizeCommonClasses(data)
                "xml_code_scanning" -> service.vectorizeXmlCodes(data)
                else -> logger.debug("无需向量化步骤: {}", stepName)
            }
        } catch (e: Exception) {
            logger.warn("向量化步骤数据失败: step={}, error={}", stepName, e.message)
        }
    }

    /**
     * 执行单个步骤
     */
    private suspend fun executeStep(
        result: ProjectAnalysisResult,
        context: AnalysisContext,
        step: AnalysisStep
    ): ProjectAnalysisResult {
        logger.info("执行步骤: {}", step.name)
        notifyProgressStart(step)

        val stepResult = executeStepSafely(step, context)

        notifyProgressComplete(step, stepResult)

        return result.updateStep(stepResult)
    }

    private suspend fun executeStepSafely(step: AnalysisStep, context: AnalysisContext): com.smancode.smanagent.analysis.model.StepResult {
        return try {
            val result = step.execute(context)
            logger.info("步骤完成: {}, status={}", step.name, result.status)
            result
        } catch (e: Exception) {
            logger.error("步骤执行失败: {}", step.name, e)
            com.smancode.smanagent.analysis.model.StepResult.create(step.name, step.description).markFailed(e.message ?: "执行失败")
        }
    }

    private fun notifyProgressStart(step: AnalysisStep) {
        progressCallback?.onStepStart(step.name, step.description)
    }

    private fun notifyProgressComplete(step: AnalysisStep, result: com.smancode.smanagent.analysis.model.StepResult) {
        when (result.status) {
            com.smancode.smanagent.analysis.model.StepStatus.COMPLETED -> progressCallback?.onStepComplete(step.name, result)
            com.smancode.smanagent.analysis.model.StepStatus.FAILED -> progressCallback?.onStepFailed(step.name, result.error ?: "执行失败")
            else -> {}
        }
    }

    /**
     * 进度回调接口
     */
    interface ProgressCallback {
        fun onStepStart(stepName: String, description: String)
        fun onStepComplete(stepName: String, result: com.smancode.smanagent.analysis.model.StepResult)
        fun onStepFailed(stepName: String, error: String)
    }
}
