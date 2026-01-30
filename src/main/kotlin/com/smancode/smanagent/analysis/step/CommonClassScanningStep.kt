package com.smancode.smanagent.analysis.step

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.smancode.smanagent.analysis.common.CommonClassScanner
import com.smancode.smanagent.analysis.model.StepResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Paths

/**
 * 公共类扫描步骤
 */
class CommonClassScanningStep : AnalysisStep {

    private val logger = org.slf4j.LoggerFactory.getLogger(CommonClassScanningStep::class.java)
    private val jsonMapper = jacksonObjectMapper()

    override val name = "common_class_scanning"
    override val description = "公共类扫描"

    override suspend fun execute(context: AnalysisContext): StepResult {
        val stepResult = StepResult.create(name, description).markStarted()

        return try {
            val basePath = context.project.basePath
                ?: throw IllegalArgumentException("项目路径不存在")

            val commonClasses = withContext(Dispatchers.IO) {
                CommonClassScanner().scan(Paths.get(basePath))
            }

            val commonClassesJson = jsonMapper.writeValueAsString(commonClasses)
            stepResult.markCompleted(commonClassesJson)
        } catch (e: Exception) {
            logger.error("公共类扫描失败", e)
            stepResult.markFailed(e.message ?: "扫描失败")
        }
    }
}
