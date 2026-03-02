package com.smancode.sman.analysis

import org.slf4j.LoggerFactory

/**
 * 调用图分析器
 * 
 * 提供代码的调用关系分析：
 * - 获取方法的调用者（谁调用了这个方法）
 * - 获取方法的被调用者（这个方法调用了谁）
 * - 构建完整调用图
 */
class CallGraphAnalyzer {

    private val logger = LoggerFactory.getLogger(CallGraphAnalyzer::class.java)

    /**
     * 获取方法的调用者
     * 
     * @param className 类名
     * @param methodName 方法名
     * @return 调用者列表
     */
    fun getCallers(className: String, methodName: String): List<MethodRef> {
        logger.info("获取调用者: {}.{}", className, methodName)
        // TODO: 集成 IntelliJ PSI 进行实际分析
        return emptyList()
    }

    /**
     * 获取方法的被调用者
     * 
     * @param className 类名
     * @param methodName 方法名
     * @return 被调用者列表
     */
    fun getCallees(className: String, methodName: String): List<MethodRef> {
        logger.info("获取被调用者: {}.{}", className, methodName)
        // TODO: 集成 IntelliJ PSI 进行实际分析
        return emptyList()
    }

    /**
     * 构建完整调用图
     * 
     * @param entryMethod 入口方法
     * @param maxDepth 最大深度
     * @return 调用图
     */
    fun buildCallGraph(entryMethod: String, maxDepth: Int = 5): CallGraph {
        logger.info("构建调用图: entry={}, depth={}", entryMethod, maxDepth)
        // TODO: 实现
        return CallGraph(entryMethod, emptyMap())
    }

    /**
     * 查找方法调用路径
     * 
     * @param from 起始方法
     * @param to 目标方法
     * @return 调用路径列表
     */
    fun findPath(from: String, to: String): List<MethodRef> {
        logger.info("查找路径: {} -> {}", from, to)
        // TODO: BFS/DFS 搜索
        return emptyList()
    }

    /**
     * 方法引用
     */
    data class MethodRef(
        val className: String,
        val methodName: String,
        val signature: String = "",
        val filePath: String = ""
    )

    /**
     * 调用图
     */
    data class CallGraph(
        val entryMethod: String,
        val edges: Map<String, List<MethodRef>>
    )
}
