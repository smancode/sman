package com.smancode.smanagent.analysis.entry

import com.smancode.smanagent.analysis.structure.ProjectSourceFinder
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * 入口扫描器
 *
 * 穷尽扫描所有可能的系统入口类型：
 * - HTTP REST 入口 (@RestController, @Controller)
 * - Feign 客户端 (@FeignClient)
 * - Retrofit 接口
 * - 消息监听器 (@JmsListener, @KafkaListener, @RabbitListener)
 * - RPC 服务 (Dubbo @Service, gRPC)
 * - 定时任务 (@Scheduled)
 * - 事件监听器 (@EventListener)
 * - Spring Batch (Reader, Writer, Processor)
 * - 其他 Handler/Processor
 */
class EntryScanner {

    private val logger = LoggerFactory.getLogger(EntryScanner::class.java)

    /**
     * 扫描所有入口
     *
     * @param projectPath 项目路径
     * @return 入口信息列表
     */
    fun scan(projectPath: Path): List<EntryInfo> {
        val entries = mutableListOf<EntryInfo>()

        try {
            val kotlinFiles = ProjectSourceFinder.findAllKotlinFiles(projectPath)
            val javaFiles = ProjectSourceFinder.findAllJavaFiles(projectPath)
            val allFiles = kotlinFiles + javaFiles

            logger.info("扫描 {} 个源文件检测系统入口 (Kotlin: {}, Java: {})",
                allFiles.size, kotlinFiles.size, javaFiles.size)

            allFiles.forEach { file ->
                try {
                    parseEntryFile(file)?.let { entries.add(it) }
                } catch (e: Exception) {
                    logger.debug("解析入口文件失败: $file", e)
                }
            }
        } catch (e: Exception) {
            logger.error("入口扫描失败", e)
        }

        return entries.also { logger.info("检测到 {} 个系统入口", it.size) }
    }

    /**
     * 解析入口文件
     */
    private fun parseEntryFile(file: Path): EntryInfo? {
        val content = file.readText()
        val fileName = file.toString()
        val isJava = fileName.endsWith(".java")

        // 检查是否为入口类
        val entryType = detectEntryType(content)
        if (entryType == EntryType.UNKNOWN) {
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
            Regex("(?:public\\s+)?(?:abstract\\s+)?(?:class|interface|enum|record)\\s+(\\w+)")
        } else {
            Regex("(?:class|object|interface|enum|data\\s+class)\\s+(\\w+)")
        }
        val classMatch = classPattern.find(content) ?: return null
        val className = classMatch.groupValues[1]
        val qualifiedName = if (packageName.isNotEmpty()) {
            "$packageName.$className"
        } else {
            className
        }

        // 提取入口方法
        val entryMethods = extractEntryMethods(content, entryType, isJava)

        return EntryInfo(
            className = className,
            qualifiedName = qualifiedName,
            packageName = packageName,
            entryType = entryType,
            entryMethods = entryMethods,
            methodCount = entryMethods.size
        )
    }

    /**
     * 检测入口类型（穷尽所有可能）
     */
    private fun detectEntryType(content: String): EntryType {
        return when {
            // HTTP REST 入口
            content.contains("@RestController") -> EntryType.REST_CONTROLLER
            content.contains("@Controller") -> EntryType.CONTROLLER

            // Feign 客户端
            content.contains("@FeignClient") -> EntryType.FEIGN_CLIENT

            // Retrofit 接口
            content.contains("@GET") || content.contains("@POST") ||
            content.contains("@PUT") || content.contains("@DELETE") -> EntryType.RETROFIT_API

            // 消息监听器
            content.contains("@JmsListener") -> EntryType.JMS_LISTENER
            content.contains("@KafkaListener") -> EntryType.KAFKA_LISTENER
            content.contains("@RabbitListener") -> EntryType.RABBIT_LISTENER
            content.contains("@StreamListener") -> EntryType.STREAM_LISTENER

            // RPC 服务
            content.contains("@DubboService") || content.contains("org.apache.dubbo") -> EntryType.DUBBO_SERVICE
            content.contains("grpc.") -> EntryType.GRPC_SERVICE

            // 定时任务
            content.contains("@Scheduled") || content.contains("@EnableScheduling") -> EntryType.SCHEDULED_TASK

            // 事件监听器
            content.contains("@EventListener") -> EntryType.EVENT_LISTENER
            content.contains("ApplicationListener") -> EntryType.APPLICATION_LISTENER
            content.contains("@Consumer") -> EntryType.MESSAGE_CONSUMER

            // Spring Batch
            content.contains("ItemReader") || content.contains("ItemWriter") ||
            content.contains("ItemProcessor") -> EntryType.BATCH_COMPONENT

            // Handler/Processor（通用模式）
            (content.contains("Handler") || content.contains("Processor")) &&
            (content.contains("void handle") || content.contains("void process")) -> EntryType.HANDLER

            // MQ 监听
            content.contains("@Listener") -> EntryType.GENERAL_LISTENER

            else -> EntryType.UNKNOWN
        }
    }

    /**
     * 提取入口方法
     */
    private fun extractEntryMethods(content: String, entryType: EntryType, isJava: Boolean): List<EntryMethod> {
        val methods = mutableListOf<EntryMethod>()

        return when (entryType) {
            EntryType.REST_CONTROLLER, EntryType.CONTROLLER -> extractHttpMethods(content, isJava)
            EntryType.FEIGN_CLIENT -> extractFeignMethods(content, isJava)
            EntryType.RETROFIT_API -> extractRetrofitMethods(content, isJava)
            EntryType.JMS_LISTENER, EntryType.KAFKA_LISTENER, EntryType.RABBIT_LISTENER -> extractMessageMethods(content, isJava)
            EntryType.SCHEDULED_TASK -> extractScheduledMethods(content, isJava)
            else -> methods
        }
    }

    /**
     * 提取 HTTP 方法
     */
    private fun extractHttpMethods(content: String, isJava: Boolean): List<EntryMethod> {
        val methods = mutableListOf<EntryMethod>()
        val httpAnnotations = listOf(
            "@GetMapping", "@PostMapping", "@PutMapping", "@DeleteMapping",
            "@PatchMapping", "@RequestMapping"
        )

        for (annotation in httpAnnotations) {
            val pattern = Regex("$annotation\\s*\\([^)]*\\)")
            pattern.findAll(content).forEach { match ->
                val annotationText = match.value

                // 提取路径
                val pathPattern = Regex("[\"']([^\"']+)[\"']")
                val pathMatch = pathPattern.find(annotationText)
                val path = pathMatch?.groupValues?.get(1) ?: ""

                // 提取方法名
                val methodPattern = if (isJava) {
                    Regex("(?:public|private|protected)\\s+[\\w<>\\s,]+?\\s+(\\w+)\\s*\\(")
                } else {
                    Regex("fun\\s+(\\w+)\\s*\\(")
                }

                val afterAnnotation = content.substring(match.range.last)
                val methodMatch = methodPattern.find(afterAnnotation.take(500))
                val methodName = methodMatch?.groupValues?.get(1) ?: "unknown"

                val httpMethod = when (annotation) {
                    "@GetMapping" -> "GET"
                    "@PostMapping" -> "POST"
                    "@PutMapping" -> "PUT"
                    "@DeleteMapping" -> "DELETE"
                    "@PatchMapping" -> "PATCH"
                    else -> "REQUEST"
                }

                methods.add(EntryMethod(
                    name = methodName,
                    signature = "$httpMethod $path",
                    description = "HTTP 入口"
                ))
            }
        }

        return methods
    }

    /**
     * 提取 Feign 方法
     */
    private fun extractFeignMethods(content: String, isJava: Boolean): List<EntryMethod> {
        val methods = mutableListOf<EntryMethod>()
        val methodPattern = if (isJava) {
            Regex("(?:public|private|protected)\\s+(?:[\\w<>\\s,]+?)\\s+(\\w+)\\s*\\(")
        } else {
            Regex("fun\\s+(\\w+)\\s*\\(")
        }

        methodPattern.findAll(content).forEach { match ->
            val methodName = match.groupValues[1]
            // 过滤掉 Object 的方法
            if (methodName in listOf("equals", "hashCode", "toString", "notify", "notifyAll")) return@forEach

            methods.add(EntryMethod(
                name = methodName,
                signature = "FEIGN_CALL",
                description = "Feign 远程调用"
            ))
        }

        return methods
    }

    /**
     * 提取 Retrofit 方法
     */
    private fun extractRetrofitMethods(content: String, isJava: Boolean): List<EntryMethod> {
        val methods = mutableListOf<EntryMethod>()

        // 提取 HTTP 注解后的方法
        val httpAnnotations = listOf("@GET", "@POST", "@PUT", "@DELETE", "@PATCH")
        for (annotation in httpAnnotations) {
            val pattern = Regex("$annotation\\s*\\([^)]*\\)")
            pattern.findAll(content).forEach { match ->
                val annotationText = match.value

                // 提取路径
                val pathPattern = Regex("[\"']([^\"']+)[\"']")
                val pathMatch = pathPattern.find(annotationText)
                val path = pathMatch?.groupValues?.get(1) ?: ""

                val methodPattern = if (isJava) {
                    Regex("(?:public|private|protected)\\s+(?:[\\w<>\\s,]+?)\\s+(\\w+)\\s*\\(")
                } else {
                    Regex("fun\\s+(\\w+)\\s*\\(")
                }

                val afterAnnotation = content.substring(match.range.last)
                val methodMatch = methodPattern.find(afterAnnotation.take(500))
                val methodName = methodMatch?.groupValues?.get(1) ?: "unknown"

                methods.add(EntryMethod(
                    name = methodName,
                    signature = "${annotation.removePrefix("@")} $path",
                    description = "Retrofit API 调用"
                ))
            }
        }

        return methods
    }

    /**
     * 提取消息监听方法
     */
    private fun extractMessageMethods(content: String, isJava: Boolean): List<EntryMethod> {
        val methods = mutableListOf<EntryMethod>()

        // 查找 @JmsListener/@KafkaListener 等注解后的方法
        val listenerAnnotations = listOf(
            "@JmsListener", "@KafkaListener", "@RabbitListener",
            "@StreamListener", "@Listener"
        )

        for (annotation in listenerAnnotations) {
            val pattern = Regex("$annotation\\s*\\([^)]*\\)")
            pattern.findAll(content).forEach { match ->
                // 提取监听的话题/队列
                val annotationText = match.value
                val topicPattern = Regex("(?:destination|topic|queue)\\s*=\\s*[\"']([^\"']+)[\"']")
                val topicMatch = topicPattern.find(annotationText)
                val topic = topicMatch?.groupValues?.get(1) ?: ""

                val methodPattern = if (isJava) {
                    Regex("(?:public|private|protected)\\s+(?:[\\w<>\\s,]+?)\\s+(\\w+)\\s*\\(")
                } else {
                    Regex("fun\\s+(\\w+)\\s*\\(")
                }

                val afterAnnotation = content.substring(match.range.last)
                val methodMatch = methodPattern.find(afterAnnotation.take(500))
                val methodName = methodMatch?.groupValues?.get(1) ?: "unknown"

                methods.add(EntryMethod(
                    name = methodName,
                    signature = "MESSAGE: $topic",
                    description = "消息监听"
                ))
            }
        }

        return methods
    }

    /**
     * 提取定时任务方法
     */
    private fun extractScheduledMethods(content: String, isJava: Boolean): List<EntryMethod> {
        val methods = mutableListOf<EntryMethod>()

        val pattern = Regex("@Scheduled\\s*\\([^)]*\\)")
        pattern.findAll(content).forEach { match ->
            val annotationText = match.value

            // 提取 cron 表达式
            val cronPattern = Regex("cron\\s*=\\s*[\"']([^\"']+)[\"']")
            val cronMatch = cronPattern.find(annotationText)
            val cron = cronMatch?.groupValues?.get(1) ?: ""

            val methodPattern = if (isJava) {
                Regex("(?:public|private|protected)\\s+(?:[\\w<>\\s,]+?)\\s+(\\w+)\\s*\\(")
            } else {
                Regex("fun\\s+(\\w+)\\s*\\(")
            }

            val afterAnnotation = content.substring(match.range.last)
            val methodMatch = methodPattern.find(afterAnnotation.take(500))
            val methodName = methodMatch?.groupValues?.get(1) ?: "unknown"

            methods.add(EntryMethod(
                name = methodName,
                signature = "SCHEDULED: cron=$cron",
                description = "定时任务"
            ))
        }

        return methods
    }
}

/**
 * 入口信息
 */
@Serializable
data class EntryInfo(
    val className: String,
    val qualifiedName: String,
    val packageName: String,
    val entryType: EntryType,
    val entryMethods: List<EntryMethod>,
    val methodCount: Int
)

/**
 * 入口类型（穷尽所有可能）
 */
@Serializable
enum class EntryType {
    // HTTP 入口
    REST_CONTROLLER,      // @RestController
    CONTROLLER,           // @Controller

    // 远程调用客户端
    FEIGN_CLIENT,         // @FeignClient
    RETROFIT_API,         // Retrofit 接口

    // 消息监听
    JMS_LISTENER,         // @JmsListener
    KAFKA_LISTENER,       // @KafkaListener
    RABBIT_LISTENER,      // @RabbitListener
    STREAM_LISTENER,      // @StreamListener
    MESSAGE_CONSUMER,     // @Consumer

    // RPC 服务
    DUBBO_SERVICE,        // Dubbo 服务
    GRPC_SERVICE,         // gRPC 服务

    // 定时任务
    SCHEDULED_TASK,       // @Scheduled

    // 事件监听
    EVENT_LISTENER,       // @EventListener
    APPLICATION_LISTENER, // ApplicationListener

    // 批处理
    BATCH_COMPONENT,      // ItemReader/Writer/Processor

    // 通用处理
    HANDLER,              // Handler/Processor
    GENERAL_LISTENER,     // @Listener

    UNKNOWN                // 未知
}

/**
 * 入口方法
 */
@Serializable
data class EntryMethod(
    val name: String,
    val signature: String,
    val description: String
)
