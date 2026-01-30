package com.smancode.smanagent.analysis.techstack

import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * 技术栈识别器
 *
 * 从 build.gradle、pom.xml 等构建文件中识别项目使用的技术栈
 */
class TechStackDetector {

    private val logger = LoggerFactory.getLogger(TechStackDetector::class.java)

    /**
     * 检测技术栈
     *
     * @param projectPath 项目路径
     * @return 技术栈信息
     */
    fun detect(projectPath: Path): TechStack {
        val buildType = detectBuildType(projectPath)
        val frameworks = detectFrameworks(projectPath, buildType)
        val languages = detectLanguages(projectPath)
        val databases = detectDatabases(projectPath)

        return TechStack(
            buildType = buildType,
            frameworks = frameworks,
            languages = languages,
            databases = databases
        )
    }

    /**
     * 检测构建类型
     */
    private fun detectBuildType(projectPath: Path): BuildType {
        return when {
            projectPath.resolve("build.gradle.kts").toFile().exists() -> BuildType.GRADLE_KTS
            projectPath.resolve("build.gradle").toFile().exists() -> BuildType.GRADLE
            projectPath.resolve("pom.xml").toFile().exists() -> BuildType.MAVEN
            else -> BuildType.UNKNOWN
        }
    }

    /**
     * 检测框架
     */
    private fun detectFrameworks(projectPath: Path, buildType: BuildType): List<FrameworkInfo> {
        val frameworks = mutableListOf<FrameworkInfo>()

        // 从 build.gradle.kts 检测
        if (buildType == BuildType.GRADLE_KTS || buildType == BuildType.GRADLE) {
            val buildFile = if (buildType == BuildType.GRADLE_KTS) {
                projectPath.resolve("build.gradle.kts")
            } else {
                projectPath.resolve("build.gradle")
            }

            if (buildFile.toFile().exists()) {
                val content = buildFile.readText()

                // Spring Boot
                if (content.contains("org.springframework.boot")) {
                    frameworks.add(FrameworkInfo(
                        name = "Spring Boot",
                        version = extractVersion(content, "spring-boot")
                    ))
                }

                // Spring Framework
                if (content.contains("org.springframework")) {
                    frameworks.add(FrameworkInfo(
                        name = "Spring Framework",
                        version = extractVersion(content, "spring-framework")
                    ))
                }

                // Kotlin Coroutines
                if (content.contains("kotlinx-coroutines")) {
                    frameworks.add(FrameworkInfo(
                        name = "Kotlin Coroutines",
                        version = extractVersion(content, "kotlinx-coroutines")
                    ))
                }

                // Ktor
                if (content.contains("io.ktor")) {
                    frameworks.add(FrameworkInfo(
                        name = "Ktor",
                        version = extractVersion(content, "ktor")
                    ))
                }

                // Micronaut
                if (content.contains("io.micronaut")) {
                    frameworks.add(FrameworkInfo(
                        name = "Micronaut",
                        version = extractVersion(content, "micronaut")
                    ))
                }
            }
        }

        // 从 pom.xml 检测
        if (buildType == BuildType.MAVEN) {
            val pomFile = projectPath.resolve("pom.xml")
            if (pomFile.toFile().exists()) {
                val content = pomFile.readText()

                // Spring Boot
                if (content.contains("spring-boot-starter")) {
                    frameworks.add(FrameworkInfo(
                        name = "Spring Boot",
                        version = extractVersion(content, "spring-boot-starter-parent")
                    ))
                }

                // MyBatis
                if (content.contains("mybatis") || content.contains("mybatis-plus")) {
                    frameworks.add(FrameworkInfo(
                        name = "MyBatis",
                        version = extractVersion(content, "mybatis")
                    ))
                }

                // Hibernate
                if (content.contains("hibernate")) {
                    frameworks.add(FrameworkInfo(
                        name = "Hibernate",
                        version = extractVersion(content, "hibernate")
                    ))
                }
            }
        }

        return frameworks
    }

    /**
     * 检测编程语言
     */
    private fun detectLanguages(projectPath: Path): List<LanguageInfo> {
        val languages = mutableListOf<LanguageInfo>()

        val srcMain = projectPath.resolve("src/main")

        // 检测 Kotlin
        val kotlinFiles = countFilesByExtension(srcMain, "kt")
        if (kotlinFiles > 0) {
            languages.add(LanguageInfo(
                name = "Kotlin",
                version = "1.9",
                fileCount = kotlinFiles
            ))
        }

        // 检测 Java
        val javaFiles = countFilesByExtension(srcMain, "java")
        if (javaFiles > 0) {
            languages.add(LanguageInfo(
                name = "Java",
                version = "17",
                fileCount = javaFiles
            ))
        }

        // 检测 Groovy
        val groovyFiles = countFilesByExtension(srcMain, "groovy")
        if (groovyFiles > 0) {
            languages.add(LanguageInfo(
                name = "Groovy",
                version = "3.0",
                fileCount = groovyFiles
            ))
        }

        return languages
    }

    /**
     * 检测数据库
     */
    private fun detectDatabases(projectPath: Path): List<DatabaseInfo> {
        val databases = mutableListOf<DatabaseInfo>()

        // 从 build.gradle/pom.xml 检测
        val buildFile = projectPath.resolve("build.gradle.kts")
        if (buildFile.toFile().exists()) {
            val content = buildFile.readText()

            // PostgreSQL
            if (content.contains("postgresql")) {
                databases.add(DatabaseInfo(
                    name = "PostgreSQL",
                    type = DatabaseType.RELATIONAL
                ))
            }

            // MySQL
            if (content.contains("mysql")) {
                databases.add(DatabaseInfo(
                    name = "MySQL",
                    type = DatabaseType.RELATIONAL
                ))
            }

            // MongoDB
            if (content.contains("mongodb")) {
                databases.add(DatabaseInfo(
                    name = "MongoDB",
                    type = DatabaseType.NOSQL_DOCUMENT
                ))
            }

            // Redis
            if (content.contains("redis") || content.contains("jedis")) {
                databases.add(DatabaseInfo(
                    name = "Redis",
                    type = DatabaseType.NOSQL_KEY_VALUE
                ))
            }

            // Elasticsearch
            if (content.contains("elasticsearch")) {
                databases.add(DatabaseInfo(
                    name = "Elasticsearch",
                    type = DatabaseType.SEARCH_ENGINE
                ))
            }
        }

        return databases
    }

    /**
     * 提取版本号
     */
    private fun extractVersion(content: String, artifact: String): String? {
        // 匹配版本号模式
        val versionPattern = Regex("$artifact[^:]*:([\\d.]+)")
        val match = versionPattern.find(content)
        return match?.groupValues?.get(1)
    }

    /**
     * 统计指定扩展名的文件数量
     */
    private fun countFilesByExtension(baseDir: Path, extension: String): Int {
        if (!baseDir.toFile().exists()) return 0

        return try {
            java.nio.file.Files.walk(baseDir)
                .filter { it.toFile().isFile }
                .filter { it.toString().endsWith(".$extension") }
                .count()
                .toInt()
        } catch (e: Exception) {
            logger.warn("Failed to count files with extension: $extension", e)
            0
        }
    }
}

/**
 * 技术栈信息
 */
@Serializable
data class TechStack(
    val buildType: BuildType,
    val frameworks: List<FrameworkInfo>,
    val languages: List<LanguageInfo>,
    val databases: List<DatabaseInfo>
)

/**
 * 构建类型
 */
@Serializable
enum class BuildType {
    GRADLE_KTS, GRADLE, MAVEN, UNKNOWN
}

/**
 * 框架信息
 */
@Serializable
data class FrameworkInfo(
    val name: String,
    val version: String? = null
)

/**
 * 语言信息
 */
@Serializable
data class LanguageInfo(
    val name: String,
    val version: String? = null,
    val fileCount: Int = 0
)

/**
 * 数据库信息
 */
@Serializable
data class DatabaseInfo(
    val name: String,
    val type: DatabaseType
)

/**
 * 数据库类型
 */
@Serializable
enum class DatabaseType {
    RELATIONAL,        // 关系型数据库
    NOSQL_DOCUMENT,    // 文档型 NoSQL
    NOSQL_KEY_VALUE,   // 键值型 NoSQL
    SEARCH_ENGINE,     // 搜索引擎
    IN_MEMORY          // 内存数据库
}
