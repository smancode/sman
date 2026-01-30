package com.smancode.smanagent.analysis.step

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.smancode.smanagent.analysis.model.StepResult
import com.smancode.smanagent.analysis.scanner.PsiAstScanner
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

            val srcMainKotlin = Paths.get(basePath, "src/main/kotlin")
            val srcMainJava = Paths.get(basePath, "src/main/java")

            val scanner = PsiAstScanner()
            val classes = mutableListOf<com.smancode.smanagent.analysis.model.ClassAstInfo>()

            // 扫描 Kotlin 文件
            if (srcMainKotlin.toFile().exists()) {
                withContext(Dispatchers.IO) {
                    java.nio.file.Files.walk(srcMainKotlin)
                        .filter { it.toFile().isFile }
                        .filter { it.toString().endsWith(".kt") }
                        .forEach { file ->
                            try {
                                scanner.scanFile(file)?.let { classes.add(it) }
                            } catch (e: Exception) {
                                logger.debug("扫描文件失败: $file")
                            }
                        }
                }
            }

            // 扫描 Java 文件
            if (srcMainJava.toFile().exists()) {
                withContext(Dispatchers.IO) {
                    java.nio.file.Files.walk(srcMainJava)
                        .filter { it.toFile().isFile }
                        .filter { it.toString().endsWith(".java") }
                        .forEach { file ->
                            try {
                                scanner.scanFile(file)?.let { classes.add(it) }
                            } catch (e: Exception) {
                                logger.debug("扫描文件失败: $file")
                            }
                        }
                }
            }

            val astResult = mapOf(
                "classes" to classes.map { it.className },
                "methods" to classes.flatMap { cls -> cls.methods.map { "${cls.className}::${it.name}" } }
            )
            val astResultJson = jsonMapper.writeValueAsString(astResult)
            stepResult.markCompleted(astResultJson)
        } catch (e: Exception) {
            logger.error("AST 扫描失败", e)
            stepResult.markFailed(e.message ?: "扫描失败")
        }
    }
}
