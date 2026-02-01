package com.smancode.smanagent.analysis.step

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.smancode.smanagent.analysis.external.ExternalApiScanner
import com.smancode.smanagent.analysis.external.toSerializableMap
import com.smancode.smanagent.analysis.model.StepResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Paths

/**
 * 外调接口扫描步骤
 *
 * 扫描项目中的外部 API 调用，包括：
 * - Spring Cloud OpenFeign (@FeignClient)
 * - Retrofit (retrofit2.http.*)
 * - RestTemplate
 * - WebClient
 */
class ExternalApiScanningStep : AnalysisStep {

    private val logger = org.slf4j.LoggerFactory.getLogger(ExternalApiScanningStep::class.java)
    private val jsonMapper = jacksonObjectMapper()

    override val name = "external_api_scanning"
    override val description = "外调接口扫描"

    override suspend fun execute(context: AnalysisContext): StepResult {
        val stepResult = StepResult.create(name, description).markStarted()

        return try {
            val basePath = context.project.basePath
                ?: throw IllegalArgumentException("项目路径不存在")

            val apis = withContext(Dispatchers.IO) {
                ExternalApiScanner().scan(Paths.get(basePath))
            }

            val resultData = mapOf(
                "externalApis" to apis.map { it.toSerializableMap() },
                "count" to apis.size
            )

            val apisJson = jsonMapper.writeValueAsString(resultData)
            stepResult.markCompleted(apisJson)
        } catch (e: Exception) {
            logger.error("外调接口扫描失败", e)
            stepResult.markFailed(e.message ?: "扫描失败")
        }
    }
}
