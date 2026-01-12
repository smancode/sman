package com.smancode.smanagent.tools;

import java.util.Map;

/**
 * 工具接口
 * <p>
 * 所有工具必须实现此接口。
 */
public interface Tool {

    /**
     * 获取工具名称
     *
     * @return 工具名称
     */
    String getName();

    /**
     * 获取工具描述
     *
     * @return 工具描述
     */
    String getDescription();

    /**
     * 获取参数定义
     *
     * @return 参数定义映射（参数名 → 参数定义）
     */
    Map<String, ParameterDef> getParameters();

    /**
     * 执行工具
     *
     * @param projectKey 项目 Key
     * @param params     参数映射
     * @return 工具执行结果
     */
    ToolResult execute(String projectKey, Map<String, Object> params);

    /**
     * 获取执行模式
     *
     * @param params 参数映射
     * @return 执行模式
     */
    default ExecutionMode getExecutionMode(Map<String, Object> params) {
        String mode = getOptionalString(params, "mode", "intellij");
        return "local".equalsIgnoreCase(mode) ? ExecutionMode.LOCAL : ExecutionMode.INTELLIJ;
    }

    /**
     * 获取字符串参数（带默认值）
     *
     * @param params      参数映射
     * @param key         参数键
     * @param defaultValue 默认值
     * @return 参数值
     */
    default String getOptionalString(Map<String, Object> params, String key, String defaultValue) {
        Object value = params.get(key);
        if (value == null) {
            return defaultValue;
        }
        return value.toString();
    }

    /**
     * 获取整型参数（带默认值）
     *
     * @param params      参数映射
     * @param key         参数键
     * @param defaultValue 默认值
     * @return 参数值
     */
    default int getOptionalInt(Map<String, Object> params, String key, int defaultValue) {
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
     * 执行模式
     */
    enum ExecutionMode {
        /**
         * 后端执行（需要向量索引等后端资源）
         */
        LOCAL,

        /**
         * IDE 执行（需要本地文件访问）
         */
        INTELLIJ
    }
}
