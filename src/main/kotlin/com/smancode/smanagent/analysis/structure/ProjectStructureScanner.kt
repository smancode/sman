package com.smancode.smanagent.analysis.structure

import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.nio.file.Files

/**
 * 项目结构扫描器
 *
 * 扫描项目目录结构，识别模块、包、层次架构
 * 支持多模块项目
 */
class ProjectStructureScanner {

    private val logger = LoggerFactory.getLogger(ProjectStructureScanner::class.java)

    fun scan(projectPath: java.nio.file.Path): ProjectStructure {
        // 使用通用工具查找所有源代码目录
        val allSourceDirs = ProjectSourceFinder.findAllSourceDirectories(projectPath)
        logger.info("发现 {} 个源代码目录: {}", allSourceDirs.size,
            allSourceDirs.map { it.moduleName })

        val modules = allSourceDirs.map { srcDir ->
            ModuleInfo(
                name = srcDir.moduleName,
                type = ModuleType.GRADLE, // 简化：假设都是 Gradle
                path = srcDir.modulePath
            )
        }

        val packages = detectPackages(allSourceDirs)
        val layers = detectLayers(allSourceDirs)
        val totalFiles = countFiles(allSourceDirs)
        val totalLines = countLines(allSourceDirs)

        return ProjectStructure(
            rootPath = projectPath.toString(),
            modules = modules,
            packages = packages,
            layers = layers,
            totalFiles = totalFiles,
            totalLines = totalLines
        )
    }

    private fun detectPackages(allSourceDirs: List<SourceDirectory>): List<PackageInfo> {
        val packages = mutableListOf<PackageInfo>()

        for (srcDir in allSourceDirs) {
            for (sourcePath in srcDir.sourcePaths) {
                try {
                    val sourcePathObj = java.nio.file.Path.of(sourcePath)
                    Files.walk(sourcePathObj)
                        .filter { it.toFile().isDirectory }
                        .filter { dir ->
                            val files = Files.list(dir).toList()
                            files.any { it.toString().endsWith(".kt") || it.toString().endsWith(".java") }
                        }
                        .forEach { pkgDir ->
                            val packagePath = sourcePathObj.relativize(pkgDir).toString()
                                .replace("/", ".")
                            val classCount = Files.list(pkgDir).toList().count {
                                it.toString().endsWith(".kt") || it.toString().endsWith(".java")
                            }

                            packages.add(PackageInfo(
                                name = packagePath,
                                path = pkgDir.toString(),
                                classCount = classCount.toInt()
                            ))
                        }
                } catch (e: Exception) {
                    logger.warn("扫描目录失败: $sourcePath", e)
                }
            }
        }

        return packages
    }

    private fun detectLayers(allSourceDirs: List<SourceDirectory>): List<LayerInfo> {
        val layers = mutableListOf<LayerInfo>()
        val layerPatterns = mapOf(
            "controller" to LayerType.PRESENTATION,
            "web" to LayerType.PRESENTATION,
            "rest" to LayerType.PRESENTATION,
            "api" to LayerType.API,
            "service" to LayerType.SERVICE,
            "business" to LayerType.SERVICE,
            "domain" to LayerType.DOMAIN,
            "model" to LayerType.DOMAIN,
            "entity" to LayerType.DOMAIN,
            "dto" to LayerType.DOMAIN,
            "repository" to LayerType.INFRASTRUCTURE,
            "dao" to LayerType.INFRASTRUCTURE,
            "mapper" to LayerType.INFRASTRUCTURE,
            "config" to LayerType.CONFIG,
            "util" to LayerType.UTIL,
            "common" to LayerType.UTIL,
            "helper" to LayerType.UTIL,
            "infrastructure" to LayerType.INFRASTRUCTURE
        )

        for (srcDir in allSourceDirs) {
            for (sourcePath in srcDir.sourcePaths) {
                try {
                    val sourcePathObj = java.nio.file.Path.of(sourcePath)
                    Files.walk(sourcePathObj)
                        .filter { it.toFile().isDirectory }
                        .forEach { dir ->
                            val dirName = dir.fileName.toString()
                            layerPatterns[dirName]?.let { layerType ->
                                val packagePath = sourcePathObj.relativize(dir).toString()
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
                    logger.warn("检测层次失败: $sourcePath", e)
                }
            }
        }

        return layers
    }

    private fun countFiles(allSourceDirs: List<SourceDirectory>): Int {
        return allSourceDirs.sumOf { srcDir ->
            srcDir.sourcePaths.sumOf { sourcePath ->
                try {
                    val sourcePathObj = java.nio.file.Path.of(sourcePath)
                    Files.walk(sourcePathObj)
                        .filter { it.toFile().isFile }
                        .filter { file ->
                            file.toString().endsWith(".kt") ||
                            file.toString().endsWith(".java") ||
                            file.toString().endsWith(".xml")
                        }
                        .count()
                        .toInt()
                } catch (e: Exception) {
                    0
                }
            }
        }
    }

    private fun countLines(allSourceDirs: List<SourceDirectory>): Long {
        return allSourceDirs.sumOf { srcDir ->
            srcDir.sourcePaths.sumOf { sourcePath ->
                try {
                    val sourcePathObj = java.nio.file.Path.of(sourcePath)
                    Files.walk(sourcePathObj)
                        .filter { it.toFile().isFile }
                        .filter { file ->
                            file.toString().endsWith(".kt") ||
                            file.toString().endsWith(".java")
                        }
                        .mapToLong { file ->
                            Files.readAllLines(file).count().toLong()
                        }
                        .sum()
                } catch (e: Exception) {
                    0L
                }
            }
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
    PRESENTATION,   // 表现层 (controller, web, rest)
    API,            // API 层
    SERVICE,        // 服务层 (service, business)
    DOMAIN,         // 领域层 (domain, model, entity, dto)
    INFRASTRUCTURE, // 基础设施层 (repository, dao, mapper)
    CONFIG,         // 配置层
    UTIL            // 工具层 (util, common, helper)
}
