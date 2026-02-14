package com.smancode.sman.evolution.knowledge

/**
 * 知识作用域 - 定义知识的共享范围
 *
 * 支持三种层级：
 * - GLOBAL：全局知识，跨项目通用（如 Spring Boot 最佳实践）
 * - TEAM：团队知识，项目内共享（如项目特定设计决策）
 * - PERSONAL：个人知识，用户私有（如个人代码风格偏好）
 *
 * 存储位置：
 * - GLOBAL：~/.sman/global/
 * - TEAM：项目目录下的 .sman/shared/
 * - PERSONAL：~/.sman/personal/
 */
enum class KnowledgeScope {
    /**
     * 全局知识 - 跨项目通用
     *
     * 适用场景：
     * - 通用框架最佳实践
     * - 语言特性知识
     * - 设计模式应用
     *
     * 存储路径：~/.sman/global/
     */
    GLOBAL,

    /**
     * 团队知识 - 项目内共享
     *
     * 适用场景：
     * - 项目特定设计决策
     * - 业务领域知识
     * - 模块间调用关系
     *
     * 存储路径：项目目录/.sman/shared/
     * 同步方式：通过 Git 同步
     */
    TEAM,

    /**
     * 个人知识 - 用户私有
     *
     * 适用场景：
     * - 个人代码风格偏好
     * - 私有调试技巧
     * - 个人学习笔记
     *
     * 存储路径：~/.sman/personal/
     */
    PERSONAL;

    companion object {
        /**
         * 默认作用域
         */
        val DEFAULT = PERSONAL
    }
}
