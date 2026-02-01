package com.smancode.smanagent.analysis.model

import kotlinx.serialization.Serializable

/**
 * 案例 SOP（标准操作流程）
 *
 * 为单个类生成使用说明文档
 *
 * @property className 类名
 * @property qualifiedName 全限定名
 * @property description 描述
 * @property steps 操作步骤
 * @property usage 使用示例
 */
@Serializable
data class CaseSop(
    val className: String,
    val qualifiedName: String,
    val description: String,
    val steps: List<SopStep>,
    val usage: List<String> = emptyList()
)

/**
 * SOP 操作步骤
 *
 * @property stepNumber 步骤编号
 * @property action 操作描述
 * @property expected 预期结果
 * @property notes 备注
 */
@Serializable
data class SopStep(
    val stepNumber: Int,
    val action: String,
    val expected: String,
    val notes: String = ""
)
