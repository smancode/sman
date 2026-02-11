package com.smancode.sman.smancode.core

/**
 * 参数格式化器
 *
 * 提供参数的格式化功能
 */
object ParamsFormatter {

    /**
     * 格式化参数简述
     *
     * @param params 参数 Map
     * @return 格式化后的参数字符串
     */
    fun formatBrief(params: Map<String, Any>?): String {
        if (params.isNullOrEmpty()) {
            return "无"
        }

        val sb = StringBuilder()
        sb.append("{")
        params.forEach { (key, value) ->
            if (sb.length > 1) {
                sb.append(", ")
            }
            var valueStr = value.toString()
            if (valueStr.length > 50) {
                valueStr = valueStr.take(50) + "..."
            }
            sb.append(key).append("=").append(valueStr)
        }
        sb.append("}")
        return sb.toString()
    }
}
