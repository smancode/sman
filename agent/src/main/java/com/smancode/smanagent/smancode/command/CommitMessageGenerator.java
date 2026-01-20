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
     * 构建 LLM 提示词（遵循 prompt_rules.md 规范）
     */
    private String buildPrompt(List<FileChange> changes, Session session) {
        StringBuilder prompt = new StringBuilder();

        // ========== System Configuration ==========
        prompt.append("<system_config>\n");
        prompt.append("  <language_rule>\n");
        prompt.append("    <thinking_language>English (For logic & reasoning)</thinking_language>\n");
        prompt.append("    <output_language>Simplified Chinese (For commit message)</output_language>\n");
        prompt.append("    <preserve_english>Keep technical terms and type prefixes in English</preserve_english>\n");
        prompt.append("  </language_rule>\n");
        prompt.append("</system_config>\n\n");

        // ========== Role & Task Definition ==========
        prompt.append("# Role\n");
        prompt.append("You are a Git commit message generator. Analyze code changes and generate concise commit messages.\n\n");

        // ========== Input Data ==========
        prompt.append("<input_data>\n");

        // 用户原始问题
        prompt.append("  <user_question>\n");
        Message latestUser = session.getLatestUserMessage();
        if (latestUser != null && latestUser.getContent() != null) {
            prompt.append("    ").append(latestUser.getContent()).append("\n");
        } else {
            prompt.append("    (None)\n");
        }
        prompt.append("  </user_question>\n\n");

        // 按类型分组变更
        Map<FileChange.ChangeType, List<FileChange>> grouped = changes.stream()
                .collect(Collectors.groupingBy(FileChange::getType));

        prompt.append("  <file_changes>\n");

        if (grouped.containsKey(FileChange.ChangeType.MODIFY)) {
            prompt.append("    <modifications>\n");
            List<FileChange> modifyChanges = grouped.get(FileChange.ChangeType.MODIFY);
            for (FileChange change : modifyChanges) {
                prompt.append("      <file>\n");
                prompt.append("        <path>").append(change.getRelativePath()).append("</path>\n");
                prompt.append("        <summary>").append(escapeXml(change.getChangeSummary())).append("</summary>\n");
                prompt.append("      </file>\n");
            }
            prompt.append("    </modifications>\n\n");
        }

        if (grouped.containsKey(FileChange.ChangeType.ADD)) {
            prompt.append("    <additions>\n");
            List<FileChange> addChanges = grouped.get(FileChange.ChangeType.ADD);
            for (FileChange change : addChanges) {
                prompt.append("      <file>\n");
                prompt.append("        <path>").append(change.getRelativePath()).append("</path>\n");
                prompt.append("        <summary>").append(escapeXml(change.getChangeSummary())).append("</summary>\n");
                prompt.append("      </file>\n");
            }
            prompt.append("    </additions>\n\n");
        }

        if (grouped.containsKey(FileChange.ChangeType.DELETE)) {
            prompt.append("    <deletions>\n");
            List<FileChange> deleteChanges = grouped.get(FileChange.ChangeType.DELETE);
            for (FileChange change : deleteChanges) {
                prompt.append("      <file>\n");
                prompt.append("        <path>").append(change.getRelativePath()).append("</path>\n");
                prompt.append("        <summary>").append(escapeXml(change.getChangeSummary())).append("</summary>\n");
                prompt.append("      </file>\n");
            }
            prompt.append("    </deletions>\n\n");
        }

        prompt.append("  </file_changes>\n");
        prompt.append("</input_data>\n\n");

        // ========== Thinking Process ==========
        prompt.append("<thinking_process>\n");
        prompt.append("Before generating the commit message:\n");
        prompt.append("1. **Analyze in English**: What is the core business value of these changes?\n");
        prompt.append("2. **Identify Type**: Is it a fix, feature, performance, refactoring, or chore?\n");
        prompt.append("3. **Synthesize**: Extract the essence, not technical details.\n");
        prompt.append("</thinking_process>\n\n");

        // ========== Output Format ==========
        prompt.append("<output_format>\n");
        prompt.append("**CRITICAL**: You MUST respond with a valid commit message in the following format:\n\n");
        prompt.append("```\n");
        prompt.append("type:message\n");
        prompt.append("```\n\n");
        prompt.append("**Where**:\n");
        prompt.append("- `type`: One of [fix, feat, perf, refactor, chore, test, docs, style]\n");
        prompt.append("- `message`: Concise Chinese description (max 30 characters)\n\n");
        prompt.append("**Examples**:\n");
        prompt.append("- `fix:修复登录样式`\n");
        prompt.append("- `feat:添加密码校验`\n");
        prompt.append("- `perf:优化查询性能`\n");
        prompt.append("- `refactor:重构认证逻辑`\n");
        prompt.append("- `chore:更新依赖`\n\n");
        prompt.append("**Requirements**:\n");
        prompt.append("- Start with verb (修复/添加/优化/重构/更新)\n");
        prompt.append("- Focus on business value, not implementation details\n");
        prompt.append("- Max 30 Chinese characters (will be truncated if longer)\n");
        prompt.append("- Keep type prefix in English\n");
        prompt.append("- Return ONLY the commit message, no explanations\n");
        prompt.append("</output_format>\n");

        return prompt.toString();
    }

    /**
     * 转义 XML 特殊字符
     */
    private String escapeXml(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("'", "&apos;");
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
