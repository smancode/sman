package ai.smancode.sman.agent.claude;

import ai.smancode.sman.agent.config.ProjectConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 快速分析控制器 - 端到端测试
 *
 * 架构说明（新流程，使用 --resume 模式）:
 * 前端 → 本Controller → 创建Worker进程 → AI推理 → HTTP回调Agent工具API → 返回结果 → Worker进程退出
 *
 * 特点：
 * - 每个请求都是独立Worker进程
 * - 使用 --resume 参数恢复会话历史
 * - 不需要保持Worker运行
 * - 通过并发控制限制同时运行的进程数
 */
@RestController
@RequestMapping("/api/analysis")
public class QuickAnalysisController {

    private static final Logger log = LoggerFactory.getLogger(QuickAnalysisController.class);

    @Autowired
    private ClaudeCodeProcessPool processPool;

    @Autowired
    private ProjectConfigService projectConfigService;

    @Value("${claude-code.http-api.endpoint:/api/claude-code/tools/execute}")
    private String httpApiEndpoint;

    @Value("${server.port:8080}")
    private int serverPort;

    private final Map<String, List<Message>> sessions = new HashMap<>();

    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestBody Map<String, Object> request) {
        String sessionId = (String) request.get("sessionId");
        String message = (String) request.get("message");
        String projectKey = (String) request.getOrDefault("projectKey", "test");
        String mode = (String) request.getOrDefault("mode", "agent");

        log.info("========================================");
        log.info("📨 收到分析请求");
        log.info("  sessionId: {}", sessionId);
        log.info("  message: {}", message);
        log.info("  projectKey: {}", projectKey);
        log.info("  mode: {}", mode);
        log.info("========================================");

        ClaudeCodeWorker worker = null;

        try {
            // 🔥 查询 projectPath：始终使用配置文件中的路径
            String projectPath;
            if (projectConfigService.hasProject(projectKey)) {
                projectPath = projectConfigService.getProjectPath(projectKey);
                log.info("✅ 从配置文件获取 projectPath: projectKey={}, projectPath={}", projectKey, projectPath);
            } else {
                log.error("❌ 未找到 projectKey 映射: {}，请检查 application.yml 配置", projectKey);
                Map<String, Object> errorResponse = new HashMap<>();
                errorResponse.put("error", "PROJECT_NOT_FOUND");
                errorResponse.put("message", "未找到 projectKey 映射: " + projectKey + "，请检查 application.yml 配置");
                return errorResponse;
            }

            // 构建发送给 Claude Code 的消息
            String claudeMessage = buildClaudeMessage(message, projectKey, sessionId);

            // ⭐ 获取并发许可（限制同时运行的进程数）
            log.info("🔄 等待并发许可 (sessionId={})...", sessionId);
            processPool.acquireConcurrency();
            log.info("✅ 获得并发许可 (sessionId={})", sessionId);

            try {
                // 🔥 创建Worker进程（传入 projectKey 和 projectPath 用于环境变量）
                // 🔥 生成 logTag
                String shortUuid = sessionId.length() > 12 ? sessionId.substring(sessionId.length() - 12) : sessionId;
                String timestamp = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HHmmss"));
                String logTag = "[" + shortUuid + "_" + timestamp + "]";

                log.info("🚀 创建Worker进程 (sessionId={}, projectKey={}, projectPath={}, mode={})...",
                        sessionId, projectKey, projectPath, mode);

                // 🔥 解析执行模式并传递给 createWorker
                ClaudeCodeProcessPool.ExecutionMode execMode =
                    ClaudeCodeProcessPool.ExecutionMode.fromString(mode);

                worker = processPool.createWorker(sessionId, projectKey, projectPath, logTag, execMode);
                log.info("✅ Worker进程创建成功: {} (sessionId={}, mode={})",
                        worker.getWorkerId(), sessionId, execMode);

                // ⭐ 发送给 Claude Code 并获取响应
                log.info("📤 发送消息给 Claude Code (sessionId={}, timeout=1800s)...", sessionId);
                String claudeResponse = worker.sendAndReceive(claudeMessage, 1800);
                log.info("📥 收到 Claude Code 响应");

                // 解析响应
                Map<String, Object> response = new HashMap<>();
                response.put("sessionId", sessionId);
                response.put("answer", claudeResponse);
                response.put("workerId", worker.getWorkerId());
                response.put("timestamp", System.currentTimeMillis());

                // 保存到会话历史
                List<Message> history = sessions.computeIfAbsent(sessionId, k -> new ArrayList<>());
                history.add(new Message("user", message));
                history.add(new Message("assistant", claudeResponse));

                log.info("✅ 分析完成");
                return response;

            } finally {
                // ⭐ 释放并发许可
                processPool.releaseConcurrency();

                // ⭐ 标记Worker完成
                if (worker != null) {
                    processPool.markWorkerCompleted(worker);
                    log.info("✅ Worker {} 完成 (sessionId={})", worker.getWorkerId(), sessionId);
                }
            }

        } catch (Exception e) {
            log.error("❌ 处理失败", e);
            Map<String, Object> error = new HashMap<>();
            error.put("sessionId", sessionId);
            error.put("error", e.getMessage());
            error.put("errorType", e.getClass().getSimpleName());
            return error;
        }
    }

    /**
     * 构建发送给 Claude Code 的消息
     */
    private String buildClaudeMessage(String userMessage, String projectKey, String sessionId) {
        // Agent HTTP API 基础 URL
        String agentApiUrl = "http://localhost:" + serverPort + httpApiEndpoint;

        StringBuilder sb = new StringBuilder();
        sb.append("## 用户需求\n\n");
        sb.append(userMessage);
        sb.append("\n\n");

        sb.append("## 项目信息\n\n");
        sb.append("- projectKey: ").append(projectKey).append("\n");
        sb.append("- sessionId: ").append(sessionId).append("\n");
        sb.append("- agentApiUrl: ").append(agentApiUrl).append("\n");
        sb.append("\n");

        sb.append("## 工具使用说明\n\n");
        sb.append("你需要使用以下工具来完成任务：\n\n");
        sb.append("1. **semantic_search**: 语义搜索相关代码\n");
        sb.append("   调用示例: curl -X POST ").append(agentApiUrl).append(" \\\n");
        sb.append("     -H 'Content-Type: application/json' \\\n");
        sb.append("     -d '{\"toolName\":\"semantic_search\",\"params\":{\"recallQuery\":\"文件读取\",\"recallTopK\":50,\"rerankQuery\":\"文件异常\",\"rerankTopN\":10,\"enableReranker\":true}}'\n\n");

        sb.append("2. **apply_change**: 应用代码修改\n");
        sb.append("   调用示例: curl -X POST ").append(agentApiUrl).append(" \\\n");
        sb.append("     -H 'Content-Type: application/json' \\\n");
        sb.append("     -d '{\"toolName\":\"apply_change\",\"params\":{\"relativePath\":\"src/main/java/io/FileReader.java\",\"searchContent\":\"int maxRetries = 1;\",\"replaceContent\":\"int maxRetries = 3;\",\"description\":\"修改重试次数\"}}'\n\n");

        sb.append("## 重要提示\n\n");
        sb.append("1. 你必须使用上述 HTTP API 来调用工具\n");
        sb.append("2. 禁止使用 Read、Edit、Bash、Write 等内置工具\n");
        sb.append("3. 所有工具调用都通过 curl 命令发送 HTTP 请求\n");
        sb.append("4. 分析结果后，给出清晰的结论和建议\n");

        return sb.toString();
    }

    public static class Message {
        String role;
        String content;

        public Message(String role, String content) {
            this.role = role;
            this.content = content;
        }

        public String getRole() { return role; }
        public String getContent() { return content; }
    }
}
