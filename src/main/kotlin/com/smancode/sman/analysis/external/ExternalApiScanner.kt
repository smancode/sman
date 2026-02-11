package com.smancode.sman.analysis.external

import com.smancode.sman.analysis.model.ApiMethodInfo
import com.smancode.sman.analysis.model.ApiParameterInfo
import com.smancode.sman.analysis.model.ApiType
import com.smancode.sman.analysis.model.ExternalApiInfo
import com.smancode.sman.analysis.structure.ProjectSourceFinder
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.regex.Pattern

/**
 * 外调接口扫描器
 *
 * 支持两种扫描方式：
 * 1. 基于 PSI 的类级别扫描（scanClass）- 用于精确分析
 * 2. 基于文件的项目级别扫描（scan）- 用于全项目扫描
 */
class ExternalApiScanner {

    private val logger = LoggerFactory.getLogger(ExternalApiScanner::class.java)

    companion object {
        // Feign 客户端注解模式
        private const val FEIGN_ANNOTATION = "FeignClient"

        // 正则表达式常量
        private val FEIGN_NAME_PATTERN = Pattern.compile("name\\s*=\\s*[\"']([^\"']+)[\"']")
        private val FEIGN_URL_PATTERN = Pattern.compile("url\\s*=\\s*[\"']([^\"']+)[\"']")
        private val STRING_LITERAL_PATTERN = Pattern.compile("[\"']([^\"']+)[\"']")
        private val BASE_URL_PATTERN = Regex("(?:BASE_URL|baseUrl)\\s*=\\s*[\\\"']([^\\\"']+)[\\\"']")
        private val PACKAGE_PATTERN = Regex("package\\s+([\\w.]+)")
        private val FUN_SIGNATURE_PATTERN = Regex("fun\\s+(\\w+)\\s*\\(([^)]*)\\)(?:\\s*:\\s*(\\w+))?")
        private val TYPE_NAME_PATTERN = Regex("(?:interface|class|object)\\s+(\\w+)")
        private val ANNOTATION_VALUE_PATTERN = Regex("([\\\"'])([^\\\"']+)(\\1)")

        // Retrofit HTTP 方法注解
        private val RETROFIT_HTTP_ANNOTATIONS = setOf("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS")

        // Spring HTTP 方法注解映射
        private val SPRING_HTTP_ANNOTATIONS = mapOf(
            "GetMapping" to "GET",
            "PostMapping" to "POST",
            "PutMapping" to "PUT",
            "DeleteMapping" to "DELETE",
            "PatchMapping" to "PATCH",
            "RequestMapping" to "REQUEST"
        )

        // RestTemplate 调用模式
        private val REST_TEMPLATE_PATTERNS = listOf(
            Regex("""\.exchange\s*\(\s*["']([^"']+)["']\s*,\s*(\w+)"""),
            Regex("""\.getForEntity\s*\(\s*["']([^"']+)["']\s*,\s*(\w+)"""),
            Regex("""\.postForEntity\s*\(\s*["']([^"']+)["']\s*,\s*([^,]+)""")
        )

        // RestClient 调用模式 (Spring 6.1+)
        // 支持 DotALL 模式（多行匹配）
        // 匹配 .post().uri(变量) 或 .post().uri("字符串") 两种形式
        private val REST_CLIENT_PATTERNS = listOf(
            Regex("""\.get\(\)\s*\.uri\(([^)]+)\)""", RegexOption.DOT_MATCHES_ALL),
            Regex("""\.post\(\)\s*\.uri\(([^)]+)\)""", RegexOption.DOT_MATCHES_ALL),
            Regex("""\.put\(\)\s*\.uri\(([^)]+)\)""", RegexOption.DOT_MATCHES_ALL),
            Regex("""\.delete\(\)\s*\.uri\(([^)]+)\)""", RegexOption.DOT_MATCHES_ALL),
            Regex("""\.patch\(\)\s*\.uri\(([^)]+)\)""", RegexOption.DOT_MATCHES_ALL)
        )

        // HTTP URL 模式
        private val HTTP_URL_PATTERN = Regex("""["']https?://[^"']+["']""")

        // Retrofit 方法模式
        private val RETROFIT_METHOD_PATTERN = Regex(
            "(?:(@GET|@POST|@PUT|@DELETE|@PATCH)\\s*\\([\\\"]?([^\\\")]+)[\\\"]?\\)\\s*)?fun\\s+(\\w+)\\s*\\(([^)]*)\\)(?:\\s*:\\s*(\\w+))?"
        )

        // HTTP 指示器
        private val HTTP_INDICATORS = listOf("OkHttp", "HttpClient", "HttpRequest", "HttpURLConnection", "RestClient")

        // Retrofit 注解列表
        private val RETROFIT_ANNOTATIONS = listOf("@GET", "@POST", "@PUT", "@DELETE", "@PATCH")
    }

    /**
     * 扫描单个类，提取外调接口信息（基于 PSI）
     *
     * @param ktClass Kotlin 类
     * @return 外调接口信息列表
     * @throws IllegalArgumentException 如果 ktClass 为 null
     */
    fun scanClass(ktClass: KtClass?): List<ExternalApiInfo> {
        // 白名单校验：ktClass 不能为 null
        if (ktClass == null) {
            throw IllegalArgumentException("ktClass 不能为 null")
        }

        // 白名单校验：必须有全限定名
        val qualifiedName = ktClass.fqName?.asString()
            ?: throw IllegalArgumentException("缺少全限定名: ${ktClass.name}")

        // 检测 API 类型
        val apiType = detectApiType(ktClass)

        // 如果不是外调接口，返回空列表
        if (apiType == ApiType.UNKNOWN) {
            return emptyList()
        }

        // 提取 Feign/Retrofit 配置
        val config = extractApiConfig(ktClass, apiType)

        // 扫描方法
        val methods = scanMethods(ktClass, apiType)

        return listOf(
            ExternalApiInfo(
                qualifiedName = qualifiedName,
                simpleName = ktClass.getName() ?: "",
                apiType = apiType,
                targetUrl = config.targetUrl,
                serviceName = config.serviceName,
                methods = methods
            )
        )
    }

    /**
     * 检测 API 类型
     */
    private fun detectApiType(ktClass: KtClass): ApiType {
        val annotations = ktClass.annotationEntries.map { it.shortName?.asString() }.filterNotNull()

        return when {
            annotations.any { it.contains("FeignClient") } -> ApiType.FEIGN
            annotations.any { it == "GET" || it == "POST" || it == "PUT" || it == "DELETE" ||
                                it == "PATCH" || it == "HEAD" || it == "OPTIONS" } -> ApiType.RETROFIT
            else -> ApiType.UNKNOWN
        }
    }

    /**
     * 提取 API 配置（服务名、URL）
     */
    private data class ApiConfig(
        val serviceName: String? = null,
        val targetUrl: String? = null
    )

    private fun extractApiConfig(ktClass: KtClass, apiType: ApiType): ApiConfig {
        if (apiType == ApiType.FEIGN) {
            val annotationText = ktClass.annotationEntries
                .firstOrNull { it.shortName?.asString()?.contains("FeignClient") == true }
                ?.getText() ?: return ApiConfig()

            val nameMatcher = FEIGN_NAME_PATTERN.matcher(annotationText)
            val urlMatcher = FEIGN_URL_PATTERN.matcher(annotationText)

            val serviceName = if (nameMatcher.find()) nameMatcher.group(1) else null
            val targetUrl = if (urlMatcher.find()) urlMatcher.group(1) else null

            return ApiConfig(serviceName = serviceName, targetUrl = targetUrl)
        }

        return ApiConfig()
    }

    /**
     * 扫描方法
     */
    private fun scanMethods(ktClass: KtClass, apiType: ApiType): List<ApiMethodInfo> {
        return ktClass.declarations
            .filterIsInstance<KtNamedFunction>()
            .mapNotNull { function ->
                extractMethodInfo(function, apiType)
            }
    }

    /**
     * 提取方法信息
     */
    private fun extractMethodInfo(function: KtNamedFunction, apiType: ApiType): ApiMethodInfo? {
        val annotations = function.annotationEntries
        val httpAnnotation = annotations.firstOrNull {
            val name = it.shortName?.asString() ?: ""
            when (apiType) {
                ApiType.RETROFIT -> name in RETROFIT_HTTP_ANNOTATIONS
                ApiType.FEIGN -> SPRING_HTTP_ANNOTATIONS.containsKey(name)
                else -> false
            }
        } ?: return null

        val annotationName = httpAnnotation.shortName?.asString() ?: return null
        val httpMethod = when (apiType) {
            ApiType.RETROFIT -> annotationName.uppercase()
            ApiType.FEIGN -> SPRING_HTTP_ANNOTATIONS[annotationName] ?: "GET"
            else -> "GET"
        }

        val path = extractPathFromAnnotation(httpAnnotation)
        val returnType = function.getTypeReference()?.getText() ?: "Any"

        return ApiMethodInfo(
            name = function.getName() ?: "",
            httpMethod = httpMethod,
            path = path,
            returnType = returnType,
            parameters = emptyList()  // 暂不提取参数
        )
    }

    /**
     * 从注解中提取路径
     */
    private fun extractPathFromAnnotation(annotation: org.jetbrains.kotlin.psi.KtAnnotationEntry): String {
        val text = annotation.getText()
        val matcher = STRING_LITERAL_PATTERN.matcher(text)

        return if (matcher.find()) matcher.group(1) ?: "" else ""
    }

    /**
     * 扫描外部接口（基于文件的正则解析方式）
     *
     * @param projectPath 项目路径
     * @return 外部接口信息列表
     */
    fun scan(projectPath: Path): List<LegacyExternalApiInfo> {
        val apis = mutableListOf<LegacyExternalApiInfo>()

        try {
            val kotlinFiles = ProjectSourceFinder.findAllKotlinFiles(projectPath)
            val javaFiles = ProjectSourceFinder.findAllJavaFiles(projectPath)
            val allFiles = kotlinFiles + javaFiles

            logger.info("扫描 {} 个源文件检测外调接口 (Kotlin: {}, Java: {})",
                allFiles.size, kotlinFiles.size, javaFiles.size)

            allFiles.forEach { file ->
                try {
                    parseFileApis(file).let { apis.addAll(it) }
                } catch (e: Exception) {
                    logger.debug("解析文件失败: $file", e)
                }
            }
        } catch (e: Exception) {
            logger.error("外调接口扫描失败", e)
        }

        return apis.also { logger.info("检测到 {} 个外调接口", it.size) }
    }

    /**
     * 解析文件中的外部接口
     */
    private fun parseFileApis(file: Path): List<LegacyExternalApiInfo> {
        val content = file.toFile().readText()
        val packageName = PACKAGE_PATTERN.find(content)?.groupValues?.get(1) ?: ""

        return listOf(
            scanFeignClients(content, packageName, file),
            scanRetrofitInterfaces(content, packageName, file),
            scanRestTemplateCalls(content, packageName, file),
            scanRestClientCalls(content, packageName, file),
            scanHttpClientCalls(content, packageName, file)
        ).flatten()
    }

    /**
     * 扫描 Feign 客户端
     */
    private fun scanFeignClients(content: String, packageName: String, file: Path): List<LegacyExternalApiInfo> {
        if (!content.contains("FeignClient")) {
            return emptyList()
        }

        val interfaceName = extractTypeName(content) ?: return emptyList()
        val baseUrl = extractAnnotationValue(content, "@FeignClient\\(", "url") ?: ""
        val serviceName = extractAnnotationValue(content, "@FeignClient\\(", "name")
            ?: extractAnnotationValue(content, "@FeignClient\\(", "value")
            ?: interfaceName
        val methods = extractLegacyApiMethods(content)

        return listOf(createLegacyApiInfo(
            apiName = interfaceName,
            apiType = LegacyExternalApiType.FEIGN,
            baseUrl = baseUrl,
            serviceName = serviceName,
            methods = methods,
            packageName = packageName,
            filePath = file
        ))
    }

    /**
     * 扫描 Retrofit 接口
     */
    private fun scanRetrofitInterfaces(content: String, packageName: String, file: Path): List<LegacyExternalApiInfo> {
        if (RETROFIT_ANNOTATIONS.none { content.contains(it) }) {
            return emptyList()
        }

        val interfaceName = extractTypeName(content, "interface") ?: return emptyList()
        val baseUrl = extractBaseUrl(content) ?: ""
        val methods = extractRetrofitMethods(content)

        return listOf(createLegacyApiInfo(
            apiName = interfaceName,
            apiType = LegacyExternalApiType.RETROFIT,
            baseUrl = baseUrl,
            serviceName = interfaceName,
            methods = methods,
            packageName = packageName,
            filePath = file
        ))
    }

    /**
     * 扫描 RestTemplate 调用
     */
    private fun scanRestTemplateCalls(content: String, packageName: String, file: Path): List<LegacyExternalApiInfo> {
        return scanApiType(
            content = content,
            indicator = "RestTemplate",
            packageName = packageName,
            file = file,
            apiType = LegacyExternalApiType.REST_TEMPLATE,
            methodsExtractor = ::extractRestTemplateMethods
        )
    }

    /**
     * 扫描 RestClient 调用（Spring 6.1+）
     */
    private fun scanRestClientCalls(content: String, packageName: String, file: Path): List<LegacyExternalApiInfo> {
        if (!content.contains("RestClient")) {
            return emptyList()
        }

        logger.info("========== 检测到 RestClient 使用 ==========")
        logger.info("  文件: {}", file.fileName)
        logger.info("  包名: {}", packageName)

        val methods = extractRestClientMethods(content)
        logger.info("  提取到 {} 个方法", methods.size)
        methods.forEach { method ->
            logger.info("    - {} {}", method.httpMethod, method.path)
        }

        return scanApiType(
            content = content,
            indicator = "RestClient",
            packageName = packageName,
            file = file,
            apiType = LegacyExternalApiType.REST_CLIENT,
            methodsExtractor = ::extractRestClientMethods
        )
    }

    /**
     * 扫描 HTTP 客户端调用
     */
    private fun scanHttpClientCalls(content: String, packageName: String, file: Path): List<LegacyExternalApiInfo> {
        return if (HTTP_INDICATORS.none { content.contains(it) }) {
            emptyList()
        } else {
            scanApiType(
                content = content,
                indicator = HTTP_INDICATORS.firstOrNull { content.contains(it) } ?: "HttpClient",
                packageName = packageName,
                file = file,
                apiType = LegacyExternalApiType.HTTP_CLIENT,
                methodsExtractor = ::extractHttpClientMethods
            )
        }
    }

    /**
     * 通用 API 类型扫描方法
     */
    @Suppress("UNUSED_PARAMETER")
    private fun scanApiType(
        content: String,
        indicator: String,
        packageName: String,
        file: Path,
        apiType: LegacyExternalApiType,
        methodsExtractor: (String) -> List<LegacyApiMethodInfo>
    ): List<LegacyExternalApiInfo> {
        val className = extractTypeName(content) ?: return emptyList()
        val methods = methodsExtractor(content)

        if (methods.isEmpty()) {
            return emptyList()
        }

        return listOf(createLegacyApiInfo(
            apiName = className,
            apiType = apiType,
            baseUrl = "",
            serviceName = className,
            methods = methods,
            packageName = packageName,
            filePath = file
        ))
    }

    /**
     * 提取 API 方法（通用）
     */
    private fun extractLegacyApiMethods(content: String): List<LegacyApiMethodInfo> {
        return FUN_SIGNATURE_PATTERN.findAll(content).map { match ->
            val methodName = match.groupValues[1]
            val parametersStr = match.groupValues[2]
            val returnType = match.groupValues[3].ifEmpty { "Unit" }

            // 提取 HTTP 方法和路径（从注解）
            val (httpMethod, path) = extractHttpMapping(content, methodName) ?: ("?" to "")

            LegacyApiMethodInfo(
                name = methodName,
                httpMethod = httpMethod,
                path = path,
                parameters = parseParameters(parametersStr),
                returnType = returnType
            )
        }.toList()
    }

    /**
     * 提取 Retrofit 方法
     */
    private fun extractRetrofitMethods(content: String): List<LegacyApiMethodInfo> {
        return RETROFIT_METHOD_PATTERN.findAll(content).map { match ->
            val httpMethod = match.groupValues[1].ifEmpty { "?" }
            val path = match.groupValues[2]
            val methodName = match.groupValues[3]
            val parametersStr = match.groupValues[4]
            val returnType = match.groupValues[5].ifEmpty { "Unit" }

            LegacyApiMethodInfo(
                name = methodName,
                httpMethod = httpMethod,
                path = path,
                parameters = parseParameters(parametersStr),
                returnType = returnType
            )
        }.toList()
    }

    /**
     * 提取 RestTemplate 方法
     */
    private fun extractRestTemplateMethods(content: String): List<LegacyApiMethodInfo> {
        val methods = REST_TEMPLATE_PATTERNS.flatMap { pattern ->
            pattern.findAll(content).map { match ->
                val url = match.groupValues[1]
                val httpMethod = if (match.groupValues.size > 2 && match.groupValues[2].contains("GET")) "GET" else "POST"

                LegacyApiMethodInfo(
                    name = "httpCall",
                    httpMethod = httpMethod,
                    path = url,
                    parameters = emptyList(),
                    returnType = "ResponseEntity"
                )
            }
        }

        return methods.distinctBy { it.path }
    }

    /**
     * 提取 RestClient 方法（Spring 6.1+）
     */
    private fun extractRestClientMethods(content: String): List<LegacyApiMethodInfo> {
        val methods = REST_CLIENT_PATTERNS.flatMap { pattern ->
            pattern.findAll(content).map { match ->
                val url = match.groupValues[1]
                // 从方法调用推断 HTTP 方法
                val httpMethod = when {
                    match.groupValues[0].contains(".get()") -> "GET"
                    match.groupValues[0].contains(".post()") -> "POST"
                    match.groupValues[0].contains(".put()") -> "PUT"
                    match.groupValues[0].contains(".delete()") -> "DELETE"
                    match.groupValues[0].contains(".patch()") -> "PATCH"
                    else -> "?"
                }

                LegacyApiMethodInfo(
                    name = "httpCall",
                    httpMethod = httpMethod,
                    path = url,
                    parameters = emptyList(),
                    returnType = "String"
                )
            }
        }

        return methods.distinctBy { it.path }
    }

    /**
     * 提取 HTTP 客户端方法
     */
    private fun extractHttpClientMethods(content: String): List<LegacyApiMethodInfo> {
        val methods = HTTP_URL_PATTERN.findAll(content).map { match ->
            val url = match.groupValues[1].removeSurrounding("\"", "'")

            LegacyApiMethodInfo(
                name = "httpRequest",
                httpMethod = "?",
                path = url,
                parameters = emptyList(),
                returnType = "Response"
            )
        }.toList()

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

    // ==================== 辅助方法 ====================

    /**
     * 提取类型名称（类名或接口名）
     */
    private fun extractTypeName(content: String, keyword: String = "(?:interface|class|object)"): String? {
        val pattern = if (keyword == "(?:interface|class|object)") {
            TYPE_NAME_PATTERN
        } else {
            Regex("$keyword\\s+(\\w+)")
        }
        return pattern.find(content)?.groupValues?.get(1)
    }

    /**
     * 从注解中提取指定属性的值
     */
    private fun extractAnnotationValue(content: String, annotationPrefix: String, attributeName: String): String? {
        val pattern = Regex("$annotationPrefix.*?$attributeName\\s*=\\s*[\\\"']([^\\\"']+)[\\\"']")
        return pattern.find(content)?.groupValues?.get(1)
    }

    /**
     * 提取 base URL
     */
    private fun extractBaseUrl(content: String): String? {
        return BASE_URL_PATTERN.find(content)?.groupValues?.get(1)
    }

    /**
     * 创建 LegacyExternalApiInfo 实例
     */
    private fun createLegacyApiInfo(
        apiName: String,
        apiType: LegacyExternalApiType,
        baseUrl: String,
        serviceName: String,
        methods: List<LegacyApiMethodInfo>,
        packageName: String,
        filePath: Path
    ): LegacyExternalApiInfo {
        return LegacyExternalApiInfo(
            apiName = apiName,
            apiType = apiType,
            baseUrl = baseUrl,
            serviceName = serviceName,
            methods = methods,
            qualifiedName = if (packageName.isNotEmpty()) "$packageName.$apiName" else apiName,
            filePath = filePath.toString()
        )
    }
}

/**
 * 旧版外部接口信息（用于文件扫描）
 */
@kotlinx.serialization.Serializable
data class LegacyExternalApiInfo(
    val apiName: String,
    val apiType: LegacyExternalApiType,
    val baseUrl: String,
    val serviceName: String,
    val methods: List<LegacyApiMethodInfo>,
    val qualifiedName: String,
    val filePath: String
)

/**
 * 旧版外部接口类型
 */
@kotlinx.serialization.Serializable
enum class LegacyExternalApiType {
    FEIGN,           // OpenFeign 声明式 HTTP 客户端
    RETROFIT,        // Retrofit 接口
    REST_TEMPLATE,   // Spring RestTemplate
    REST_CLIENT,     // Spring RestClient (6.1+)
    HTTP_CLIENT,     // OkHttp/Apache HttpClient
    GATEWAY,         // API Gateway 路由
    UNKNOWN          // 未知类型
}

/**
 * 旧版 API 方法信息
 */
@kotlinx.serialization.Serializable
data class LegacyApiMethodInfo(
    val name: String,
    val httpMethod: String,
    val path: String,
    val parameters: List<String>,
    val returnType: String
)
