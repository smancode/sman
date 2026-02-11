package com.smancode.sman.model.part

import java.util.UUID

/**
 * TODO 列表 Part
 *
 * 用于显示任务列表，支持动态添加、更新、删除 TODO 项。
 */
class TodoPart : Part {

    /**
     * TODO 项列表
     */
    var items: MutableList<TodoItem> = mutableListOf()

    constructor() : super() {
        this.type = PartType.TODO
        this.items = mutableListOf()
    }

    constructor(id: String, messageId: String, sessionId: String) : super(id, messageId, sessionId, PartType.TODO) {
        this.items = mutableListOf()
    }

    /**
     * 添加 TODO 项
     *
     * @param content TODO 内容
     * @return 创建的 TodoItem
     */
    fun addItem(content: String): TodoItem {
        val item = TodoItem().apply {
            id = UUID.randomUUID().toString()
            this.content = content
            status = TodoStatus.PENDING
        }
        items.add(item)
        touch()
        return item
    }

    /**
     * 更新 TODO 项状态
     *
     * @param itemId TODO 项 ID
     * @param status 新状态
     */
    fun updateItemStatus(itemId: String, status: TodoStatus) {
        items.find { it.id == itemId }?.let { item ->
            item.status = status
            touch()
        }
    }

    /**
     * 获取已完成数量
     */
    fun getCompletedCount(): Int = items.count { it.status == TodoStatus.COMPLETED }

    /**
     * 获取总数
     */
    fun getTotalCount(): Int = items.size

    /**
     * 获取完成进度
     *
     * @return 完成进度（0.0 ~ 1.0）
     */
    fun getProgress(): Double {
        val total = getTotalCount()
        return if (total == 0) 0.0 else getCompletedCount().toDouble() / total
    }

    /**
     * TODO 项
     */
    class TodoItem {
        /**
         * TODO 项 ID
         */
        var id: String? = null

        /**
         * TODO 内容
         */
        var content: String? = null

        /**
         * TODO 状态
         */
        var status: TodoStatus? = null
    }

    /**
     * TODO 状态
     */
    enum class TodoStatus {
        /**
         * 待处理
         */
        PENDING,

        /**
         * 进行中
         */
        IN_PROGRESS,

        /**
         * 已完成
         */
        COMPLETED,

        /**
         * 已取消
         */
        CANCELLED
    }
}
