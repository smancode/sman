package com.smancode.smanagent.model.message;

/**
 * Token 使用统计
 */
public class TokenUsage {

    /**
     * 输入 Token 数
     */
    private int inputTokens;

    /**
     * 输出 Token 数
     */
    private int outputTokens;

    /**
     * 总 Token 数
     */
    private int totalTokens;

    public TokenUsage() {
    }

    public TokenUsage(int inputTokens, int outputTokens) {
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.totalTokens = inputTokens + outputTokens;
    }

    public int getInputTokens() {
        return inputTokens;
    }

    public void setInputTokens(int inputTokens) {
        this.inputTokens = inputTokens;
        updateTotal();
    }

    public int getOutputTokens() {
        return outputTokens;
    }

    public void setOutputTokens(int outputTokens) {
        this.outputTokens = outputTokens;
        updateTotal();
    }

    public int getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(int totalTokens) {
        this.totalTokens = totalTokens;
    }

    private void updateTotal() {
        this.totalTokens = this.inputTokens + this.outputTokens;
    }

    /**
     * 添加 Token 使用
     *
     * @param input  输入 Token 数
     * @param output 输出 Token 数
     */
    public void add(int input, int output) {
        this.inputTokens += input;
        this.outputTokens += output;
        updateTotal();
    }
}
