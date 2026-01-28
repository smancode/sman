package com.smancode.smanagent.tools

import java.time.Instant

/**
 * 工具执行结果
 */
class ToolResult {

    /**
     * 是否成功
     */
    var isSuccess: Boolean = false

    /**
     * 返回数据
     */
    var data: Any? = null

    /**
     * 显示标题
     */
    var displayTitle: String? = null

    /**
     * 显示内容
     */
    var displayContent: String? = null

    /**
     * 错误信息
     */
    var error: String? = null

    /**
     * 执行时长（毫秒）
     */
    var executionTimeMs: Long = 0

    /**
     * 执行时间
     */
    var executionTime: Instant = Instant.now()

    /**
     * 相对路径（用于后续重新读取或修改）
     */
    var relativePath: String? = null

    /**
     * 相关文件路径列表（用于 find_file 等工具）
     *
     * 存储 find_file 找到的所有匹配文件路径
     */
    var relatedFilePaths: List<String>? = null

    /**
     * 元数据（存储扩展信息）
     *
     * 例如: absolutePath, totalLines, startLine, endLine 等
     */
    var metadata: Map<String, Any>? = null

    /**
     * 批量执行的子工具结果列表
     *
     * 用于 batch 工具存储所有子工具的执行结果
     */
    var batchSubResults: List<BatchSubResult>? = null

    /**
     * 摘要（用于压缩后的结果）
     *
     * 存储工具结果的摘要，用于减少 Token 使用
     */
    var summary: String? = null

    /**
     * 计算执行时长
     */
    fun calculateDuration() {
        executionTimeMs = java.time.Duration.between(executionTime, Instant.now()).toMillis()
    }

    companion object {
        /**
         * 创建成功结果
         *
         * @param data         数据
         * @param displayTitle 显示标题
         * @param displayContent 显示内容
         * @return 成功结果
         */
        fun success(data: Any?, displayTitle: String?, displayContent: String?): ToolResult {
            return ToolResult().apply {
                isSuccess = true
                this.data = data
                this.displayTitle = displayTitle
                this.displayContent = displayContent
            }
        }

        /**
         * 创建失败结果
         *
         * @param error 错误信息
         * @return 失败结果
         */
        fun failure(error: String?): ToolResult {
            return ToolResult().apply {
                isSuccess = false
                this.error = error
            }
        }

        /**
         * 创建成功结果（带相对路径）
         *
         * @param data         数据
         * @param relativePath 相对路径
         * @param displayContent 显示内容
         * @return 成功结果
         */
        fun successWithPath(data: Any?, relativePath: String?, displayContent: String?): ToolResult {
            return ToolResult().apply {
                isSuccess = true
                this.data = data
                this.relativePath = relativePath
                this.displayTitle = relativePath  // 标题使用相对路径
                this.displayContent = displayContent
            }
        }
    }
}
