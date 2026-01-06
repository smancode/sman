package ai.smancode.sman.agent.claude;

import java.util.Map;

/**
 * Claude Code HTTP Tool API 数据模型
 *
 * @author SiliconMan Team
 * @since 1.0.0
 */
public class ClaudeCodeToolModels {

    /**
     * 工具执行请求
     */
    public static class ToolExecutionRequest {
        /** 工具名称 */
        private String tool;
        /** 工具参数 */
        private Map<String, Object> params;
        /** Worker ID（可选，用于会话管理） */
        private String workerId;
        /** 会话ID（可选，用于多轮对话） */
        private String sessionId;
        /** 项目标识符（必需，用于定位项目路径） */
        private String projectKey;
        /** WebSocket Session ID（必需，用于转发工具调用给 IDE Plugin） */
        private String webSocketSessionId;

        public String getTool() {
            return tool;
        }

        public void setTool(String tool) {
            this.tool = tool;
        }

        public Map<String, Object> getParams() {
            return params;
        }

        public void setParams(Map<String, Object> params) {
            this.params = params;
        }

        public String getWorkerId() {
            return workerId;
        }

        public void setWorkerId(String workerId) {
            this.workerId = workerId;
        }

        public String getSessionId() {
            return sessionId;
        }

        public void setSessionId(String sessionId) {
            this.sessionId = sessionId;
        }

        public String getProjectKey() {
            return projectKey;
        }

        public void setProjectKey(String projectKey) {
            this.projectKey = projectKey;
        }

        public String getWebSocketSessionId() {
            return webSocketSessionId;
        }

        public void setWebSocketSessionId(String webSocketSessionId) {
            this.webSocketSessionId = webSocketSessionId;
        }
    }

    /**
     * 工具执行响应
     */
    public static class ToolExecutionResponse {
        /** 是否成功 */
        private boolean success;
        /** 结果数据（成功时） */
        private Map<String, Object> result;
        /** 错误信息（失败时） */
        private String error;

        public static ToolExecutionResponse success(Map<String, Object> result) {
            ToolExecutionResponse response = new ToolExecutionResponse();
            response.success = true;
            response.result = result;
            return response;
        }

        public static ToolExecutionResponse failure(String error) {
            ToolExecutionResponse response = new ToolExecutionResponse();
            response.success = false;
            response.error = error;
            return response;
        }

        public boolean isSuccess() {
            return success;
        }

        public void setSuccess(boolean success) {
            this.success = success;
        }

        public Map<String, Object> getResult() {
            return result;
        }

        public void setResult(Map<String, Object> result) {
            this.result = result;
        }

        public String getError() {
            return error;
        }

        public void setError(String error) {
            this.error = error;
        }
    }
}
