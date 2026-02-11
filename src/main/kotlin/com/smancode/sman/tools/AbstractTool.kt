package com.smancode.sman.tools

/**
 * 工具基类
 *
 * 提供工具实现的通用方法。
 */
abstract class AbstractTool : Tool {

    /**
     * 获取可选字符串参数
     *
     * @param params         参数 Map
     * @param key            参数键
     * @param defaultValue   默认值
     * @return 参数值，如果不存在则返回默认值
     */
    protected fun getOptString(params: Map<String, Any>, key: String, defaultValue: String): String {
        val value = params[key] ?: return defaultValue
        return value.toString()
    }

    /**
     * 获取可选整数参数
     *
     * @param params         参数 Map
     * @param key            参数键
     * @param defaultValue   默认值
     * @return 参数值，如果不存在或转换失败则返回默认值
     */
    protected fun getOptInt(params: Map<String, Any>, key: String, defaultValue: Int): Int {
        val value = params[key] ?: return defaultValue
        if (value is Number) return value.toInt()
        return try {
            value.toString().toInt()
        } catch (e: NumberFormatException) {
            defaultValue
        }
    }

    /**
     * 获取可选布尔参数
     *
     * @param params         参数 Map
     * @param key            参数键
     * @param defaultValue   默认值
     * @return 参数值，如果不存在则返回默认值
     */
    protected fun getOptBoolean(params: Map<String, Any>, key: String, defaultValue: Boolean): Boolean {
        val value = params[key] ?: return defaultValue
        if (value is Boolean) return value
        return value.toString().toBoolean()
    }

    /**
     * 获取必需字符串参数
     *
     * @param params 参数 Map
     * @param key    参数键
     * @return 参数值
     * @throws IllegalArgumentException 如果参数不存在或为空
     */
    protected fun getReqString(params: Map<String, Any>, key: String): String {
        val value = params[key]
            ?: throw IllegalArgumentException("缺少必需参数: $key")

        val strValue = value.toString().trim()
        require(strValue.isNotEmpty()) { "参数不能为空: $key" }

        return strValue
    }

    /**
     * 获取必需整数参数
     *
     * @param params 参数 Map
     * @param key    参数键
     * @return 参数值
     * @throws IllegalArgumentException 如果参数不存在、为空或无法转换
     */
    protected fun getReqInt(params: Map<String, Any>, key: String): Int {
        val value = params[key]
            ?: throw IllegalArgumentException("缺少必需参数: $key")

        if (value is Number) return value.toInt()

        return try {
            value.toString().toInt()
        } catch (e: NumberFormatException) {
            throw IllegalArgumentException("参数类型错误: $key 应为整数")
        }
    }
}
