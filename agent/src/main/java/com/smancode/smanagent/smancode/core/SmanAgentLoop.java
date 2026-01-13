package com.smancode.smanagent.smancode.core;

import com.smancode.smanagent.model.message.Message;
import com.smancode.smanagent.model.part.*;
import com.smancode.smanagent.model.session.Session;
import com.smancode.smanagent.smancode.llm.LlmService;
import com.smancode.smanagent.smancode.prompt.PromptDispatcher;
import com.smancode.smanagent.tools.ToolExecutor;
import com.smancode.smanagent.tools.ToolRegistry;
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
 * SmanAgent 核心循环（参考 OpenCode）
 * <p>
 * 极简设计 + 上下文隔离：
 * - 一个主循环处理所有消息
 * - 用户可以随时打断（通过 system-reminder）
 * - 完全由 LLM 决定行为（无硬编码意图识别）
 * - 每个工具调用在独立子会话中执行（防止 Token 爆炸）
 * <p>
 * 流程：
 * 1. 接收用户消息
 * 2. 检查是否需要上下文压缩
 * 3. 调用 LLM 流式处理
 * 4. 在子会话中执行工具调用（上下文隔离）
 * 5. 只保留摘要，清理完整输出
 * 6. 推送 Part 到前端
 */
@Service
public class SmanAgentLoop {

    private static final Logger logger = LoggerFactory.getLogger(SmanAgentLoop.class);

    @Autowired
    private LlmService llmService;

    @Autowired
    private PromptDispatcher promptDispatcher;

    @Autowired
    private ToolRegistry toolRegistry;

    @Autowired
    private SubTaskExecutor subTaskExecutor;

    @Autowired
    private StreamingNotificationHandler notificationHandler;

    @Autowired
    private ContextCompactor contextCompactor;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 处理用户消息（核心入口）
     *
     * @param session    会话
     * @param userInput  用户输入
     * @param partPusher Part 推送器（实时推送前端）
     * @return 助手消息
     */
    public Message process(Session session, String userInput, Consumer<Part> partPusher) {
        logger.info("开始处理: sessionId={}, userInput={}", session.getId(), userInput);

        try {
            // 注意：用户消息已经在 AgentWebSocketHandler 中创建并发送了，这里不需要重复创建

            // 1. 检查会话状态
            if (session.isBusy()) {
                return createBusyMessage(session.getId(), partPusher);
            }

            // 2. 检查是否需要上下文压缩
            if (contextCompactor.needsCompaction(session)) {
                logger.info("触发上下文压缩: sessionId={}", session.getId());
                contextCompactor.prune(session);

                if (contextCompactor.needsCompaction(session)) {
                    // Pruning 后仍然超限，执行 Compaction
                    String summary = contextCompactor.compact(session);

                    // 插入压缩消息
                    Message compactionMessage = createCompactionMessage(session.getId(), summary);
                    session.addMessage(compactionMessage);
                    partPusher.accept(compactionMessage.getParts().get(0));
                }
            }

            // 3. 标记会话为忙碌
            session.markBusy();

            // 4. 主循环：调用 LLM 处理
            Message assistantMessage = processWithLLM(session, partPusher);

            // 5. 添加助手消息到会话
            session.addMessage(assistantMessage);

            // 6. 标记会话为空闲
            session.markIdle();

            return assistantMessage;

        } catch (Exception e) {
            logger.error("处理失败", e);
            session.markIdle();
            return createErrorMessage(session.getId(), e.getMessage(), partPusher);
        }
    }

    /**
     * 使用 LLM 处理（ReAct 循环核心）
     * <p>
     * 参考 OpenCode 实现，支持真正的推理-行动循环：
     * 1. LLM 思考并决定是否调用工具
     * 2. 如果调用工具，在子会话中执行
     * 3. 工具结果添加到对话历史
     * 4. 回到步骤 1，LLM 基于工具结果决定下一步
     * 5. 直到 LLM 不再调用工具，返回最终结果
     */
    private Message processWithLLM(Session session, Consumer<Part> partPusher) {
        Message assistantMessage = createAssistantMessage(session.getId());

        try {
            // 1. 立即推送确认消息
            notificationHandler.pushImmediateAcknowledgment(session, partPusher);

            // ========== ReAct 循环开始 ==========
            int maxSteps = 10;  // 最大步数限制（防止无限循环）
            int step = 0;

            while (step < maxSteps) {
                step++;
                logger.info("ReAct 循环: step={}/{}", step, maxSteps);

                // 2. 构建提示词（包含之前的工具结果）
                String systemPrompt = buildSystemPrompt(session);
                String userPrompt = buildUserPrompt(session);

                // 3. 调用 LLM
                String responseText = llmService.simpleRequest(systemPrompt, userPrompt);

                // 4. 从纯文本响应中提取 JSON
                String jsonString = extractJsonFromResponse(responseText);
                if (jsonString == null) {
                    // 无法提取 JSON，当作纯文本处理（循环结束）
                    logger.warn("无法提取 JSON，当作纯文本处理，结束循环。responseText长度={}",
                            responseText != null ? responseText.length() : 0);
                    if (responseText != null && responseText.length() < 500) {
                        logger.warn("responseText内容: {}", responseText);
                    }
                    String partId = UUID.randomUUID().toString();
                    TextPart textPart = new TextPart(partId, assistantMessage.getId(), session.getId());
                    textPart.setText(responseText);
                    textPart.touch();
                    assistantMessage.addPart(textPart);
                    partPusher.accept(textPart);
                    break;  // 退出循环
                }

                // 5. 解析 JSON
                JsonNode json = objectMapper.readTree(jsonString);
                logger.info("解析后的 JSON: has parts={}, has text={}",
                        json.has("parts"), json.has("text"));

                // 6. 处理响应中的各个 Part
                JsonNode parts = json.path("parts");
                if (!parts.isArray() || parts.isEmpty()) {
                    // 没有 parts，检查是否有纯文本响应
                    String text = json.path("text").asText(null);
                    if (text != null && !text.isEmpty()) {
                        String partId = UUID.randomUUID().toString();
                        TextPart textPart = new TextPart(partId, assistantMessage.getId(), session.getId());
                        textPart.setText(text);
                        textPart.touch();
                        assistantMessage.addPart(textPart);
                        partPusher.accept(textPart);
                    }
                    break;  // 退出循环
                }

                // 7. 解析 Part
                List<Part> currentParts = new ArrayList<>();
                for (JsonNode partJson : parts) {
                    Part part = parsePart(partJson, assistantMessage.getId(), session.getId());
                    if (part != null) {
                        currentParts.add(part);
                    }
                }

                // 8. 检查是否有工具调用
                boolean hasTools = currentParts.stream().anyMatch(p -> p instanceof ToolPart);

                if (!hasTools) {
                    // 没有工具调用，添加非工具 Part 并退出循环
                    logger.info("没有工具调用，添加 Part 并退出循环");
                    for (Part part : currentParts) {
                        assistantMessage.addPart(part);
                        partPusher.accept(part);
                    }
                    break;  // 退出循环
                }

                // 9. 执行工具（在子会话中）
                logger.info("检测到工具调用，开始执行工具，数量: {}",
                        currentParts.stream().filter(p -> p instanceof ToolPart).count());

                for (Part part : currentParts) {
                    if (part instanceof ToolPart toolPart) {
                        // Doom Loop 检测
                        if (detectDoomLoop(session, toolPart)) {
                            logger.warn("检测到 Doom Loop，跳过工具调用: toolName={}",
                                    toolPart.getToolName());
                            TextPart warningPart = new TextPart();
                            warningPart.setMessageId(assistantMessage.getId());
                            warningPart.setSessionId(session.getId());
                            warningPart.setText("⚠️ 检测到重复的工具调用，停止循环以避免无限循环。");
                            warningPart.touch();
                            assistantMessage.addPart(warningPart);
                            partPusher.accept(warningPart);
                            break;  // 退出工具执行循环
                        }

                        // 关键：执行工具并获取摘要
                        SubTaskResult result = subTaskExecutor.executeToolIsolated(
                                toolPart, session, partPusher
                        );

                        // 关键：将工具 Part 添加到助手消息
                        // 下一次循环时，buildUserPrompt 会包含这个工具的结果
                        assistantMessage.addPart(toolPart);

                        logger.info("工具执行完成: toolName={}, success={}, summaryLength={}",
                                toolPart.getToolName(), result.isSuccess(),
                                result.getSummary() != null ? result.getSummary().length() : 0);

                    } else {
                        // 非 ToolPart 直接添加
                        assistantMessage.addPart(part);
                        partPusher.accept(part);
                    }
                }

                // 关键：将当前助手消息添加到会话
                // 这样下一次循环时，buildUserPrompt 就能看到工具结果了
                if (!assistantMessage.getParts().isEmpty()) {
                    session.addMessage(assistantMessage);
                    logger.info("助手消息已添加到会话，包含 {} 个 Part", assistantMessage.getParts().size());
                }

                // 创建新的助手消息供下一轮使用
                assistantMessage = createAssistantMessage(session.getId());

                // 继续循环
            }
            // ========== ReAct 循环结束 ==========

            // 10. 如果有工具执行，推送最终总结
            if (hasExecutedTools(assistantMessage)) {
                notificationHandler.pushFinalSummary(assistantMessage, session, partPusher);
            }

        } catch (Exception e) {
            logger.error("LLM 处理失败", e);
            String partId = UUID.randomUUID().toString();
            TextPart errorPart = new TextPart(partId, assistantMessage.getId(), session.getId());
            errorPart.setText("处理失败: " + e.getMessage());
            errorPart.touch();
            assistantMessage.addPart(errorPart);
            partPusher.accept(errorPart);
        }

        return assistantMessage;
    }

    /**
     * 从响应中提取 JSON（参考 bank-core-analysis-agent）
     */
    private String extractJsonFromResponse(String response) {
        if (response == null || response.trim().isEmpty()) {
            return null;
        }

        String trimmedResponse = response.trim();

        // 策略1: 提取```json代码块
        String jsonStart = "```json";
        String jsonEnd = "```";

        int startIndex = trimmedResponse.indexOf(jsonStart);
        if (startIndex != -1) {
            startIndex += jsonStart.length();
            int endIndex = trimmedResponse.indexOf(jsonEnd, startIndex);
            if (endIndex != -1) {
                return trimmedResponse.substring(startIndex, endIndex).trim();
            }
        }

        // 策略2: 检查是否为纯JSON格式
        if (trimmedResponse.startsWith("{") && trimmedResponse.endsWith("}")) {
            return trimmedResponse;
        }

        // 策略3: 查找文本中的JSON片段（智能匹配大括号）
        int braceStart = trimmedResponse.indexOf('{');
        if (braceStart >= 0) {
            int depth = 0;
            boolean inString = false;
            boolean escape = false;

            for (int i = braceStart; i < trimmedResponse.length(); i++) {
                char c = trimmedResponse.charAt(i);

                if (escape) {
                    escape = false;
                    continue;
                }

                if (c == '\\' && inString) {
                    escape = true;
                    continue;
                }

                if (c == '"' && !escape) {
                    inString = !inString;
                    continue;
                }

                if (!inString) {
                    if (c == '{') depth++;
                    else if (c == '}') {
                        depth--;
                        if (depth == 0) {
                            return trimmedResponse.substring(braceStart, i + 1);
                        }
                    }
                }
            }
        }

        return null;
    }

    /**
     * 执行 ToolPart（带上下文隔离）
     * <p>
     * 关键改进：使用 SubTaskExecutor 在独立子会话中执行工具
     */
    private void executeToolPartIsolated(ToolPart toolPart, Session session, Consumer<Part> partPusher) {
        String toolName = toolPart.getToolName();
        logger.info("执行隔离工具调用: toolName={}", toolName);

        // 使用 SubTaskExecutor 在独立子会话中执行
        SubTaskResult result = subTaskExecutor.executeToolIsolated(toolPart, session, partPusher);

        // 记录结果摘要
        logger.info("工具执行完成: toolName={}, success={}, summaryLength={}",
                toolName, result.isSuccess(),
                result.getSummary() != null ? result.getSummary().length() : 0);
    }

    /**
     * 创建压缩消息
     */
    private Message createCompactionMessage(String sessionId, String summary) {
        Message message = new Message();
        message.setId(UUID.randomUUID().toString());
        message.setSessionId(sessionId);
        message.setRole(com.smancode.smanagent.model.message.Role.ASSISTANT);
        message.setCreatedTime(Instant.EPOCH);  // 特殊标记

        String partId = UUID.randomUUID().toString();
        TextPart textPart = new TextPart(partId, message.getId(), sessionId);
        textPart.setText("🗑️ 上下文已压缩\n\n为避免 Token 超限，之前的对话历史已压缩为以下摘要：\n\n" + summary + "\n");
        textPart.touch();

        message.addPart(textPart);
        return message;
    }

    /**
     * 创建助手消息
     */
    private Message createAssistantMessage(String sessionId) {
        Message message = new Message();
        message.setId(UUID.randomUUID().toString());
        message.setSessionId(sessionId);
        message.setRole(com.smancode.smanagent.model.message.Role.ASSISTANT);
        message.setCreatedTime(Instant.now());
        return message;
    }

    /**
     * 检查是否有执行工具
     */
    private boolean hasExecutedTools(Message message) {
        return message.getParts().stream()
                .anyMatch(part -> part instanceof ToolPart);
    }

    /**
     * 构建系统提示词
     */
    private String buildSystemPrompt(Session session) {
        StringBuilder prompt = new StringBuilder();
        prompt.append(promptDispatcher.buildSystemPrompt());
        prompt.append("\n\n").append(promptDispatcher.getToolSummary());
        return prompt.toString();
    }

    /**
     * 构建用户提示词（包含压缩后的上下文和工具结果）
     * <p>
     * 关键修改：将 ToolPart 的执行结果添加到对话历史
     * 这样 LLM 可以看到之前工具调用的结果，并基于此决定下一步行动
     */
    private String buildUserPrompt(Session session) {
        StringBuilder prompt = new StringBuilder();
        Message lastAssistant = session.getLatestAssistantMessage();

        // 检查是否有新的用户消息（支持打断）
        if (lastAssistant != null && session.hasNewUserMessageAfter(lastAssistant.getId())) {
            prompt.append("\n\n");
            prompt.append("<system-reminder>\n");
            prompt.append("用户发送了以下消息：\n\n");

            List<Message> messages = session.getMessages();
            boolean foundAssistant = false;
            for (Message msg : messages) {
                if (msg.getId().equals(lastAssistant.getId())) {
                    foundAssistant = true;
                } else if (foundAssistant && msg.isUserMessage()) {
                    for (Part part : msg.getParts()) {
                        if (part instanceof TextPart) {
                            prompt.append(((TextPart) part).getText()).append("\n");
                        }
                    }
                }
            }

            prompt.append("\n请立即响应该消息，并调整你的计划。\n");
            prompt.append("</system-reminder>\n");
        }

        // 添加历史上下文（最近 3 轮对话，自动停止于压缩点）
        List<Message> messages = getFilteredMessages(session);
        int contextSize = Math.min(6, messages.size());

        if (!messages.isEmpty()) {
            prompt.append("\n\n## 对话历史\n\n");
        }

        for (int i = Math.max(0, messages.size() - contextSize); i < messages.size(); i++) {
            Message msg = messages.get(i);
            if (msg.isUserMessage()) {
                prompt.append("### 用户\n");
                for (Part part : msg.getParts()) {
                    if (part instanceof TextPart) {
                        prompt.append(((TextPart) part).getText()).append("\n");
                    }
                }
            } else {
                prompt.append("### 助手\n");
                for (Part part : msg.getParts()) {
                    if (part instanceof TextPart) {
                        prompt.append(((TextPart) part).getText()).append("\n");
                    } else if (part instanceof ReasoningPart) {
                        prompt.append("思考: ").append(((ReasoningPart) part).getText()).append("\n");
                    } else if (part instanceof ToolPart toolPart) {
                        // 关键：添加工具调用和结果摘要
                        prompt.append("调用工具: ").append(toolPart.getToolName()).append("\n");

                        // 添加参数
                        if (toolPart.getParameters() != null && !toolPart.getParameters().isEmpty()) {
                            prompt.append("参数: ").append(formatParamsBrief(toolPart.getParameters())).append("\n");
                        }

                        // 关键：添加工具结果摘要
                        if (toolPart.getResult() != null) {
                            com.smancode.smanagent.tools.ToolResult result = toolPart.getResult();
                            if (result.isSuccess()) {
                                String summary = result.getDisplayContent();
                                if (summary != null && !summary.isEmpty()) {
                                    prompt.append("结果摘要: ").append(summary).append("\n");
                                } else {
                                    prompt.append("结果: (执行成功，无返回内容)\n");
                                }
                            } else {
                                String error = result.getError();
                                prompt.append("执行失败: ").append(error != null ? error : "未知错误").append("\n");
                            }
                        }
                    }
                }
            }
            prompt.append("\n");
        }

        return prompt.toString();
    }

    /**
     * 获取过滤后的消息列表（停止于压缩点）
     */
    private List<Message> getFilteredMessages(Session session) {
        List<Message> result = new ArrayList<>();
        List<Message> messages = session.getMessages();

        // 从最新到最旧遍历
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message msg = messages.get(i);
            result.add(msg);

            // 遇到压缩点则停止
            if (msg.isAssistantMessage() && msg.getCreatedTime().isBefore(Instant.EPOCH.plusSeconds(1))) {
                break;
            }
        }

        // 反转回最新在前
        Collections.reverse(result);
        return result;
    }

    /**
     * 解析 Part（从 LLM JSON 响应）
     */
    private Part parsePart(JsonNode partJson, String messageId, String sessionId) {
        String type = partJson.path("type").asText();

        return switch (type) {
            case "text" -> createTextPart(partJson, messageId, sessionId);
            case "reasoning" -> createReasoningPart(partJson, messageId, sessionId);
            case "tool" -> createToolPart(partJson, messageId, sessionId);
            case "subtask" -> createSubtaskPart(partJson, messageId, sessionId);
            default -> {
                logger.warn("未知的 Part 类型: {}", type);
                yield null;
            }
        };
    }

    private TextPart createTextPart(JsonNode partJson, String messageId, String sessionId) {
        String partId = UUID.randomUUID().toString();
        TextPart part = new TextPart(partId, messageId, sessionId);
        part.setText(partJson.path("text").asText());
        part.touch();
        return part;
    }

    private ReasoningPart createReasoningPart(JsonNode partJson, String messageId, String sessionId) {
        String partId = UUID.randomUUID().toString();
        ReasoningPart part = new ReasoningPart(partId, messageId, sessionId);
        part.setText(partJson.path("text").asText());
        part.touch();
        return part;
    }

    private ToolPart createToolPart(JsonNode partJson, String messageId, String sessionId) {
        String partId = UUID.randomUUID().toString();
        ToolPart part = new ToolPart(partId, messageId, sessionId, partJson.path("toolName").asText());

        Map<String, Object> params = new HashMap<>();
        JsonNode paramsJson = partJson.path("parameters");
        if (paramsJson.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = paramsJson.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                params.put(entry.getKey(), entry.getValue().asText());
            }
        }
        part.setParameters(params);
        part.touch();
        return part;
    }

    private TextPart createSubtaskPart(JsonNode partJson, String messageId, String sessionId) {
        String partId = UUID.randomUUID().toString();
        TextPart part = new TextPart(partId, messageId, sessionId);
        part.setText(partJson.path("text").asText("子任务列表"));
        part.touch();
        return part;
    }

    /**
     * 创建忙碌消息
     */
    private Message createBusyMessage(String sessionId, Consumer<Part> partPusher) {
        Message message = new Message();
        message.setId(UUID.randomUUID().toString());
        message.setSessionId(sessionId);
        message.setRole(com.smancode.smanagent.model.message.Role.ASSISTANT);
        message.setCreatedTime(Instant.now());

        String partId = UUID.randomUUID().toString();
        TextPart textPart = new TextPart(partId, message.getId(), sessionId);
        textPart.setText("正在处理上一个请求，请稍候...");
        textPart.touch();

        message.addPart(textPart);
        partPusher.accept(textPart);
        return message;
    }

    /**
     * 创建错误消息
     */
    private Message createErrorMessage(String sessionId, String error, Consumer<Part> partPusher) {
        Message message = new Message();
        message.setId(UUID.randomUUID().toString());
        message.setSessionId(sessionId);
        message.setRole(com.smancode.smanagent.model.message.Role.ASSISTANT);
        message.setCreatedTime(Instant.now());

        String partId = UUID.randomUUID().toString();
        TextPart textPart = new TextPart(partId, message.getId(), sessionId);
        textPart.setText("处理失败: " + error);
        textPart.touch();

        message.addPart(textPart);
        partPusher.accept(textPart);
        return message;
    }

    /**
     * 格式化参数简述
     */
    private String formatParamsBrief(Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            sb.append(entry.getKey()).append("=").append(entry.getValue()).append(" ");
        }
        return sb.toString().trim();
    }

    /**
     * 检测 Doom Loop（无限循环）
     * <p>
     * 参考 OpenCode 实现，检测最近 3 次是否有相同的工具调用
     *
     * @param session     会话
     * @param currentTool 当前工具
     * @return 是否检测到无限循环
     */
    private boolean detectDoomLoop(Session session, ToolPart currentTool) {
        List<Message> messages = session.getMessages();
        if (messages.size() < 2) {
            return false;
        }

        // 检查最近 3 次工具调用
        final int DOOM_LOOP_THRESHOLD = 3;
        int count = 0;

        // 从最新到最旧检查
        for (int i = messages.size() - 1; i >= Math.max(0, messages.size() - DOOM_LOOP_THRESHOLD); i--) {
            Message msg = messages.get(i);
            if (!msg.isAssistantMessage()) {
                continue;
            }

            for (Part part : msg.getParts()) {
                if (part instanceof ToolPart toolPart) {
                    if (toolPart.getToolName().equals(currentTool.getToolName()) &&
                        toolPart.getState() == ToolPart.ToolState.COMPLETED &&
                        objectsEqual(toolPart.getParameters(), currentTool.getParameters())) {
                        count++;
                    }
                }
            }
        }

        if (count >= DOOM_LOOP_THRESHOLD) {
            logger.warn("检测到 Doom Loop: toolName={}, 参数重复 {} 次",
                    currentTool.getToolName(), count);
            return true;
        }

        return false;
    }

    /**
     * 比较两个对象是否相等（支持 Map 比较）
     */
    private boolean objectsEqual(Object obj1, Object obj2) {
        if (obj1 == obj2) {
            return true;
        }
        if (obj1 == null || obj2 == null) {
            return false;
        }
        if (obj1 instanceof Map && obj2 instanceof Map) {
            Map<?, ?> map1 = (Map<?, ?>) obj1;
            Map<?, ?> map2 = (Map<?, ?>) obj2;
            return map1.equals(map2);
        }
        return obj1.equals(obj2);
    }
}
