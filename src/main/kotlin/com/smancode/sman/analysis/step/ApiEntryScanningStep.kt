package com.smancode.sman.analysis.step

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.smancode.sman.analysis.entry.EntryScanner
import com.smancode.sman.analysis.model.StepResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Paths

/**
 * API 入口扫描步骤
 *
 * 穷尽扫描所有可能的系统入口：
 * - HTTP REST 入口 (@RestController, @Controller)
 * - Feign 客户端 (@FeignClient)
 * - Retrofit 接口
 * - 消息监听器 (@JmsListener, @KafkaListener, @RabbitListener)
 * - RPC 服务 (Dubbo, gRPC)
 * - 定时任务 (@Scheduled)
 * - 事件监听器 (@EventListener)
 * - Handler/Processor
 */
class ApiEntryScanningStep : AnalysisStep {

    private val logger = org.slf4j.LoggerFactory.getLogger(ApiEntryScanningStep::class.java)
    private val jsonMapper = jacksonObjectMapper()

    override val name = "api_entry_scanning"
    override val description = "API 入口扫描"

    override suspend fun execute(context: AnalysisContext): StepResult {
        val stepResult = StepResult.create(name, description).markStarted()

        return try {
            val basePath = context.project.basePath
                ?: throw IllegalArgumentException("项目路径不存在")

            // 扫描所有入口（穷尽所有可能）
            val entries = withContext(Dispatchers.IO) {
                EntryScanner().scan(Paths.get(basePath))
            }

            // 按类型分组
            val entriesByType = entries.groupBy { it.entryType.name }

            val apiEntriesJson = jsonMapper.writeValueAsString(
                mapOf(
                    "entries" to entries.map { it.qualifiedName },
                    "entryCount" to entries.size,
                    "entriesByType" to entriesByType.mapValues { it.value.size },
                    "totalMethods" to entries.sumOf { it.methodCount },
                    "controllers" to entries.filter { it.entryType.name in listOf("REST_CONTROLLER", "CONTROLLER") }.map { it.qualifiedName },
                    "controllerCount" to entries.count { it.entryType.name in listOf("REST_CONTROLLER", "CONTROLLER") },
                    "feignClients" to entries.filter { it.entryType.name == "FEIGN_CLIENT" }.map { it.qualifiedName },
                    "feignClientCount" to entries.count { it.entryType.name == "FEIGN_CLIENT" },
                    "listeners" to entries.filter {
                        it.entryType.name in listOf("JMS_LISTENER", "KAFKA_LISTENER", "RABBIT_LISTENER",
                                              "STREAM_LISTENER", "MESSAGE_CONSUMER", "GENERAL_LISTENER")
                    }.map { it.qualifiedName },
                    "listenerCount" to entries.count {
                        it.entryType.name in listOf("JMS_LISTENER", "KAFKA_LISTENER", "RABBIT_LISTENER",
                                              "STREAM_LISTENER", "MESSAGE_CONSUMER", "GENERAL_LISTENER")
                    },
                    "scheduledTasks" to entries.filter { it.entryType.name == "SCHEDULED_TASK" }.map { it.qualifiedName },
                    "scheduledTaskCount" to entries.count { it.entryType.name == "SCHEDULED_TASK" }
                )
            )
            stepResult.markCompleted(apiEntriesJson)
        } catch (e: Exception) {
            logger.error("API 入口扫描失败", e)
            stepResult.markFailed(e.message ?: "扫描失败")
        }
    }
}
