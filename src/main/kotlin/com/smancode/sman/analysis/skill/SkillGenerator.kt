package com.smancode.sman.analysis.skill

import com.smancode.sman.analysis.*
import java.io.File

/**
 * L0-L4 分析结果 → Skill 转换器
 * 
 * 每个分析层生成独立的 Skill
 */
class SkillGenerator(
    private val projectPath: String,
    private val projectKey: String
) {

    /**
     * 生成所有 Skill
     */
    fun generateAll(outputDir: String) {
        generateL0(outputDir)
        generateL1(outputDir)
        generateL2(outputDir)
        generateL3(outputDir)
        generateL4(outputDir)
        
        println("所有 Skill 已生成到: $outputDir")
    }

    /**
     * L0: 项目结构 Skill
     */
    private fun generateL0(outputDir: String) {
        val result = L0StructureAnalyzer(projectPath).analyze()
        
        val content = buildString {
            appendLine("# ${projectKey} - L0: 项目结构")
            appendLine()
            appendLine("## 技术栈")
            appendLine("- 语言: ${result.techStack.languages.joinToString(", ")}")
            appendLine("- 框架: ${result.techStack.frameworks.joinToString(", ")}")
            appendLine("- 数据库: ${result.techStack.databases.joinToString(", ")}")
            appendLine()
            appendLine("## 模块")
            result.modules.forEach { mod ->
                appendLine("- ${mod.name} (${mod.type})")
            }
            appendLine()
            appendLine("## 代码统计")
            appendLine("- 文件: ${result.statistics.totalFiles}")
            appendLine("- 类: ${result.statistics.totalClasses}")
            appendLine("- 行数: ${result.statistics.totalLines}")
            appendLine()
            appendLine("## 入口点")
            result.entryPoints.forEach { ep ->
                appendLine("- ${ep.type}: ${ep.className}")
            }
        }
        
        save("L0_PROJECT_STRUCTURE.md", content, outputDir)
    }

    /**
     * L1: 模块分析 Skill
     */
    private fun generateL1(outputDir: String) {
        val l0 = L0StructureAnalyzer(projectPath).analyze()
        
        val content = buildString {
            appendLine("# ${projectKey} - L1: 模块分析")
            appendLine()
            
            l0.modules.forEach { mod ->
                appendLine("## 模块: ${mod.name}")
                appendLine("- 类型: ${mod.type}")
                appendLine("- 路径: ${mod.path}")
                appendLine()
            }
            
            appendLine("## 模块依赖")
            appendLine("(需要更详细的分析)")
        }
        
        save("L1_MODULE_ANALYSIS.md", content, outputDir)
    }

    /**
     * L2: 入口分析 Skill
     */
    private fun generateL2(outputDir: String) {
        val result = L2EntryAnalyzer(projectPath).analyze()
        
        val content = buildString {
            appendLine("# ${projectKey} - L2: 入口分析")
            appendLine()
            
            appendLine("## Controllers (${result.controllers.size})")
            result.controllers.forEach { c ->
                appendLine("- ${c.name}")
            }
            appendLine()
            
            appendLine("## Services (${result.services.size})")
            result.services.take(20).forEach { s ->
                appendLine("- ${s.name}")
            }
            if (result.services.size > 20) {
                appendLine("... 还有 ${result.services.size - 20} 个")
            }
            appendLine()
            
            appendLine("## REST APIs (${result.restApis.size})")
            result.restApis.take(10).forEach { api ->
                appendLine("- ${api.method} ${api.path} → ${api.handler}")
            }
        }
        
        save("L2_ENTRY_POINTS.md", content, outputDir)
    }

    /**
     * L3: 业务场景 Skill
     */
    private fun generateL3(outputDir: String) {
        val result = L3ScenarioAnalyzer(projectPath).analyze("main")
        
        val content = buildString {
            appendLine("# ${projectKey} - L3: 业务场景")
            appendLine()
            appendLine("## 入口方法")
            appendLine("- ${result.entryPoint}")
            appendLine()
            appendLine("## 调用链")
            result.callChain.forEach { node ->
                appendLine("${"  ".repeat(node.depth)}- ${node.className}.${node.methodName}")
            }
            appendLine()
            appendLine("## 涉及的模块")
            result.involvedModules.forEach { mod ->
                appendLine("- $mod")
            }
        }
        
        save("L3_SCENARIO.md", content, outputDir)
    }

    /**
     * L4: 深度理解 Skill
     */
    private fun generateL4(outputDir: String) {
        val result = L4DeepAnalyzer(projectPath).analyze()
        
        // 枚举详情
        val enumFiles = File(projectPath).walkTopDown()
            .filter { it.isFile && it.name.endsWith("Enum.java") }
            .filter { !it.absolutePath.contains("/build/") }
            .take(30)
            .toList()
        
        val content = buildString {
            appendLine("# ${projectKey} - L4: 深度理解")
            appendLine()
            
            appendLine("## 枚举详情 (${enumFiles.size})")
            enumFiles.forEach { file ->
                try {
                    val parser = EnumParser()
                    val info = parser.parse(file)
                    if (info.values.isNotEmpty()) {
                        appendLine("### ${info.name}")
                        info.values.take(10).forEach { v ->
                            appendLine("- $v")
                        }
                        if (info.values.size > 10) {
                            appendLine("  ... 共 ${info.values.size} 个")
                        }
                        appendLine()
                    }
                } catch (e: Exception) { }
            }
            
            appendLine("## XML 业务逻辑 (${result.xmlBusinessLogic.size})")
            result.xmlBusinessLogic.take(10).forEach { xml ->
                appendLine("- ${xml.file}")
                appendLine("  - ${xml.desc}")
            }
            appendLine()
            
            appendLine("## 反模式 (${result.antiPatterns.size})")
            result.antiPatterns.take(10).forEach { ap ->
                appendLine("- ${ap.type}: ${ap.desc}")
            }
        }
        
        save("L4_DEEP_UNDERSTANDING.md", content, outputDir)
    }

    private fun save(filename: String, content: String, outputDir: String) {
        val dir = File(outputDir, projectKey)
        dir.mkdirs()
        File(dir, filename).writeText(content)
    }
}
