package com.smancode.smanagent.analysis.step

import com.smancode.smanagent.analysis.model.StepResult
import com.smancode.smanagent.analysis.model.StepStatus

/**
 * 项目分析步骤接口
 *
 * 每个分析步骤需要：
 * 1. 实现这个接口
 * 2. 返回序列化的结果数据
 * 3. 支持异步执行
 */
interface AnalysisStep {
    /**
     * 步骤名称（唯一标识符）
     */
    val name: String

    /**
     * 步骤描述（显示给用户）
     */
    val description: String

    /**
     * 执行分析步骤
     *
     * @param context 分析上下文
     * @return 步骤结果
     */
    suspend fun execute(context: AnalysisContext): StepResult

    /**
     * 检查步骤是否可以执行
     *
     * @param context 分析上下文
     * @return true 如果可以执行，false 表示跳过
     */
    suspend fun canExecute(context: AnalysisContext): Boolean = true
}

/**
 * 分析上下文
 *
 * 在各个步骤之间传递共享数据
 *
 * @property projectKey 项目标识符
 * @property project 项目对象（IntelliJ Project）
 * @property config 配置参数
 * @property previousSteps 前面步骤的结果
 * @property cachedAnalysis 已缓存的分析结果（用于增量分析）
 * @property currentProjectMd5 当前项目 MD5
 */
data class AnalysisContext(
    val projectKey: String,
    val project: com.intellij.openapi.project.Project,
    val config: AnalysisConfig = AnalysisConfig(),
    val previousSteps: Map<String, StepResult> = emptyMap(),
    val cachedAnalysis: com.smancode.smanagent.analysis.model.ProjectAnalysisResult? = null,
    val currentProjectMd5: String? = null
) {
    /**
     * 获取前面某个步骤的结果
     */
    fun <T> getPreviousStepResult(stepName: String, parser: (String?) -> T?): T? {
        return parser(previousSteps[stepName]?.data)
    }

    /**
     * 检查前面某个步骤是否成功
     */
    fun isPreviousStepSuccessful(stepName: String): Boolean {
        return previousSteps[stepName]?.status == StepStatus.COMPLETED
    }

    /**
     * 检查项目是否发生变化
     */
    fun hasProjectChanged(): Boolean {
        val cachedMd5 = cachedAnalysis?.projectMd5
        val currentMd5 = currentProjectMd5
        return cachedMd5 != null && currentMd5 != null && cachedMd5 != currentMd5
    }
}

/**
 * 分析配置
 */
data class AnalysisConfig(
    val enableDeepScan: Boolean = true,
    val timeoutMs: Long = 300000,  // 5 分钟
    val maxFiles: Int = 10000
)
