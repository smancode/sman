package com.smancode.smanagent.model.part;

import java.time.Instant;
import java.util.Map;

/**
 * 工具调用 Part
 * <p>
 * 用于表示工具调用过程，包含完整的状态机：pending → running → completed/error
 */
public class ToolPart extends Part {

    /**
     * 工具名称
     */
    private String toolName;

    /**
     * 当前状态
     */
    private ToolState state;

    public ToolPart() {
        super();
        this.type = PartType.TOOL;
    }

    public ToolPart(String id, String messageId, String sessionId, String toolName) {
        super(id, messageId, sessionId, PartType.TOOL);
        this.toolName = toolName;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
        touch();
    }

    public ToolState getState() {
        return state;
    }

    public void setState(ToolState state) {
        this.state = state;
        touch();
    }

    /**
     * 转换到运行中状态
     */
    public void transitionToRunning() {
        if (!(this.state instanceof PendingState)) {
            throw new IllegalStateException("只能从 Pending 状态转换到 Running 状态");
        }
        PendingState pending = (PendingState) this.state;
        this.state = new RunningState(pending.getInput(), Instant.now());
        touch();
    }

    /**
     * 转换到完成状态
     *
     * @param output     输出结果
     * @param title      显示标题
     * @param content    显示内容
     */
    public void transitionToCompleted(Object output, String title, String content) {
        if (!(this.state instanceof RunningState)) {
            throw new IllegalStateException("只能从 Running 状态转换到 Completed 状态");
        }
        RunningState running = (RunningState) this.state;
        PendingState original = running.getOriginalState();
        this.state = new CompletedState(original.getInput(), output, title, content, running.getStartTime(), Instant.now());
        touch();
    }

    /**
     * 转换到错误状态
     *
     * @param error 错误信息
     */
    public void transitionToError(String error) {
        PendingState original;
        if (this.state instanceof PendingState) {
            original = (PendingState) this.state;
        } else if (this.state instanceof RunningState) {
            original = ((RunningState) this.state).getOriginalState();
        } else {
            throw new IllegalStateException("只能从 Pending 或 Running 状态转换到 Error 状态");
        }
        this.state = new ErrorState(original.getInput(), error, Instant.now());
        touch();
    }

    // ==================== 状态类 ====================

    /**
     * 工具状态基类
     */
    public abstract static class ToolState {
        private final Map<String, Object> input;

        protected ToolState(Map<String, Object> input) {
            this.input = input;
        }

        public Map<String, Object> getInput() {
            return input;
        }

        /**
         * 获取状态类型
         */
        public abstract String getStatus();
    }

    /**
     * 等待中状态
     */
    public static class PendingState extends ToolState {
        private final String toolCallJson;

        public PendingState(Map<String, Object> input, String toolCallJson) {
            super(input);
            this.toolCallJson = toolCallJson;
        }

        public String getToolCallJson() {
            return toolCallJson;
        }

        @Override
        public String getStatus() {
            return "pending";
        }
    }

    /**
     * 运行中状态
     */
    public static class RunningState extends ToolState {
        private final Instant startTime;
        private final PendingState originalState;

        public RunningState(Map<String, Object> input, Instant startTime) {
            super(input);
            this.startTime = startTime;
            this.originalState = new PendingState(input, "{}");
        }

        public Instant getStartTime() {
            return startTime;
        }

        public PendingState getOriginalState() {
            return originalState;
        }

        @Override
        public String getStatus() {
            return "running";
        }
    }

    /**
     * 完成状态
     */
    public static class CompletedState extends ToolState {
        private final Object output;
        private final String displayTitle;
        private final String displayContent;
        private final Instant startTime;
        private final Instant endTime;

        public CompletedState(Map<String, Object> input, Object output, String displayTitle,
                             String displayContent, Instant startTime, Instant endTime) {
            super(input);
            this.output = output;
            this.displayTitle = displayTitle;
            this.displayContent = displayContent;
            this.startTime = startTime;
            this.endTime = endTime;
        }

        public Object getOutput() {
            return output;
        }

        public String getDisplayTitle() {
            return displayTitle;
        }

        public String getDisplayContent() {
            return displayContent;
        }

        public Instant getStartTime() {
            return startTime;
        }

        public Instant getEndTime() {
            return endTime;
        }

        /**
         * 获取执行时长（毫秒）
         */
        public long getDurationMs() {
            return java.time.Duration.between(startTime, endTime).toMillis();
        }

        @Override
        public String getStatus() {
            return "completed";
        }
    }

    /**
     * 错误状态
     */
    public static class ErrorState extends ToolState {
        private final String error;
        private final Instant errorTime;

        public ErrorState(Map<String, Object> input, String error, Instant errorTime) {
            super(input);
            this.error = error;
            this.errorTime = errorTime;
        }

        public String getError() {
            return error;
        }

        public Instant getErrorTime() {
            return errorTime;
        }

        @Override
        public String getStatus() {
            return "error";
        }
    }
}
