package com.smancode.smanagent.analysis.cli

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.smancode.smanagent.analysis.structure.ProjectStructureScanner
import com.smancode.smanagent.analysis.techstack.TechStackDetector
import com.smancode.smanagent.analysis.external.ExternalApiScanner
import com.smancode.smanagent.analysis.enum.EnumScanner
import com.smancode.smanagent.analysis.common.CommonClassScanner
import com.smancode.smanagent.analysis.xml.XmlCodeScanner
import java.nio.file.Paths
import kotlin.system.exitProcess

/**
 * 独立的项目分析 CLI 工具（简化版）
 *
 * 只包含不依赖 IntelliJ PSI 的分析步骤
 */
class SimpleAnalysisCli {

    private val jsonMapper = jacksonObjectMapper()

    fun analyze(projectPath: String): AnalysisResult {
        println("==========================================")
        println("  项目分析工具（简化版）")
        println("==========================================")
        println("项目路径: $projectPath")
        println("")

        val path = Paths.get(projectPath).toAbsolutePath().normalize()
        val steps = mutableMapOf<String, StepResult>()

        // 步骤 1: 项目结构扫描
        println("[1/6] 项目结构扫描...")
        try {
            val structure = ProjectStructureScanner().scan(path)
            val structureJson = jsonMapper.writeValueAsString(structure)
            steps["project_structure"] = StepResult("project_structure", "项目结构扫描", true, structureJson)
            println("  ✓ 找到 ${structure.modules.size} 个模块")
            println("  ✓ 找到 ${structure.packages.size} 个包")
            println("  ✓ 总文件数: ${structure.totalFiles}")
            println("  ✓ 总代码行数: ${structure.totalLines}")
        } catch (e: Exception) {
            steps["project_structure"] = StepResult("project_structure", "项目结构扫描", false, null, e.message)
            println("  ✗ 失败: ${e.message}")
        }

        // 步骤 2: 技术栈检测
        println("[2/6] 技术栈检测...")
        try {
            val techStack = TechStackDetector().detect(path)
            val techStackJson = jsonMapper.writeValueAsString(techStack)
            steps["tech_stack_detection"] = StepResult("tech_stack_detection", "技术栈检测", true, techStackJson)
            println("  ✓ 检测到 ${techStack.frameworks.size} 个框架")
            println("  ✓ 数据库: ${techStack.databases.joinToString { it.name }}")
            println("  ✓ 语言: ${techStack.languages.joinToString { it.name }}")
        } catch (e: Exception) {
            steps["tech_stack_detection"] = StepResult("tech_stack_detection", "技术栈检测", false, null, e.message)
            println("  ✗ 失败: ${e.message}")
        }

        // 步骤 3: 外调接口扫描
        println("[3/6] 外调接口扫描...")
        try {
            val externalApis = ExternalApiScanner().scan(path)
            val externalApisJson = jsonMapper.writeValueAsString(externalApis)
            steps["external_api_scanning"] = StepResult("external_api_scanning", "外调接口扫描", true, externalApisJson)
            println("  ✓ 找到 ${externalApis.size} 个外调接口")
        } catch (e: Exception) {
            steps["external_api_scanning"] = StepResult("external_api_scanning", "外调接口扫描", false, null, e.message)
            println("  ✗ 失败: ${e.message}")
        }

        // 步骤 4: 枚举扫描
        println("[4/6] 枚举扫描...")
        try {
            val enums = EnumScanner().scan(path)
            val enumsJson = jsonMapper.writeValueAsString(enums)
            steps["enum_scanning"] = StepResult("enum_scanning", "枚举扫描", true, enumsJson)
            println("  ✓ 找到 ${enums.size} 个枚举类")
        } catch (e: Exception) {
            steps["enum_scanning"] = StepResult("enum_scanning", "枚举扫描", false, null, e.message)
            println("  ✗ 失败: ${e.message}")
        }

        // 步骤 5: 公共类扫描
        println("[5/6] 公共类扫描...")
        try {
            val commonClasses = CommonClassScanner().scan(path)
            val commonClassesJson = jsonMapper.writeValueAsString(commonClasses)
            steps["common_class_scanning"] = StepResult("common_class_scanning", "公共类扫描", true, commonClassesJson)
            println("  ✓ 找到 ${commonClasses.size} 个公共类/工具类")
        } catch (e: Exception) {
            steps["common_class_scanning"] = StepResult("common_class_scanning", "公共类扫描", false, null, e.message)
            println("  ✗ 失败: ${e.message}")
        }

        // 步骤 6: XML 代码扫描
        println("[6/6] XML 代码扫描...")
        try {
            val xmlCodes = XmlCodeScanner().scan(path)
            val xmlCodesJson = jsonMapper.writeValueAsString(xmlCodes)
            steps["xml_code_scanning"] = StepResult("xml_code_scanning", "XML 代码扫描", true, xmlCodesJson)
            println("  ✓ 找到 ${xmlCodes.size} 个 XML 文件")
        } catch (e: Exception) {
            steps["xml_code_scanning"] = StepResult("xml_code_scanning", "XML 代码扫描", false, null, e.message)
            println("  ✗ 失败: ${e.message}")
        }

        println("")
        println("==========================================")
        println("  分析完成！")
        println("==========================================")

        return AnalysisResult(path.fileName.toString(), steps)
    }

    data class AnalysisResult(
        val projectKey: String,
        val steps: Map<String, StepResult>
    )

    data class StepResult(
        val name: String,
        val description: String,
        val success: Boolean,
        val data: String? = null,
        val error: String? = null
    )

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            if (args.isEmpty()) {
                println("用法: java -cp <classpath> com.smancode.smanagent.analysis.cli.SimpleAnalysisCli <project-path>")
                println("示例: java -cp build/libs/* com.smancode.smanagent.analysis.cli.SimpleAnalysisCli ../autoloop")
                exitProcess(1)
            }

            val projectPath = args[0]

            try {
                val cli = SimpleAnalysisCli()
                val result = cli.analyze(projectPath)

                // 打印总结
                println("\n总结:")
                result.steps.forEach { (name, step) ->
                    val status = if (step.success) "✅" else "❌"
                    println("  $status $name: ${step.description}")
                }

                val successCount = result.steps.values.count { it.success }
                println("\n成功: $successCount/${result.steps.size}")

                exitProcess(0)
            } catch (e: Exception) {
                println("错误: ${e.message}")
                e.printStackTrace()
                exitProcess(1)
            }
        }
    }
}
