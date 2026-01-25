package com.smancode.smanagent.smancode.prompt;

import com.smancode.smanagent.util.StackTraceUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Prompt 自动注入器
 * <p>
 * 设计原则（极简方案）：
 * - 每个会话首次请求时，一次性加载所有必要的 Prompt
 * - 避免硬编码模式匹配
 * - 避免复杂的对话状态跟踪
 * - 避免额外的 LLM 调用
 * <p>
 * 为什么这样设计？
 * - 系统主要处理复杂业务需求
 * - Prompt 只加载一次，后续请求不再加载
 * - 简单、透明、高效
 */
@Service
public class DynamicPromptInjector {

    private static final Logger logger = LoggerFactory.getLogger(DynamicPromptInjector.class);

    @Autowired
    private PromptLoaderService promptLoader;

    /**
     * 记录已加载过 Prompt 的会话
     * key: sessionKey, value: true 表示已加载
     */
    private final ConcurrentHashMap<String, Boolean> loadedSessions = new ConcurrentHashMap<>();

    /**
     * 检测并注入动态 Prompt
     * <p>
     * 策略：
     * - 如果该会话是首次请求，加载所有 Prompt
     * - 如果该会话已加载过，返回空结果
     * <p>
     * 无需：
     * - 硬编码模式匹配
     * - 对话轮次判断
     * - 用户确认检测
     * - LLM 显式请求
     *
     * @param sessionKey 会话标识
     * @return 需要注入的额外 Prompt 内容
     */
    public InjectResult detectAndInject(String sessionKey) {
        InjectResult result = new InjectResult();

        // 检查该会话是否已加载过
        if (loadedSessions.containsKey(sessionKey)) {
            logger.debug("会话 {} 已加载过 Prompt，跳过", sessionKey);
            return result;
        }

        // 首次请求：加载所有 Prompt
        logger.info("会话 {} 首次请求，加载所有 Prompt", sessionKey);

        try {
            // 加载复杂任务工作流
            String workflowPrompt = promptLoader.loadPrompt("common/complex-task-workflow.md");
            result.setComplexTaskWorkflow(workflowPrompt);
            result.setNeedComplexTaskWorkflow(true);

            // 加载编码最佳实践
            String practicesPrompt = promptLoader.loadPrompt("common/coding-best-practices.md");
            result.setCodingBestPractices(practicesPrompt);
            result.setNeedCodingBestPractices(true);

            // 标记该会话已加载
            loadedSessions.put(sessionKey, true);

            logger.info("会话 {} Prompt 加载完成", sessionKey);
        } catch (Exception e) {
            logger.error("加载 Prompt 失败, sessionKey={}, {}", sessionKey, StackTraceUtils.formatStackTrace(e));
        }

        return result;
    }

    /**
     * 清理会话记录（会话结束时调用）
     *
     * @param sessionKey 会话标识
     */
    public void clearSession(String sessionKey) {
        loadedSessions.remove(sessionKey);
        logger.debug("清理会话 {} 的 Prompt 加载记录", sessionKey);
    }

    /**
     * 注入结果
     */
    public static class InjectResult {
        private boolean needComplexTaskWorkflow = false;
        private boolean needCodingBestPractices = false;
        private String complexTaskWorkflow;
        private String codingBestPractices;

        public InjectResult() {
        }

        public boolean isNeedComplexTaskWorkflow() {
            return needComplexTaskWorkflow;
        }

        public void setNeedComplexTaskWorkflow(boolean needComplexTaskWorkflow) {
            this.needComplexTaskWorkflow = needComplexTaskWorkflow;
        }

        public boolean isNeedCodingBestPractices() {
            return needCodingBestPractices;
        }

        public void setNeedCodingBestPractices(boolean needCodingBestPractices) {
            this.needCodingBestPractices = needCodingBestPractices;
        }

        public String getComplexTaskWorkflow() {
            return complexTaskWorkflow;
        }

        public void setComplexTaskWorkflow(String complexTaskWorkflow) {
            this.complexTaskWorkflow = complexTaskWorkflow;
        }

        public String getCodingBestPractices() {
            return codingBestPractices;
        }

        public void setCodingBestPractices(String codingBestPractices) {
            this.codingBestPractices = codingBestPractices;
        }

        /**
         * 获取需要注入的完整内容
         */
        public String getInjectedContent() {
            StringBuilder sb = new StringBuilder();

            if (needComplexTaskWorkflow && complexTaskWorkflow != null) {
                sb.append("\n\n## Loaded: Complex Task Workflow\n\n");
                sb.append(complexTaskWorkflow);
            }

            if (needCodingBestPractices && codingBestPractices != null) {
                sb.append("\n\n## Loaded: Coding Best Practices\n\n");
                sb.append(codingBestPractices);
            }

            return sb.toString();
        }

        /**
         * 是否有需要注入的内容
         */
        public boolean hasContent() {
            return (needComplexTaskWorkflow && complexTaskWorkflow != null)
                || (needCodingBestPractices && codingBestPractices != null);
        }
    }
}
