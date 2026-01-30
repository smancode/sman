package com.smancode.smanagent.analysis.step

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.smancode.smanagent.analysis.model.StepResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * API 入口扫描步骤
 */
class ApiEntryScanningStep : AnalysisStep {

    private val logger = org.slf4j.LoggerFactory.getLogger(ApiEntryScanningStep::class.java)
    private val jsonMapper = jacksonObjectMapper()

    override val name = "api_entry_scanning"
    override val description = "API 入口扫描"

    override suspend fun execute(context: AnalysisContext): StepResult {
        val stepResult = StepResult.create(name, description).markStarted()

        return try {
            // TODO: 实现 API 入口扫描
            val apiEntries = mapOf("apis" to emptyList<String>(), "controllers" to emptyList<String>())
            val apiEntriesJson = jsonMapper.writeValueAsString(apiEntries)
            stepResult.markCompleted(apiEntriesJson)
        } catch (e: Exception) {
            logger.error("API 入口扫描失败", e)
            stepResult.markFailed(e.message ?: "扫描失败")
        }
    }
}
