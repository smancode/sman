package com.smancode.smanagent.analysis.step

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.smancode.smanagent.analysis.model.StepResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
            // TODO: 实现技术栈识别
            val techStack = mapOf("frameworks" to emptyList<String>(), "languages" to emptyList<String>())
            val techStackJson = jsonMapper.writeValueAsString(techStack)
            stepResult.markCompleted(techStackJson)
        } catch (e: Exception) {
            logger.error("技术栈识别失败", e)
            stepResult.markFailed(e.message ?: "识别失败")
        }
    }
}
