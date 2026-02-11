package com.smancode.sman.analysis.step

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.smancode.sman.analysis.model.StepResult
import com.smancode.sman.analysis.techstack.TechStackDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Paths

/**
 * 技术栈识别步骤
 */
class TechStackDetectionStep : AnalysisStep {

    private val logger = org.slf4j.LoggerFactory.getLogger(TechStackDetectionStep::class.java)
    private val jsonMapper = jacksonObjectMapper()

    override val name = "tech_stack_detection"
    override val description = "技术栈识别"

    override suspend fun execute(context: AnalysisContext): StepResult {
        val stepResult = StepResult.create(name, description).markStarted()

        return try {
            val basePath = context.project.basePath
                ?: throw IllegalArgumentException("项目路径不存在")

            val techStack = withContext(Dispatchers.IO) {
                TechStackDetector().detect(Paths.get(basePath))
            }

            val techStackJson = jsonMapper.writeValueAsString(techStack)
            stepResult.markCompleted(techStackJson)
        } catch (e: Exception) {
            logger.error("技术栈识别失败", e)
            stepResult.markFailed(e.message ?: "识别失败")
        }
    }
}
