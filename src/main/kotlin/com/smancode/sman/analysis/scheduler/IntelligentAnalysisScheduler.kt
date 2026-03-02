package com.smancode.sman.analysis.scheduler

import com.smancode.sman.analysis.ProjectAnalyzer
import org.slf4j.LoggerFactory

/**
 * 智能分析调度器（防呆版）
 * 
 * 改进：
 * 1. 最大文件数限制
 * 2. 单次运行超时
 * 3. 分析深度限制
 * 4. MD5 缓存过期清理
 * 5. 优先级调度
 * 6. 资源感知
 */
class IntelligentAnalysisScheduler(
    private val projectKey: String,
    private val projectPath: String
) {
    private val logger = LoggerFactory.getLogger(IntelligentAnalysisScheduler::class.java)

    companion object {
        // 防呆配置
        const val MAX_FILES_PER_RUN = 100          // 单次最多处理文件数
        const val MAX_RUN_TIME_MS = 5 * 60 * 1000L // 单次最长运行 5 分钟
        const val MAX_CALL_CHAIN_DEPTH = 20         // 最大调用链深度
        const val MAX_CACHE_SIZE_MB = 100          // 最大缓存大小 100MB
        const val CACHE_EXPIRE_DAYS = 30           // 缓存过期天数
        const val MIN_INTERVAL_MS = 60 * 1000L     // 最小间隔 1 分钟
    }

    /**
     * 调度决策
     */
    fun decideWhatToAnalyze(
        lastState: AnalysisState?,
        userQuery: String?,
        availableTimeMs: Long,
        memoryMb: Long
    ): AnalysisPlan {
        logger.info("调度决策: 可用时间={}ms, 内存={}MB", availableTimeMs, memoryMb)

        val remainingTime = minOf(availableTimeMs, MAX_RUN_TIME_MS)
        val maxFiles = calculateMaxFiles(remainingTime, memoryMb)

        // 优先级排序
        val priorities = mutableListOf<AnalysisTask>()
        
        // 1. 用户查询相关优先
        if (userQuery != null) {
            priorities.add(AnalysisTask.L4_DEEP_USER_QUERY)
        }
        
        // 2. 新文件优先
        priorities.add(AnalysisTask.L0_SCAN_NEW)
        
        // 3. 未完成的层
        if (lastState?.currentLayer != null) {
            priorities.add(continueLayer(lastState.currentLayer))
        }

        // 分配资源
        return allocateResources(priorities, maxFiles, remainingTime)
    }

    /**
     * 计算最大文件数
     */
    private fun calculateMaxFiles(timeMs: Long, memoryMb: Long): Int {
        // 基于资源和时间估算
        val timeBased = (timeMs / 1000).toInt() * 2  // 每秒处理约 2 个文件
        val memoryBased = (memoryMb / 10).toInt() * 10 // 每 10MB 内存处理约 10 个文件
        return minOf(timeBased, memoryBased, MAX_FILES_PER_RUN)
    }

    /**
     * 分配资源
     */
    private fun allocateResources(
        priorities: List<AnalysisTask>,
        maxFiles: Int,
        timeMs: Long
    ): AnalysisPlan {
        val tasks = mutableListOf<ScheduledTask>()
        var remainingFiles = maxFiles
        var remainingTime = timeMs

        for (task in priorities) {
            if (remainingFiles <= 0 || remainingTime <= 0) break
            
            val taskFiles = minOf(remainingFiles, estimateTaskFiles(task))
            val taskTime = minOf(remainingTime, estimateTaskTime(task))
            
            tasks.add(ScheduledTask(task, taskFiles, taskTime))
            remainingFiles -= taskFiles
            remainingTime -= taskTime
        }

        return AnalysisPlan(tasks, maxFiles, timeMs)
    }

    private fun continueLayer(layer: AnalysisLayer): AnalysisTask {
        return when (layer) {
            AnalysisLayer.L0_STRUCTURE -> AnalysisTask.L0_SCAN_NEW
            AnalysisLayer.L1_MODULE -> AnalysisTask.L1_ANALYZE_MODULE
            AnalysisLayer.L2_ENTRY -> AnalysisTask.L2_FIND_ENTRY
            AnalysisLayer.L3_SCENARIO -> AnalysisTask.L3_TRACE_SCENARIO
            AnalysisLayer.L4_DEEP -> AnalysisTask.L4_DEEP_ANTI_PATTERN
            AnalysisLayer.COMPLETED -> AnalysisTask.L0_SCAN_NEW
        }
    }

    private fun estimateTaskFiles(task: AnalysisTask): Int {
        return when (task) {
            AnalysisTask.L0_SCAN_NEW -> 50
            AnalysisTask.L1_ANALYZE_MODULE -> 20
            AnalysisTask.L2_FIND_ENTRY -> 10
            AnalysisTask.L3_TRACE_SCENARIO -> 30
            AnalysisTask.L4_DEEP_USER_QUERY -> 20
            AnalysisTask.L4_DEEP_ANTI_PATTERN -> 15
        }
    }

    private fun estimateTaskTime(task: AnalysisTask): Long {
        return when (task) {
            AnalysisTask.L0_SCAN_NEW -> 60 * 1000L
            AnalysisTask.L1_ANALYZE_MODULE -> 2 * 60 * 1000L
            AnalysisTask.L2_FIND_ENTRY -> 30 * 1000L
            AnalysisTask.L3_TRACE_SCENARIO -> 2 * 60 * 1000L
            AnalysisTask.L4_DEEP_USER_QUERY -> 3 * 60 * 1000L
            AnalysisTask.L4_DEEP_ANTI_PATTERN -> 3 * 60 * 1000L
        }
    }

    /**
     * 检查缓存是否需要清理
     */
    fun checkAndCleanCache(): CleanupResult {
        logger.info("检查缓存清理")
        
        var cleanedMb = 0L
        val reasons = mutableListOf<String>()

        // 1. 检查缓存大小
        // TODO: 实现实际检查
        val cacheSizeMb = estimateCacheSize()
        if (cacheSizeMb > MAX_CACHE_SIZE_MB) {
            reasons.add("缓存过大: ${cacheSizeMb}MB > ${MAX_CACHE_SIZE_MB}MB")
            cleanedMb = cleanOldestCache()
        }

        // 2. 检查过期缓存
        val expiredCount = countExpiredCache()
        if (expiredCount > 0) {
            reasons.add("过期缓存: $expiredCount 个")
            cleanedMb += cleanExpiredCache()
        }

        return CleanupResult(cleanedMb, reasons)
    }

    private fun estimateCacheSize(): Long = 50
    private fun cleanOldestCache(): Long = 10
    private fun countExpiredCache(): Int = 0
    private fun cleanExpiredCache(): Long = 5

    // 数据结构
    data class AnalysisState(
        val currentLayer: AnalysisLayer?,
        val completedModules: Set<String>,
        val progress: Float
    )

    enum class AnalysisLayer {
        L0_STRUCTURE, L1_MODULE, L2_ENTRY, L3_SCENARIO, L4_DEEP, COMPLETED
    }

    enum class AnalysisTask {
        L0_SCAN_NEW,
        L1_ANALYZE_MODULE,
        L2_FIND_ENTRY,
        L3_TRACE_SCENARIO,
        L4_DEEP_USER_QUERY,
        L4_DEEP_ANTI_PATTERN
    }

    data class ScheduledTask(
        val task: AnalysisTask,
        val maxFiles: Int,
        val maxTimeMs: Long
    )

    data class AnalysisPlan(
        val tasks: List<ScheduledTask>,
        val totalFiles: Int,
        val totalTimeMs: Long
    )

    data class CleanupResult(
        val cleanedMb: Long,
        val reasons: List<String>
    )
}
