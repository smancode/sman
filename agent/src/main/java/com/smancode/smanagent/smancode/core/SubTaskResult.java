package com.smancode.smanagent.smancode.core;

/**
 * 子任务结果
 * <p>
 * 表示在独立子会话中执行的工具结果摘要
 */
public class SubTaskResult {

    private final String toolName;
    private final boolean success;
    private final String summary;
    private final String displayTitle;
    private final String error;

    private SubTaskResult(Builder builder) {
        this.toolName = builder.toolName;
        this.success = builder.success;
        this.summary = builder.summary;
        this.displayTitle = builder.displayTitle;
        this.error = builder.error;
    }

    public String getToolName() {
        return toolName;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getSummary() {
        return summary;
    }

    public String getDisplayTitle() {
        return displayTitle;
    }

    public String getError() {
        return error;
    }

    public boolean hasError() {
        return error != null && !error.isEmpty();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String toolName;
        private boolean success = true;
        private String summary;
        private String displayTitle;
        private String error;

        public Builder toolName(String toolName) {
            this.toolName = toolName;
            return this;
        }

        public Builder success(boolean success) {
            this.success = success;
            return this;
        }

        public Builder summary(String summary) {
            this.summary = summary;
            return this;
        }

        public Builder displayTitle(String displayTitle) {
            this.displayTitle = displayTitle;
            return this;
        }

        public Builder error(String error) {
            this.error = error;
            this.success = false;
            return this;
        }

        public SubTaskResult build() {
            return new SubTaskResult(this);
        }
    }

    @Override
    public String toString() {
        return "SubTaskResult{" +
                "toolName='" + toolName + '\'' +
                ", success=" + success +
                ", summary='" + (summary != null ? summary.substring(0, Math.min(50, summary.length())) : "null") + '\'' +
                (hasError() ? ", error='" + error + '\'' : "") +
                '}';
    }
}
