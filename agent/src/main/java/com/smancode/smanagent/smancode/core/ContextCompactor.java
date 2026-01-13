package com.smancode.smanagent.smancode.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smancode.smanagent.model.message.Message;
import com.smancode.smanagent.model.part.Part;
import com.smancode.smanagent.model.part.ToolPart;
import com.smancode.smanagent.model.session.Session;
import com.smancode.smanagent.smancode.llm.LlmService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 上下文压缩器
 * <p>
 * 实现 OpenCode 风格的上下文压缩机制：
 * 1. Pruning: 清理旧的工具输出
 * 2. Compaction: 生成会话摘要
 */
@Component
public class ContextCompactor {

    private static final Logger logger = LoggerFactory.getLogger(ContextCompactor.class);

    // 配置参数（参考 OpenCode）
    private static final int PRUNE_MINIMUM_TOKENS = 5_000;     // 至少清理 5k tokens
    private static final int PRUNE_PROTECT_TOKENS = 10_000;    // 保护最近 10k tokens
    private static final int PRUNE_PROTECT_ROUNDS = 2;         // 保护最近 2 轮对话
    private static final int MAX_CONTEXT_TOKENS = 100_000;     // 最大上下文 tokens

    @Autowired
    private LlmService llmService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 检查是否需要压缩
     */
    public boolean needsCompaction(Session session) {
        int estimatedTokens = estimateSessionTokens(session);
        return estimatedTokens > MAX_CONTEXT_TOKENS;
    }

    /**
     * 估算会话 Token 数量
     */
    public int estimateSessionTokens(Session session) {
        int total = 0;
        for (Message message : session.getMessages()) {
            for (Part part : message.getParts()) {
                total += TokenEstimator.estimate(part);
            }
        }
        return total;
    }

    /**
     * 执行 Pruning（清理旧工具输出）
     *
     * @return 清理的 token 数量
     */
    public int prune(Session session) {
        logger.info("开始 Pruning: sessionId={}", session.getId());

        int totalTokens = 0;
        int prunedTokens = 0;
        List<Part> partsToPrune = new ArrayList<>();
        int rounds = 0;
        boolean protect = true;

        // 倒序遍历消息（从最新到最旧）
        List<Message> messages = session.getMessages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message msg = messages.get(i);

            if (msg.isAssistantMessage()) {
                rounds++;

                // 跳过最近 N 轮对话
                if (rounds <= PRUNE_PROTECT_ROUNDS) {
                    continue;
                }

                // 检查是否已有压缩标记
                if (msg.getCreatedTime().isBefore(Instant.EPOCH)) {
                    break;  // 已压缩过，停止
                }

                // 查找可清理的工具调用
                for (Part part : msg.getParts()) {
                    if (part instanceof ToolPart toolPart) {
                        if (toolPart.getState() == ToolPart.ToolState.COMPLETED) {
                            // 检查是否已清理过
                            if (toolPart.getResult() != null &&
                                toolPart.getResult().getData() != null &&
                                !isPruned(toolPart.getResult())) {

                                int partTokens = TokenEstimator.estimate(part);
                                totalTokens += partTokens;

                                // 超过保护阈值后开始清理
                                if (totalTokens > PRUNE_PROTECT_TOKENS) {
                                    partsToPrune.add(part);
                                    prunedTokens += partTokens;
                                }
                            }
                        }
                    }
                }
            }
        }

        // 如果清理量足够大，执行清理
        if (prunedTokens > PRUNE_MINIMUM_TOKENS) {
            for (Part part : partsToPrune) {
                if (part instanceof ToolPart toolPart) {
                    pruneToolPart(toolPart);
                }
            }
            logger.info("Pruning 完成: sessionId={}, prunedTokens={}", session.getId(), prunedTokens);
        } else {
            logger.info("Pruning 跳过: sessionId={}, prunedTokens={} (阈值: {})",
                    session.getId(), prunedTokens, PRUNE_MINIMUM_TOKENS);
        }

        return prunedTokens;
    }

    /**
     * 执行 Compaction（生成会话摘要）
     */
    public String compact(Session session) {
        logger.info("开始 Compaction: sessionId={}", session.getId());

        try {
            // 1. 构建压缩提示词
            String prompt = buildCompactionPrompt(session);

            // 2. 调用 LLM 生成摘要
            String response = llmService.simpleRequest(prompt);

            // 3. 解析摘要
            JsonNode json = objectMapper.readTree(response);
            String summary = json.path("summary").asText("");

            // 4. 标记压缩点
            markCompactionPoint(session);

            logger.info("Compaction 完成: sessionId={}, summaryLength={}",
                    session.getId(), summary.length());

            return summary;

        } catch (Exception e) {
            logger.error("Compaction 失败: sessionId={}", session.getId(), e);
            return "";
        }
    }

    /**
     * 清理工具 Part
     */
    private void pruneToolPart(ToolPart toolPart) {
        if (toolPart.getResult() != null && toolPart.getResult().getData() != null) {
            String original = String.valueOf(toolPart.getResult().getData());
            String pruned = "[Pruned: " + original.length() + " chars]";

            // 替换为占位符
            toolPart.getResult().setData(pruned);

            // 标记已清理（通过设置 displayContent）
            toolPart.getResult().setDisplayContent("[COMPACTED]");
        }
    }

    /**
     * 检查是否已清理
     */
    private boolean isPruned(com.smancode.smanagent.tools.ToolResult result) {
        if (result.getData() == null) {
            return true;
        }
        String dataStr = String.valueOf(result.getData());
        return dataStr.startsWith("[Pruned:");
    }

    /**
     * 标记压缩点
     */
    private void markCompactionPoint(Session session) {
        // 设置最早的助手消息的创建时间为特殊值
        for (Message message : session.getMessages()) {
            if (message.isAssistantMessage()) {
                message.setCreatedTime(Instant.EPOCH);
                break;  // 只标记第一个
            }
        }
    }

    /**
     * 构建压缩提示词
     */
    private String buildCompactionPrompt(Session session) {
        StringBuilder prompt = new StringBuilder();

        prompt.append("你是代码分析助手。请将以下对话历史压缩为简洁的摘要。\n\n");

        prompt.append("## 用户原始问题\n");
        Message firstUser = getFirstUserMessage(session);
        if (firstUser != null && !firstUser.getParts().isEmpty()) {
            Part firstPart = firstUser.getParts().get(0);
            if (firstPart instanceof com.smancode.smanagent.model.part.TextPart) {
                prompt.append(((com.smancode.smanagent.model.part.TextPart) firstPart).getText()).append("\n\n");
            }
        }

        prompt.append("## 对话历史\n");
        prompt.append(formatConversationHistory(session)).append("\n\n");

        prompt.append("## 要求\n");
        prompt.append("请生成一个详细的摘要，包含以下信息：\n");
        prompt.append("1. 我们做了什么（已执行的工具和发现）\n");
        prompt.append("2. 我们正在做什么（当前状态）\n");
        prompt.append("3. 我们需要做什么（下一步计划）\n");
        prompt.append("4. 关键的用户请求、约束或偏好\n");
        prompt.append("5. 重要的技术决策及其原因\n\n");

        prompt.append("请以 JSON 格式返回：\n");
        prompt.append("{\n");
        prompt.append("  \"summary\": \"你的详细摘要\"\n");
        prompt.append("}");

        return prompt.toString();
    }

    /**
     * 格式化对话历史
     */
    private String formatConversationHistory(Session session) {
        StringBuilder sb = new StringBuilder();

        for (Message message : session.getMessages()) {
            if (message.isUserMessage()) {
                sb.append("### 用户\n");
                for (Part part : message.getParts()) {
                    if (part instanceof com.smancode.smanagent.model.part.TextPart) {
                        sb.append(((com.smancode.smanagent.model.part.TextPart) part).getText()).append("\n");
                    }
                }
            } else {
                sb.append("### 助手\n");
                for (Part part : message.getParts()) {
                    if (part instanceof com.smancode.smanagent.model.part.TextPart) {
                        sb.append(((com.smancode.smanagent.model.part.TextPart) part).getText()).append("\n");
                    } else if (part instanceof ToolPart) {
                        ToolPart toolPart = (ToolPart) part;
                        sb.append("调用工具: ").append(toolPart.getToolName());
                        if (toolPart.getResult() != null && toolPart.getResult().getData() != null) {
                            String data = String.valueOf(toolPart.getResult().getData());
                            if (!data.startsWith("[Pruned:")) {
                                sb.append("\n结果: ").append(data.substring(0, Math.min(200, data.length())));
                            }
                        }
                        sb.append("\n");
                    }
                }
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * 获取第一个用户消息
     */
    private Message getFirstUserMessage(Session session) {
        for (Message message : session.getMessages()) {
            if (message.isUserMessage()) {
                return message;
            }
        }
        return null;
    }
}
