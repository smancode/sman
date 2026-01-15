package com.smancode.smanagent.smancode.command;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smancode.smanagent.model.message.Message;
import com.smancode.smanagent.model.session.Session;
import com.smancode.smanagent.smancode.llm.LlmService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Commit Message 生成器
 * <p>
 * 基于文件变更和用户问题生成 Git commit message
 */
@Component
public class CommitMessageGenerator {

    private static final Logger logger = LoggerFactory.getLogger(CommitMessageGenerator.class);

    @Autowired
    private LlmService llmService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 生成 commit message
     *
     * @param changes 文件变更列表
     * @param session 会话
     * @return commit message（格式：#AI commit# ${message}）
     */
    public String generate(List<FileChange> changes, Session session) {
        if (changes == null || changes.isEmpty()) {
            logger.info("【CommitMessage生成】无文件变更，返回默认消息");
            return "#AI commit# 无变更";
        }

        try {
            String prompt = buildPrompt(changes, session);

            logger.info("【CommitMessage生成】请求LLM生成commit message, 变更数={}", changes.size());
            String response = llmService.simpleRequest(prompt);
            logger.info("【CommitMessage生成】LLM响应长度={}, 完整响应={}",
                    response != null ? response.length() : 0, response);

            // 提取 message（去掉可能的 JSON 格式）
            String message = extractMessage(response);

            // 截断到 30 字符
            if (message.length() > 30) {
                message = message.substring(0, 30);
            }

            String result = "#AI commit# " + message;
            logger.info("【CommitMessage生成】最终commit message={}", result);
            return result;

        } catch (Exception e) {
            logger.warn("【CommitMessage生成】生成commit message失败，使用默认消息", e);
            return "#AI commit# 更新 " + changes.size() + " 个文件";
        }
    }

    /**
     * 构建 LLM 提示词
     */
    private String buildPrompt(List<FileChange> changes, Session session) {
        StringBuilder prompt = new StringBuilder();

        prompt.append("你是 Git commit message 生成助手。根据以下 Agent 执行的代码变更操作，生成一个简洁的 commit message。\n\n");

        // 用户原始问题
        prompt.append("## 用户原始问题\n");
        Message latestUser = session.getLatestUserMessage();
        if (latestUser != null && latestUser.getContent() != null) {
            prompt.append(latestUser.getContent()).append("\n\n");
        } else {
            prompt.append("(无)\n\n");
        }

        // 按类型分组变更
        Map<FileChange.ChangeType, List<FileChange>> grouped = changes.stream()
                .collect(Collectors.groupingBy(FileChange::getType));

        prompt.append("## Agent 执行的变更操作\n");

        if (grouped.containsKey(FileChange.ChangeType.MODIFY)) {
            prompt.append("### 文件修改 (apply_change)\n");
            List<FileChange> modifyChanges = grouped.get(FileChange.ChangeType.MODIFY);
            for (int i = 0; i < modifyChanges.size(); i++) {
                FileChange change = modifyChanges.get(i);
                prompt.append(String.format("%d. %s\n   变更内容: %s\n\n",
                        i + 1, change.getRelativePath(), change.getChangeSummary()));
            }
        }

        if (grouped.containsKey(FileChange.ChangeType.ADD)) {
            prompt.append("### 新增文件 (create_file)\n");
            List<FileChange> addChanges = grouped.get(FileChange.ChangeType.ADD);
            for (int i = 0; i < addChanges.size(); i++) {
                FileChange change = addChanges.get(i);
                prompt.append(String.format("%d. %s\n   变更内容: %s\n\n",
                        i + 1, change.getRelativePath(), change.getChangeSummary()));
            }
        }

        if (grouped.containsKey(FileChange.ChangeType.DELETE)) {
            prompt.append("### 删除文件 (delete_file)\n");
            List<FileChange> deleteChanges = grouped.get(FileChange.ChangeType.DELETE);
            for (int i = 0; i < deleteChanges.size(); i++) {
                FileChange change = deleteChanges.get(i);
                prompt.append(String.format("%d. %s\n   变更内容: %s\n\n",
                        i + 1, change.getRelativePath(), change.getChangeSummary()));
            }
        }

        prompt.append("## 要求\n");
        prompt.append("1. 最多 50 个字符（中文）\n");
        prompt.append("2. 概括变更的核心业务价值（不说技术细节）\n");
        prompt.append("3. 使用动词开头：修复、添加、优化、重构等\n\n");

        prompt.append("## 示例\n");
        prompt.append("- fix:修复登录页样式问题\n");
        prompt.append("- feat:添加用户密码强度校验\n");
        prompt.append("- perf:优化查询性能\n");
        prompt.append("- refactor:重构认证逻辑\n\n");

        prompt.append("请直接返回 commit message，不要其他内容。");

        return prompt.toString();
    }

    /**
     * 从 LLM 响应中提取 message
     * 处理可能的 JSON 格式或纯文本格式
     */
    private String extractMessage(String response) {
        if (response == null || response.isEmpty()) {
            return "更新代码";
        }

        String trimmed = response.trim();

        // 尝试解析为 JSON
        try {
            JsonNode json = objectMapper.readTree(trimmed);
            if (json.has("message")) {
                return json.get("message").asText();
            }
            if (json.has("commitMessage")) {
                return json.get("commitMessage").asText();
            }
            // 如果是纯字符串
            if (json.isTextual()) {
                return json.asText();
            }
        } catch (Exception e) {
            // 不是 JSON，直接使用原始响应
            logger.debug("【CommitMessage生成】响应不是JSON格式，直接使用原始响应");
        }

        // 去掉可能的 Markdown 代码块标记
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline > 0) {
                String content = trimmed.substring(firstNewline + 1);
                int lastBackticks = content.lastIndexOf("```");
                if (lastBackticks > 0) {
                    content = content.substring(0, lastBackticks);
                }
                return content.trim();
            }
        }

        return trimmed;
    }
}
