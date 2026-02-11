package com.smancode.sman.analysis.step

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.smancode.sman.analysis.database.DbEntityDetector
import com.smancode.sman.analysis.model.StepResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Paths

/**
 * 数据库实体扫描步骤
 */
class DbEntityDetectionStep : AnalysisStep {

    private val logger = org.slf4j.LoggerFactory.getLogger(DbEntityDetectionStep::class.java)
    private val jsonMapper = jacksonObjectMapper()

    override val name = "db_entity_detection"
    override val description = "数据库实体扫描"

    override suspend fun execute(context: AnalysisContext): StepResult {
        val stepResult = StepResult.create(name, description).markStarted()

        return try {
            val basePath = context.project.basePath
                ?: throw IllegalArgumentException("项目路径不存在")

            val dbEntities = withContext(Dispatchers.IO) {
                DbEntityDetector().detect(Paths.get(basePath))
            }

            val dbEntitiesJson = jsonMapper.writeValueAsString(
                mapOf(
                    "entities" to dbEntities.map { it.qualifiedName },
                    "tables" to dbEntities.map { it.tableName },
                    "count" to dbEntities.size
                )
            )
            stepResult.markCompleted(dbEntitiesJson)
        } catch (e: Exception) {
            logger.error("数据库实体扫描失败", e)
            stepResult.markFailed(e.message ?: "扫描失败")
        }
    }
}
