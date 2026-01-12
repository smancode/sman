package com.smancode.smanagent.smancode.core;

import com.smancode.smanagent.model.message.Message;
import com.smancode.smanagent.model.session.Session;
import com.smancode.smanagent.smancode.llm.LlmService;
import com.smancode.smanagent.smancode.prompt.PromptDispatcher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 意图识别器
 * <p>
 * 负责识别用户意图，决定后续处理流程。
 */
@Service
public class IntentRecognizer {

    private static final Logger logger = LoggerFactory.getLogger(IntentRecognizer.class);

    @Autowired
    private LlmService llmService;

    @Autowired
    private PromptDispatcher promptDispatcher;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 意图类型枚举
     */
    public enum IntentType {
        CHAT,           // 闲聊
        WORK,           // 工作（需要规划+执行）
        PLAN_ADJUST,    // 调整计划
        SUMMARY         // 汇总
    }

    /**
     * 意图识别结果
     */
    public static class IntentResult {
        private IntentType intentType;
        private String chatReply;
        private double confidence;
        private String reason;

        public static IntentResult chat(String reply) {
            IntentResult r = new IntentResult();
            r.intentType = IntentType.CHAT;
            r.chatReply = reply;
            r.confidence = 1.0;
            return r;
        }

        public static IntentResult work() {
            IntentResult r = new IntentResult();
            r.intentType = IntentType.WORK;
            r.confidence = 1.0;
            return r;
        }

        public static IntentResult planAdjust() {
            IntentResult r = new IntentResult();
            r.intentType = IntentType.PLAN_ADJUST;
            r.confidence = 1.0;
            return r;
        }

        public static IntentResult summary() {
            IntentResult r = new IntentResult();
            r.intentType = IntentType.SUMMARY;
            r.confidence = 1.0;
            return r;
        }

        // Getters and Setters
        public IntentType getIntentType() { return intentType; }
        public void setIntentType(IntentType intentType) { this.intentType = intentType; }
        public String getChatReply() { return chatReply; }
        public void setChatReply(String chatReply) { this.chatReply = chatReply; }
        public double getConfidence() { return confidence; }
        public void setConfidence(double confidence) { this.confidence = confidence; }
        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
    }

    /** 闲聊触发词 */
    private static final Set<String> CHAT_TRIGGERS = Set.of(
        "谢谢", "感谢", "你好", "在吗", "能做什么", "帮助",
        "hi", "hello", "hey"
    );

    /** 确认触发词 */
    private static final Set<String> CONFIRM_TRIGGERS = Set.of(
        "好", "是", "对", "确认", "执行", "开始", "继续",
        "开始吧", "继续吧", "ok", "yes", "好的"
    );

    /**
     * 识别用户意图
     *
     * @param session   会话
     * @param userInput 用户输入
     * @return 意图识别结果
     */
    public IntentResult recognize(Session session, String userInput) {
        logger.info("识别意图: userInput={}", truncate(userInput, 50));

        try {
            // 1. 快速匹配闲聊
            if (isChatTrigger(userInput)) {
                logger.info("意图识别: CHAT (规则匹配)");
                return IntentResult.chat(getChatReply(userInput));
            }

            // 2. 检查是否有待确认的计划
            if (hasPendingPlan(session) && isConfirmTrigger(userInput)) {
                logger.info("意图识别: PLAN_ADJUST (确认执行)");
                return IntentResult.planAdjust();
            }

            // 3. 使用 LLM 进行意图分类
            return llmClassifyIntent(session, userInput);

        } catch (Exception e) {
            logger.error("意图识别失败", e);
            // 失败时默认为 WORK
            return IntentResult.work();
        }
    }

    /**
     * 使用 LLM 进行意图分类
     */
    private IntentResult llmClassifyIntent(Session session, String userInput) {
        try {
            // 构建提示词
            String prompt = promptDispatcher.buildIntentRecognitionPrompt(userInput);

            // 调用 LLM
            String response = llmService.call(prompt);

            // 解析响应
            JsonNode json = objectMapper.readTree(response);
            String intentStr = json.path("intent").asText("WORK");
            String reason = json.path("reason").asText("");

            logger.info("LLM 意图识别: intent={}, reason={}", intentStr, reason);

            // 映射到意图类型
            return switch (intentStr.toUpperCase()) {
                case "CHAT" -> IntentResult.chat(getChatReply(userInput));
                case "PLAN_ADJUST" -> IntentResult.planAdjust();
                case "SUMMARY" -> IntentResult.summary();
                default -> IntentResult.work();
            };

        } catch (Exception e) {
            logger.error("LLM 意图分类失败", e);
            return IntentResult.work();
        }
    }

    /**
     * 判断是否为闲聊触发词
     */
    private boolean isChatTrigger(String input) {
        String trimmed = input.trim().toLowerCase();
        return CHAT_TRIGGERS.stream().anyMatch(trimmed::contains);
    }

    /**
     * 判断是否为确认触发词
     */
    private boolean isConfirmTrigger(String input) {
        String trimmed = input.trim().toLowerCase();
        return CONFIRM_TRIGGERS.stream().anyMatch(trimmed::contains);
    }

    /**
     * 检查是否有待确认的计划
     */
    private boolean hasPendingPlan(Session session) {
        // 检查会话中是否有待执行的子任务
        return session.getGoal() != null &&
               session.getGoal().getGoalStatus() == Session.Goal.GoalStatus.IN_PROGRESS;
    }

    /**
     * 获取闲聊回复
     */
    private String getChatReply(String input) {
        if (input.contains("你好") || input.contains("hi") || input.contains("hello")) {
            return "你好！我是 SmanAgent，可以帮你分析代码、搜索文件、理解调用链等。";
        }
        if (input.contains("谢谢") || input.contains("感谢")) {
            return "不客气！有需要随时叫我。";
        }
        if (input.contains("能做什么") || input.contains("帮助")) {
            return "我可以帮你：\n- 搜索代码（语义搜索/正则搜索）\n- 分析调用链\n- 读取文件\n- 提取 XML 标签内容\n\n直接告诉我你的需求即可！";
        }
        return "收到！有其他需要随时告诉我。";
    }

    /**
     * 截断字符串（用于日志）
     */
    private String truncate(String str, int maxLen) {
        if (str == null) return null;
        return str.length() > maxLen ? str.substring(0, maxLen) + "..." : str;
    }
}
