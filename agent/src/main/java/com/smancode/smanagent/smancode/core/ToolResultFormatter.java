package com.smancode.smanagent.smancode.core;

import com.smancode.smanagent.tools.ToolResult;

/**
 * 工具结果格式化器
 * <p>
 * 提供工具结果的格式化和摘要生成功能
 */
public final class ToolResultFormatter {

    private ToolResultFormatter() {
        // 工具类，不允许实例化
    }

    /**
     * 格式化工具结果
     * <p>
     * 注意：不截取结果，完整输出！
     */
    public static String formatToolResult(ToolResult result) {
        Object data = result.getData();
        if (data == null) {
            return "";
        }

        // 完整返回结果，不截取
        return data.toString();
    }

    /**
     * 生成结果摘要
     */
    public static String generateResultSummary(String toolName, Object data) {
        String dataStr = String.valueOf(data);

        return switch (toolName) {
            case "semantic_search" -> {
                int count = countSearchResults(dataStr);
                yield count > 0 ? "找到 " + count + " 个相关结果" : "未找到相关结果";
            }
            case "grep_file" -> {
                int count = countMatches(dataStr);
                yield count > 0 ? "匹配到 " + count + " 处" : "未找到匹配";
            }
            case "find_file" -> {
                int count = countFiles(dataStr);
                yield count > 0 ? "找到 " + count + " 个文件" : "未找到文件";
            }
            case "read_file" -> {
                int lines = countLines(dataStr);
                yield "读取了 " + lines + " 行";
            }
            case "call_chain" -> {
                int depth = countCallDepth(dataStr);
                yield "调用链深度: " + depth;
            }
            default -> "执行完成";
        };
    }

    private static int countSearchResults(String data) {
        // 简单的启发式计数
        return Math.max(0, data.split("\\{").length - 1);
    }

    private static int countMatches(String data) {
        return Math.max(0, data.split("\n").length - 1);
    }

    private static int countFiles(String data) {
        return Math.max(0, data.split("\n").length);
    }

    private static int countLines(String data) {
        return Math.max(0, data.split("\n").length);
    }

    private static int countCallDepth(String data) {
        return Math.max(0, data.split(" -> ").length);
    }
}
