package ai.smancode.sman.agent.claude;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Claude Code HTTP Tool API 控制器
 *
 * 功能：
 * - 接收来自 Claude Code 的工具调用请求
 * - 调用 HttpToolExecutor 执行工具
 * - 返回执行结果
 *
 * API 端点：
 * - POST /api/claude-code/tools/execute - 执行工具
 *
 * @author SiliconMan Team
 * @since 1.0.0
 */
@RestController
@RequestMapping("/api/claude-code")
public class ClaudeCodeToolController {

    private static final Logger log = LoggerFactory.getLogger(ClaudeCodeToolController.class);

    @Autowired
    private HttpToolExecutor toolExecutor;

    @Autowired
    private SessionManager sessionManager;

    @Autowired
    private ClaudeCodeProcessPool processPool;

    /**
     * 执行工具
     *
     * @param request 工具执行请求
     * @return 工具执行响应
     */
    @PostMapping("/tools/execute")
    public ResponseEntity<ClaudeCodeToolModels.ToolExecutionResponse> executeTool(
            @RequestBody ClaudeCodeToolModels.ToolExecutionRequest request) {

        log.info("🔧 收到工具调用: tool={}, workerId={}, projectKey={}, sessionId={}, webSocketSessionId={}, params={}",
                request.getTool(), request.getWorkerId(), request.getProjectKey(),
                request.getSessionId(), request.getWebSocketSessionId(), request.getParams());

        try {
            // 验证请求
            if (request.getTool() == null || request.getTool().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(ClaudeCodeToolModels.ToolExecutionResponse.failure("缺少 tool 参数"));
            }

            if (request.getParams() == null) {
                return ResponseEntity.badRequest()
                        .body(ClaudeCodeToolModels.ToolExecutionResponse.failure("缺少 params 参数"));
            }

            // 记录会话
            if (request.getWorkerId() != null) {
                sessionManager.recordActivity(request.getWorkerId(), request.getTool());
            }

            // 执行工具（传递 projectKey、sessionId 和 webSocketSessionId）
            ClaudeCodeToolModels.ToolExecutionResponse response =
                    toolExecutor.execute(
                            request.getTool(),
                            request.getParams(),
                            request.getProjectKey(),
                            request.getSessionId(),
                            request.getWebSocketSessionId()
                    );

            if (response.isSuccess()) {
                log.info("✅ 工具执行成功: tool={}", request.getTool());
            } else {
                log.warn("❌ 工具执行失败: tool={}, error={}", request.getTool(), response.getError());
            }

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("❌ 工具执行异常: tool={}, error={}", request.getTool(), e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(ClaudeCodeToolModels.ToolExecutionResponse.failure(e.getMessage()));
        }
    }

    /**
     * 健康检查
     */
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("OK");
    }

    /**
     * 查看进程池状态
     */
    @GetMapping("/pool/status")
    public ResponseEntity<ClaudeCodeProcessPool.PoolStatus> getPoolStatus() {
        log.debug("📊 查询进程池状态");

        try {
            ClaudeCodeProcessPool.PoolStatus status = processPool.getStatus();
            return ResponseEntity.ok(status);

        } catch (Exception e) {
            log.error("❌ 获取进程池状态失败: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }
}
