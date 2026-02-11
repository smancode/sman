package com.smancode.sman.analysis.pipeline

import com.smancode.sman.analysis.config.VectorDatabaseConfig
import com.smancode.sman.analysis.config.VectorDbType
import com.smancode.sman.analysis.config.JVectorConfig
import com.smancode.sman.analysis.database.TieredVectorStore
import com.smancode.sman.analysis.model.ProjectAnalysisResult
import com.smancode.sman.analysis.model.StepStatus
import com.smancode.sman.analysis.repository.ProjectAnalysisRepository
import com.smancode.sman.analysis.step.AnalysisContext
import com.smancode.sman.analysis.step.AnalysisStep
import com.smancode.sman.analysis.step.ApiEntryScanningStep
import com.smancode.sman.analysis.step.CommonClassScanningStep
import com.smancode.sman.analysis.step.DbEntityDetectionStep
import com.smancode.sman.analysis.step.EnumScanningStep
import com.smancode.sman.analysis.step.ProjectStructureStep
import com.smancode.sman.analysis.step.TechStackDetectionStep
import com.smancode.sman.analysis.step.XmlCodeScanningStep
import com.smancode.sman.analysis.step.ExternalApiScanningStep
import com.smancode.sman.analysis.step.LlmCodeVectorizationStep
import com.smancode.sman.analysis.vectorization.ProjectInfoVectorizationService
import com.smancode.sman.smancode.llm.config.LlmEndpoint
import com.smancode.sman.smancode.llm.config.LlmPoolConfig
import com.smancode.sman.smancode.llm.LlmService
import com.smancode.sman.ide.service.storageService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/**
 * 项目分析 Pipeline
 *
 * 负责编排各个分析步骤的执行，并向量化结果
 *
 * @param coreOnly 是否仅执行核心步骤（project_structure + tech_stack_detection）
 */
class ProjectAnalysisPipeline(
    private val repository: ProjectAnalysisRepository,
    private val progressCallback: ProgressCallback? = null,
    private val projectKey: String = "",
    private val project: com.intellij.openapi.project.Project? = null,
    private val coreOnly: Boolean = false
) {

    private val logger = LoggerFactory.getLogger(ProjectAnalysisPipeline::class.java)

    companion object {
        // 步骤名称常量
        private const val STEP_PROJECT_STRUCTURE = "project_structure"
        private const val STEP_TECH_STACK = "tech_stack_detection"
        private const val STEP_DB_ENTITIES = "db_entity_detection"
        private const val STEP_API_ENTRIES = "api_entry_scanning"
        private const val STEP_EXTERNAL_APIS = "external_api_scanning"
        private const val STEP_ENUMS = "enum_scanning"
        private const val STEP_COMMON_CLASSES = "common_class_scanning"
        private const val STEP_XML_CODES = "xml_code_scanning"

        // 核心步骤名称（阻塞步骤）
        private val CORE_STEPS = setOf(STEP_PROJECT_STRUCTURE, STEP_TECH_STACK)
    }

    // 向量化服务（懒加载）
    private val vectorizationService by lazy {
        project?.let { proj ->
            val storage = proj.storageService()

            // 优先级: 用户设置 > 本地检测 > 配置文件
            val bgeEndpoint = storage.bgeEndpoint.takeIf { it.isNotBlank() }
                ?: detectLocalBgeService()?.also { endpoint ->
                    storage.bgeEndpoint = endpoint
                    logger.info("自动检测到 BGE 服务: $endpoint")
                }
                ?: com.smancode.sman.config.SmanConfig.bgeM3Config?.endpoint?.also { endpoint ->
                    logger.info("使用配置文件中的 BGE 端点: $endpoint")
                    storage.bgeEndpoint = endpoint
                }

            if (bgeEndpoint == null) {
                logger.warn("BGE 端点未配置且未检测到本地服务，向量化功能将不可用")
                return@lazy null
            }

            val config = VectorDatabaseConfig.create(
                projectKey = projectKey,
                type = VectorDbType.JVECTOR,
                jvector = JVectorConfig()
            )
            val vectorStore = TieredVectorStore(config)

            ProjectInfoVectorizationService(
                projectKey = projectKey,
                vectorStore = vectorStore,
                bgeEndpoint = bgeEndpoint
            )
        } ?: run {
            logger.warn("Project 为 null，无法初始化向量化服务")
            null
        }
    }

    /**
     * 检测本地 BGE 服务
     */
    private fun detectLocalBgeService(): String? {
        val defaultPorts = listOf(8000, 8001, 5000)
        return defaultPorts.firstNotNullOfOrNull { port ->
            try {
                java.net.Socket("localhost", port).use {
                    logger.info("检测到 BGE 服务运行在端口 $port")
                    "http://localhost:$port"
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    // 分析步骤列表（按执行顺序）
    private val steps: List<AnalysisStep> = listOf(
        ProjectStructureStep(),
        TechStackDetectionStep(),
        DbEntityDetectionStep(),
        ApiEntryScanningStep(),
        ExternalApiScanningStep(),
        EnumScanningStep(),
        CommonClassScanningStep(),
        XmlCodeScanningStep()
        // 注意：LlmCodeVectorizationStep 不在此列表中，它在完整模式下单独执行（见 executeAllSteps）
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

            // 计算当前项目 MD5
            val currentMd5 = try {
                com.smancode.sman.analysis.util.ProjectHashCalculator.calculate(project)
            } catch (e: Exception) {
                logger.warn("计算项目 MD5 失败", e)
                null
            }

            // 加载已缓存的分析结果
            val cachedAnalysis = repository.loadAnalysisResult(projectKey)

            val context = AnalysisContext(
                projectKey = projectKey,
                project = project,
                cachedAnalysis = cachedAnalysis,
                currentProjectMd5 = currentMd5
            )

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
        // 根据模式选择要执行的步骤
        val stepsToExecute = if (coreOnly) {
            // 核心模式：仅执行前 2 个核心步骤
            steps.filter { it.name in CORE_STEPS }
        } else {
            // 完整模式：执行所有标准分析步骤
            steps
        }

        // 执行选定的步骤
        val standardStepsCompleted = stepsToExecute.fold(result) { currentResult, step ->
            if (step.canExecute(context)) {
                executeAndVectorizeStep(currentResult, context, step)
            } else {
                logger.info("跳过步骤: {}", step.name)
                currentResult
            }
        }.markCompleted()

        // 完整模式下，最后执行 LLM 驱动的代码向量化
        if (!coreOnly && shouldExecuteLlmVectorization()) {
            logger.info("开始执行 LLM 代码向量化步骤")
            val llmStep = createLlmCodeVectorizationStep(context)
            val llmResult = executeStep(standardStepsCompleted, context, llmStep)
            repository.saveAnalysisResult(llmResult)
        } else if (coreOnly) {
            logger.info("核心模式：跳过 LLM 代码向量化步骤")
        } else {
            logger.info("跳过 LLM 代码向量化步骤: BGE 端点未配置")
        }

        return standardStepsCompleted
    }

    /**
     * 判断是否应该执行 LLM 向量化步骤
     */
    private fun shouldExecuteLlmVectorization(): Boolean {
        // 检查 BGE 端点是否配置
        val bgeEndpoint = project?.let { proj ->
            proj.storageService().bgeEndpoint
        } ?: return false

        return bgeEndpoint.isNotBlank()
    }

    /**
     * 创建 LLM 代码向量化步骤
     */
    private fun createLlmCodeVectorizationStep(context: AnalysisContext): LlmCodeVectorizationStep {
        val llmService = createLlmService()
        val bgeEndpoint = project?.storageService()?.bgeEndpoint ?: ""

        val basePath = context.project.basePath ?: throw IllegalStateException("项目基础路径为空")
        val projectPath = java.nio.file.Paths.get(basePath)

        return LlmCodeVectorizationStep(
            projectPath = projectPath,
            projectKey = projectKey,
            llmService = llmService,
            bgeEndpoint = bgeEndpoint
        )
    }

    /**
     * 创建 LLM 服务
     */
    private fun createLlmService(): LlmService {
        val endpoint = LlmEndpoint().apply {
            baseUrl = com.smancode.sman.config.SmanConfig.llmBaseUrl
            apiKey = com.smancode.sman.config.SmanConfig.llmApiKey
            model = com.smancode.sman.config.SmanConfig.llmModelName
            maxTokens = com.smancode.sman.config.SmanConfig.llmResponseMaxTokens
        }

        val poolConfig = LlmPoolConfig().apply {
            endpoints.add(endpoint)
            retry.maxRetries = com.smancode.sman.config.SmanConfig.llmRetryMax
            retry.baseDelay = com.smancode.sman.config.SmanConfig.llmRetryBaseDelay
        }

        return LlmService(poolConfig)
    }

    private suspend fun executeAndVectorizeStep(result: ProjectAnalysisResult, context: AnalysisContext, step: AnalysisStep): ProjectAnalysisResult {
        val updatedResult = executeStep(result, context, step)
        repository.saveAnalysisResult(updatedResult)

        // 向量化步骤结果
        if (updatedResult.steps[step.name]?.status == StepStatus.COMPLETED) {
            vectorizeStepData(step.name, updatedResult.steps[step.name]?.data)
        }

        return updatedResult
    }

    /**
     * 向量化步骤数据
     */
    private suspend fun vectorizeStepData(stepName: String, data: String?) {
        if (data == null) {
            logger.warn("向量化数据为空，跳过: stepName={}", stepName)
            return
        }

        val service = vectorizationService ?: run {
            logger.debug("向量化服务未初始化，跳过向量化: step={}", stepName)
            return
        }

        val vectorizeAction = when (stepName) {
            STEP_PROJECT_STRUCTURE -> service::vectorizeProjectStructure
            STEP_TECH_STACK -> service::vectorizeTechStack
            STEP_DB_ENTITIES -> service::vectorizeDbEntitiesIndividually
            STEP_API_ENTRIES -> service::vectorizeApiEntriesIndividually
            STEP_EXTERNAL_APIS -> service::vectorizeExternalApis
            STEP_ENUMS -> service::vectorizeEnumsIndividually
            STEP_COMMON_CLASSES -> service::vectorizeCommonClasses
            STEP_XML_CODES -> service::vectorizeXmlCodes
            else -> null
        }

        vectorizeAction?.let {
            try {
                it.invoke(data)
            } catch (e: Exception) {
                logger.error("向量化步骤数据失败: step={}, error={}", stepName, e.message, e)
            }
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

    private suspend fun executeStepSafely(step: AnalysisStep, context: AnalysisContext): com.smancode.sman.analysis.model.StepResult {
        return try {
            val result = step.execute(context)
            logger.info("步骤完成: {}, status={}", step.name, result.status)
            result
        } catch (e: Exception) {
            logger.error("步骤执行失败: {}", step.name, e)
            com.smancode.sman.analysis.model.StepResult.create(step.name, step.description).markFailed(e.message ?: "执行失败")
        }
    }

    private fun notifyProgressStart(step: AnalysisStep) {
        progressCallback?.onStepStart(step.name, step.description)
    }

    private fun notifyProgressComplete(step: AnalysisStep, result: com.smancode.sman.analysis.model.StepResult) {
        when (result.status) {
            com.smancode.sman.analysis.model.StepStatus.COMPLETED -> progressCallback?.onStepComplete(step.name, result)
            com.smancode.sman.analysis.model.StepStatus.FAILED -> progressCallback?.onStepFailed(step.name, result.error ?: "执行失败")
            else -> {}
        }
    }

    /**
     * 进度回调接口
     */
    interface ProgressCallback {
        fun onStepStart(stepName: String, description: String)
        fun onStepComplete(stepName: String, result: com.smancode.sman.analysis.model.StepResult)
        fun onStepFailed(stepName: String, error: String)
    }
}
