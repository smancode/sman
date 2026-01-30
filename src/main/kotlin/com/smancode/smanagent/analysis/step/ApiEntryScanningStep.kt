package com.smancode.smanagent.analysis.step

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.smancode.smanagent.analysis.external.ExternalApiScanner
import com.smancode.smanagent.analysis.model.StepResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Paths

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
            val basePath = context.project.basePath
                ?: throw IllegalArgumentException("项目路径不存在")

            val externalApis = withContext(Dispatchers.IO) {
                ExternalApiScanner().scan(Paths.get(basePath))
            }

            val apiEntriesJson = jsonMapper.writeValueAsString(
                mapOf(
                    "externalApis" to externalApis.map { it.qualifiedName },
                    "controllers" to externalApis.map { it.apiName },
                    "count" to externalApis.size,
                    "types" to externalApis.map { it.apiType.name }.distinct()
                )
            )
            stepResult.markCompleted(apiEntriesJson)
        } catch (e: Exception) {
            logger.error("API 入口扫描失败", e)
            stepResult.markFailed(e.message ?: "扫描失败")
        }
    }
}
