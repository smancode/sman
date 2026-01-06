package ai.smancode.sman.agent.models;

import java.util.List;
import java.util.Map;

/**
 * WebSocket 通信数据模型
 *
 * 用于 IDE Plugin 与 Agent 后端之间的双向通信
 *
 * @author SiliconMan Team
 * @since 1.0.0
 */
public class WebSocketModels {

    /**
     * WebSocket 请求消息
     */
    public static class WebSocketRequest {
        private String type;
        private String requestId;
        private Map<String, Object> data;

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }

        public String getRequestId() { return requestId; }
        public void setRequestId(String requestId) { this.requestId = requestId; }

        public Map<String, Object> getData() { return data; }
        public void setData(Map<String, Object> data) { this.data = data; }
    }

    /**
     * WebSocket 响应消息
     */
    public static class WebSocketResponse {
        private String type;
        private String requestId;
        private boolean success;
        private Object result;
        private String error;

        public static WebSocketResponse success(String requestId, Object result) {
            WebSocketResponse response = new WebSocketResponse();
            response.setType("COMPLETE");
            response.setRequestId(requestId);
            response.setSuccess(true);
            response.setResult(result);
            return response;
        }

        public static WebSocketResponse error(String requestId, String error) {
            WebSocketResponse response = new WebSocketResponse();
            response.setType("ERROR");
            response.setRequestId(requestId);
            response.setSuccess(false);
            response.setError(error);
            return response;
        }

        public static WebSocketResponse progress(String requestId, String message, int progress) {
            WebSocketResponse response = new WebSocketResponse();
            response.setType("PROGRESS");
            response.setRequestId(requestId);
            response.setSuccess(true);
            response.setResult(new ProgressData(message, progress));
            return response;
        }

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }

        public String getRequestId() { return requestId; }
        public void setRequestId(String requestId) { this.requestId = requestId; }

        public boolean isSuccess() { return success; }
        public void setSuccess(boolean success) { this.success = success; }

        public Object getResult() { return result; }
        public void setResult(Object result) { this.result = result; }

        public String getError() { return error; }
        public void setError(String error) { this.error = error; }
    }

    /**
     * 进度数据
     */
    public static class ProgressData {
        private String message;
        private int progress;

        public ProgressData(String message, int progress) {
            this.message = message;
            this.progress = progress;
        }

        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }

        public int getProgress() { return progress; }
        public void setProgress(int progress) { this.progress = progress; }
    }

    /**
     * 分析请求数据
     */
    public static class AnalyzeRequest {
        private String message;
        private List<ChatMessage> history;
        private String projectKey;

        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }

        public List<ChatMessage> getHistory() { return history; }
        public void setHistory(List<ChatMessage> history) { this.history = history; }

        public String getProjectKey() { return projectKey; }
        public void setProjectKey(String projectKey) { this.projectKey = projectKey; }
    }

    /**
     * 聊天消息
     */
    public static class ChatMessage {
        private String role;  // "user" or "assistant"
        private String content;
        private long timestamp;

        public ChatMessage() {}

        public ChatMessage(String role, String content) {
            this.role = role;
            this.content = content;
            this.timestamp = System.currentTimeMillis();
        }

        public String getRole() { return role; }
        public void setRole(String role) { this.role = role; }

        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }

        public long getTimestamp() { return timestamp; }
        public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
    }

    /**
     * 分析结果数据
     */
    public static class AnalyzeResult {
        private String answer;
        private List<ToolCall> toolCalls;
        private String sessionId;

        public String getAnswer() { return answer; }
        public void setAnswer(String answer) { this.answer = answer; }

        public List<ToolCall> getToolCalls() { return toolCalls; }
        public void setToolCalls(List<ToolCall> toolCalls) { this.toolCalls = toolCalls; }

        public String getSessionId() { return sessionId; }
        public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    }

    /**
     * 工具调用记录
     */
    public static class ToolCall {
        private String toolName;
        private Map<String, Object> params;
        private Object result;
        private long executionTime;

        public String getToolName() { return toolName; }
        public void setToolName(String toolName) { this.toolName = toolName; }

        public Map<String, Object> getParams() { return params; }
        public void setParams(Map<String, Object> params) { this.params = params; }

        public Object getResult() { return result; }
        public void setResult(Object result) { this.result = result; }

        public long getExecutionTime() { return executionTime; }
        public void setExecutionTime(long executionTime) { this.executionTime = executionTime; }
    }

    /**
     * 流式内容数据
     */
    public static class StreamingContentData {
        private String content;
        private String contentType;  // "markdown" | "text" | "thinking"
        private boolean isComplete;
        private int chunkIndex;

        public StreamingContentData(String content, String contentType, boolean isComplete, int chunkIndex) {
            this.content = content;
            this.contentType = contentType;
            this.isComplete = isComplete;
            this.chunkIndex = chunkIndex;
        }

        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }

        public String getContentType() { return contentType; }
        public void setContentType(String contentType) { this.contentType = contentType; }

        public boolean isComplete() { return isComplete; }
        public void setComplete(boolean complete) { isComplete = complete; }

        public int getChunkIndex() { return chunkIndex; }
        public void setChunkIndex(int chunkIndex) { this.chunkIndex = chunkIndex; }
    }
}
