package com.smancode.sman.analysis.step

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.smancode.sman.analysis.model.StepResult
import com.smancode.sman.analysis.scanner.PsiAstScanner
import com.smancode.sman.analysis.structure.ProjectSourceFinder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Paths

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
            val basePath = context.project.basePath
                ?: throw IllegalArgumentException("项目路径不存在")

            val projectPath = Paths.get(basePath)
            val scanner = PsiAstScanner()
            val classes = mutableListOf<com.smancode.sman.analysis.model.ClassAstInfo>()

            // 使用通用工具查找所有源文件
            val kotlinFiles = ProjectSourceFinder.findAllKotlinFiles(projectPath)
            val javaFiles = ProjectSourceFinder.findAllJavaFiles(projectPath)

            logger.info("AST 扫描: 发现 {} 个 Kotlin 文件, {} 个 Java 文件", kotlinFiles.size, javaFiles.size)

            // 扫描所有文件
            val allFiles = kotlinFiles + javaFiles
            allFiles.forEach { file ->
                try {
                    scanner.scanFile(file)?.let { classes.add(it) }
                } catch (e: Exception) {
                    logger.debug("扫描文件失败: $file", e)
                }
            }

            val astResult = mapOf(
                "classes" to classes.map { it.className },
                "methods" to classes.flatMap { cls -> cls.methods.map { "${cls.className}::${it.name}" } },
                "statistics" to mapOf(
                    "totalClasses" to classes.size,
                    "totalMethods" to classes.sumOf { it.methods.size },
                    "totalFields" to classes.sumOf { it.fields.size }
                )
            )
            val astResultJson = jsonMapper.writeValueAsString(astResult)
            stepResult.markCompleted(astResultJson)
        } catch (e: Exception) {
            logger.error("AST 扫描失败", e)
            stepResult.markFailed(e.message ?: "扫描失败")
        }
    }
}
