package com.smancode.smanagent.model

/**
 * 业务分析结果
 *
 * 针对用户问题的业务理解和代码映射
 */
class BusinessAnalysis {
    var userQuestion: String? = null
    var identifiedTerms: List<String>? = null
    var termExplanations: Map<String, String>? = null
    var relations: List<TermRelation>? = null
    var relevantCode: List<CodeElement>? = null
    var missingInfo: List<String>? = null
}
