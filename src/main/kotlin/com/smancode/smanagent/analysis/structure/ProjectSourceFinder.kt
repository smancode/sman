package com.smancode.smanagent.analysis.structure

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * 项目源代码查找工具
 *
 * 提供通用的源代码查找能力，支持多模块项目
 * 所有扫描器都应该使用这个工具来获取源文件，而不是各自实现
 */
object ProjectSourceFinder {

    private val logger = LoggerFactory.getLogger(ProjectSourceFinder::class.java)

    /**
     * 查找所有源代码目录（main + test）
     *
     * 递归查找所有 src/ 目录，包括子模块
     */
    fun findAllSourceDirectories(projectPath: Path): List<SourceDirectory> {
        val sourceDirs = mutableListOf<SourceDirectory>()

        try {
            Files.walk(projectPath)
                .filter { it.toFile().isDirectory }
                .filter { dir -> dir.fileName.toString() == "src" }
                .filter { srcDir ->
                    // 确认 src 目录下有 kotlin 或 java 目录
                    val hasKotlin = srcDir.resolve("main/kotlin").toFile().exists()
                    val hasJava = srcDir.resolve("main/java").toFile().exists()
                    val hasTestKotlin = srcDir.resolve("test/kotlin").toFile().exists()
                    val hasTestJava = srcDir.resolve("test/java").toFile().exists()
                    hasKotlin || hasJava || hasTestKotlin || hasTestJava
                }
                .forEach { srcDir ->
                    val modulePath = srcDir.parent ?: projectPath
                    val moduleName = modulePath.fileName.toString()

                    // 找到所有的 kotlin/java 目录
                    val mainKotlin = srcDir.resolve("main/kotlin")
                    val mainJava = srcDir.resolve("main/java")
                    val testKotlin = srcDir.resolve("test/kotlin")
                    val testJava = srcDir.resolve("test/java")

                    val sourcePaths = mutableListOf<Path>()
                    if (mainKotlin.toFile().exists()) sourcePaths.add(mainKotlin)
                    if (mainJava.toFile().exists()) sourcePaths.add(mainJava)
                    if (testKotlin.toFile().exists()) sourcePaths.add(testKotlin)
                    if (testJava.toFile().exists()) sourcePaths.add(testJava)

                    if (sourcePaths.isNotEmpty()) {
                        sourceDirs.add(SourceDirectory(
                            moduleName = moduleName,
                            modulePath = modulePath.toString(),
                            srcPath = srcDir.toString(),
                            sourcePaths = sourcePaths.map { it.toString() }
                        ))
                    }
                }
        } catch (e: Exception) {
            logger.warn("查找源代码目录失败", e)
        }

        return sourceDirs
    }

    /**
     * 获取所有 Kotlin 源文件
     */
    fun findAllKotlinFiles(projectPath: Path): List<Path> {
        return findAllSourceFiles(projectPath, "kt")
    }

    /**
     * 获取所有 Java 源文件
     */
    fun findAllJavaFiles(projectPath: Path): List<Path> {
        return findAllSourceFiles(projectPath, "java")
    }

    /**
     * 获取所有源文件（Kotlin + Java）
     */
    fun findAllSourceFiles(projectPath: Path): List<Path> {
        return findAllKotlinFiles(projectPath) + findAllJavaFiles(projectPath)
    }

    /**
     * 获取所有构建文件（build.gradle, build.gradle.kts, pom.xml）
     */
    fun findAllBuildFiles(projectPath: Path): List<Path> {
        val buildFiles = mutableListOf<Path>()

        try {
            Files.walk(projectPath)
                .filter { it.toFile().isFile }
                .filter { file ->
                    val fileName = file.fileName.toString()
                    fileName == "build.gradle" ||
                    fileName == "build.gradle.kts" ||
                    fileName == "pom.xml"
                }
                .forEach { buildFiles.add(it) }
        } catch (e: Exception) {
            logger.warn("查找构建文件失败", e)
        }

        return buildFiles
    }

    /**
     * 获取所有 MyBatis Mapper XML 文件
     */
    fun findAllMyBatisMappers(projectPath: Path): List<Path> {
        val mappers = mutableListOf<Path>()

        try {
            Files.walk(projectPath)
                .filter { it.toFile().isFile }
                .filter { file ->
                    val path = file.toString()
                    // 通常在 resources/mapper/ 或 resources/mybatis/ 下
                    path.contains("mapper/") && file.toString().endsWith(".xml")
                }
                .forEach { mappers.add(it) }
        } catch (e: Exception) {
            logger.warn("查找 MyBatis Mapper 失败", e)
        }

        return mappers
    }

    /**
     * 获取所有配置文件（application.yml, application.properties）
     */
    fun findAllConfigFiles(projectPath: Path): List<Path> {
        val configs = mutableListOf<Path>()

        try {
            Files.walk(projectPath)
                .filter { it.toFile().isFile }
                .filter { file ->
                    val fileName = file.fileName.toString()
                    fileName == "application.yml" ||
                    fileName == "application.properties" ||
                    fileName == "application-*.yml" ||
                    fileName.startsWith("application-")
                }
                .filter { file ->
                    // 排除 build 目录中的文件
                    !file.toString().contains("/build/")
                }
                .forEach { configs.add(it) }
        } catch (e: Exception) {
            logger.warn("查找配置文件失败", e)
        }

        return configs
    }

    // ========== 私有方法 ==========

    private fun findAllSourceFiles(projectPath: Path, extension: String): List<Path> {
        val files = mutableListOf<Path>()

        for (srcDir in findAllSourceDirectories(projectPath)) {
            for (sourcePath in srcDir.sourcePaths) {
                try {
                    val sourcePathObj = Path.of(sourcePath)
                    if (!sourcePathObj.toFile().exists()) continue

                    Files.walk(sourcePathObj)
                        .filter { it.toFile().isFile }
                        .filter { it.toString().endsWith(".$extension") }
                        .forEach { files.add(it) }
                } catch (e: Exception) {
                    logger.warn("扫描源文件失败: path=$sourcePath, extension=$extension", e)
                }
            }
        }

        return files
    }
}

/**
 * 源代码目录信息
 */
data class SourceDirectory(
    val moduleName: String,        // 模块名（如 loan, core）
    val modulePath: String,        // 模块路径
    val srcPath: String,           // src 路径
    val sourcePaths: List<String>  // 源代码路径列表（main/kotlin, main/java 等）
)
