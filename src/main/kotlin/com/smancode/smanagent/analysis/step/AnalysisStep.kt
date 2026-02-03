package com.smancode.smanagent.analysis.step

import com.smancode.smanagent.analysis.model.StepResult
import com.smancode.smanagent.analysis.model.StepStatus
import com.smancode.smanagent.config.SmanAgentConfig

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
     * 默认逻辑：
     * 1. 如果配置了强制刷新（analysis.force.refresh=true），总是执行
     * 2. 如果项目 MD5 发生变化，需要执行
     * 3. 如果缓存中没有该步骤结果，需要执行
     * 4. 如果缓存中的步骤失败，重新执行
     * 5. 其他情况跳过执行（复用缓存）
     *
     * @param context 分析上下文
     * @return true 如果可以执行，false 表示跳过
     */
    suspend fun canExecute(context: AnalysisContext): Boolean {
        // 1. 检查是否配置了强制刷新
        if (SmanAgentConfig.analysisForceRefresh) {
            return true
        }

        // 2. 如果项目 MD5 发生变化，需要执行
        if (context.hasProjectChanged()) {
            return true
        }

        // 3. 如果缓存中没有该步骤的结果，需要执行
        val cachedStep = context.cachedAnalysis?.steps?.get(name)
        if (cachedStep == null) {
            return true
        }

        // 4. 如果缓存中的步骤失败了，重新执行
        if (cachedStep.status != StepStatus.COMPLETED) {
            return true
        }

        // 5. 项目未变化且缓存中有成功的结果，跳过执行
        return false
    }
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
     *
     * @return true 表示项目变化了（需要重新分析），false 表示项目未变化
     */
    fun hasProjectChanged(): Boolean {
        val cachedMd5 = cachedAnalysis?.projectMd5
        val currentMd5 = currentProjectMd5

        // 如果没有当前 MD5（计算失败），保守起见认为项目变化了
        if (currentMd5 == null) {
            return true
        }

        // 如果没有缓存的 MD5（第一次分析），认为项目变化了
        if (cachedMd5 == null) {
            return true
        }

        // MD5 不同，项目变化了
        return cachedMd5 != currentMd5
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
