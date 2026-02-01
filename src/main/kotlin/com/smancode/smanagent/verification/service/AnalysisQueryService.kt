package com.smancode.smanagent.verification.service

import com.smancode.smanagent.verification.model.AnalysisQueryRequest
import com.smancode.smanagent.verification.model.AnalysisQueryResponse
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * 分析结果查询服务
 *
 * 支持 9 个分析模块的结果查询（与 ANALYSIS_STEP 表中的 STEP_NAME 一致）：
 * - project_structure: 项目结构扫描
 * - tech_stack_detection: 技术栈识别
 * - ast_scanning: AST 扫描
 * - db_entity_detection: DB 实体扫描
 * - api_entry_scanning: API 入口扫描
 * - external_api_scanning: 外调接口扫描
 * - enum_scanning: Enum 扫描
 * - common_class_scanning: 公共类扫描
 * - xml_code_scanning: XML 代码扫描
 *
 * 白名单机制：参数不满足直接抛异常
 */
@Service
class AnalysisQueryService(
    private val h2QueryService: H2QueryService
) {

    private val logger = LoggerFactory.getLogger(AnalysisQueryService::class.java)

    companion object {
        val SUPPORTED_MODULES = setOf(
            "project_structure",
            "tech_stack_detection",
            "ast_scanning",
            "db_entity_detection",
            "api_entry_scanning",
            "external_api_scanning",
            "enum_scanning",
            "common_class_scanning",
            "xml_code_scanning"
        )
    }

    fun <T> queryResults(request: AnalysisQueryRequest): AnalysisQueryResponse<T> {
        logger.info("查询分析结果: module={}, projectKey={}, page={}, size={}",
            request.module, request.projectKey, request.page, request.size)

        // 白名单参数校验
        validateRequest(request)

        // 查询 H2 数据库
        val result = h2QueryService.queryAnalysisResults(
            request.module,
            request.projectKey,
            request.page,
            request.size
        )

        @Suppress("UNCHECKED_CAST")
        val data = result["data"] as? List<T> ?: emptyList()
        val total = result["total"] as? Int ?: 0

        logger.info("查询完成: module={}, total={}", request.module, total)

        // 构造响应
        return AnalysisQueryResponse(
            module = request.module,
            projectKey = request.projectKey,
            data = data,
            total = total,
            page = request.page,
            size = request.size
        )
    }

    private fun validateRequest(request: AnalysisQueryRequest) {
        require(request.module.isNotBlank()) { "module 不能为空" }
        require(request.projectKey.isNotBlank()) { "projectKey 不能为空" }
        require(request.module in SUPPORTED_MODULES) {
            "不支持的模块: ${request.module}, 支持的模块: ${SUPPORTED_MODULES.joinToString()}"
        }
        require(request.page >= 0) { "page 必须大于等于 0，当前值: ${request.page}" }
        require(request.size > 0) { "size 必须大于 0，当前值: ${request.size}" }
    }
}
