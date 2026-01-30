package com.smancode.smanagent.analysis.step

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.smancode.smanagent.analysis.model.StepResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * AST 扫描步骤
 */
class ASTScanningStep : AnalysisStep {

    private val logger = org.slf4j.LoggerFactory.getLogger(ASTScanningStep::class.java)
    private val jsonMapper = jacksonObjectMapper()

    override val name = "ast_scanning"
    override val description = "AST 扫描"

    override suspend fun execute(context: AnalysisContext): StepResult {
        val stepResult = StepResult.create(name, description).markStarted()

        return try {
            // TODO: 实现 AST 扫描
            val astResult = mapOf("classes" to emptyList<String>(), "methods" to emptyList<String>())
            val astResultJson = jsonMapper.writeValueAsString(astResult)
            stepResult.markCompleted(astResultJson)
        } catch (e: Exception) {
            logger.error("AST 扫描失败", e)
            stepResult.markFailed(e.message ?: "扫描失败")
        }
    }
}
