package com.smancode.sman.analysis.step

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.smancode.sman.analysis.model.StepResult
import com.smancode.sman.analysis.xml.XmlCodeScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Paths

/**
 * XML 代码扫描步骤
 */
class XmlCodeScanningStep : AnalysisStep {

    private val logger = org.slf4j.LoggerFactory.getLogger(XmlCodeScanningStep::class.java)
    private val jsonMapper = jacksonObjectMapper()

    override val name = "xml_code_scanning"
    override val description = "XML 代码扫描"

    override suspend fun execute(context: AnalysisContext): StepResult {
        val stepResult = StepResult.create(name, description).markStarted()

        return try {
            val basePath = context.project.basePath
                ?: throw IllegalArgumentException("项目路径不存在")

            val xmlCodes = withContext(Dispatchers.IO) {
                XmlCodeScanner().scan(Paths.get(basePath))
            }

            val xmlCodesJson = jsonMapper.writeValueAsString(xmlCodes)
            stepResult.markCompleted(xmlCodesJson)
        } catch (e: Exception) {
            logger.error("XML 代码扫描失败", e)
            stepResult.markFailed(e.message ?: "扫描失败")
        }
    }
}
