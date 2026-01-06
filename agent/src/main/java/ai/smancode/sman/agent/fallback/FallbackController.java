package ai.smancode.sman.agent.fallback;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 降级模式控制器
 *
 * 提供 REST API 用于：
 * - 查看降级状态
 * - 手动启用/禁用降级模式
 * - 测试降级模式
 *
 * @author SiliconMan Team
 * @since 2.0
 */
@RestController
@RequestMapping("/api/fallback")
public class FallbackController {

    private static final Logger log = LoggerFactory.getLogger(FallbackController.class);

    @Autowired
    private FallbackDetector fallbackDetector;

    @Autowired
    private FallbackOrchestrator fallbackOrchestrator;

    /**
     * 获取降级状态
     */
    @GetMapping("/status")
    public Map<String, Object> getStatus() {
        log.debug("📊 查询降级状态");

        FallbackDetector.FallbackStatus status = fallbackDetector.getStatus();

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("status", status.toMap());

        return response;
    }

    /**
     * 手动启用降级
     */
    @PostMapping("/enable")
    public Map<String, Object> enableFallback() {
        log.info("🔴 手动启用降级模式");

        fallbackDetector.enableFallback();

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "已启用降级模式");
        response.put("status", fallbackDetector.getStatus().toMap());

        return response;
    }

    /**
     * 手动退出降级
     */
    @PostMapping("/disable")
    public Map<String, Object> disableFallback() {
        log.info("🟢 手动退出降级模式");

        fallbackDetector.disableFallback();

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "已退出降级模式");
        response.put("status", fallbackDetector.getStatus().toMap());

        return response;
    }

    /**
     * 测试降级模式
     */
    @PostMapping("/test")
    public Map<String, Object> testFallback(@RequestBody Map<String, String> request) {
        String message = request.get("message");
        String projectKey = request.getOrDefault("projectKey", "test");
        String sessionId = request.getOrDefault("sessionId", "test-session");

        log.info("🧪 测试降级模式: message={}, projectKey={}", message, projectKey);

        String result = fallbackOrchestrator.processRequest(message, projectKey, sessionId);

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("result", result);
        response.put("status", fallbackDetector.getStatus().toMap());

        return response;
    }
}
