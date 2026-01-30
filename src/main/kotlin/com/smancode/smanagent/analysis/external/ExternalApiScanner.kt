package com.smancode.smanagent.analysis.external

import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * 外调接口扫描器
 *
 * 扫描项目中的外部接口调用（HTTP、Feign、Retrofit 等）
 */
class ExternalApiScanner {

    private val logger = LoggerFactory.getLogger(ExternalApiScanner::class.java)

    /**
     * 扫描外部接口
     *
     * @param projectPath 项目路径
     * @return 外部接口信息列表
     */
    fun scan(projectPath: Path): List<ExternalApiInfo> {
        val apis = mutableListOf<ExternalApiInfo>()

        val srcMain = projectPath.resolve("src/main/kotlin")
        if (!srcMain.toFile().exists()) {
            return apis
        }

        try {
            java.nio.file.Files.walk(srcMain)
                .filter { it.toFile().isFile }
                .filter { it.toString().endsWith(".kt") }
                .forEach { file ->
                    try {
                        val fileApis = parseFileApis(file)
                        apis.addAll(fileApis)
                    } catch (e: Exception) {
                        logger.debug("Failed to parse file: $file")
                    }
                }
        } catch (e: Exception) {
            logger.error("Failed to scan external APIs", e)
        }

        return apis
    }

    /**
     * 解析文件中的外部接口
     */
    private fun parseFileApis(file: Path): List<ExternalApiInfo> {
        val apis = mutableListOf<ExternalApiInfo>()
        val content = file.toFile().readText()

        // 提取包名
        val packagePattern = Regex("package\\s+([\\w.]+)")
        val packageMatch = packagePattern.find(content)
        val packageName = packageMatch?.groupValues?.get(1) ?: ""

        // 扫描 Feign 客户端
        apis.addAll(scanFeignClients(content, packageName, file))

        // 扫描 Retrofit 接口
        apis.addAll(scanRetrofitInterfaces(content, packageName, file))

        // 扫描 RestTemplate 调用
        apis.addAll(scanRestTemplateCalls(content, packageName, file))

        // 扫描 HTTP 客户端调用
        apis.addAll(scanHttpClientCalls(content, packageName, file))

        return apis
    }

    /**
     * 扫描 Feign 客户端
     */
    private fun scanFeignClients(content: String, packageName: String, file: Path): List<ExternalApiInfo> {
        val apis = mutableListOf<ExternalApiInfo>()

        // 检查是否包含 @FeignClient 注解
        if (!content.contains("@FeignClient") && !content.contains("FeignClient")) {
            return apis
        }

        // 提取接口名
        val interfacePattern = Regex("(?:interface|class)\\s+(\\w+)")
        val interfaceMatch = interfacePattern.find(content)
        val interfaceName = interfaceMatch?.groupValues?.get(1) ?: return apis

        // 提取 base URL（从 @FeignClient 注解）
        val urlPattern = Regex("@FeignClient\\(.*?url\\s*=\\s*[\\\"]([^\\\"]+)[\\\"].*?\\)")
        val urlMatch = urlPattern.find(content)
        val baseUrl = urlMatch?.groupValues?.get(1) ?: ""

        // 提取 name 或 value
        val namePattern = Regex("@FeignClient\\(.*?(?:name|value)\\s*=\\s*[\\\"]([^\\\"]+)[\\\"].*?\\)")
        val nameMatch = namePattern.find(content)
        val serviceName = nameMatch?.groupValues?.get(1) ?: interfaceName

        // 提取方法
        val methods = extractApiMethods(content)

        apis.add(ExternalApiInfo(
            apiName = interfaceName,
            apiType = ExternalApiType.FEIGN,
            baseUrl = baseUrl,
            serviceName = serviceName,
            methods = methods,
            qualifiedName = if (packageName.isNotEmpty()) "$packageName.$interfaceName" else interfaceName,
            filePath = file.toString()
        ))

        return apis
    }

    /**
     * 扫描 Retrofit 接口
     */
    private fun scanRetrofitInterfaces(content: String, packageName: String, file: Path): List<ExternalApiInfo> {
        val apis = mutableListOf<ExternalApiInfo>()

        // 检查是否包含 Retrofit 注解
        val retrofitAnnotations = listOf("@GET", "@POST", "@PUT", "@DELETE", "@PATCH")
        if (!retrofitAnnotations.any { content.contains(it) }) {
            return apis
        }

        // 提取接口名
        val interfacePattern = Regex("interface\\s+(\\w+)")
        val interfaceMatch = interfacePattern.find(content) ?: return apis
        val interfaceName = interfaceMatch.groupValues[1]

        // 提取 base URL（如果有）
        val urlPattern = Regex("(?:BASE_URL|baseUrl)\\s*=\\s*[\\\"]([^\\\"]+)[\\\"]")
        val urlMatch = urlPattern.find(content)
        val baseUrl = urlMatch?.groupValues?.get(1) ?: ""

        // 提取方法
        val methods = extractRetrofitMethods(content)

        apis.add(ExternalApiInfo(
            apiName = interfaceName,
            apiType = ExternalApiType.RETROFIT,
            baseUrl = baseUrl,
            serviceName = interfaceName,
            methods = methods,
            qualifiedName = if (packageName.isNotEmpty()) "$packageName.$interfaceName" else interfaceName,
            filePath = file.toString()
        ))

        return apis
    }

    /**
     * 扫描 RestTemplate 调用
     */
    private fun scanRestTemplateCalls(content: String, packageName: String, file: Path): List<ExternalApiInfo> {
        val apis = mutableListOf<ExternalApiInfo>()

        // 检查是否包含 RestTemplate
        if (!content.contains("RestTemplate")) {
            return apis
        }

        // 提取类名
        val classPattern = Regex("(?:class|object)\\s+(\\w+)")
        val classMatch = classPattern.find(content) ?: return apis
        val className = classMatch.groupValues[1]

        // 提取 RestTemplate 调用的 URL 和方法
        val methods = extractRestTemplateMethods(content)

        if (methods.isNotEmpty()) {
            apis.add(ExternalApiInfo(
                apiName = className,
                apiType = ExternalApiType.REST_TEMPLATE,
                baseUrl = "",
                serviceName = className,
                methods = methods,
                qualifiedName = if (packageName.isNotEmpty()) "$packageName.$className" else className,
                filePath = file.toString()
            ))
        }

        return apis
    }

    /**
     * 扫描 HTTP 客户端调用
     */
    private fun scanHttpClientCalls(content: String, packageName: String, file: Path): List<ExternalApiInfo> {
        val apis = mutableListOf<ExternalApiInfo>()

        // 检查是否包含 HTTP 调用
        val httpIndicators = listOf("OkHttp", "HttpClient", "HttpRequest", "HttpURLConnection")
        if (!httpIndicators.any { content.contains(it) }) {
            return apis
        }

        // 提取类名
        val classPattern = Regex("(?:class|object)\\s+(\\w+)")
        val classMatch = classPattern.find(content) ?: return apis
        val className = classMatch.groupValues[1]

        // 提取 HTTP 调用
        val methods = extractHttpClientMethods(content)

        if (methods.isNotEmpty()) {
            apis.add(ExternalApiInfo(
                apiName = className,
                apiType = ExternalApiType.HTTP_CLIENT,
                baseUrl = "",
                serviceName = className,
                methods = methods,
                qualifiedName = if (packageName.isNotEmpty()) "$packageName.$className" else className,
                filePath = file.toString()
            ))
        }

        return apis
    }

    /**
     * 提取 API 方法（通用）
     */
    private fun extractApiMethods(content: String): List<ApiMethodInfo> {
        val methods = mutableListOf<ApiMethodInfo>()

        // 提取方法签名
        val funPattern = Regex("fun\\s+(\\w+)\\s*\\(([^)]*)\\)(?:\\s*:\\s*(\\w+))?")
        funPattern.findAll(content).forEach { match ->
            val methodName = match.groupValues[1]
            val parametersStr = match.groupValues[2]
            val returnType = match.groupValues[3].ifEmpty { "Unit" }

            // 提取 HTTP 方法和路径（从注解）
            val httpMethodAndPath = extractHttpMapping(content, methodName)
            val httpMethod = httpMethodAndPath?.first ?: "?"
            val path = httpMethodAndPath?.second ?: ""

            methods.add(ApiMethodInfo(
                name = methodName,
                httpMethod = httpMethod,
                path = path,
                parameters = parseParameters(parametersStr),
                returnType = returnType
            ))
        }

        return methods
    }

    /**
     * 提取 Retrofit 方法
     */
    private fun extractRetrofitMethods(content: String): List<ApiMethodInfo> {
        val methods = mutableListOf<ApiMethodInfo>()

        // 提取方法签名及注解
        val methodPattern = Regex(
            "(?:(@GET|@POST|@PUT|@DELETE|@PATCH)\\s*\\([\\\"]?([^\\\")]+)[\\\"]?\\)\\s*)?fun\\s+(\\w+)\\s*\\(([^)]*)\\)(?:\\s*:\\s*(\\w+))?"
        )

        methodPattern.findAll(content).forEach { match ->
            val httpMethod = match.groupValues[1].ifEmpty { "?" }
            val path = match.groupValues[2]
            val methodName = match.groupValues[3]
            val parametersStr = match.groupValues[4]
            val returnType = match.groupValues[5].ifEmpty { "Unit" }

            methods.add(ApiMethodInfo(
                name = methodName,
                httpMethod = httpMethod,
                path = path,
                parameters = parseParameters(parametersStr),
                returnType = returnType
            ))
        }

        return methods
    }

    /**
     * 提取 RestTemplate 方法
     */
    private fun extractRestTemplateMethods(content: String): List<ApiMethodInfo> {
        val methods = mutableListOf<ApiMethodInfo>()

        // 查找 exchange/getForEntity/postForEntity 等调用
        val callPatterns = listOf(
            Regex("""\.exchange\s*\(\s*["']([^"']+)["']\s*,\s*(\w+)"""),
            Regex("""\.getForEntity\s*\(\s*["']([^"']+)["']\s*,\s*(\w+)"""),
            Regex("""\.postForEntity\s*\(\s*["']([^"']+)["']\s*,\s*([^,]+)""")
        )

        callPatterns.forEach { pattern ->
            pattern.findAll(content).forEach { match ->
                val url = match.groupValues[1]
                val httpMethod = if (match.groupValues[2].contains("GET")) "GET" else "POST"

                methods.add(ApiMethodInfo(
                    name = "httpCall",
                    httpMethod = httpMethod,
                    path = url,
                    parameters = emptyList(),
                    returnType = "ResponseEntity"
                ))
            }
        }

        return methods.distinctBy { it.path }
    }

    /**
     * 提取 HTTP 客户端方法
     */
    private fun extractHttpClientMethods(content: String): List<ApiMethodInfo> {
        val methods = mutableListOf<ApiMethodInfo>()

        // 查找 HTTP URL
        val urlPattern = Regex("""["']https?://[^"']+["']""")
        urlPattern.findAll(content).forEach { match ->
            val url = match.groupValues[1].removeSurrounding("\"", "'")

            methods.add(ApiMethodInfo(
                name = "httpRequest",
                httpMethod = "?",
                path = url,
                parameters = emptyList(),
                returnType = "Response"
            ))
        }

        return methods.distinctBy { it.path }
    }

    /**
     * 提取 HTTP 映射（方法 + 路径）
     */
    private fun extractHttpMapping(content: String, methodName: String): Pair<String, String>? {
        // 在方法定义前查找注解
        val methodIndex = content.indexOf("fun $methodName")
        if (methodIndex < 0) return null

        // 向前查找注解
        val beforeMethod = content.substring(maxOf(0, methodIndex - 200), methodIndex)

        // 匹配 Spring MVC 注解
        val mappingPatterns = listOf(
            Regex("@(GetMapping|PostMapping|PutMapping|DeleteMapping|PatchMapping|RequestMapping)\\s*\\(\\s*[\"']?([^\"')\\]]+)"),
            Regex("@RequestMapping\\s*\\(\\s*method\\s*=\\s*\\w+\\.([A-Z]+)\\s*,\\s*value\\s*=\\s*[\"']?([^\"')\\]]+)")
        )

        for (pattern in mappingPatterns) {
            val match = pattern.find(beforeMethod)
            if (match != null) {
                val method = when (match.groupValues[1]) {
                    "GetMapping", "GET" -> "GET"
                    "PostMapping", "POST" -> "POST"
                    "PutMapping", "PUT" -> "PUT"
                    "DeleteMapping", "DELETE" -> "DELETE"
                    "PatchMapping", "PATCH" -> "PATCH"
                    else -> "?"
                }
                val path = match.groupValues.getOrNull(2) ?: ""
                return Pair(method, path)
            }
        }

        return null
    }

    /**
     * 解析参数
     */
    private fun parseParameters(parametersStr: String): List<String> {
        if (parametersStr.isBlank()) {
            return emptyList()
        }

        return parametersStr.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { param ->
                // 提取参数名
                val namePattern = Regex("(\\w+)\\s*:\\s*\\w+")
                val match = namePattern.find(param)
                match?.groupValues?.get(1) ?: param
            }
    }
}

/**
 * 外部接口信息
 */
@Serializable
data class ExternalApiInfo(
    val apiName: String,
    val apiType: ExternalApiType,
    val baseUrl: String,
    val serviceName: String,
    val methods: List<ApiMethodInfo>,
    val qualifiedName: String,
    val filePath: String
)

/**
 * 外部接口类型
 */
@Serializable
enum class ExternalApiType {
    FEIGN,           // OpenFeign 声明式 HTTP 客户端
    RETROFIT,        // Retrofit 接口
    REST_TEMPLATE,   // Spring RestTemplate
    HTTP_CLIENT,     // OkHttp/Apache HttpClient
    GATEWAY,         // API Gateway 路由
    UNKNOWN          // 未知类型
}

/**
 * API 方法信息
 */
@Serializable
data class ApiMethodInfo(
    val name: String,
    val httpMethod: String,
    val path: String,
    val parameters: List<String>,
    val returnType: String
)
