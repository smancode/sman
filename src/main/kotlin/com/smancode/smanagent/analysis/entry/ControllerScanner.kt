package com.smancode.smanagent.analysis.entry

import com.smancode.smanagent.analysis.structure.ProjectSourceFinder
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * Controller 扫描器
 *
 * 扫描 Spring MVC Controller 和 REST 入口
 */
class ControllerScanner {

    private val logger = LoggerFactory.getLogger(ControllerScanner::class.java)

    /**
     * 扫描所有 Controller
     *
     * @param projectPath 项目路径
     * @return Controller 信息列表
     */
    fun scan(projectPath: Path): List<ControllerInfo> {
        val controllers = mutableListOf<ControllerInfo>()

        try {
            val kotlinFiles = ProjectSourceFinder.findAllKotlinFiles(projectPath)
            val javaFiles = ProjectSourceFinder.findAllJavaFiles(projectPath)
            val allFiles = kotlinFiles + javaFiles

            logger.info("扫描 {} 个源文件检测 Controller (Kotlin: {}, Java: {})",
                allFiles.size, kotlinFiles.size, javaFiles.size)

            allFiles.forEach { file ->
                try {
                    parseControllerFile(file)?.let { controllers.add(it) }
                } catch (e: Exception) {
                    logger.debug("解析 Controller 文件失败: $file", e)
                }
            }
        } catch (e: Exception) {
            logger.error("Controller 扫描失败", e)
        }

        return controllers.also { logger.info("检测到 {} 个 Controller", it.size) }
    }

    /**
     * 解析 Controller 文件
     */
    private fun parseControllerFile(file: Path): ControllerInfo? {
        val content = file.readText()
        val fileName = file.toString()
        val isJava = fileName.endsWith(".java")

        // 检查是否为 Controller
        if (!isController(content)) {
            return null
        }

        // 提取包名
        val packagePattern = if (isJava) {
            Regex("package\\s+([\\w.]+);")
        } else {
            Regex("package\\s+([\\w.]+)")
        }
        val packageMatch = packagePattern.find(content)
        val packageName = packageMatch?.groupValues?.get(1) ?: ""

        // 提取类名
        val classPattern = if (isJava) {
            Regex("(?:public\\s+)?(?:abstract\\s+)?(?:class|interface)\\s+(\\w+)")
        } else {
            Regex("(?:class|object|interface)\\s+(\\w+)")
        }
        val classMatch = classPattern.find(content) ?: return null
        val className = classMatch.groupValues[1]
        val qualifiedName = if (packageName.isNotEmpty()) {
            "$packageName.$className"
        } else {
            className
        }

        // 提取基础路径
        val basePath = extractBasePath(content)

        // 提取 API 方法
        val apiMethods = extractApiMethods(content, isJava)

        return ControllerInfo(
            className = className,
            qualifiedName = qualifiedName,
            packageName = packageName,
            basePath = basePath,
            apiMethods = apiMethods,
            isRest = content.contains("@RestController")
        )
    }

    /**
     * 判断是否为 Controller
     */
    private fun isController(content: String): Boolean {
        return content.contains("@RestController") ||
                content.contains("@Controller") ||
                (content.contains("@RequestMapping") && (content.contains("@GetMapping") ||
                    content.contains("@PostMapping") ||
                    content.contains("@PutMapping") ||
                    content.contains("@DeleteMapping") ||
                    content.contains("@PatchMapping")))
    }

    /**
     * 提取基础路径
     */
    private fun extractBasePath(content: String): String {
        // 匹配 @RequestMapping("/xxx")
        val classLevelMapping = Regex("@RequestMapping\\s*\\(\\s*[\"']([^\"')]+)[\"']")
        val match = classLevelMapping.find(content)
        return match?.groupValues?.get(1) ?: ""
    }

    /**
     * 提取 API 方法
     */
    private fun extractApiMethods(content: String, isJava: Boolean): List<ApiMethodInfo> {
        val methods = mutableListOf<ApiMethodInfo>()

        // HTTP 方法注解
        val httpAnnotations = listOf(
            "@GetMapping", "@PostMapping", "@PutMapping", "@DeleteMapping",
            "@PatchMapping", "@RequestMapping"
        )

        for (annotation in httpAnnotations) {
            val pattern = if (isJava) {
                // Java: @GetMapping("/path") 或 @GetMapping(value = "/path")
                Regex("$annotation\\s*\\(\\s*(?:value\\s*=\\s*)?[\"']([^\"')]+)[\"']\\s*\\)")
            } else {
                // Kotlin
                Regex("$annotation\\s*\\(\\s*[\"']([^\"')]+)[\"']")
            }

            pattern.findAll(content).forEach { match ->
                val path = match.groupValues[1]

                // 尝试找到方法名（在注解后面）
                val methodStart = match.range.last
                val methodPattern = if (isJava) {
                    Regex("(?:public|private|protected|static|final|synchronized)\\s+" +
                          "(?:[\\w<>,\\s]+?)\\s+" +
                          "(\\w+)\\s*\\(")
                } else {
                    Regex("fun\\s+(\\w+)\\s*\\(")
                }

                // 在注解后查找最近的方法定义
                val afterAnnotation = content.substring(methodStart, minOf(methodStart + 500, content.length))
                val methodMatch = methodPattern.find(afterAnnotation)
                val methodName = methodMatch?.groupValues?.get(1) ?: "unknown"

                // 推断 HTTP 方法
                val httpMethod = when (annotation) {
                    "@GetMapping" -> "GET"
                    "@PostMapping" -> "POST"
                    "@PutMapping" -> "PUT"
                    "@DeleteMapping" -> "DELETE"
                    "@PatchMapping" -> "PATCH"
                    else -> "UNKNOWN"
                }

                methods.add(ApiMethodInfo(
                    name = methodName,
                    httpMethod = httpMethod,
                    path = path
                ))
            }
        }

        return methods
    }
}

/**
 * Controller 信息
 */
@Serializable
data class ControllerInfo(
    val className: String,
    val qualifiedName: String,
    val packageName: String,
    val basePath: String,
    val apiMethods: List<ApiMethodInfo>,
    val isRest: Boolean
)

/**
 * API 方法信息
 */
@Serializable
data class ApiMethodInfo(
    val name: String,
    val httpMethod: String,
    val path: String
)
