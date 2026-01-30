package com.smancode.smanagent.analysis.util

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import org.slf4j.LoggerFactory
import java.io.File
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.Path

/**
 * 项目哈希计算器
 *
 * 计算项目的整体 MD5 哈希值，用于检测项目是否发生变化
 *
 * 扫描范围：
 * - src/main/kotlin 和 src/main/java 目录
 * - src/test/kotlin 和 src/test/java 目录
 * - build.gradle.kts, build.gradle, pom.xml 等构建文件
 * - .kts 配置文件
 */
object ProjectHashCalculator {

    private val logger = LoggerFactory.getLogger(ProjectHashCalculator::class.java)

    /**
     * 需要包含的文件扩展名
     */
    private val SOURCE_EXTENSIONS = setOf("kt", "kts", "java", "xml", "gradle")

    /**
     * 需要包含的构建文件名
     */
    private val BUILD_FILES = setOf(
        "build.gradle.kts",
        "build.gradle",
        "settings.gradle.kts",
        "settings.gradle",
        "pom.xml",
        "gradle.properties"
    )

    /**
     * 计算项目哈希值
     *
     * @param project IntelliJ 项目
     * @return MD5 哈希值
     */
    fun calculate(project: Project): String {
        val basePath = project.basePath
            ?: throw IllegalArgumentException("项目路径不存在")

        val projectPath = Path.of(basePath)

        logger.debug("开始计算项目哈希: projectKey={}", project.name)

        val digest = MessageDigest.getInstance("MD5")

        // 1. 扫描源代码文件
        scanSourceFiles(projectPath, digest)

        // 2. 扫描构建文件
        scanBuildFiles(projectPath, digest)

        val hash = digest.digest().joinToString("") { "%02x".format(it) }

        logger.debug("项目哈希计算完成: projectKey={}, md5={}", project.name, hash)

        return hash
    }

    /**
     * 扫描源代码文件
     */
    private fun scanSourceFiles(projectPath: Path, digest: MessageDigest) {
        listOf(
            "src/main/kotlin",
            "src/main/java",
            "src/test/kotlin",
            "src/test/java"
        )
            .map { projectPath.resolve(it) }
            .filter { Files.exists(it) }
            .forEach { scanDirectory(it, digest, includeFileName = true) }
    }

    /**
     * 扫描构建文件
     */
    private fun scanBuildFiles(projectPath: Path, digest: MessageDigest) {
        BUILD_FILES
            .map { projectPath.resolve(it) }
            .filter { Files.exists(it) }
            .forEach { file ->
                updateDigest(file, digest)
                // 添加文件名作为区分
                digest.update(file.fileName.toString().toByteArray())
            }
    }

    /**
     * 递归扫描目录
     *
     * @param dir 目录路径
     * @param digest MD5 摘要
     * @param includeFileName 是否包含文件名（避免不同目录同名文件混淆）
     */
    private fun scanDirectory(dir: Path, digest: MessageDigest, includeFileName: Boolean = false) {
        try {
            Files.walk(dir).use { stream ->
                stream
                    .filter { Files.isRegularFile(it) }
                    .filter { hasSourceExtension(it) }
                    .sorted()
                    .forEach { file ->
                        updateDigest(file, digest)
                        if (includeFileName) {
                            digest.update(dir.relativize(file).toString().toByteArray())
                        }
                    }
            }
        } catch (e: Exception) {
            logger.warn("扫描目录失败: {}", dir, e)
        }
    }

    private fun hasSourceExtension(file: Path): Boolean {
        val extension = file.fileName.toString().substringAfterLast('.', "")
        return extension in SOURCE_EXTENSIONS
    }

    /**
     * 更新摘要
     *
     * @param file 文件路径
     * @param digest MD5 摘要
     */
    private fun updateDigest(file: Path, digest: MessageDigest) {
        try {
            val bytes = Files.readAllBytes(file)
            digest.update(bytes)
        } catch (e: Exception) {
            logger.debug("读取文件失败，跳过: {}", file)
        }
    }
}
