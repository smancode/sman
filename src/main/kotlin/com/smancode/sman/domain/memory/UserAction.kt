package com.smancode.sman.domain.memory

/**
 * 用户行为枚举
 *
 * 定义用户对 AI 建议的可能反应类型
 */
enum class UserAction {
    /** 接受建议 - 用户直接采纳了 AI 的建议 */
    ACCEPTED_SUGGESTION,

    /** 拒绝建议 - 用户拒绝了 AI 的建议 */
    REJECTED_SUGGESTION,

    /** 修改建议 - 用户对 AI 建议进行了修改后采纳 */
    MODIFIED_SUGGESTION,

    /** 重复动作 - 用户重复执行了某个操作 */
    REPEATED_ACTION
}
