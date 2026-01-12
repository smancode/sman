package com.smancode.smanagent.smancode.core;

import com.smancode.smanagent.model.message.Message;
import com.smancode.smanagent.model.part.*;
import com.smancode.smanagent.model.session.Session;
import com.smancode.smanagent.smancode.llm.LlmService;
import com.smancode.smanagent.smancode.prompt.PromptDispatcher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;

/**
 * SmanCode 编排器
 * <p>
 * 三阶段流程：
 * 1. 意图识别（CHAT/WORK/PLAN_ADJUST/SUMMARY）
 * 2. 需求规划（生成子任务列表）
 * 3. 执行（调用工具完成子任务）
 */
@Service
public class SmanCodeOrchestrator {

    private static final Logger logger = LoggerFactory.getLogger(SmanCodeOrchestrator.class);

    @Autowired
    private IntentRecognizer intentRecognizer;

    @Autowired
    private LlmService llmService;

    @Autowired
    private PromptDispatcher promptDispatcher;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 处理用户请求
     *
     * @param session         会话
     * @param userInput       用户输入
     * @param partPusher      Part 推送器（用于实时反馈）
     * @return 处理结果消息
     */
    public Message process(Session session, String userInput, Consumer<Part> partPusher) {
        logger.info("开始处理: sessionId={}, userInput={}",
            session.getId(), truncate(userInput, 50));

        long startTime = System.currentTimeMillis();

        try {
            // 创建响应消息
            Message responseMessage = new Message();
            responseMessage.setId(UUID.randomUUID().toString());
            responseMessage.setSessionId(session.getId());
            responseMessage.setRole(com.smancode.smanagent.model.message.Role.ASSISTANT);
            responseMessage.setCreatedTime(Instant.now());

            // 1. 意图识别
            pushThinking(partPusher, "正在识别您的需求...");
            IntentRecognizer.IntentResult intentResult = intentRecognizer.recognize(session, userInput);

            // 2. 根据意图分发处理
            switch (intentResult.getIntentType()) {
                case CHAT -> handleChat(responseMessage, intentResult, partPusher);
                case WORK -> handleWork(session, userInput, responseMessage, partPusher);
                case PLAN_ADJUST -> handlePlanAdjust(session, responseMessage, partPusher);
                case SUMMARY -> handleSummary(session, responseMessage, partPusher);
            }

            // 3. 记录执行时长
            long duration = System.currentTimeMillis() - startTime;
            logger.info("处理完成: duration={}ms", duration);

            return responseMessage;

        } catch (Exception e) {
            logger.error("处理失败", e);
            return createErrorMessage(session.getId(), e.getMessage());
        }
    }

    /**
     * 处理闲聊
     */
    private void handleChat(Message message, IntentRecognizer.IntentResult intentResult,
                           Consumer<Part> partPusher) {
        logger.info("处理闲聊");

        TextPart textPart = new TextPart();
        textPart.setMessageId(message.getId());
        textPart.setSessionId(message.getSessionId());
        textPart.setText(intentResult.getChatReply());
        textPart.touch();

        message.addPart(textPart);
        partPusher.accept(textPart);
    }

    /**
     * 处理工作请求
     */
    private void handleWork(Session session, String userInput, Message message,
                           Consumer<Part> partPusher) {
        logger.info("处理工作请求");

        try {
            // 1. 生成计划
            pushThinking(partPusher, "正在规划任务...");
            String plan = generatePlan(session, userInput);

            // 2. 显示计划
            TextPart planPart = new TextPart();
            planPart.setMessageId(message.getId());
            planPart.setSessionId(message.getSessionId());
            planPart.setText(plan);
            planPart.touch();
            message.addPart(planPart);
            partPusher.accept(planPart);

            // 3. 创建目标
            GoalPart goalPart = new GoalPart();
            goalPart.setMessageId(message.getId());
            goalPart.setSessionId(message.getSessionId());
            goalPart.setTitle("分析任务");
            goalPart.setDescription(userInput);
            goalPart.setStatus(GoalPart.GoalStatus.IN_PROGRESS);
            goalPart.touch();
            message.addPart(goalPart);
            partPusher.accept(goalPart);

            // 更新会话目标
            Session.Goal sessionGoal = new Session.Goal();
            sessionGoal.setId(goalPart.getId());
            sessionGoal.setTitle(goalPart.getTitle());
            sessionGoal.setDescription(goalPart.getDescription());
            sessionGoal.setGoalStatus(Session.Goal.GoalStatus.IN_PROGRESS);
            session.setGoal(sessionGoal);

            // 4. 提示用户确认
            TextPart confirmPart = new TextPart();
            confirmPart.setMessageId(message.getId());
            confirmPart.setSessionId(message.getSessionId());
            confirmPart.setText("\n请确认是否执行上述计划？（回复\"确认\"开始执行，或提出修改意见）");
            confirmPart.touch();
            message.addPart(confirmPart);
            partPusher.accept(confirmPart);

        } catch (Exception e) {
            logger.error("处理工作请求失败", e);
            pushError(partPusher, "规划失败: " + e.getMessage());
        }
    }

    /**
     * 处理计划调整
     */
    private void handlePlanAdjust(Session session, Message message,
                                  Consumer<Part> partPusher) {
        logger.info("处理计划调整");

        TextPart textPart = new TextPart();
        textPart.setMessageId(message.getId());
        textPart.setSessionId(message.getSessionId());
        textPart.setText("计划调整功能待实现。当前会话状态: " + session.getStatus());
        textPart.touch();

        message.addPart(textPart);
        partPusher.accept(textPart);
    }

    /**
     * 处理汇总
     */
    private void handleSummary(Session session, Message message,
                              Consumer<Part> partPusher) {
        logger.info("处理汇总");

        StringBuilder summary = new StringBuilder();
        summary.append("## 任务汇总\n\n");

        if (session.getGoal() != null) {
            summary.append("**目标**: ").append(session.getGoal().getTitle()).append("\n\n");
        }

        summary.append("**消息数量**: ").append(session.getMessages().size()).append("\n\n");
        summary.append("**会话状态**: ").append(session.getStatus()).append("\n");

        TextPart textPart = new TextPart();
        textPart.setMessageId(message.getId());
        textPart.setSessionId(message.getSessionId());
        textPart.setText(summary.toString());
        textPart.touch();

        message.addPart(textPart);
        partPusher.accept(textPart);
    }

    /**
     * 生成计划
     */
    private String generatePlan(Session session, String userInput) {
        try {
            // 构建提示词
            String prompt = promptDispatcher.buildRequirementPlanningPrompt(
                userInput,
                "可用工具: semantic_search, grep_file, find_file, read_file, call_chain, extract_xml"
            );

            // 调用 LLM
            String response = llmService.call(prompt);

            // 解析响应
            JsonNode json = objectMapper.readTree(response);
            StringBuilder plan = new StringBuilder();

            plan.append("## 执行计划\n\n");

            JsonNode steps = json.path("steps");
            if (steps.isArray()) {
                for (JsonNode step : steps) {
                    String action = step.path("action").asText();
                    String description = step.path("description").asText();
                    plan.append(String.format("- [%s] %s\n", action, description));
                }
            }

            return plan.toString();

        } catch (Exception e) {
            logger.error("生成计划失败", e);
            return "生成计划失败，请稍后重试。";
        }
    }

    /**
     * 推送思考状态
     */
    private void pushThinking(Consumer<Part> partPusher, String text) {
        ReasoningPart reasoningPart = new ReasoningPart();
        reasoningPart.setText(text);
        reasoningPart.touch();
        partPusher.accept(reasoningPart);
    }

    /**
     * 推送错误
     */
    private void pushError(Consumer<Part> partPusher, String error) {
        TextPart textPart = new TextPart();
        textPart.setText("❌ " + error);
        textPart.touch();
        partPusher.accept(textPart);
    }

    /**
     * 创建错误消息
     */
    private Message createErrorMessage(String sessionId, String error) {
        Message message = new Message();
        message.setId(UUID.randomUUID().toString());
        message.setSessionId(sessionId);
        message.setRole(com.smancode.smanagent.model.message.Role.ASSISTANT);
        message.setCreatedTime(Instant.now());

        TextPart textPart = new TextPart();
        textPart.setMessageId(message.getId());
        textPart.setSessionId(sessionId);
        textPart.setText("❌ 处理失败: " + error);
        textPart.touch();

        message.addPart(textPart);
        return message;
    }

    /**
     * 截断字符串（用于日志）
     */
    private String truncate(String str, int maxLen) {
        if (str == null) return null;
        return str.length() > maxLen ? str.substring(0, maxLen) + "..." : str;
    }
}
