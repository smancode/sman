package com.smancode.sman.analysis.step

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.smancode.sman.analysis.enum.EnumScanner
import com.smancode.sman.analysis.model.StepResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Paths

/**
 * 枚举扫描步骤
 */
class EnumScanningStep : AnalysisStep {

    private val logger = org.slf4j.LoggerFactory.getLogger(EnumScanningStep::class.java)
    private val jsonMapper = jacksonObjectMapper()

    override val name = "enum_scanning"
    override val description = "枚举扫描"

    override suspend fun execute(context: AnalysisContext): StepResult {
        val stepResult = StepResult.create(name, description).markStarted()

        return try {
            val basePath = context.project.basePath
                ?: throw IllegalArgumentException("项目路径不存在")

            val enums = withContext(Dispatchers.IO) {
                EnumScanner().scan(Paths.get(basePath))
            }

            val enumsJson = jsonMapper.writeValueAsString(
                mapOf(
                    "enums" to enums.map { it.qualifiedName },
                    "constants" to enums.flatMap { enum ->
                        enum.constants.map { "${enum.enumName}.${it.name}" }
                    },
                    "count" to enums.size
                )
            )
            stepResult.markCompleted(enumsJson)
        } catch (e: Exception) {
            logger.error("枚举扫描失败", e)
            stepResult.markFailed(e.message ?: "扫描失败")
        }
    }
}
