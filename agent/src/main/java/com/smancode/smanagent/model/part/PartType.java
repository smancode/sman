package com.smancode.smanagent.model.part;

/**
 * Part 类型
 */
public enum PartType {
    /**
     * 文本内容（Markdown 格式）
     */
    TEXT,

    /**
     * 工具调用
     */
    TOOL,

    /**
     * 思考过程
     */
    REASONING,

    /**
     * 目标/任务
     */
    GOAL,

    /**
     * 进度
     */
    PROGRESS,

    /**
     * TODO 列表
     */
    TODO
}
