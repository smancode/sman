package com.smancode.smanagent.smancode.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smancode.smanagent.model.message.Message;
import com.smancode.smanagent.model.part.Part;
import com.smancode.smanagent.model.part.TextPart;
import com.smancode.smanagent.model.part.ReasoningPart;
import com.smancode.smanagent.model.part.ToolPart;
import com.smancode.smanagent.model.session.Session;
import com.smancode.smanagent.smancode.llm.LlmService;
import com.smancode.smanagent.tools.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.function.Consumer;

/**
 * 流式通知处理器
 * <p>
 * 负责生成和推送渐进式流式输出的各种通知消息
 */
@Component
public class StreamingNotificationHandler {

    private static final Logger logger = LoggerFactory.getLogger(StreamingNotificationHandler.class);

    @Autowired
    private LlmService llmService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 立即推送确认消息（返回判断结果）
     * <p>
     * 返回一个 AcknowledgmentResult 对象，包含：
     * - needConsult: 是否需要专家咨询
     * - isChat: 是否是闲聊
     */
    public AcknowledgmentResult pushImmediateAcknowledgment(Session session, Consumer<Part> partPusher) {
        Message latestUser = session.getLatestUserMessage();
        if (latestUser == null || latestUser.getParts().isEmpty()) {
            return new AcknowledgmentResult(true, false);  // 默认需要专家咨询
        }

        Part firstPart = latestUser.getParts().get(0);
        if (!(firstPart instanceof TextPart)) {
            return new AcknowledgmentResult(true, false);
        }

        String userQuestion = ((TextPart) firstPart).getText();

        // 调用 LLM 生成简短确认并判断
        String ackPrompt = buildAcknowledgmentPrompt(userQuestion);
        try {
            JsonNode json = llmService.jsonRequest(ackPrompt);

            String ackText = json.path("acknowledgment").asText("");
            boolean needConsult = json.path("needConsult").asBoolean(true);
            boolean isChat = json.path("isChat").asBoolean(false);

            // 如果不是闲聊且有确认语，则推送
            if (!isChat && !ackText.isEmpty()) {
                ReasoningPart ackPart = new ReasoningPart();
                ackPart.setSessionId(session.getId());
                ackPart.setText(ackText);
                ackPart.touch();
                partPusher.accept(ackPart);
            }

            return new AcknowledgmentResult(needConsult, isChat);

        } catch (Exception e) {
            logger.warn("生成确认消息失败", e);
            // 失败时使用默认确认，保守策略：需要专家咨询
            ReasoningPart ackPart = new ReasoningPart();
            ackPart.setSessionId(session.getId());
            ackPart.setText("思考中");
            ackPart.touch();
            partPusher.accept(ackPart);
            return new AcknowledgmentResult(true, false);
        }
    }

    /**
     * 确认结果
     */
    public static class AcknowledgmentResult {
        private final boolean needConsult;
        private final boolean isChat;

        public AcknowledgmentResult(boolean needConsult, boolean isChat) {
            this.needConsult = needConsult;
            this.isChat = isChat;
        }

        public boolean isNeedConsult() {
            return needConsult;
        }

        public boolean isChat() {
            return isChat;
        }
    }

    /**
     * 构建确认消息提示词（英文思考，中文回答）
     */
    private String buildAcknowledgmentPrompt(String userQuestion) {
        return String.format("""
                # Task: Analyze User Input

                You are analyzing a user's input to determine:
                1. Is this a casual chat (greeting/thanks/self-introduction)?
                2. Does this require expert consultation (user has no specific target)?
                3. Generate a brief acknowledgment if needed

                ## User Input
                %s

                ## Analysis Rules (Think in English)

                ### isChat = true
                - Greetings: "你好", "嗨", "早上好", "hello"
                - Thanks: "谢谢", "感谢", "thx"
                - Self-introduction: "我是...", "我是阿瓜"

                ### needConsult = false (User has clear target)
                - User provides specific class name: "ReadFileTool.execute 方法分析一下"
                - User provides specific file path: "分析 com/smancode/... 下的文件"
                - User provides explicit instruction on what to analyze
                - DO NOT add extra steps when user is clear!

                ### needConsult = true (User needs help finding context)
                - User describes problem in natural language: "支付流程是怎样的？"
                - User mentions business terms without specific class: "账号挂失怎么处理？"
                - User asks vague questions requiring context discovery

                ## Output Format (Chinese)

                ```json
                {
                  "acknowledgment": "简短确认语（闲聊时留空）",
                  "needConsult": true/false,
                  "isChat": true/false
                }
                ```

                ## Examples

                Input: "ReadFileTool.execute 方法分析一下"
                Output: {"acknowledgment": "收到，已理解需求", "needConsult": false, "isChat": false}

                Input: "支付流程是怎样的？"
                Output: {"acknowledgment": "正在分析支付流程", "needConsult": true, "isChat": false}

                Input: "你好"
                Output: {"acknowledgment": "", "needConsult": false, "isChat": true}
                """, userQuestion);
    }

    /**
     * 推送最终总结
     */
    public void pushFinalSummary(Message assistantMessage, Session session, Consumer<Part> partPusher) {
        try {
            // 构建最终总结提示词
            String summaryPrompt = buildFinalSummaryPrompt(assistantMessage, session);

            // 调用 LLM 生成总结
            JsonNode json = llmService.jsonRequest(summaryPrompt);
            String summaryText = json.path("summary").asText("");

            if (!summaryText.isEmpty()) {
                TextPart summaryPart = new TextPart();
                summaryPart.setMessageId(assistantMessage.getId());
                summaryPart.setSessionId(session.getId());
                summaryPart.setText("📋 完整结论\n\n" + summaryText + "\n");
                summaryPart.touch();
                partPusher.accept(summaryPart);
            }

        } catch (Exception e) {
            logger.warn("生成最终总结失败", e);
            // 失败不影响主流程
        }
    }

    /**
     * 构建最终总结提示词
     */
    private String buildFinalSummaryPrompt(Message assistantMessage, Session session) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是代码分析助手。刚刚执行了一系列分析工具，请生成最终总结。\n\n");

        // 添加用户问题
        prompt.append("## 用户问题\n");
        Message latestUser = session.getLatestUserMessage();
        if (latestUser != null && !latestUser.getParts().isEmpty()) {
            Part firstPart = latestUser.getParts().get(0);
            if (firstPart instanceof TextPart) {
                prompt.append(((TextPart) firstPart).getText()).append("\n\n");
            }
        }

        // 添加执行的工具和结果摘要
        prompt.append("## 执行的工具\n");
        for (Part part : assistantMessage.getParts()) {
            if (part instanceof ToolPart toolPart) {
                prompt.append("- ").append(toolPart.getToolName());
                if (toolPart.getParameters() != null && !toolPart.getParameters().isEmpty()) {
                    prompt.append(" (参数: ").append(formatParamsBrief(toolPart.getParameters())).append(")");
                }
                prompt.append("\n");

                if (toolPart.getResult() != null && toolPart.getResult().getData() != null) {
                    String resultSummary = ToolResultFormatter.generateResultSummary(
                            toolPart.getToolName(),
                            toolPart.getResult().getData());
                    prompt.append("  结果: ").append(resultSummary).append("\n");
                }
            }
        }
        prompt.append("\n");

        prompt.append("## 要求\n");
        prompt.append("请生成完整的分析总结，包括：\n");
        prompt.append("1. 核心发现：分析过程中最重要的发现是什么\n");
        prompt.append("2. 详细说明：结合所有工具结果，给出完整的分析\n");
        prompt.append("3. 建议或结论：基于分析结果给出具体建议或结论\n\n");
        prompt.append("请以 JSON 格式返回：\n");
        prompt.append("{\n");
        prompt.append("  \"summary\": \"你的完整总结\"\n");
        prompt.append("}");

        return prompt.toString();
    }

    /**
     * 推送工具调用通知
     */
    public void pushToolCallNotification(ToolPart toolPart, Consumer<Part> partPusher) {
        TextPart notification = new TextPart();
        notification.setMessageId(toolPart.getMessageId());
        notification.setSessionId(toolPart.getSessionId());

        String toolName = toolPart.getToolName();
        Map<String, Object> params = toolPart.getParameters();

        StringBuilder sb = new StringBuilder();
        sb.append("→ 调用工具: ").append(toolName).append("\n");
        if (!params.isEmpty()) {
            sb.append("   参数: ").append(formatParamsBrief(params)).append("\n");
        }

        notification.setText(sb.toString());
        notification.touch();
        partPusher.accept(notification);
    }

    /**
     * 推送工具执行进度通知
     */
    public void pushToolProgressNotification(ToolPart toolPart, Consumer<Part> partPusher) {
        TextPart notification = new TextPart();
        notification.setMessageId(toolPart.getMessageId());
        notification.setSessionId(toolPart.getSessionId());

        String toolName = toolPart.getToolName();

        notification.setText(String.format("⏳ 执行中: %s\n", toolName));
        notification.touch();
        partPusher.accept(notification);
    }

    /**
     * 推送工具完成通知
     */
    public void pushToolCompletedNotification(ToolPart toolPart, ToolResult result, Consumer<Part> partPusher) {
        TextPart notification = new TextPart();
        notification.setMessageId(toolPart.getMessageId());
        notification.setSessionId(toolPart.getSessionId());

        StringBuilder sb = new StringBuilder();
        sb.append("✓ 工具完成: ").append(toolPart.getToolName()).append("\n");

        // 根据结果类型添加摘要
        Object data = result.getData();
        if (data != null) {
            String summary = ToolResultFormatter.generateResultSummary(toolPart.getToolName(), data);
            if (!summary.isEmpty()) {
                sb.append("   ").append(summary).append("\n");
            }
        }

        notification.setText(sb.toString());
        notification.touch();
        partPusher.accept(notification);
    }

    /**
     * 推送工具错误通知
     */
    public void pushToolErrorNotification(ToolPart toolPart, ToolResult result, Consumer<Part> partPusher) {
        TextPart notification = new TextPart();
        notification.setMessageId(toolPart.getMessageId());
        notification.setSessionId(toolPart.getSessionId());

        notification.setText(String.format("✗ 工具失败: %s\n   原因: %s\n",
                toolPart.getToolName(),
                result.getError() != null ? result.getError() : "未知错误"));
        notification.touch();
        partPusher.accept(notification);
    }

    /**
     * 推送阶段性结论（通过 LLM 生成）
     */
    public void pushIntermediateConclusion(ToolPart toolPart, ToolResult result,
                                           Session session, Consumer<Part> partPusher) {
        try {
            // 构建阶段性结论提示词
            String conclusionPrompt = buildIntermediateConclusionPrompt(toolPart, result, session);

            // 调用 LLM 生成阶段性结论
            JsonNode json = llmService.jsonRequest(conclusionPrompt);
            String conclusionText = json.path("conclusion").asText("");

            if (!conclusionText.isEmpty()) {
                TextPart conclusionPart = new TextPart();
                conclusionPart.setMessageId(toolPart.getMessageId());
                conclusionPart.setSessionId(toolPart.getSessionId());

                // 获取当前会话中已完成的工具数量
                int completedCount = countCompletedTools(session);
                conclusionPart.setText(String.format("📊 阶段性结论 %d:\n%s\n",
                        completedCount, conclusionText));

                conclusionPart.touch();
                partPusher.accept(conclusionPart);
            }

        } catch (Exception e) {
            logger.warn("生成阶段性结论失败: toolName={}", toolPart.getToolName(), e);
            // 失败不影响主流程
        }
    }

    /**
     * 构建阶段性结论提示词
     */
    private String buildIntermediateConclusionPrompt(ToolPart toolPart, ToolResult result, Session session) {
        StringBuilder prompt = new StringBuilder();

        prompt.append("你是一个代码分析助手。刚刚执行了一个工具，请生成简短的阶段性结论。\n\n");
        prompt.append("## 工具信息\n");
        prompt.append("- 工具名称: ").append(toolPart.getToolName()).append("\n");
        prompt.append("- 工具参数: ").append(formatParamsBrief(toolPart.getParameters())).append("\n");
        prompt.append("- 执行结果: ").append(ToolResultFormatter.formatToolResult(result)).append("\n\n");

        prompt.append("## 用户原始问题\n");
        Message latestUser = session.getLatestUserMessage();
        if (latestUser != null && !latestUser.getParts().isEmpty()) {
            Part firstPart = latestUser.getParts().get(0);
            if (firstPart instanceof TextPart) {
                prompt.append(((TextPart) firstPart).getText()).append("\n\n");
            }
        }

        prompt.append("## 要求\n");
        prompt.append("请生成一个简短的阶段性结论（1-3句话），说明这个工具的执行发现了什么，");
        prompt.append("以及这对解决用户问题有什么帮助。\n\n");
        prompt.append("请以 JSON 格式返回：\n");
        prompt.append("{\n");
        prompt.append("  \"conclusion\": \"你的阶段性结论\"\n");
        prompt.append("}");

        return prompt.toString();
    }

    /**
     * 统计已完成的工具数量
     */
    private int countCompletedTools(Session session) {
        int count = 0;
        for (Message message : session.getMessages()) {
            if (message.isAssistantMessage()) {
                for (Part part : message.getParts()) {
                    if (part instanceof ToolPart) {
                        ToolPart.ToolState state = ((ToolPart) part).getState();
                        if (state == ToolPart.ToolState.COMPLETED) {
                            count++;
                        }
                    }
                }
            }
        }
        return count;
    }

    /**
     * 格式化参数简述
     */
    private String formatParamsBrief(Map<String, Object> params) {
        return ParamsFormatter.formatBrief(params);
    }
}
