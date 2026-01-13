package com.smancode.smanagent.smancode.core;

import java.util.Map;

/**
 * 参数格式化器
 * <p>
 * 提供参数的格式化功能
 */
public final class ParamsFormatter {

    private ParamsFormatter() {
        // 工具类，不允许实例化
    }

    /**
     * 格式化参数简述
     *
     * @param params 参数 Map
     * @return 格式化后的参数字符串
     */
    public static String formatBrief(Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return "无";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("{");
        params.forEach((key, value) -> {
            if (sb.length() > 1) {
                sb.append(", ");
            }
            String valueStr = String.valueOf(value);
            if (valueStr.length() > 50) {
                valueStr = valueStr.substring(0, 50) + "...";
            }
            sb.append(key).append("=").append(valueStr);
        });
        sb.append("}");
        return sb.toString();
    }
}
