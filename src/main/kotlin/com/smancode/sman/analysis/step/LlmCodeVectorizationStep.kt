package com.smancode.sman.analysis.step

import com.smancode.sman.analysis.coordination.CodeVectorizationCoordinator
import com.smancode.sman.analysis.model.StepResult
import com.smancode.sman.config.SmanConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * LLM 驱动的代码向量化步骤
 *
 * 在所有分析步骤完成后，执行：
 * 1. 扫描项目中的所有源代码文件
 * 2. 检查文件 MD5 变化
 * 3. 调用 LLM 分析变化的文件
 * 4. 生成 .md 文档并持久化
 * 5. 解析 .md 文档为向量片段
 * 6. 向量化并存储到向量数据库
 *
 * 配置控制：
 * - analysis.llm.vectorization.enabled: 是否启用此步骤
 * - analysis.llm.vectorization.full.refresh: 是否全量刷新（忽略 MD5 缓存）
 */
class LlmCodeVectorizationStep(
    private val projectPath: Path,
    private val projectKey: String,
    private val llmService: com.smancode.sman.smancode.llm.LlmService,
    private val bgeEndpoint: String
) : AnalysisStep {

    private val logger = LoggerFactory.getLogger(LlmCodeVectorizationStep::class.java)

    override val name: String = "llm_code_vectorization"
    override val description: String = "LLM 驱动的代码向量化"

    override suspend fun canExecute(context: AnalysisContext): Boolean {
        // 检查是否启用了 LLM 向量化
        if (!SmanConfig.analysisLlmVectorizationEnabled) {
            logger.info("LLM 代码向量化未启用（analysis.llm.vectorization.enabled=false）")
            return false
        }

        // 检查 BGE 端点是否配置
        if (bgeEndpoint.isBlank()) {
            logger.info("LLM 代码向量化跳过：BGE 端点未配置")
            return false
        }

        return true
    }

    override suspend fun execute(context: AnalysisContext): StepResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()

        try {
            logger.info("开始 LLM 驱动的代码向量化: projectKey={}, path={}, fullRefresh={}",
                projectKey, projectPath, SmanConfig.analysisLlmVectorizationFullRefresh)

            // 创建向量化协调器
            val coordinator = CodeVectorizationCoordinator(
                projectKey = projectKey,
                projectPath = projectPath,
                llmService = llmService,
                bgeEndpoint = bgeEndpoint
            )

            // 执行向量化（使用配置决定是否全量刷新）
            val forceUpdate = SmanConfig.analysisLlmVectorizationFullRefresh
            val result = coordinator.vectorizeProject(forceUpdate = forceUpdate)

            // 关闭协调器
            coordinator.close()

            val elapsedTime = System.currentTimeMillis() - startTime

            // 构建结果数据
            val resultData = buildString {
                appendLine("LLM 代码向量化完成:")
                appendLine("  总文件数: ${result.totalFiles}")
                appendLine("  处理文件数: ${result.processedFiles}")
                appendLine("  跳过文件数: ${result.skippedFiles}")
                appendLine("  向量总数: ${result.totalVectors}")
                appendLine("  错误数: ${result.errors.size}")
                appendLine("  耗时: ${elapsedTime}ms")
                appendLine("  全量刷新: $forceUpdate")

                if (result.errors.isNotEmpty()) {
                    appendLine()
                    appendLine("错误文件:")
                    result.errors.take(10).forEach { (file, error) ->
                        appendLine("  - ${file.fileName}: $error")
                    }
                    if (result.errors.size > 10) {
                        appendLine("  ... 还有 ${result.errors.size - 10} 个错误")
                    }
                }
            }

            logger.info("LLM 代码向量化完成: {}", resultData)

            StepResult.create(name, description)
                .markCompleted()
                .copy(data = resultData)

        } catch (e: Exception) {
            logger.error("LLM 代码向量化失败", e)
            StepResult.create(name, description)
                .markFailed(e.message ?: "向量化失败")
        }
    }
}
