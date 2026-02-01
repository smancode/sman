#!/usr/bin/env kotlin

@file:DependsOn("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
@file:DependsOn("com.squareup.okhttp3:okhttp:4.12.0")
@file:DependsOn("com.fasterxml.jackson.module:jackson-module-kotlin:2.16.0")
@file:DependsOn("com.fasterxml.jackson.core:jackson-databind:2.16.0")
@file:DependsOn("org.slf4j:slf4j-api:2.0.9")
@file:DependsOn("ch.qos.logback:logback-classic:1.4.11")

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.smancode.smanagent.analysis.structure.ProjectStructureScanner
import com.smancode.smanagent.analysis.techstack.TechStackDetector
import com.smancode.smanagent.analysis.scanner.PsiAstScanner
import com.smancode.smanagent.analysis.entity.DbEntityScanner
import com.smancode.smanagent.analysis.entry.ApiEntryScanner
import com.smancode.smanagent.analysis.external.ExternalApiScanner
import com.smancode.smanagent.analysis.`enum`.EnumScanner
import com.smancode.smanagent.analysis.common.CommonClassScanner
import com.smancode.smanagent.analysis.xml.XmlCodeScanner
import kotlinx.coroutines.runBlocking
import java.nio.file.Paths
import kotlin.system.exitProcess

/**
 * 独立的项目分析工具
 *
 * 用法: ./scripts/analyze-project.kts <project-path>
 */
if (args.isEmpty()) {
    println("用法: kotlin scripts/analyze-project.kts <project-path>")
    println("示例: kotlin scripts/analyze-project.kts ../autoloop")
    exitProcess(1)
}

val projectPath = Paths.get(args[0]).toAbsolutePath().normalize()
val projectKey = projectPath.fileName.toString()

println("==========================================")
println("  项目分析工具")
println("==========================================")
println("项目路径: $projectPath")
println("项目 Key: $projectKey")
println("")

val jsonMapper = jacksonObjectMapper()

// 步骤 1: 项目结构扫描
println("[1/9] 项目结构扫描...")
try {
    val structure = ProjectStructureScanner().scan(projectPath)
    val structureJson = jsonMapper.writeValueAsString(structure)
    println("  ✓ 找到 ${structure.modules.size} 个模块")
    println("  ✓ 找到 ${structure.packages.size} 个包")
    println("  ✓ 总文件数: ${structure.totalFiles}")
    println("  ✓ 总代码行数: ${structure.totalLines}")
} catch (e: Exception) {
    println("  ✗ 失败: ${e.message}")
}

// 步骤 2: 技术栈检测
println("[2/9] 技术栈检测...")
try {
    val techStack = TechStackDetector().detect(projectPath)
    println("  ✓ 检测到 ${techStack.frameworks.size} 个框架")
    println("  ✓ 数据库: ${techStack.database}")
    println("  ✓ 语言: ${techStack.languages.joinToString()}")
} catch (e: Exception) {
    println("  ✗ 失败: ${e.message}")
}

// 步骤 3: AST 扫描
println("[3/9] AST 扫描...")
println("  ⚠ 跳过 (需要 PSI 环境)")

// 步骤 4: 数据库实体检测
println("[4/9] 数据库实体检测...")
try {
    val entities = DbEntityScanner().scan(projectPath)
    println("  ✓ 找到 ${entities.size} 个实体类")
} catch (e: Exception) {
    println("  ✗ 失败: ${e.message}")
}

// 步骤 5: API 入口扫描
println("[5/9] API 入口扫描...")
try {
    val apiEntries = ApiEntryScanner().scan(projectPath)
    println("  ✓ 找到 ${apiEntries.size} 个 API 入口")
    apiEntries.take(3).forEach { entry ->
        println("    - ${entry.method}: ${entry.path}")
    }
} catch (e: Exception) {
    println("  ✗ 失败: ${e.message}")
}

// 步骤 6: 外调接口扫描
println("[6/9] 外调接口扫描...")
try {
    val externalApis = ExternalApiScanner().scan(projectPath)
    println("  ✓ 找到 ${externalApis.size} 个外调接口")
} catch (e: Exception) {
    println("  ✗ 失败: ${e.message}")
}

// 步骤 7: 枚举扫描
println("[7/9] 枚举扫描...")
try {
    val enums = EnumScanner().scan(projectPath)
    println("  ✓ 找到 ${enums.size} 个枚举类")
} catch (e: Exception) {
    println("  ✗ 失败: ${e.message}")
}

// 步骤 8: 公共类扫描
println("[8/9] 公共类扫描...")
try {
    val commonClasses = CommonClassScanner().scan(projectPath)
    println("  ✓ 找到 ${commonClasses.size} 个公共类/工具类")
} catch (e: Exception) {
    println("  ✗ 失败: ${e.message}")
}

// 步骤 9: XML 代码扫描
println("[9/9] XML 代码扫描...")
try {
    val xmlCodes = XmlCodeScanner().scan(projectPath)
    println("  ✓ 找到 ${xmlCodes.size} 个 XML 文件")
} catch (e: Exception) {
    println("  ✗ 失败: ${e.message}")
}

println("")
println("==========================================")
println("  分析完成！")
println("==========================================")
