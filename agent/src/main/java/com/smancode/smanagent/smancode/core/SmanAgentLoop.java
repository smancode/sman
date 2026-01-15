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

            // 4. 【智能判断】先判断用户意图，再决定是否需要 Search
            StreamingNotificationHandler.AcknowledgmentResult ackResult =
                    notificationHandler.pushImmediateAcknowledgment(session, partPusher);

            logger.info("用户意图判断: needSearch={}, isChat={}",
                    ackResult.isNeedSearch(), ackResult.isChat());

            // 5. 主循环：调用 LLM 处理
            Message assistantMessage = processWithLLM(session, partPusher);

            // 7. 添加助手消息到会话
            session.addMessage(assistantMessage);

            // 8. 标记会话为空闲
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

                // 7.5 处理 LLM 生成的摘要：将新工具的 summary 保存到上一个无摘要的工具
                logger.info("【摘要处理】开始检查 currentParts 中的 summary，总 Part 数={}", currentParts.size());

                Part summaryCarrier = currentParts.stream()
                        .filter(p -> p instanceof ToolPart)
                        .filter(p -> {
                            String summary = ((ToolPart) p).getSummary();
                            boolean hasSummary = summary != null && !summary.isEmpty();
                            logger.info("【摘要处理】检查 ToolPart: toolName={}, hasSummary={}, summary={}",
                                    ((ToolPart) p).getToolName(), hasSummary,
                                    hasSummary ? summary.substring(0, Math.min(50, summary.length())) : "null");
                            return hasSummary;
                        })
                        .findFirst()
                        .orElse(null);

                logger.info("【摘要处理】summaryCarrier={}", summaryCarrier != null ? ((ToolPart) summaryCarrier).getToolName() : "null");

                if (summaryCarrier != null && summaryCarrier instanceof ToolPart) {
                    String summary = ((ToolPart) summaryCarrier).getSummary();
                    logger.info("【摘要处理】找到 summary，开始查找目标工具，summary={}", summary);

                    // 查找上一个无摘要的 ToolPart
                    ToolPart targetTool = findLastToolWithoutSummary(session);
                    if (targetTool != null) {
                        targetTool.setSummary(summary);
                        logger.info("【摘要处理】成功保存摘要: targetTool={}, summary={}",
                                targetTool.getToolName(), summary);
                        // 清空 summaryCarrier 的 summary，避免混淆
                        ((ToolPart) summaryCarrier).setSummary(null);
                    } else {
                        logger.warn("【摘要处理】LLM 生成了摘要，但没有找到需要摘要的历史工具");
                    }
                } else {
                    logger.info("【摘要处理】没有找到包含 summary 的 ToolPart");
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
     * 从响应中提取 JSON（8级递进式解析策略）
     * <p>
     * 解析策略从简单到复杂，逐级尝试，确保最大容错能力：
     * Level 1: 直接解析（最快）
     * Level 2: 清理后解析（去除 markdown 代码块）
     * Level 3: 修复转义后解析（修复常见转义问题）
     * Level 4: 智能大括号提取（增强版策略3）
     * Level 5: 正则提取尝试（多种模式匹配）
     * Level 6: 简单正则快速尝试（补充兜底）
     * Level 7: 终极大招 - LLM 辅助提取
     * Level 8: 降级为纯文本（兜底）
     *
     * @param response LLM 返回的原始响应
     * @return 提取出的 JSON 字符串，如果所有策略都失败则返回 null
     */
    private String extractJsonFromResponse(String response) {
        if (response == null || response.trim().isEmpty()) {
            return null;
        }

        String trimmedResponse = response.trim();

        // ========== Level 1: 直接解析 ==========
        if (tryParseJson(trimmedResponse)) {
            logger.debug("Level 1 成功: 直接解析");
            return trimmedResponse;
        }

        // ========== Level 2: 清理 markdown 代码块 ==========
        String level2Result = extractFromMarkdownBlock(trimmedResponse);
        if (level2Result != null && tryParseJson(level2Result)) {
            logger.debug("Level 2 成功: 清理 markdown 代码块");
            return level2Result;
        }

        // ========== Level 3: 修复转义字符 ==========
        String level3Result = fixAndParse(trimmedResponse);
        if (level3Result != null) {
            logger.debug("Level 3 成功: 修复转义字符");
            return level3Result;
        }

        // ========== Level 4: 智能大括号提取（增强版）==========
        String level4Result = extractWithSmartBraceMatching(trimmedResponse);
        if (level4Result != null && tryParseJson(level4Result)) {
            logger.debug("Level 4 成功: 智能大括号提取");
            return level4Result;
        }

        // ========== Level 5: 正则提取尝试 ==========
        String level5Result = extractWithRegex(trimmedResponse);
        if (level5Result != null && tryParseJson(level5Result)) {
            logger.debug("Level 5 成功: 正则提取");
            return level5Result;
        }

        // ========== Level 6: 简单正则快速尝试 ==========
        String level6Result = extractWithSimpleRegex(trimmedResponse);
        if (level6Result != null && tryParseJson(level6Result)) {
            logger.debug("Level 6 成功: 简单正则提取");
            return level6Result;
        }

        // ========== Level 7: 终极大招 - LLM 辅助提取 ==========
        String level7Result = extractWithLlmHelper(response);
        if (level7Result != null && tryParseJson(level7Result)) {
            logger.debug("Level 7 成功: LLM 辅助提取");
            return level7Result;
        }

        // ========== Level 8: 所有策略失败，降级为纯文本 ==========
        logger.warn("所有 JSON 提取策略失败，将降级为纯文本处理");
        return null;
    }

    /**
     * Level 1: 尝试直接解析 JSON
     */
    private boolean tryParseJson(String str) {
        if (str == null || str.trim().isEmpty()) {
            return false;
        }
        try {
            objectMapper.readTree(str);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Level 2: 从 markdown 代码块中提取 JSON
     */
    private String extractFromMarkdownBlock(String response) {
        // 尝试提取 ```json...``` 代码块
        String jsonStart = "```json";
        String jsonEnd = "```";

        int startIndex = response.indexOf(jsonStart);
        if (startIndex != -1) {
            startIndex += jsonStart.length();
            int endIndex = response.indexOf(jsonEnd, startIndex);
            if (endIndex != -1) {
                return response.substring(startIndex, endIndex).trim();
            }
        }

        // 尝试提取 ```...``` 代码块（没有 json 标记）
        String codeStart = "```";
        int codeStartIndex = response.indexOf(codeStart);
        if (codeStartIndex != -1) {
            int afterStart = codeStartIndex + codeStart.length();
            // 跳过可能的语言标记
            int firstBrace = response.indexOf('{', afterStart);
            if (firstBrace != -1) {
                int endIndex = response.indexOf(codeStart, firstBrace);
                if (endIndex != -1) {
                    return response.substring(firstBrace, endIndex).trim();
                }
            }
        }

        return null;
    }

    /**
     * Level 3: 修复转义字符并解析
     * <p>
     * 处理 LLM 返回的 JSON 中常见的转义问题：
     * - 字符串内部的换行符 \n 未转义
     * - 字符串内部的引号 " 未转义
     * - 字符串内部的反斜杠 \ 未转义
     */
    private String fixAndParse(String response) {
        // 先尝试从 markdown 代码块中提取
        String extracted = extractFromMarkdownBlock(response);
        String toFix = extracted != null ? extracted : response;

        // 尝试多种修复策略
        String[] fixedVersions = {
                fixStringNewlines(toFix),           // 修复字符串内的换行
                fixUnescapedQuotes(toFix),          // 修复未转义的引号
                fixUnescapedBackslashes(toFix),     // 修复未转义的反斜杠
                fixAllCommonIssues(toFix)           // 修复所有常见问题
        };

        for (String fixed : fixedVersions) {
            if (tryParseJson(fixed)) {
                return fixed;
            }
        }

        return null;
    }

    /**
     * 修复 JSON 字符串值中的未转义换行符
     * <p>
     * 例如: {"text": "hello\nworld"} -> {"text": "hello\\nworld"}
     */
    private String fixStringNewlines(String json) {
        // 这是一个简化版本，只处理最常见的情况
        // 更复杂的版本需要跟踪字符串状态
        return json.replace("\\\n", "\\\\n");
    }

    /**
     * 修复 JSON 字符串值中的未转义引号
     * <p>
     * 这是一个启发式方法，尝试修复常见的未转义引号问题
     */
    private String fixUnescapedQuotes(String json) {
        // 简化版本：只处理明显的情况
        // 注意：这是一个有损修复，可能不是所有情况都适用
        return json;
    }

    /**
     * 修复 JSON 字符串值中的未转义反斜杠
     */
    private String fixUnescapedBackslashes(String json) {
        // 简化版本：只处理明显的情况
        return json;
    }

    /**
     * 修复所有常见的转义问题
     */
    private String fixAllCommonIssues(String json) {
        String result = json;
        result = fixStringNewlines(result);
        result = fixUnescapedQuotes(result);
        result = fixUnescapedBackslashes(result);
        return result;
    }

    /**
     * Level 4: 智能大括号匹配提取
     * <p>
     * 从复杂文本中提取完整的 JSON 对象，处理：
     * - 嵌套大括号
     * - 字符串内部的大括号
     * - 转义字符
     */
    private String extractWithSmartBraceMatching(String response) {
        int braceStart = response.indexOf('{');
        if (braceStart < 0) {
            return null;
        }

        int depth = 0;
        boolean inString = false;
        boolean escape = false;

        for (int i = braceStart; i < response.length(); i++) {
            char c = response.charAt(i);

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
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        return response.substring(braceStart, i + 1);
                    }
                }
            }
        }

        return null;
    }

    /**
     * Level 5: 使用正则表达式提取 JSON
     */
    private String extractWithRegex(String response) {
        // 尝试多种正则模式
        java.util.regex.Pattern[] patterns = {
                // 模式1: 匹配 ```json 和 ``` 之间的内容
                java.util.regex.Pattern.compile("```json\\s*([\\s\\S]*?)\\s*```"),
                // 模式2: 匹配 { 和 } 之间的完整 JSON 对象（贪婪）
                java.util.regex.Pattern.compile("\\{[\\s\\S]*\\}"),
                // 模式3: 匹配嵌套的 JSON 对象
                java.util.regex.Pattern.compile("\\{(?:[^{}]|\\{[^{}]*\\})*\\}")
        };

        for (java.util.regex.Pattern pattern : patterns) {
            java.util.regex.Matcher matcher = pattern.matcher(response);
            if (matcher.find()) {
                String match = matcher.group(1);
                if (match == null) {
                    match = matcher.group(0);
                }
                if (match != null && !match.trim().isEmpty()) {
                    return match.trim();
                }
            }
        }

        return null;
    }

    /**
     * Level 6: 简单正则快速提取
     */
    private String extractWithSimpleRegex(String response) {
        // 快速尝试：找到第一个 { 和最后一个 }
        int firstBrace = response.indexOf('{');
        int lastBrace = response.lastIndexOf('}');

        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return response.substring(firstBrace, lastBrace + 1);
        }

        return null;
    }

    /**
     * Level 7: 终极大招 - 使用 LLM 辅助修复 JSON
     * <p>
     * 当所有常规方法都失败时，调用 LLM 让它帮我们修复 JSON 中的问题字段值。
     * <p>
     * 注意：这不是重新提取 JSON，而是修复已识别出的 JSON 结构中无法解析的字段值。
     * <p>
     * 常见问题：
     * - 字段值中包含未转义的换行符、引号、反斜杠
     * - 字段值中包含嵌套的代码块标记
     * - 字段值中包含特殊字符导致 JSON 结构破坏
     * <p>
     * 这是一个"大招"，因为：
     * 1. 它会消耗额外的 Token 和时间
     * 2. 但它是最智能的方式，可以处理各种复杂的字段值问题
     * 3. LLM 自己输出的内容，LLM 自己应该能理解并修复
     */
    private String extractWithLlmHelper(String response) {
        if (response == null || response.trim().isEmpty()) {
            return null;
        }

        try {
            logger.info("启动 Level 7 终极大招: 使用 LLM 辅助修复 JSON 字段值");

            // 先尝试用智能大括号提取找出 JSON 结构
            String candidateJson = extractWithSmartBraceMatching(response);
            if (candidateJson == null) {
                logger.warn("LLM 辅助修复: 无法提取 JSON 结构，跳过");
                return null;
            }

            // 分析 JSON 结构，找出问题字段
            // 简单启发式：查找 "text": "...", "reasoning": "...", "summary": "..." 等常见字段
            String problematicField = extractProblematicField(candidateJson);
            if (problematicField == null) {
                logger.warn("LLM 辅助修复: 无法识别问题字段，跳过");
                return null;
            }

            logger.info("LLM 辅助修复: 识别到问题字段，开始修复");

            // 调用 LLM 修复这个字段值
            String fixedJson = fixProblematicFieldWithLlm(candidateJson, problematicField);
            if (fixedJson == null) {
                logger.warn("LLM 辅助修复: LLM 修复失败");
                return null;
            }

            logger.info("LLM 辅助修复完成");
            return fixedJson;

        } catch (Exception e) {
            logger.error("LLM 辅助修复异常", e);
            return null;
        }
    }

    /**
     * 从 JSON 中提取出可能有问题的字段定义
     * <p>
     * 简单启发式：查找 text、reasoning、summary 等常见字段
     */
    private String extractProblematicField(String json) {
        // 常见问题字段模式： "fieldName": "可能包含换行等内容"
        String[] fieldNames = {"text", "reasoning", "summary", "content", "description"};

        for (String fieldName : fieldNames) {
            String pattern = "\"" + fieldName + "\"\\s*:\\s*\"(.{50,})(?:\"|\\n|$)";
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern, java.util.regex.Pattern.DOTALL);
            java.util.regex.Matcher m = p.matcher(json);
            if (m.find()) {
                return "\"" + fieldName + "\": " + m.group(1);
            }
        }

        return null;
    }

    /**
     * 调用 LLM 修复问题字段并重新组装 JSON
     */
    private String fixProblematicFieldWithLlm(String json, String problematicField) {
        try {
            // 提取字段名和原始值
            String fieldName = problematicField.split(":", 2)[0].trim().replace("\"", "");
            String rawValue = problematicField.split(":", 2)[1].trim();

            String systemPrompt = """
                    # JSON 字段值修复专家

                    你是一个 JSON 字段值修复专家。

                    ## 任务

                    把一个可能有格式问题的字段值修复成合法的 JSON 字段值。

                    ## 要求

                    1. **保持原始内容** - 不要改变内容含义，只修复格式问题
                    2. **输出简单 JSON** - 直接输出 {"fieldName": "修复后的值"} 这种格式
                    3. **只输出 JSON** - 不要 markdown 代码块，不要解释

                    ## 输出格式

                    直接输出简单的 JSON 对象，例如：
                    {"text": "修复后的内容"}

                    ## 重要

                    - 字段值中如果有换行，用 \\n 表示
                    - 字段值中如果有引号，用 \\" 表示
                    - 确保输出的 JSON 可以被标准解析器解析
                    """;

            String userPrompt = """
                    请修复下面的字段值，并输出简单的 JSON 格式。

                    字段名：%s
                    原始值：
                    %s

                    直接输出修复后的 JSON（格式：{"fieldName": "修复后的值"}）：
                    """.formatted(fieldName, rawValue.length() > 2000 ? rawValue.substring(0, 2000) + "..." : rawValue);

            String llmResponse = llmService.simpleRequest(systemPrompt, userPrompt);
            if (llmResponse == null || llmResponse.trim().isEmpty()) {
                return null;
            }

            // 清理可能的 markdown 标记
            String cleaned = llmResponse.trim();
            if (cleaned.startsWith("```")) {
                cleaned = extractFromMarkdownBlock(cleaned);
                if (cleaned == null) {
                    cleaned = llmResponse.trim();
                }
            }

            // 解析 LLM 返回的简单 JSON，提取修复后的字段值
            String fixedValue = extractFieldValueFromSimpleJson(cleaned, fieldName);
            if (fixedValue == null) {
                // 如果解析失败，直接使用 cleaned 作为字段值
                fixedValue = cleaned;
            }

            // 重新组装完整的 JSON
            // 替换原始字段值
            String patternStr = "\"" + fieldName + "\"\\s*:\\s*\".*?(?=\"|\\n)";
            String fixedJson = json.replaceAll(patternStr, "\"" + fieldName + "\": " + fixedValue);

            // 如果替换失败（内容太复杂），尝试简单的字符串替换
            if (fixedJson.equals(json)) {
                // 尝试替换前100个字符作为匹配
                String prefix = rawValue.length() > 100 ? rawValue.substring(0, 100) : rawValue;
                fixedJson = json.replace(prefix, fixedValue.replace("\"", ""));
            }

            return fixedJson;

        } catch (Exception e) {
            logger.error("LLM 修复字段值异常", e);
            return null;
        }
    }

    /**
     * 从简单 JSON 中提取字段值
     * <p>
     * 例如：{"text": "hello"} -> "hello"
     */
    private String extractFieldValueFromSimpleJson(String simpleJson, String fieldName) {
        try {
            JsonNode node = objectMapper.readTree(simpleJson);
            JsonNode valueNode = node.path(fieldName);
            if (!valueNode.isMissingNode()) {
                // 返回带引号的字符串值
                return "\"" + valueNode.asText().replace("\\", "\\\\").replace("\"", "\\\"")
                        .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
            }
        } catch (Exception e) {
            logger.debug("解析简单 JSON 失败: {}", e.getMessage());
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
                        // 关键：智能摘要机制
                        // - 有 summary：说明是历史工具，只发送摘要（避免 Token 爆炸）
                        // - 无 summary：说明是新执行完的工具，发送完整结果 + 要求 LLM 生成摘要
                        prompt.append("调用工具: ").append(toolPart.getToolName()).append("\n");

                        // 添加参数
                        if (toolPart.getParameters() != null && !toolPart.getParameters().isEmpty()) {
                            prompt.append("参数: ").append(formatParamsBrief(toolPart.getParameters())).append("\n");
                        }

                        if (toolPart.getResult() != null) {
                            com.smancode.smanagent.tools.ToolResult result = toolPart.getResult();
                            if (result.isSuccess()) {
                                // 智能摘要机制
                                if (toolPart.getSummary() != null && !toolPart.getSummary().isEmpty()) {
                                    // 有 summary：历史工具，只发送摘要
                                    prompt.append("结果: \n").append(toolPart.getSummary()).append("\n");
                                } else {
                                    // 无 summary：新执行完的工具，发送完整结果
                                    // 新增：添加 relativePath（如果有）
                                    if (result.getRelativePath() != null && !result.getRelativePath().isEmpty()) {
                                        prompt.append("文件路径: ").append(result.getRelativePath()).append("\n");
                                    }

                                    String fullData = result.getData() != null ? result.getData().toString() : null;
                                    if (fullData != null && !fullData.isEmpty()) {
                                        prompt.append("结果: \n").append(fullData).append("\n");
                                        // 标记为需要生成摘要，要求保留文件路径
                                        prompt.append("【此工具结果尚无摘要，需要你生成】\n");
                                        prompt.append("【重要：生成摘要时必须保留文件路径信息】\n");
                                    } else {
                                        String displayContent = result.getDisplayContent();
                                        if (displayContent != null && !displayContent.isEmpty()) {
                                            prompt.append("结果: \n").append(displayContent).append("\n");
                                        } else {
                                            prompt.append("结果: (执行成功，无返回内容)\n");
                                        }
                                    }
                                }

                                // 新增：如果有 metadata，添加关键变更信息（用于 apply_change 等工具）
                                if (result.getMetadata() != null && !result.getMetadata().isEmpty()) {
                                    java.util.Map<String, Object> metadata = result.getMetadata();
                                    // 添加 description（如果有）
                                    if (metadata.containsKey("description")) {
                                        Object desc = metadata.get("description");
                                        if (desc != null && !desc.toString().isEmpty()) {
                                            prompt.append("变更说明: ").append(desc.toString()).append("\n");
                                        }
                                    }
                                    // 添加 changeSummary（如果有）
                                    if (metadata.containsKey("changeSummary")) {
                                        Object summary = metadata.get("changeSummary");
                                        if (summary != null && !summary.toString().isEmpty()) {
                                            prompt.append("变更详情: \n").append(summary.toString()).append("\n");
                                        }
                                    }
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
        prompt.append("2. **生成摘要（重要）**：\n");
        prompt.append("   - 如果发现工具结果标注了【此工具结果尚无摘要，需要你生成】\n");
        prompt.append("   - 并且你决定调用新工具：在新工具的 ToolPart 中添加 \"summary\" 字段，\n");
        prompt.append("     为**刚才执行的工具**（不是新工具）生成摘要\n");
        prompt.append("   - **关键要求：生成摘要时必须保留文件路径（relativePath）信息**\n");
        prompt.append("     摘要格式应包含：\"路径: xxx/yyy/File.java\" 或 \"read_file(路径: xxx/yyy/File.java): ...\"\n");
        prompt.append("   - 如果不调用新工具：直接返回文本答案即可，不需要生成摘要\n");
        prompt.append("   - 摘要格式：{\"type\": \"tool\", \"toolName\": \"新工具名\", \"parameters\": {...}, \"summary\": \"刚才工具的摘要\"}\n");
        prompt.append("3. **评估进展**：当前信息是否足够回答用户问题？\n");
        prompt.append("4. **决定行动**：\n");
        prompt.append("   - 如果信息充足 → 直接给出答案（不再调用工具）\n");
        prompt.append("   - 如果需要更多信息 → 继续调用工具（说明为什么需要）\n");
        prompt.append("   - 如果工具失败 → 换个方法重试（不要重复失败的方法）\n\n");
        prompt.append("**示例**：\n");
        prompt.append("如果你刚刚执行了 read_file（无摘要），文件路径是 agent/src/main/java/CallChainTool.java，现在要调用 apply_change，\n");
        prompt.append("返回的 JSON 中应该包含：\n");
        prompt.append("{\"type\": \"tool\", \"toolName\": \"apply_change\", \"parameters\": {...}, \"summary\": \"read_file(路径: agent/src/main/java/CallChainTool.java): 找到了CallChainTool类，包含callChain方法...\"}\n\n");

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
                JsonNode valueNode = entry.getValue();
                // 根据实际类型转换，避免将数字转为字符串
                if (valueNode.isTextual()) {
                    params.put(entry.getKey(), valueNode.asText());
                } else if (valueNode.isNumber()) {
                    params.put(entry.getKey(), valueNode.numberValue());
                } else if (valueNode.isBoolean()) {
                    params.put(entry.getKey(), valueNode.asBoolean());
                } else {
                    params.put(entry.getKey(), valueNode.asText());
                }
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
     * 查找最后一个无摘要的 ToolPart
     * <p>
     * 用于将 LLM 生成的摘要保存到对应的历史工具
     *
     * @param session 会话
     * @return 最后一个无摘要的 ToolPart，如果没有则返回 null
     */
    private ToolPart findLastToolWithoutSummary(Session session) {
        logger.info("【查找无摘要工具】开始查找，消息总数={}", session.getMessages().size());

        // 从后往前遍历所有消息
        for (int i = session.getMessages().size() - 1; i >= 0; i--) {
            Message message = session.getMessages().get(i);
            logger.info("【查找无摘要工具】检查消息 {}/{}: role={}, Part 数={}",
                    i + 1, session.getMessages().size(),
                    message.getRole(), message.getParts().size());

            if (message.isAssistantMessage()) {
                // 从后往前遍历该消息的所有 Part
                for (int j = message.getParts().size() - 1; j >= 0; j--) {
                    Part part = message.getParts().get(j);
                    if (part instanceof ToolPart toolPart) {
                        String summary = toolPart.getSummary();
                        boolean hasSummary = summary != null && !summary.isEmpty();

                        logger.info("【查找无摘要工具】  检查 ToolPart: toolName={}, hasSummary={}, summary={}",
                                toolPart.getToolName(), hasSummary,
                                hasSummary ? summary.substring(0, Math.min(30, summary.length())) : "null");

                        // 检查是否有摘要
                        if (!hasSummary) {
                            // 找到最后一个无摘要的 ToolPart
                            logger.info("【查找无摘要工具】✅ 找到无摘要的工具: toolName={}, messageId={}",
                                    toolPart.getToolName(), message.getId());
                            return toolPart;
                        }
                    }
                }
            }
        }

        logger.info("【查找无摘要工具】❌ 没有找到无摘要的工具");
        return null;
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
