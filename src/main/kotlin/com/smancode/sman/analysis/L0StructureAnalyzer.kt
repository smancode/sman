package com.smancode.sman.analysis

import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * L0: 项目结构分析器 - 支持多模块项目
 */
class L0StructureAnalyzer(private val projectPath: String) {
    private val logger = LoggerFactory.getLogger(L0StructureAnalyzer::class.java)
    private val rootPath: Path = Paths.get(projectPath)

    fun analyze(): L0Result {
        logger.info("L0: 开始项目结构扫描: {}", projectPath)
        return L0Result(
            modules = discoverModules(),
            techStack = detectTechStack(),
            entryPoints = findEntryPoints(),
            statistics = calculateStatistics()
        )
    }

    private fun discoverModules(): List<Module> {
        val modules = mutableListOf<Module>()
        
        Files.walk(rootPath)
            .filter { it.toFile().isFile }
            .filter { !it.toString().contains("/build/") && !it.toString().contains("/.gradle/") }
            .filter { it.fileName.toString() in listOf("pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts") }
            .forEach { file ->
                val moduleName = file.parent?.fileName?.toString() ?: rootPath.fileName.toString()
                val type = when (file.fileName.toString()) {
                    "pom.xml" -> "maven"
                    "build.gradle", "build.gradle.kts" -> "gradle"
                    else -> "other"
                }
                if (!modules.any { it.name == moduleName }) {
                    modules.add(Module(name = moduleName, path = rootPath.relativize(file.parent).toString(), type = type))
                }
            }

        logger.info("发现模块: {} 个", modules.size)
        return modules
    }

    private fun detectTechStack(): TechStack {
        val languages = mutableSetOf<String>()
        val frameworks = mutableSetOf<String>()
        val databases = mutableSetOf<String>()

        Files.walk(rootPath)
            .filter { it.toFile().isFile }
            .filter { !it.toString().contains("/build/") }
            .filter { it.fileName.toString() in listOf("pom.xml", "build.gradle", "build.gradle.kts") }
            .forEach { file ->
                try {
                    val content = file.toFile().readText()
                    if (content.contains("kotlin")) languages.add("Kotlin")
                    if (languages.isEmpty()) languages.add("Java")
                    if (content.contains("spring-boot")) frameworks.add("Spring Boot")
                    if (content.contains("mybatis")) frameworks.add("MyBatis")
                    if (content.contains("hibernate")) frameworks.add("Hibernate")
                    if (content.contains("mysql")) databases.add("MySQL")
                    if (content.contains("postgresql")) databases.add("PostgreSQL")
                    if (content.contains("oracle")) databases.add("Oracle")
                    if (content.contains("redis")) databases.add("Redis")
                    if (content.contains("mongodb")) databases.add("MongoDB")
                    if (content.contains("elasticsearch")) databases.add("Elasticsearch")
                    if (content.contains("cassandra")) databases.add("Cassandra")
                    if (content.contains("sqlserver")) databases.add("SQLServer")
                    if (content.contains("h2")) databases.add("H2")
                    if (content.contains("druid")) databases.add("Druid")
                    if (content.contains("sharding-sphere")) databases.add("ShardingSphere")
                    if (content.contains("shardingsphere")) databases.add("ShardingSphere")
                } catch (e: Exception) { }
            }

        return TechStack(languages = languages.toList(), frameworks = frameworks.toList(), databases = databases.toList(), others = emptyList())
    }

    private fun findAllSourceDirs(): List<Path> {
        val sourceDirs = mutableListOf<Path>()
        
        // 1. 根目录的 src
        listOf("src/main/java", "src/main/kotlin").forEach { dir ->
            val sourceDir = rootPath.resolve(dir)
            if (sourceDir.toFile().exists()) sourceDirs.add(sourceDir)
        }
        
        // 2. 每个模块的 src（多模块项目）
        Files.walk(rootPath)
            .filter { it.toFile().isDirectory }
            .filter { !it.toString().contains("/build/") && !it.toString().contains("/.gradle/") }
            .forEach { dir ->
                listOf("src/main/java", "src/main/kotlin").forEach { srcDir ->
                    val moduleSrc = dir.resolve(srcDir)
                    if (moduleSrc.toFile().exists() && !sourceDirs.any { it.toString() == moduleSrc.toString() }) {
                        sourceDirs.add(moduleSrc)
                    }
                }
            }
        
        return sourceDirs
    }

    private fun findEntryPoints(): List<EntryPoint> {
        val entryPoints = mutableListOf<EntryPoint>()
        
        findAllSourceDirs().forEach { sourceDir ->
            Files.walk(sourceDir)
                .filter { it.toFile().isFile }
                .filter { it.fileName.toString().endsWith(".java") || it.fileName.toString().endsWith(".kt") }
                .forEach { file ->
                    try {
                        val content = file.toFile().readText()
                        val className = file.toFile().nameWithoutExtension
                        if (content.contains("fun main") || content.contains("public static void main")) {
                            if (!entryPoints.any { it.type == "main" && it.className == className }) {
                                entryPoints.add(EntryPoint(type = "main", className = className, method = "main"))
                            }
                        }
                        if (content.contains("@RestController") || content.contains("@Controller")) {
                            if (!entryPoints.any { it.type == "controller" && it.className == className }) {
                                entryPoints.add(EntryPoint(type = "controller", className = className, method = ""))
                            }
                        }
                        if (content.contains("@Scheduled")) {
                            if (!entryPoints.any { it.type == "scheduler" && it.className == className }) {
                                entryPoints.add(EntryPoint(type = "scheduler", className = className, method = ""))
                            }
                        }
                    } catch (e: Exception) { }
                }
        }

        logger.info("找到入口点: {} 个", entryPoints.size)
        return entryPoints
    }

    private fun calculateStatistics(): ProjectStatistics {
        var totalFiles = 0
        var totalClasses = 0
        var totalLines = 0

        findAllSourceDirs().forEach { sourceDir ->
            Files.walk(sourceDir)
                .filter { it.toFile().isFile }
                .filter { it.fileName.toString().endsWith(".java") || it.fileName.toString().endsWith(".kt") }
                .forEach { file ->
                    totalFiles++
                    try {
                        val content = file.toFile().readText()
                        totalLines += content.lines().size
                        totalClasses += content.lines().count { 
                            it.contains("class ") || it.contains("interface ") || it.contains("enum ") || it.contains("data class ") 
                        }
                    } catch (e: Exception) { }
                }
        }

        return ProjectStatistics(totalFiles = totalFiles, totalClasses = totalClasses, totalLines = totalLines, maxDepth = 0)
    }
}

data class Module(val name: String, val path: String, val type: String)
data class TechStack(val languages: List<String>, val frameworks: List<String>, val databases: List<String>, val others: List<String>)
data class EntryPoint(val type: String, val className: String, val method: String)
data class ProjectStatistics(val totalFiles: Int, val totalClasses: Int, val totalLines: Int, val maxDepth: Int)
data class L0Result(val modules: List<Module>, val techStack: TechStack, val entryPoints: List<EntryPoint>, val statistics: ProjectStatistics)
