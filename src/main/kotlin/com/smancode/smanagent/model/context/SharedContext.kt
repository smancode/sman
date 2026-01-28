package com.smancode.smanagent.model.context

import com.smancode.smanagent.model.subtask.SubTaskConclusion
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * 共享上下文
 *
 * 用于在 SubTask 之间和 IntegrationAgent 之间共享信息。
 *
 * 存储内容：
 * - SubTask 结论（conclusions）：每个 SubTask 执行后的结论
 * - 全局上下文（globalContext）：领域知识、已扫描的文件等累积信息
 */
class SharedContext {

    private val logger = LoggerFactory.getLogger(SharedContext::class.java)

    /**
     * 存储 SubTask 结论（subTaskId -> SubTaskConclusion）
     */
    private val conclusions: MutableMap<String, SubTaskConclusion> = ConcurrentHashMap()

    /**
     * 全局上下文（key -> value）
     * 用于存储领域知识片段、已扫描的文件等累积信息
     */
    private val globalContext: MutableMap<String, Any> = ConcurrentHashMap()

    /**
     * 添加 SubTask 结论
     *
     * @param subTaskId   SubTask ID
     * @param conclusion  SubTask 结论
     */
    fun addConclusion(subTaskId: String, conclusion: SubTaskConclusion) {
        require(subTaskId.isNotEmpty()) { "subTaskId 不能为空" }
        require(conclusion != null) { "conclusion 不能为空" }

        conclusions[subTaskId] = conclusion
        logger.debug("添加 SubTask 结论: subTaskId={}, 结论长度={}",
            subTaskId, conclusion.conclusion?.length ?: 0)
    }

    /**
     * 获取 SubTask 结论
     *
     * @param subTaskId  SubTask ID
     * @return SubTask 结论，不存在则返回 null
     */
    fun getConclusion(subTaskId: String): SubTaskConclusion? {
        require(subTaskId.isNotEmpty()) { "subTaskId 不能为空" }
        return conclusions[subTaskId]
    }

    /**
     * 获取所有 SubTask 结论
     *
     * @return 所有 SubTask 结论的不可变视图
     */
    fun getAllConclusions(): Map<String, SubTaskConclusion> = conclusions.toMap()

    /**
     * 添加全局上下文
     *
     * @param key    键
     * @param value  值
     */
    fun addGlobalContext(key: String, value: Any) {
        require(key.isNotEmpty()) { "key 不能为空" }
        require(value != null) { "value 不能为空" }

        globalContext[key] = value
        logger.debug("添加全局上下文: key={}, 类型={}", key, value.javaClass.simpleName)
    }

    /**
     * 获取全局上下文
     *
     * @param key  键
     * @return 值，不存在则返回 null
     */
    fun getGlobalContext(key: String): Any? {
        require(key.isNotEmpty()) { "key 不能为空" }
        return globalContext[key]
    }

    /**
     * 获取所有全局上下文
     *
     * @return 所有全局上下文的不可变视图
     */
    fun getAllGlobalContext(): Map<String, Any> = globalContext.toMap()

    /**
     * 清空所有内容（用于测试）
     */
    fun clear() {
        conclusions.clear()
        globalContext.clear()
        logger.debug("清空 SharedContext")
    }

    /**
     * 获取 SubTask 结论数量
     */
    fun getConclusionCount(): Int = conclusions.size

    /**
     * 获取全局上下文数量
     */
    fun getGlobalContextCount(): Int = globalContext.size
}
