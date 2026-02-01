package com.smancode.smanagent.ide.service

/**
 * 初始化错误格式化工具
 */
object InitializationErrorFormatter {

    /**
     * 格式化初始化错误信息
     */
    fun format(e: Exception): String = when {
        e.message?.contains("LLM_API_KEY") == true -> """
            ⚠️ 配置错误：缺少 API Key

            请设置 LLM_API_KEY 环境变量：

            1. 打开 Run → Edit Configurations...
            2. 选择你的插件运行配置
            3. 在 Environment variables 中添加：
               LLM_API_KEY=your_api_key_here

            或在终端中执行：
            export LLM_API_KEY=your_api_key_here
        """.trimIndent()

        e.message?.contains("Connection") == true ||
        e.message?.contains("timeout") == true -> """
            ⚠️ 网络错误：无法连接到 LLM 服务

            请检查：
            • 网络连接是否正常
            • API Key 是否有效
            • LLM 服务是否可用
        """.trimIndent()

        else -> """
            ⚠️ 初始化失败：${e.message}

            请查看日志获取详细信息。
        """.trimIndent()
    }
}
