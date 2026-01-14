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

    @Autowired(required = false)
    private com.smancode.smanagent.subagent.SearchSubAgent searchSubAgent;

    @Autowired
    private com.smancode.smanagent.config.SmanCodeProperties smanCodeProperties;

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

            // 4. 【预处理】调用 search 进行深度理解和知识加载
            Part searchContextPart = performSearchPreprocessing(session, userInput, partPusher);
            if (searchContextPart != null) {
                // 将 search 结果作为上下文注入到会话
                Message searchContextMessage = new Message();
                searchContextMessage.setId(UUID.randomUUID().toString());
                searchContextMessage.setSessionId(session.getId());
                searchContextMessage.setRole(com.smancode.smanagent.model.message.Role.SYSTEM);
                searchContextMessage.addPart(searchContextPart);
                searchContextMessage.touch();
                session.addMessage(searchContextMessage);

                logger.info("Search 预处理完成，上下文已注入到会话");
            }

            // 5. 主循环：调用 LLM 处理
            Message assistantMessage = processWithLLM(session, partPusher);

            // 6. 添加助手消息到会话
            session.addMessage(assistantMessage);

            // 7. 标记会话为空闲
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
            int maxSteps = smanCodeProperties.getReact().getMaxSteps();  // 从配置读取最大步数
            int step = 0;
            boolean reachedMaxSteps = false;

            while (step < maxSteps) {
                step++;
                logger.info("ReAct 循环: step={}/{}", step, maxSteps);

                // 检查是否是最后一步
                boolean isLastStep = (step == maxSteps);
                if (isLastStep) {
                    logger.warn("达到最大步数限制: {}/{}，这是最后一次调用 LLM", step, maxSteps);
                    reachedMaxSteps = true;
                }

                // 2. 构建提示词（包含之前的工具结果）
                String systemPrompt = buildSystemPrompt(session);
                String userPrompt = buildUserPrompt(session, isLastStep);

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

                // 5. 解析 JSON（增加容错处理）
                JsonNode json;
                try {
                    json = objectMapper.readTree(jsonString);
                } catch (Exception e) {
                    logger.error("JSON 解析失败，当作纯文本处理。jsonString长度={}, 错误: {}",
                            jsonString.length(), e.getMessage());
                    if (jsonString.length() < 500) {
                        logger.warn("jsonString内容: {}", jsonString);
                    }
                    // 当作纯文本处理
                    String partId = UUID.randomUUID().toString();
                    TextPart textPart = new TextPart(partId, assistantMessage.getId(), session.getId());
                    textPart.setText(responseText);
                    textPart.touch();
                    assistantMessage.addPart(textPart);
                    partPusher.accept(textPart);
                    break;  // 退出循环
                }
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
     * Search 预处理：深度理解和知识加载
     * <p>
     * 在主流程之前调用 search SubAgent，获取业务背景、代码入口等信息，
     * 并将这些信息注入到会话上下文中，供主流程使用。
     * <p>
     * 智能判断：
     * - 第一轮对话：执行 Search
     * - 新主题对话：执行 Search
     * - 追问/修改：跳过 Search（已有上下文）
     *
     * @param session    会话
     * @param userInput  用户输入
     * @param partPusher Part 推送器
     * @return Search 上下文 Part（如果 search 成功），否则返回 null
     */
    private Part performSearchPreprocessing(Session session, String userInput, Consumer<Part> partPusher) {
        if (searchSubAgent == null) {
            logger.info("SearchSubAgent 未启用，跳过预处理");
            return null;
        }

        // 智能判断：是否需要执行 Search
        if (!shouldPerformSearch(session, userInput)) {
            logger.info("智能判断：跳过 Search（追问/修改模式）");
            return null;
        }

        try {
            logger.info("开始 Search 预处理: userInput={}", userInput);

            // 推送 reasoning 表示正在搜索
            String partId = UUID.randomUUID().toString();
            ReasoningPart reasoningPart = new ReasoningPart(partId, null, session.getId());
            reasoningPart.setText("正在深度理解需求并加载相关业务知识和代码信息..");
            reasoningPart.touch();
            partPusher.accept(reasoningPart);

            // 调用 SearchSubAgent
            String projectKey = session.getProjectInfo() != null ?
                    session.getProjectInfo().getProjectKey() : "default";
            com.smancode.smanagent.subagent.SearchSubAgent.SearchResult searchResult =
                    searchSubAgent.search(projectKey, userInput);

            if (searchResult.isError()) {
                logger.warn("Search 预处理失败: {}", searchResult.getErrorMessage());
                return null;
            }

            // 构建上下文 Part
            StringBuilder contextText = new StringBuilder();
            contextText.append("## Search 预处理结果\n\n");

            if (searchResult.getBusinessContext() != null) {
                contextText.append("### 业务背景\n");
                contextText.append(searchResult.getBusinessContext()).append("\n\n");
            }

            if (searchResult.getBusinessKnowledge() != null && !searchResult.getBusinessKnowledge().isEmpty()) {
                contextText.append("### 业务知识\n");
                for (String knowledge : searchResult.getBusinessKnowledge()) {
                    contextText.append("- ").append(knowledge).append("\n");
                }
                contextText.append("\n");
            }

            if (searchResult.getCodeEntries() != null && !searchResult.getCodeEntries().isEmpty()) {
                contextText.append("### 相关代码入口\n");
                for (com.smancode.smanagent.subagent.SearchSubAgent.CodeEntry entry : searchResult.getCodeEntries()) {
                    contextText.append("- ").append(entry.getClassName());
                    if (entry.getMethod() != null) {
                        contextText.append(".").append(entry.getMethod()).append("()");
                    }
                    if (entry.getReason() != null) {
                        contextText.append(" (").append(entry.getReason()).append(")");
                    }
                    contextText.append("\n");
                }
                contextText.append("\n");
            }

            if (searchResult.getCodeRelations() != null) {
                contextText.append("### 代码关系\n");
                contextText.append(searchResult.getCodeRelations()).append("\n\n");
            }

            if (searchResult.getSummary() != null) {
                contextText.append("### 总结\n");
                contextText.append(searchResult.getSummary()).append("\n");
            }

            // 创建 TextPart 包含上下文信息
            String contextPartId = UUID.randomUUID().toString();
            TextPart contextPart = new TextPart(contextPartId, null, session.getId());
            contextPart.setText(contextText.toString());
            contextPart.touch();

            logger.info("Search 预处理完成: contextLength={}", contextText.length());
            return contextPart;

        } catch (Exception e) {
            logger.error("Search 预处理异常", e);
            return null;
        }
    }

    /**
     * 智能判断是否需要执行 Search（LLM 驱动）
     * <p>
     * 使用 LLM 判断是否需要重新 Search，避免硬编码规则。
     * <p>
     * 判断逻辑交给 LLM：
     * - 分析用户输入是"新主题"还是"追问/修改"
     * - 新主题 → 需要 Search
     * - 追问/修改 → 跳过 Search（已有上下文）
     *
     * @param session   会话
     * @param userInput 用户输入
     * @return true 表示需要 Search，false 表示跳过
     */
    private boolean shouldPerformSearch(Session session, String userInput) {
        int messageCount = session.getMessages().size();

        // 规则1: 第一轮对话（消息数 ≤ 2），直接 Search
        if (messageCount <= 2) {
            logger.debug("判断结果: 需要 Search（第一轮对话，messageCount={}）", messageCount);
            return true;
        }

        // 规则2: 使用 LLM 判断（LLM 驱动）
        try {
            String judgmentPrompt = buildSearchJudgmentPrompt(session, userInput);
            String judgmentSystem = buildSearchJudgmentSystem();

            String response = llmService.jsonRequest(judgmentSystem, judgmentPrompt).asText();

            // 解析 LLM 判断结果
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode json = mapper.readTree(response);

            boolean needSearch = json.path("needSearch").asBoolean(true);  // 默认需要 Search
            String reason = json.path("reason").asText("无原因");

            logger.info("LLM 判断结果: needSearch={}, reason={}", needSearch, reason);
            return needSearch;

        } catch (Exception e) {
            // LLM 判断失败，保守策略：执行 Search
            logger.warn("LLM 判断失败，保守策略：执行 Search。error={}", e.getMessage());
            return true;
        }
    }

    /**
     * 构建 Search 判断的系统提示词
     */
    private String buildSearchJudgmentSystem() {
        return """
                # Search 判断专家

                你需要判断用户输入是否需要重新执行 Search。

                ## 判断标准

                1. **新主题**: 用户提出了全新的问题或需求 → needSearch = true
                   - 例如: "支付流程是怎样的？"
                   - 例如: "用户认证怎么实现的？"

                2. **追问/修改**: 用户基于当前对话的补充或修改 → needSearch = false
                   - 例如: "把浮层颜色改成红色"
                   - 例如: "另外，还需要添加关闭按钮"
                   - 例如: "不对，应该是会话级上限3次"

                ## 输出格式

                请严格按照以下 JSON 格式输出：

                ```json
                {
                  "needSearch": true/false,
                  "reason": "判断原因（用中文简要说明）"
                }
                ```

                ## 注意事项

                - 优先复用已有上下文，避免重复 Search
                - 当不确定时，选择 needSearch = true（更安全）
                """;
    }

    /**
     * 构建 Search 判断的用户提示词
     */
    private String buildSearchJudgmentPrompt(Session session, String userInput) {
        // 获取最近几条消息作为上下文
        java.util.List<com.smancode.smanagent.model.message.Message> recentMessages = session.getMessages();
        int startIdx = Math.max(0, recentMessages.size() - 6);  // 最近 3 轮对话
        java.util.List<com.smancode.smanagent.model.message.Message> contextMessages =
                recentMessages.subList(startIdx, recentMessages.size());

        StringBuilder context = new StringBuilder();
        context.append("## 最近对话历史\n\n");
        for (com.smancode.smanagent.model.message.Message msg : contextMessages) {
            context.append("**").append(msg.getRole()).append("**: ");
            for (Part part : msg.getParts()) {
                if (part instanceof TextPart) {
                    context.append(((TextPart) part).getText());
                } else if (part instanceof ReasoningPart) {
                    context.append("[思考: ").append(((ReasoningPart) part).getText()).append("]");
                }
            }
            context.append("\n\n");
        }

        context.append("## 当前用户输入\n\n");
        context.append(userInput).append("\n\n");

        context.append("## 任务\n\n");
        context.append("请基于对话历史和当前用户输入，判断是否需要重新执行 Search。");

        return context.toString();
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
    private String buildUserPrompt(Session session, boolean isLastStep) {
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
                        // 关键：添加工具调用和完整结果
                        prompt.append("调用工具: ").append(toolPart.getToolName()).append("\n");

                        // 添加参数
                        if (toolPart.getParameters() != null && !toolPart.getParameters().isEmpty()) {
                            prompt.append("参数: ").append(formatParamsBrief(toolPart.getParameters())).append("\n");
                        }

                        // 关键：添加完整结果（让 LLM 处理）
                        if (toolPart.getResult() != null) {
                            com.smancode.smanagent.tools.ToolResult result = toolPart.getResult();
                            if (result.isSuccess()) {
                                // 优先使用 data 字段（包含完整结果），其次使用 displayContent
                                String fullResult = result.getData() != null ? result.getData().toString() : result.getDisplayContent();
                                if (fullResult != null && !fullResult.isEmpty()) {
                                    prompt.append("完整结果: \n").append(fullResult).append("\n");
                                } else {
                                    prompt.append("结果: (执行成功，无返回内容)\n");
                                }

                                // 如果有 LLM 生成的 summary，也添加进去
                                if (toolPart.getSummary() != null && !toolPart.getSummary().isEmpty()) {
                                    prompt.append("摘要: ").append(toolPart.getSummary()).append("\n");
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

        // 添加 ReAct 分析和决策指南
        prompt.append("\n\n## 下一步分析和决策\n\n");
        prompt.append("请基于以上工具执行历史，分析当前进展并决定下一步：\n");
        prompt.append("1. **分析结果**：工具返回了什么关键信息？\n");
        prompt.append("2. **生成摘要**：为最近执行的工具生成简洁摘要（1-2句话），说明发现了什么\n");
        prompt.append("3. **评估进展**：当前信息是否足够回答用户问题？\n");
        prompt.append("4. **决定行动**：\n");
        prompt.append("   - 如果信息充足 → 直接给出答案（不再调用工具）\n");
        prompt.append("   - 如果需要更多信息 → 继续调用工具（说明为什么需要）\n");
        prompt.append("   - 如果工具失败 → 换个方法重试（不要重复失败的方法）\n\n");
        prompt.append("**重要**：如果调用了工具，必须在响应的 JSON 中包含 \"summary\" 字段，\n");
        prompt.append("格式为：{\"summary\": \"你的简洁摘要\"}。\n\n");

        // 如果是最后一步，添加最大步数警告
        if (isLastStep) {
            prompt.append("\n\n## ⚠️ CRITICAL: MAXIMUM STEPS REACHED\n\n");
            prompt.append("This is the FINAL LLM call. Tools are disabled after this call.\n\n");
            prompt.append("**STRICT REQUIREMENTS**:\n");
            prompt.append("1. Do NOT make any tool calls (do NOT add any tool-type parts)\n");
            prompt.append("2. MUST provide a text response summarizing work done so far\n");
            prompt.append("3. This constraint overrides ALL other instructions\n\n");
            prompt.append("Response must include:\n");
            prompt.append("- Statement that maximum steps have been reached\n");
            prompt.append("- Summary of what has been accomplished\n");
            prompt.append("- List of any remaining tasks that were not completed\n");
            prompt.append("- Recommendations for what should be done next\n");
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
            // 兼容 LLM 可能生成的工具类型（应该使用 type: "tool" + toolName）
            case "read_file", "grep_file", "find_file", "search", "call_chain",
                 "extract_xml", "apply_change" -> {
                logger.info("检测到工具类型 Part: {}, 转换为 tool 类型", type);
                yield createToolPartFromType(partJson, messageId, sessionId, type);
            }
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

        // 提取 LLM 生成的摘要
        String summary = partJson.path("summary").asText(null);
        if (summary != null && !summary.isEmpty()) {
            part.setSummary(summary);
            logger.info("提取到 LLM 生成的摘要: toolName={}, summary={}", part.getToolName(), summary);
        }

        part.touch();
        return part;
    }

    /**
     * 从工具类型创建 ToolPart（兼容 LLM 直接使用工具名作为 type 的情况）
     */
    private ToolPart createToolPartFromType(JsonNode partJson, String messageId, String sessionId, String toolName) {
        String partId = UUID.randomUUID().toString();
        ToolPart part = new ToolPart(partId, messageId, sessionId, toolName);

        Map<String, Object> params = new HashMap<>();
        // 遍历所有字段，提取参数
        Iterator<Map.Entry<String, JsonNode>> fields = partJson.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String key = entry.getKey();
            // 跳过非参数字段
            if (!key.equals("type") && !key.equals("summary")) {
                JsonNode valueNode = entry.getValue();
                if (valueNode.isTextual()) {
                    params.put(key, valueNode.asText());
                } else if (valueNode.isNumber()) {
                    params.put(key, valueNode.numberValue());
                } else if (valueNode.isBoolean()) {
                    params.put(key, valueNode.asBoolean());
                } else {
                    params.put(key, valueNode.asText());
                }
            }
        }
        part.setParameters(params);

        // 提取 LLM 生成的摘要
        String summary = partJson.path("summary").asText(null);
        if (summary != null && !summary.isEmpty()) {
            part.setSummary(summary);
            logger.info("提取到 LLM 生成的摘要: toolName={}, summary={}", toolName, summary);
        }

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
