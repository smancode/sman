package com.smancode.smanagent.ide.renderer

import com.smancode.smanagent.ide.model.PartData
import com.smancode.smanagent.ide.model.TodoItem
import com.smancode.smanagent.ide.model.GraphModels.TodoPartData

/**
 * TodoPart 统一渲染器
 * <p>
 * 职责：
 * - 从 PartData 提取 TodoItem 列表
 * - 格式化 TodoItem 为文本
 * <p>
 * 设计原则：
 * - 单一职责：只负责 TodoPart 的数据提取和格式化
 * - DRY：避免在多个地方重复实现格式化逻辑
 * - 可测试：纯函数，无状态
 */
object TodoRenderer {

    // Todo 状态常量（与 TodoItem.status 保持一致）
    private const val STATUS_PENDING = "PENDING"
    private const val STATUS_IN_PROGRESS = "IN_PROGRESS"
    private const val STATUS_COMPLETED = "COMPLETED"
    private const val STATUS_CANCELLED = "CANCELLED"

    // 颜色类型常量
    private const val COLOR_TYPE_MUTED = "muted"
    private const val COLOR_TYPE_NORMAL = "normal"

    /**
     * 从 PartData 提取 TodoItem 列表
     */
    fun extractItems(part: PartData): List<TodoItem> {
        return when (part) {
            is TodoPartData -> part.items
            else -> extractItemsFromDataMap(part.data)
        }
    }

    /**
     * 从通用 data Map 中提取 TodoItem 列表（向后兼容）
     */
    private fun extractItemsFromDataMap(data: Map<String, Any>): List<TodoItem> {
        @Suppress("UNCHECKED_CAST")
        val itemsList = data["items"] as? List<Map<String, Any>> ?: return emptyList()

        return itemsList.map { itemData ->
            TodoItem(
                id = itemData["id"] as? String ?: "",
                content = itemData["content"] as? String ?: "",
                status = itemData["status"] as? String ?: STATUS_PENDING
            )
        }
    }

    /**
     * 格式化单个 TodoItem 为文本
     * <p>
     * 格式：[ ] 未完成任务  或  [x] 已完成任务
     */
    fun formatTodoItem(item: TodoItem): String {
        val checkbox = if (isCompleted(item.status)) "[x]" else "[ ]"
        return "$checkbox ${item.content}"
    }

    /**
     * 格式化 TodoItem 列表为多行文本
     */
    fun formatTodoList(items: List<TodoItem>): String {
        return items.joinToString("\n") { formatTodoItem(it) }
    }

    /**
     * 获取 TodoItem 的颜色类型（用于主题应用）
     * @return "muted" 表示灰色（已完成/已取消），"normal" 表示正常颜色
     */
    fun getItemColorType(item: TodoItem): String {
        return if (isMuted(item.status)) COLOR_TYPE_MUTED else COLOR_TYPE_NORMAL
    }

    /**
     * 判断任务是否已完成
     */
    private fun isCompleted(status: String): Boolean {
        return status == STATUS_COMPLETED
    }

    /**
     * 判断任务是否需要使用灰色显示（已完成或已取消）
     */
    private fun isMuted(status: String): Boolean {
        return status == STATUS_COMPLETED || status == STATUS_CANCELLED
    }
}
