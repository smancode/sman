package com.smancode.sman.shared.model

/**
 * 拼图类型枚举
 * 定义项目知识拼图的六种核心维度
 */
enum class PuzzleType {
    /** 结构拼图 - 项目架构、模块组织 */
    STRUCTURE,
    /** 技术拼图 - 技术栈、框架、依赖 */
    TECH_STACK,
    /** 入口拼图 - API 端点、入口点 */
    API,
    /** 数据拼图 - 数据模型、数据库表 */
    DATA,
    /** 流程拼图 - 业务流程、调用链 */
    FLOW,
    /** 规则拼图 - 业务规则、校验逻辑 */
    RULE
}
