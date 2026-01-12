package com.smancode.smanagent.tools;

import java.util.Map;

/**
 * 工具基类
 * <p>
 * 提供工具实现的通用方法。
 */
public abstract class AbstractTool implements Tool {

    /**
     * 获取可选字符串参数
     *
     * @param params         参数 Map
     * @param key            参数键
     * @param defaultValue   默认值
     * @return 参数值，如果不存在则返回默认值
     */
    protected String getOptString(Map<String, Object> params, String key, String defaultValue) {
        Object value = params.get(key);
        if (value == null) {
            return defaultValue;
        }
        return value.toString();
    }

    /**
     * 获取可选整数参数
     *
     * @param params         参数 Map
     * @param key            参数键
     * @param defaultValue   默认值
     * @return 参数值，如果不存在或转换失败则返回默认值
     */
    protected int getOptInt(Map<String, Object> params, String key, int defaultValue) {
        Object value = params.get(key);
        if (value == null) {
            return defaultValue;
        }

        if (value instanceof Number) {
            return ((Number) value).intValue();
        }

        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
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
    protected boolean getOptBoolean(Map<String, Object> params, String key, boolean defaultValue) {
        Object value = params.get(key);
        if (value == null) {
            return defaultValue;
        }

        if (value instanceof Boolean) {
            return (Boolean) value;
        }

        return Boolean.parseBoolean(value.toString());
    }

    /**
     * 获取必需字符串参数
     *
     * @param params 参数 Map
     * @param key    参数键
     * @return 参数值
     * @throws IllegalArgumentException 如果参数不存在或为空
     */
    protected String getReqString(Map<String, Object> params, String key) {
        Object value = params.get(key);
        if (value == null) {
            throw new IllegalArgumentException("缺少必需参数: " + key);
        }

        String strValue = value.toString().trim();
        if (strValue.isEmpty()) {
            throw new IllegalArgumentException("参数不能为空: " + key);
        }

        return strValue;
    }

    /**
     * 获取必需整数参数
     *
     * @param params 参数 Map
     * @param key    参数键
     * @return 参数值
     * @throws IllegalArgumentException 如果参数不存在、为空或无法转换
     */
    protected int getReqInt(Map<String, Object> params, String key) {
        Object value = params.get(key);
        if (value == null) {
            throw new IllegalArgumentException("缺少必需参数: " + key);
        }

        if (value instanceof Number) {
            return ((Number) value).intValue();
        }

        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("参数类型错误: " + key + " 应为整数");
        }
    }
}
