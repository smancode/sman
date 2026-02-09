package com.smancode.smanagent.analysis.retry

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * 分片处理器（支持批量失败后单条重试）
 *
 * 用途：
 * - 将大批量任务拆分为小块处理
 * - 单块失败不影响其他块
 * - 支持单条重试机制
 *
 * @param T 输入类型
 * @param R 输出类型
 * @param chunkSize 每个分片的大小
 * @param retryExecutor 重试执行器
 * @param maxConcurrentChunks 最大并发分片数
 */
class ChunkProcessor<T, R>(
    private val chunkSize: Int,
    private val retryExecutor: EnhancedRetryExecutor,
    private val maxConcurrentChunks: Int = 3
) {
    private val logger = LoggerFactory.getLogger(this::class.java)
    private val limiter = ConcurrencyLimiter(maxConcurrentChunks, "ChunkProcessor")

    init {
        require(chunkSize > 0) { "分片大小必须大于 0" }
        require(maxConcurrentChunks > 0) { "最大并发分片数必须大于 0" }
    }

    /**
     * 处理批量任务（分片 + 并发 + 重试）
     *
     * @param items 输入列表
     * @param processor 处理函数（单条处理）
     * @param identifier 标识符（用于日志）
     * @return 处理结果列表（成功的）
     */
    suspend fun processChunks(
        items: List<T>,
        processor: suspend (T) -> R,
        identifier: String = "batch"
    ): List<R> = withContext(Dispatchers.IO) {
        if (items.isEmpty()) {
            logger.warn("输入列表为空: {}", identifier)
            return@withContext emptyList()
        }

        val chunks = items.chunked(chunkSize)
        logger.info("分片处理: id={}, 总数={}, 分片数={}, 分片大小={}",
            identifier, items.size, chunks.size, chunkSize)

        val results = mutableListOf<R>()
        val failures = mutableListOf<Pair<T, Exception>>()

        // 并发处理各个分片
        chunks.mapIndexed { index, chunk ->
            async {
                limiter.execute {
                    processChunk(chunk, processor, "$identifier-chunk-$index")
                }
            }
        }.awaitAll().forEach { chunkResult ->
            results.addAll(chunkResult.successes)
            failures.addAll(chunkResult.failures)
        }

        // 单条重试失败的项
        if (failures.isNotEmpty()) {
            logger.warn("分片处理完成，但有 {} 项失败，开始单条重试", failures.size)
            val retried = retryFailures(failures, processor, identifier)
            results.addAll(retried)
        }

        logger.info("分片处理完成: id={}, 成功={}, 总数={}",
            identifier, results.size, items.size)
        results
    }

    /**
     * 处理单个分片（带单条重试）
     *
     * @param chunk 分片数据
     * @param processor 处理函数
     * @param chunkIdentifier 分片标识符
     * @return 分片处理结果
     */
    private suspend fun processChunk(
        chunk: List<T>,
        processor: suspend (T) -> R,
        chunkIdentifier: String
    ): GenericChunkResult<T, R> {
        val successes = mutableListOf<R>()
        val failures = mutableListOf<Pair<T, Exception>>()

        for ((index, item) in chunk.withIndex()) {
            try {
                val result = retryExecutor.executeWithRetry(
                    operationName = "$chunkIdentifier-$index",
                    operation = { processor(item) }
                )
                successes.add(result)
            } catch (e: Exception) {
                logger.warn("分片 {} 第 {} 项失败: {}", chunkIdentifier, index, e.message)
                failures.add(item to e)
            }
        }

        return GenericChunkResult(successes, failures)
    }

    /**
     * 重试失败的项（单条重试）
     *
     * @param failures 失败项列表
     * @param processor 处理函数
     * @param identifier 标识符
     * @return 重试成功的结果列表
     */
    private suspend fun retryFailures(
        failures: List<Pair<T, Exception>>,
        processor: suspend (T) -> R,
        identifier: String
    ): List<R> {
        val results = mutableListOf<R>()
        var successCount = 0
        var failCount = 0

        for ((item, originalException) in failures) {
            try {
                val result = retryExecutor.executeWithRetry(
                    operationName = "$identifier-retry-${item.hashCode()}",
                    operation = { processor(item) }
                )
                results.add(result)
                successCount++
            } catch (e: Exception) {
                failCount++
                logger.error("单条重试失败: item={}, 原因={}", item, e.message)
                // 记录到持久化存储
                recordFailure(item, originalException, e)
            }
        }

        logger.info("单条重试完成: 成功={}, 失败={}", successCount, failCount)
        return results
    }

    /**
     * 记录失败到持久化存储
     *
     * TODO: 实现持久化逻辑（写入 H2 数据库）
     */
    private fun recordFailure(item: T, originalException: Exception, retryException: Exception) {
        logger.error("持久化失败记录: item={}, 原因={}", item, retryException.message)
        // TODO: 实现 H2 持久化
    }

    /**
     * 分片处理结果
     */
    private data class ChunkResult<R>(
        val successes: List<R>,
        val failures: List<Pair<Any, Exception>>
    )

    private data class GenericChunkResult<T, R>(
        val successes: List<R>,
        val failures: List<Pair<T, Exception>>
    )

    companion object {
        /**
         * 创建 BGE 批处理专用分片处理器
         */
        fun forBge(
            chunkSize: Int = 10,
            maxConcurrentChunks: Int = 3,
            retryExecutor: EnhancedRetryExecutor
        ) = ChunkProcessor<String, FloatArray>(
            chunkSize = chunkSize,
            retryExecutor = retryExecutor,
            maxConcurrentChunks = maxConcurrentChunks
        )

        /**
         * 创建向量化专用分片处理器
         */
        fun forVectorization(
            chunkSize: Int = 5,
            maxConcurrentChunks: Int = 2,
            retryExecutor: EnhancedRetryExecutor
        ) = ChunkProcessor<Path, List<String>>(
            chunkSize = chunkSize,
            retryExecutor = retryExecutor,
            maxConcurrentChunks = maxConcurrentChunks
        )
    }
}
