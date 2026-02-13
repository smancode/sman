package com.smancode.sman.evolution.guard

/**
 * 工具调用去重器
 *
 * 使用 LRU 缓存记录最近的工具调用，防止 LLM 重复调用相同工具导致 doom loop。
 * 这是 Doom Loop 防护机制的 Layer 2 组件。
 *
 * @param maxSize 缓存最大容量，默认 100
 */
class ToolCallDeduplicator(private val maxSize: Int = DEFAULT_CACHE_SIZE) {

    /**
     * 工具调用缓存条目
     *
     * @param toolName 工具名称
     * @param parameters 工具参数
     * @param result 调用结果
     * @param timestamp 调用时间戳
     */
    data class ToolCallCache(
        val toolName: String,
        val parameters: Map<String, Any?>,
        val result: Any?,
        val timestamp: Long = System.currentTimeMillis()
    )

    /**
     * 工具调用签名
     *
     * @param toolName 工具名称
     * @param parameterHash 参数哈希值
     */
    data class CallSignature(
        val toolName: String,
        val parameterHash: Int
    )

    /**
     * LRU 缓存，使用 LinkedHashMap 的 accessOrder=true 实现
     * Key: 调用签名
     * Value: 缓存条目
     */
    private val cache: LinkedHashMap<CallSignature, ToolCallCache> = object : LinkedHashMap<CallSignature, ToolCallCache>(
        16, // initial capacity
        0.75f, // load factor
        true // accessOrder - 按访问顺序排序，实现 LRU
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CallSignature, ToolCallCache>?): Boolean {
            return size > maxSize
        }
    }

    /**
     * 计算工具调用的签名
     *
     * 签名由工具名称和参数哈希组成，用于唯一标识一次工具调用。
     *
     * @param toolName 工具名称
     * @param parameters 工具参数
     * @return 调用签名
     */
    private fun computeSignature(toolName: String, parameters: Map<String, Any?>): CallSignature {
        val parameterHash = computeParameterHash(parameters)
        return CallSignature(toolName, parameterHash)
    }

    /**
     * 计算参数的哈希值
     *
     * 递归处理嵌套的 Map 和 List，确保相同参数结构产生相同哈希。
     *
     * @param parameters 参数映射
     * @return 哈希值
     */
    private fun computeParameterHash(parameters: Map<String, Any?>): Int {
        var result = 0
        // 按键排序确保相同内容产生相同哈希
        val sortedKeys = parameters.keys.sorted()
        for (key in sortedKeys) {
            result = 31 * result + key.hashCode()
            result = 31 * result + deepHashCode(parameters[key])
        }
        return result
    }

    /**
     * 深度计算任意值的哈希值
     *
     * @param value 任意值
     * @return 哈希值
     */
    private fun deepHashCode(value: Any?): Int {
        return when (value) {
            null -> 0
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                computeParameterHash(value as Map<String, Any?>)
            }
            is List<*> -> {
                var result = 1
                for (item in value) {
                    result = 31 * result + deepHashCode(item)
                }
                result
            }
            is Set<*> -> {
                // 对 Set 排序后计算哈希，确保顺序无关
                var result = 1
                value.map { deepHashCode(it) }.sorted().forEach { hash ->
                    result = 31 * result + hash
                }
                result
            }
            is Array<*> -> value.contentDeepHashCode()
            is BooleanArray -> value.contentHashCode()
            is ByteArray -> value.contentHashCode()
            is CharArray -> value.contentHashCode()
            is ShortArray -> value.contentHashCode()
            is IntArray -> value.contentHashCode()
            is LongArray -> value.contentHashCode()
            is FloatArray -> value.contentHashCode()
            is DoubleArray -> value.contentHashCode()
            else -> value.hashCode()
        }
    }

    /**
     * 检查工具调用是否为重复调用
     *
     * @param toolName 工具名称
     * @param parameters 工具参数
     * @return 如果是重复调用返回 true，否则返回 false
     */
    fun isDuplicate(toolName: String, parameters: Map<String, Any?>): Boolean {
        val signature = computeSignature(toolName, parameters)
        return cache.containsKey(signature)
    }

    /**
     * 记录工具调用到缓存
     *
     * 如果已存在相同签名的调用，将更新为最新的调用记录（LRU 顺序调整）。
     *
     * @param toolName 工具名称
     * @param parameters 工具参数
     * @param result 调用结果
     */
    fun recordCall(toolName: String, parameters: Map<String, Any?>, result: Any?) {
        val signature = computeSignature(toolName, parameters)
        val cacheEntry = ToolCallCache(toolName, parameters, result)
        cache[signature] = cacheEntry
    }

    /**
     * 获取缓存中的调用结果
     *
     * 如果缓存中存在相同签名的调用，返回其结果；否则返回 null。
     * 此操作会触发 LRU 访问顺序更新。
     *
     * @param toolName 工具名称
     * @param parameters 工具参数
     * @return 缓存的结果，如果不存在则返回 null
     */
    fun getCachedResult(toolName: String, parameters: Map<String, Any?>): Any? {
        val signature = computeSignature(toolName, parameters)
        return cache[signature]?.result
    }

    /**
     * 获取完整的缓存条目
     *
     * @param toolName 工具名称
     * @param parameters 工具参数
     * @return 缓存条目，如果不存在则返回 null
     */
    fun getCacheEntry(toolName: String, parameters: Map<String, Any?>): ToolCallCache? {
        val signature = computeSignature(toolName, parameters)
        return cache[signature]
    }

    /**
     * 清空缓存
     */
    fun clear() {
        cache.clear()
    }

    /**
     * 获取当前缓存大小
     */
    fun size(): Int = cache.size

    /**
     * 检查缓存是否为空
     */
    fun isEmpty(): Boolean = cache.isEmpty()

    companion object {
        /**
         * 默认缓存大小
         */
        const val DEFAULT_CACHE_SIZE = 100
    }
}
