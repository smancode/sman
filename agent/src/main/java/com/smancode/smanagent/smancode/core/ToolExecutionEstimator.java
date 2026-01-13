package com.smancode.smanagent.smancode.core;

/**
 * 工具执行估算器
 * <p>
 * 提供工具执行时间的估算功能
 */
public final class ToolExecutionEstimator {

    private ToolExecutionEstimator() {
        // 工具类，不允许实例化
    }

    /**
     * 估算工具执行时间（秒）
     *
     * @param toolName 工具名称
     * @return 预估执行时间（秒）
     */
    public static int estimateTime(String toolName) {
        return switch (toolName) {
            case "semantic_search" -> 3;
            case "grep_file" -> 2;
            case "find_file" -> 1;
            case "read_file" -> 1;
            case "call_chain" -> 5;
            case "extract_xml" -> 2;
            default -> 3;
        };
    }
}
