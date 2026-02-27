package com.smancode.sman.domain.puzzle

/**
 * LLM 分析器接口
 *
 * 定义了使用 LLM 进行代码分析的标准接口。
 * 实现类负责：
 * - 构建 Prompt
 * - 调用 LLM
 * - 解析响应为结构化结果
 */
interface LlmAnalyzer {

    /**
     * 分析代码，生成知识内容
     *
     * @param target 分析目标（文件路径或目录路径）
     * @param context 分析上下文（包含相关文件、已有知识、用户查询）
     * @return 分析结果
     * @throws AnalysisException 当分析过程出错时抛出
     */
    suspend fun analyze(target: String, context: AnalysisContext): AnalysisResult
}

/**
 * 分析异常
 *
 * 当分析过程中发生错误时抛出
 */
class AnalysisException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause) {

    /**
     * 创建带原因的分析异常
     */
    constructor(cause: Throwable) : this(cause.message ?: "分析失败", cause)
}
