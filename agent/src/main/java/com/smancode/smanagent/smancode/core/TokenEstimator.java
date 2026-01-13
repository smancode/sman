package com.smancode.smanagent.smancode.core;

import com.smancode.smanagent.model.part.Part;
import com.smancode.smanagent.model.part.TextPart;
import com.smancode.smanagent.model.part.ToolPart;

/**
 * Token 估算器
 * <p>
 * 粗略估算 Part 的 Token 数量
 * <p>
 * 参考 OpenCode: 4 字符 ≈ 1 token
 */
public final class TokenEstimator {

    private static final int CHARS_PER_TOKEN = 4;

    private TokenEstimator() {
        // 工具类
    }

    /**
     * 估算 Part 的 Token 数量
     */
    public static int estimate(Part part) {
        if (part instanceof TextPart) {
            return estimateText(((TextPart) part).getText());
        } else if (part instanceof ToolPart) {
            return estimateToolPart((ToolPart) part);
        } else {
            return 100;  // 其他类型默认 100 tokens
        }
    }

    /**
     * 估算文本的 Token 数量
     */
    public static int estimateText(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return Math.max(0, Math.round(text.length() / (float) CHARS_PER_TOKEN));
    }

    /**
     * 估算工具 Part 的 Token 数量
     */
    private static int estimateToolPart(ToolPart toolPart) {
        int total = 0;

        // 工具名称和参数
        total += estimateText(toolPart.getToolName());
        total += 50;  // 参数开销

        // 结果数据
        if (toolPart.getResult() != null && toolPart.getResult().getData() != null) {
            String data = String.valueOf(toolPart.getResult().getData());
            total += estimateText(data);
        }

        return total;
    }

    /**
     * 估算字符串的 Token 数量
     */
    public static int estimate(String str) {
        return estimateText(str);
    }

    /**
     * 计算压缩率
     */
    public static double compressionRatio(int originalTokens, int compressedTokens) {
        if (originalTokens == 0) {
            return 0;
        }
        return (double) compressedTokens / originalTokens;
    }

    /**
     * 格式化 Token 数量
     */
    public static String format(int tokens) {
        if (tokens < 1_000) {
            return tokens + " tokens";
        } else if (tokens < 1_000_000) {
            return String.format("%.1fK tokens", tokens / 1_000.0);
        } else {
            return String.format("%.1fM tokens", tokens / 1_000_000.0);
        }
    }
}
