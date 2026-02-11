package com.smancode.sman.analysis.techstack

import com.smancode.sman.analysis.structure.ProjectSourceFinder
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * 技术栈识别器
 *
 * 从 build.gradle、pom.xml 等构建文件中识别项目使用的技术栈
 * 支持多模块项目
 */
class TechStackDetector {

    private val logger = LoggerFactory.getLogger(TechStackDetector::class.java)

    fun detect(projectPath: Path): TechStack {
        // 使用通用工具查找所有构建文件
        val allBuildFiles = ProjectSourceFinder.findAllBuildFiles(projectPath)
        logger.info("发现 {} 个构建文件", allBuildFiles.size)

        // 检测构建类型
        val buildType = detectBuildType(allBuildFiles)

        // 从所有构建文件中检测框架
        val frameworks = detectFrameworks(allBuildFiles)

        // 检测编程语言
        val languages = detectLanguages(projectPath)

        // 检测数据库
        val databases = detectDatabases(allBuildFiles)

        return TechStack(
            buildType = buildType,
            frameworks = frameworks,
            languages = languages,
            databases = databases
        )
    }

    private fun detectBuildType(allBuildFiles: List<Path>): BuildType {
        return when {
            allBuildFiles.any { it.fileName.toString() == "pom.xml" } -> BuildType.MAVEN
            allBuildFiles.any { it.fileName.toString() == "build.gradle.kts" } -> BuildType.GRADLE_KTS
            allBuildFiles.any { it.fileName.toString() == "build.gradle" } -> BuildType.GRADLE
            else -> BuildType.UNKNOWN
        }
    }

    private fun detectFrameworks(allBuildFiles: List<Path>): List<FrameworkInfo> {
        val frameworks = mutableListOf<FrameworkInfo>()
        val seenFrameworks = mutableSetOf<String>()

        for (buildFile in allBuildFiles) {
            try {
                val content = java.nio.file.Files.readString(buildFile)

                // Spring Boot
                if (content.contains("org.springframework.boot") || content.contains("spring-boot")) {
                    if (seenFrameworks.add("Spring Boot")) {
                        frameworks.add(FrameworkInfo(name = "Spring Boot", version = extractVersion(content, "spring-boot")))
                    }
                }

                // Spring Framework
                if (content.contains("org.springframework") && !content.contains("spring-boot")) {
                    if (seenFrameworks.add("Spring Framework")) {
                        frameworks.add(FrameworkInfo(name = "Spring Framework", version = extractVersion(content, "spring-framework")))
                    }
                }

                // Kotlin Coroutines
                if (content.contains("kotlinx-coroutines") || content.contains("org.jetbrains.kotlinx:kotlinx-coroutines")) {
                    if (seenFrameworks.add("Kotlin Coroutines")) {
                        frameworks.add(FrameworkInfo(name = "Kotlin Coroutines", version = extractVersion(content, "kotlinx-coroutines")))
                    }
                }

                // MyBatis
                if (content.contains("mybatis") || content.contains("mybatis-plus") || content.contains("org.mybatis")) {
                    if (seenFrameworks.add("MyBatis")) {
                        frameworks.add(FrameworkInfo(name = "MyBatis", version = extractVersion(content, "mybatis")))
                    }
                }

                // Hibernate
                if (content.contains("hibernate") || content.contains("org.hibernate")) {
                    if (seenFrameworks.add("Hibernate")) {
                        frameworks.add(FrameworkInfo(name = "Hibernate", version = extractVersion(content, "hibernate")))
                    }
                }

            } catch (e: Exception) {
                logger.warn("解析构建文件失败: $buildFile", e)
            }
        }

        return frameworks
    }

    private fun detectLanguages(projectPath: Path): List<LanguageInfo> {
        val languages = mutableListOf<LanguageInfo>()

        // 使用通用工具查找所有源文件
        val kotlinFiles = ProjectSourceFinder.findAllKotlinFiles(projectPath)
        val javaFiles = ProjectSourceFinder.findAllJavaFiles(projectPath)

        if (kotlinFiles.isNotEmpty()) {
            languages.add(LanguageInfo(name = "Kotlin", version = "1.9", fileCount = kotlinFiles.size))
        }

        if (javaFiles.isNotEmpty()) {
            languages.add(LanguageInfo(name = "Java", version = "17", fileCount = javaFiles.size))
        }

        return languages
    }

    private fun detectDatabases(allBuildFiles: List<Path>): List<DatabaseInfo> {
        val databases = mutableListOf<DatabaseInfo>()
        val seenDatabases = mutableSetOf<String>()

        for (buildFile in allBuildFiles) {
            try {
                val content = java.nio.file.Files.readString(buildFile)

                // H2
                if (content.contains("com.h2database") || content.contains("h2")) {
                    if (seenDatabases.add("H2")) {
                        databases.add(DatabaseInfo(name = "H2", type = DatabaseType.RELATIONAL))
                    }
                }

                // MySQL
                if (content.contains("mysql") || content.contains("mysql-connector")) {
                    if (seenDatabases.add("MySQL")) {
                        databases.add(DatabaseInfo(name = "MySQL", type = DatabaseType.RELATIONAL))
                    }
                }

                // PostgreSQL
                if (content.contains("postgresql")) {
                    if (seenDatabases.add("PostgreSQL")) {
                        databases.add(DatabaseInfo(name = "PostgreSQL", type = DatabaseType.RELATIONAL))
                    }
                }

            } catch (e: Exception) {
                logger.warn("解析构建文件失败: $buildFile", e)
            }
        }

        return databases
    }

    private fun extractVersion(content: String, artifact: String): String? {
        val patterns = listOf(
            Regex("$artifact:([\\d.]+)"),
            Regex("$artifact[^:]*:([\\d.]+)"),
            Regex("\"$artifact\":\\s*\"([\\d.]+)\""),
            Regex("'$artifact':\\s*'([\\d.]+)'"),
            Regex("[$]artifact:([\\d.]+)")
        )

        for (pattern in patterns) {
            val match = pattern.find(content)
            if (match != null) {
                return match.groupValues[1]
            }
        }

        return null
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
    RELATIONAL, NOSQL_DOCUMENT, NOSQL_KEY_VALUE, SEARCH_ENGINE, IN_MEMORY
}
