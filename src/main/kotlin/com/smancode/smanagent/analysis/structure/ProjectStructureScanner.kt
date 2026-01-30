package com.smancode.smanagent.analysis.structure

import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * 项目结构扫描器
 *
 * 扫描项目目录结构，识别模块、包、层次架构
 */
class ProjectStructureScanner {

    private val logger = org.slf4j.LoggerFactory.getLogger(ProjectStructureScanner::class.java)

    /**
     * 扫描项目结构
     *
     * @param projectPath 项目路径
     * @return 项目结构信息
     */
    fun scan(projectPath: Path): ProjectStructure {
        val modules = detectModules(projectPath)
        val packages = detectPackages(projectPath)
        val layers = detectLayers(projectPath)
        val totalFiles = countFiles(projectPath)
        val totalLines = countLines(projectPath)

        return ProjectStructure(
            rootPath = projectPath.toString(),
            modules = modules,
            packages = packages,
            layers = layers,
            totalFiles = totalFiles,
            totalLines = totalLines
        )
    }

    /**
     * 检测模块（Maven/Gradle）
     */
    private fun detectModules(projectPath: Path): List<ModuleInfo> {
        val modules = mutableListOf<ModuleInfo>()

        // 检测 Maven 模块
        if (projectPath.resolve("pom.xml").toFile().exists()) {
            modules.add(ModuleInfo(
                name = projectPath.fileName.toString(),
                type = ModuleType.MAVEN,
                path = projectPath.toString()
            ))
        }

        // 检测 Gradle 模块
        if (projectPath.resolve("build.gradle.kts").toFile().exists() ||
            projectPath.resolve("build.gradle").toFile().exists()) {
            modules.add(ModuleInfo(
                name = projectPath.fileName.toString(),
                type = ModuleType.GRADLE,
                path = projectPath.toString()
            ))
        }

        return modules
    }

    /**
     * 检测包结构
     */
    private fun detectPackages(projectPath: Path): List<PackageInfo> {
        val packages = mutableListOf<PackageInfo>()

        val srcMainKotlin = projectPath.resolve("src/main/kotlin")
        val srcMainJava = projectPath.resolve("src/main/java")

        val srcDirs = listOfNotNull(
            if (srcMainKotlin.toFile().exists()) srcMainKotlin else null,
            if (srcMainJava.toFile().exists()) srcMainJava else null
        )

        for (srcDir in srcDirs) {
            try {
                java.nio.file.Files.walk(srcDir)
                    .filter { it.toFile().isDirectory }
                    .filter { dir ->
                        // 至少包含一个 Kotlin/Java 文件
                        val files = java.nio.file.Files.list(dir).toList()
                        files.any { it.toString().endsWith(".kt") || it.toString().endsWith(".java") }
                    }
                    .forEach { pkgDir ->
                        val packagePath = srcDir.relativize(pkgDir).toString()
                            .replace("/", ".")
                        val classCount = java.nio.file.Files.list(pkgDir).count()

                        packages.add(PackageInfo(
                            name = packagePath,
                            path = pkgDir.toString(),
                            classCount = classCount.toInt()
                        ))
                    }
            } catch (e: Exception) {
                logger.warn("Failed to scan directory: $srcDir", e)
            }
        }

        return packages
    }

    /**
     * 检测架构层次
     */
    private fun detectLayers(projectPath: Path): List<LayerInfo> {
        val layers = mutableListOf<LayerInfo>()
        val srcMain = projectPath.resolve("src/main/kotlin")
        if (!srcMain.toFile().exists()) {
            return layers
        }

        // 常见的层次包
        val layerPatterns = mapOf(
            "controller" to LayerType.PRESENTATION,
            "web" to LayerType.PRESENTATION,
            "api" to LayerType.API,
            "service" to LayerType.SERVICE,
            "business" to LayerType.SERVICE,
            "domain" to LayerType.DOMAIN,
            "model" to LayerType.DOMAIN,
            "entity" to LayerType.DOMAIN,
            "repository" to LayerType.INFRASTRUCTURE,
            "dao" to LayerType.INFRASTRUCTURE,
            "mapper" to LayerType.INFRASTRUCTURE,
            "config" to LayerType.CONFIG,
            "util" to LayerType.UTIL,
            "common" to LayerType.UTIL,
            "infrastructure" to LayerType.INFRASTRUCTURE
        )

        try {
            java.nio.file.Files.walk(srcMain)
                .filter { it.toFile().isDirectory }
                .forEach { dir ->
                    val dirName = dir.fileName.toString()
                    layerPatterns[dirName]?.let { layerType ->
                        val packagePath = srcMain.relativize(dir).toString()
                            .replace("/", ".")

                        layers.add(LayerInfo(
                            name = dirName,
                            type = layerType,
                            path = dir.toString(),
                            packagePath = packagePath
                        ))
                    }
                }
        } catch (e: Exception) {
            logger.warn("Failed to detect layers", e)
        }

        return layers
    }

    /**
     * 统计文件数量
     */
    private fun countFiles(projectPath: Path): Int {
        return try {
            java.nio.file.Files.walk(projectPath)
                .filter { it.toFile().isFile }
                .filter { file ->
                    file.toString().endsWith(".kt") ||
                    file.toString().endsWith(".java") ||
                    file.toString().endsWith(".xml")
                }
                .count()
                .toInt()
        } catch (e: Exception) {
            logger.warn("Failed to count files", e)
            0
        }
    }

    /**
     * 统计代码行数
     */
    private fun countLines(projectPath: Path): Long {
        return try {
            java.nio.file.Files.walk(projectPath)
                .filter { it.toFile().isFile }
                .filter { file ->
                    file.toString().endsWith(".kt") ||
                    file.toString().endsWith(".java")
                }
                .mapToLong { file ->
                    java.nio.file.Files.readAllLines(file).count().toLong()
                }
                .sum()
        } catch (e: Exception) {
            logger.warn("Failed to count lines", e)
            0L
        }
    }
}

/**
 * 项目结构信息
 */
@Serializable
data class ProjectStructure(
    val rootPath: String,
    val modules: List<ModuleInfo>,
    val packages: List<PackageInfo>,
    val layers: List<LayerInfo>,
    val totalFiles: Int,
    val totalLines: Long
)

/**
 * 模块信息
 */
@Serializable
data class ModuleInfo(
    val name: String,
    val type: ModuleType,
    val path: String
)

/**
 * 模块类型
 */
@Serializable
enum class ModuleType {
    MAVEN, GRADLE, UNKNOWN
}

/**
 * 包信息
 */
@Serializable
data class PackageInfo(
    val name: String,
    val path: String,
    val classCount: Int
)

/**
 * 层次信息
 */
@Serializable
data class LayerInfo(
    val name: String,
    val type: LayerType,
    val path: String,
    val packagePath: String
)

/**
 * 层次类型
 */
@Serializable
enum class LayerType {
    PRESENTATION,   // 表现层 (controller, web)
    API,            // API 层
    SERVICE,        // 服务层 (service, business)
    DOMAIN,         // 领域层 (domain, model, entity)
    INFRASTRUCTURE, // 基础设施层 (repository, dao, mapper)
    CONFIG,         // 配置层
    UTIL            // 工具层
}
