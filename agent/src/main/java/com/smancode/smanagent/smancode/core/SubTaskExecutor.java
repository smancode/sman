package com.smancode.smanagent.smancode.core;

import com.smancode.smanagent.model.message.Message;
import com.smancode.smanagent.model.part.Part;
import com.smancode.smanagent.model.part.TextPart;
import com.smancode.smanagent.model.part.ToolPart;
import com.smancode.smanagent.model.session.Session;
import com.smancode.smanagent.smancode.llm.LlmService;
import com.smancode.smanagent.tools.ToolExecutor;
import com.smancode.smanagent.tools.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

/**
 * 子任务执行器
 * <p>
 * 实现工具调用的上下文隔离：
 * 1. 每个工具调用在独立的子会话中执行
 * 2. 只保留摘要，清理完整输出
 * 3. 防止 Token 爆炸
 */
@Component
public class SubTaskExecutor {

    private static final Logger logger = LoggerFactory.getLogger(SubTaskExecutor.class);

    @Autowired
    private SessionManager sessionManager;

    @Autowired
    private ToolExecutor toolExecutor;

    @Autowired
    private ResultSummarizer resultSummarizer;

    @Autowired
    private LlmService llmService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 执行工具（带上下文隔离）
     *
     * @param toolPart      工具 Part
     * @param parentSession 父会话
     * @param partPusher    Part 推送器
     * @return 工具结果摘要
     */
    public SubTaskResult executeToolIsolated(ToolPart toolPart,
                                              Session parentSession,
                                              Consumer<Part> partPusher) {
        String toolName = toolPart.getToolName();

        logger.info("【工具调用开始】toolName={}, parentSessionId={}, 参数={}",
                toolName, parentSession.getId(), toolPart.getParameters());

        // 1. 创建子会话
        Session childSession = sessionManager.createChildSession(parentSession.getId());

        try {
            // 2. 推送工具调用通知
            notificationHandler.pushToolCallNotification(toolPart, partPusher);

            // 3. 更新状态为 RUNNING
            toolPart.setState(ToolPart.ToolState.RUNNING);
            toolPart.touch();
            partPusher.accept(toolPart);

            // 4. 推送执行进度
            notificationHandler.pushToolProgressNotification(toolPart, partPusher);

            // 5. 在子会话中执行工具
            String projectKey = childSession.getProjectInfo().getProjectKey();
            logger.info("【工具执行中】toolName={}, projectKey={}", toolName, projectKey);
            ToolResult fullResult = toolExecutor.execute(toolName, projectKey, toolPart.getParameters());
            logger.info("【工具执行完成】toolName={}, success={}, displayTitle={}, displayContent长度={}, error={}, 完整displayContent={}",
                    toolName, fullResult.isSuccess(), fullResult.getDisplayTitle(),
                    fullResult.getDisplayContent() != null ? fullResult.getDisplayContent().length() : 0,
                    fullResult.getError(), fullResult.getDisplayContent());

            // 6. 生成摘要（关键！）
            String summary = resultSummarizer.summarize(toolName, fullResult, parentSession);

            // 7. 创建压缩后的结果
            ToolResult compressedResult = ToolResult.success(
                    summary,                      // 只保留摘要
                    fullResult.getDisplayTitle(),
                    null                         // 清理完整内容
            );
            compressedResult.setSuccess(fullResult.isSuccess());
            if (!fullResult.isSuccess()) {
                compressedResult.setError(fullResult.getError());
            }

            // 8. 更新工具状态
            if (fullResult.isSuccess()) {
                toolPart.setState(ToolPart.ToolState.COMPLETED);
            } else {
                toolPart.setState(ToolPart.ToolState.ERROR);
            }
            toolPart.setResult(compressedResult);
            toolPart.touch();
            partPusher.accept(toolPart);

            // 9. 推送完成通知
            if (fullResult.isSuccess()) {
                notificationHandler.pushToolCompletedNotification(toolPart, compressedResult, partPusher);
            } else {
                notificationHandler.pushToolErrorNotification(toolPart, compressedResult, partPusher);
            }

            // 10. 推送摘要（而不是完整结果）
            Part summaryPart = createSummaryPart(toolPart, summary, fullResult);
            partPusher.accept(summaryPart);

            // 11. 推送阶段性结论
            pushIntermediateConclusion(toolPart, summary, parentSession, partPusher);

            // 12. 清理子会话
            sessionManager.cleanupChildSession(childSession.getId());

            return SubTaskResult.builder()
                    .toolName(toolName)
                    .success(fullResult.isSuccess())
                    .summary(summary)
                    .displayTitle(compressedResult.getDisplayTitle())
                    .build();

        } catch (Exception e) {
            logger.error("工具执行失败: toolName={}", toolName, e);

            toolPart.setState(ToolPart.ToolState.ERROR);
            toolPart.setResult(ToolResult.failure(e.getMessage()));
            toolPart.touch();
            partPusher.accept(toolPart);

            notificationHandler.pushToolErrorNotification(toolPart, toolPart.getResult(), partPusher);

            sessionManager.cleanupChildSession(childSession.getId());

            return SubTaskResult.builder()
                    .toolName(toolName)
                    .success(false)
                    .error(e.getMessage())
                    .build();
        }
    }

    /**
     * 创建摘要 Part
     */
    private Part createSummaryPart(ToolPart toolPart, String summary, ToolResult fullResult) {
        // 计算原始输出大小
        String originalOutput = ToolResultFormatter.formatToolResult(fullResult);
        int originalSize = originalOutput.length();
        int compressedSize = summary.length();
        double compressionRatio = originalSize > 0 ? (double) compressedSize / originalSize : 0;

        // 构建摘要文本
        StringBuilder sb = new StringBuilder();
        sb.append("📄 工具结果摘要\n");
        sb.append("   ").append(toolPart.getToolName()).append("\n");
        sb.append("   原始大小: ").append(formatSize(originalSize)).append("\n");
        sb.append("   压缩后: ").append(formatSize(compressedSize));
        if (compressionRatio > 0) {
            sb.append(" (压缩比: ").append(String.format("%.1f%%", compressionRatio * 100)).append(")");
        }
        sb.append("\n\n");
        sb.append(summary).append("\n");

        TextPart textPart = new TextPart();
        textPart.setMessageId(toolPart.getMessageId());
        textPart.setSessionId(toolPart.getSessionId());
        textPart.setText(sb.toString());
        textPart.touch();

        return textPart;
    }

    /**
     * 推送阶段性结论
     */
    private void pushIntermediateConclusion(ToolPart toolPart, String summary,
                                            Session session, Consumer<Part> partPusher) {
        try {
            // 构建阶段性结论提示词
            String conclusionPrompt = buildConclusionPrompt(toolPart, summary, session);

            // 调用 LLM 生成结论
            logger.info("【阶段性结论LLM请求】toolName={}, prompt长度={}, 完整prompt={}",
                    toolPart.getToolName(), conclusionPrompt.length(), conclusionPrompt);
            String conclusion = llmService.simpleRequest(conclusionPrompt);
            logger.info("【阶段性结论LLM响应】toolName={}, 响应长度={}, 完整响应={}",
                    toolPart.getToolName(), conclusion != null ? conclusion.length() : 0, conclusion);

            // 解析结论
            JsonNode json = objectMapper.readTree(conclusion);
            String conclusionText = json.path("conclusion").asText("");

            if (!conclusionText.isEmpty()) {
                com.smancode.smanagent.model.part.TextPart conclusionPart =
                        new com.smancode.smanagent.model.part.TextPart();
                conclusionPart.setMessageId(toolPart.getMessageId());
                conclusionPart.setSessionId(toolPart.getSessionId());

                // 获取已完成的工具数量
                int completedCount = countCompletedTools(session);
                conclusionPart.setText(String.format("📊 阶段性结论 %d:\n%s\n",
                        completedCount, conclusionText));

                conclusionPart.touch();
                partPusher.accept(conclusionPart);
            }

        } catch (Exception e) {
            logger.warn("生成阶段性结论失败: toolName={}", toolPart.getToolName(), e);
        }
    }

    /**
     * 构建结论提示词
     */
    private String buildConclusionPrompt(ToolPart toolPart, String summary, Session session) {
        StringBuilder prompt = new StringBuilder();

        prompt.append("你是代码分析助手。刚刚执行了一个工具并生成了摘要，");
        prompt.append("请生成简短的阶段性结论。\n\n");

        prompt.append("## 工具信息\n");
        prompt.append("- 工具名称: ").append(toolPart.getToolName()).append("\n");
        prompt.append("- 工具参数: ").append(ParamsFormatter.formatBrief(toolPart.getParameters())).append("\n");
        prompt.append("- 执行摘要: ").append(summary).append("\n\n");

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
     * 格式化文件大小
     */
    private String formatSize(int bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        } else {
            return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        }
    }

    // 注入依赖
    @Autowired
    private StreamingNotificationHandler notificationHandler;
}
