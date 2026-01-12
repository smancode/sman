package com.smancode.smanagent.model.part;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * TODO 列表 Part
 * <p>
 * 用于显示任务列表，支持动态添加、更新、删除 TODO 项。
 */
public class TodoPart extends Part {

    /**
     * TODO 项列表
     */
    private List<TodoItem> items;

    public TodoPart() {
        super();
        this.type = PartType.TODO;
        this.items = new ArrayList<>();
    }

    public TodoPart(String id, String messageId, String sessionId) {
        super(id, messageId, sessionId, PartType.TODO);
        this.items = new ArrayList<>();
    }

    public List<TodoItem> getItems() {
        return items;
    }

    public void setItems(List<TodoItem> items) {
        this.items = items;
        touch();
    }

    /**
     * 添加 TODO 项
     *
     * @param content TODO 内容
     * @return 创建的 TodoItem
     */
    public TodoItem addItem(String content) {
        TodoItem item = new TodoItem();
        item.setId(UUID.randomUUID().toString());
        item.setContent(content);
        item.setStatus(TodoStatus.PENDING);
        items.add(item);
        touch();
        return item;
    }

    /**
     * 更新 TODO 项状态
     *
     * @param itemId TODO 项 ID
     * @param status 新状态
     */
    public void updateItemStatus(String itemId, TodoStatus status) {
        for (TodoItem item : items) {
            if (item.getId().equals(itemId)) {
                item.setStatus(status);
                touch();
                return;
            }
        }
    }

    /**
     * 获取已完成数量
     */
    public int getCompletedCount() {
        int count = 0;
        for (TodoItem item : items) {
            if (item.getStatus() == TodoStatus.COMPLETED) {
                count++;
            }
        }
        return count;
    }

    /**
     * 获取总数
     */
    public int getTotalCount() {
        return items.size();
    }

    /**
     * 获取完成进度
     *
     * @return 完成进度（0.0 ~ 1.0）
     */
    public double getProgress() {
        int total = getTotalCount();
        if (total == 0) {
            return 0.0;
        }
        return (double) getCompletedCount() / total;
    }

    // ==================== TodoItem 内部类 ====================

    /**
     * TODO 项
     */
    public static class TodoItem {

        /**
         * TODO 项 ID
         */
        private String id;

        /**
         * TODO 内容
         */
        private String content;

        /**
         * TODO 状态
         */
        private TodoStatus status;

        public TodoItem() {
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }

        public TodoStatus getStatus() {
            return status;
        }

        public void setStatus(TodoStatus status) {
            this.status = status;
        }
    }

    /**
     * TODO 状态
     */
    public enum TodoStatus {
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
