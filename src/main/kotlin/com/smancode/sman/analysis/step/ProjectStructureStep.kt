package com.smancode.sman.analysis.step

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.smancode.sman.analysis.model.StepResult
import com.smancode.sman.analysis.structure.ProjectStructureScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Paths

/**
 * 项目结构扫描步骤
 */
class ProjectStructureStep : AnalysisStep {

    private val logger = org.slf4j.LoggerFactory.getLogger(ProjectStructureStep::class.java)
    private val jsonMapper = jacksonObjectMapper()

    override val name = "project_structure"
    override val description = "项目结构扫描"

    override suspend fun execute(context: AnalysisContext): StepResult {
        val stepResult = StepResult.create(name, description).markStarted()

        return try {
            val basePath = context.project.basePath
                ?: throw IllegalArgumentException("项目路径不存在")

            val structure = withContext(Dispatchers.IO) {
                ProjectStructureScanner().scan(java.nio.file.Paths.get(basePath))
            }

            val structureJson = jsonMapper.writeValueAsString(structure)
            stepResult.markCompleted(structureJson)
        } catch (e: Exception) {
            logger.error("项目结构扫描失败", e)
            stepResult.markFailed(e.message ?: "扫描失败")
        }
    }
}
