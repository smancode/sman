package com.smancode.sman.analysis.loop

import org.slf4j.LoggerFactory

/**
 * 分析接力状态机
 * 
 * 解决上下文窗口有限问题：
 * - 分层分析，每层只做一件事
 * - 状态持久化，支持中断恢复
 * - 优先级调度，高价值先分析
 */
class Analysis接力StateMachine(
    private val projectKey: String
) {
    private val logger = LoggerFactory.getLogger(Analysis接力StateMachine::class.java)

    /**
     * 分析层级
     */
    enum class AnalysisLayer {
        L0_STRUCTURE,   // 项目结构扫描
        L1_MODULE,      // 模块分析
        L2_ENTRY,       // 入口点发现
        L3_SCENARIO,    // 业务场景追踪
        L4_DEEP,       // 深度理解（非常规设计）
        COMPLETED       // 完成
    }

    /**
     * 分析状态
     */
    data class AnalysisState(
        val layer: AnalysisLayer,
        val completedModules: Set<String> = emptySet(),
        val pendingModules: List<String> = emptyList(),
        val currentModule: String? = null,
        val lastAnalysisTime: Long = 0,
        val totalAnalyzedFiles: Int = 0,
        val layerProgress: Map<AnalysisLayer, Float> = emptyMap()
    )

    /**
     * 接力点：保存状态
     */
    fun checkpoint(state: AnalysisState) {
        logger.info("保存接力点: layer={}, module={}, files={}", 
            state.layer, state.currentModule, state.totalAnalyzedFiles)
        // TODO: 持久化到 .sman/analysis/state.json
    }

    /**
     * 接力点：恢复状态
     */
    fun resume(): AnalysisState {
        logger.info("恢复接力点")
        // TODO: 从持久化恢复
        return AnalysisState(AnalysisLayer.L0_STRUCTURE)
    }

    /**
     * 决定下一步分析什么
     * 
     * 策略：
     * 1. 如果有未完成的层，继续完成
     * 2. 如果有新文件，优先分析
     * 3. 如果用户有查询，相关模块优先
     */
    fun decideNextStep(
        currentState: AnalysisState,
        userQuery: String? = null,
        newFiles: List<String> = emptyList()
    ): NextAction {
        // 策略：根据上下文决定
        return when {
            // 有新文件，优先分析
            newFiles.isNotEmpty() -> NextAction.AnalyzeFiles(newFiles)
            
            // 用户有查询，找到相关模块
            userQuery != null -> NextAction.AnalyzeModules(findRelevantModules(userQuery))
            
            // 继续当前层
            else -> NextAction.ContinueLayer(currentState.layer)
        }
    }

    /**
     * 找到相关模块（基于关键词）
     */
    private fun findRelevantModules(query: String): List<String> {
        // TODO: 从已有分析结果匹配
        return emptyList()
    }

    /**
     * 下一步动作
     */
    sealed class NextAction {
        data class AnalyzeFiles(val files: List<String>) : NextAction()
        data class AnalyzeModules(val modules: List<String>) : NextAction()
        data class ContinueLayer(val layer: AnalysisLayer) : NextAction()
        data object Complete : NextAction()
    }
}
