package com.smancode.sman.model.message

/**
 * Token 使用统计
 */
class TokenUsage {

    /**
     * 输入 Token 数
     */
    var inputTokens: Int = 0
        set(value) {
            field = value
            updateTotal()
        }

    /**
     * 输出 Token 数
     */
    var outputTokens: Int = 0
        set(value) {
            field = value
            updateTotal()
        }

    /**
     * 总 Token 数
     */
    var totalTokens: Int = 0

    constructor()

    constructor(inputTokens: Int, outputTokens: Int) {
        this.inputTokens = inputTokens
        this.outputTokens = outputTokens
        this.totalTokens = inputTokens + outputTokens
    }

    private fun updateTotal() {
        this.totalTokens = this.inputTokens + this.outputTokens
    }

    /**
     * 添加 Token 使用
     *
     * @param input  输入 Token 数
     * @param output 输出 Token 数
     */
    fun add(input: Int, output: Int) {
        this.inputTokens += input
        this.outputTokens += output
        updateTotal()
    }
}
