package com.smancode.sman.domain.puzzle

/**
 * 空白类型枚举
 *
 * 定义 Puzzle 知识库中可能发现的空白类型
 */
enum class GapType {
    /** 缺失 - 完全不存在的知识 */
    MISSING,

    /** 过时 - 已存在但需要更新的知识 */
    OUTDATED,

    /** 不一致 - 多处定义存在矛盾 */
    INCONSISTENT,

    /** 不完整 - 存在但信息不全的知识 */
    INCOMPLETE,

    /** 低完成度 - 完成度低于阈值 */
    LOW_COMPLETENESS,

    /** 低置信度 - 置信度低于阈值 */
    LOW_CONFIDENCE,

    /** 文件变更触发 - 相关文件变更需要更新 */
    FILE_CHANGE_TRIGGERED,

    /** 用户查询触发 - 用户查询暴露的知识空白 */
    USER_QUERY_TRIGGERED,

    /** 交叉验证发现 - 多源数据不一致 */
    CROSS_VALIDATION,

    /** 引用追踪发现 - 代码引用关系缺失记录 */
    REFERENCE_TRACING
}
