package com.smancode.smanagent.smancode.command;

import com.smancode.smanagent.model.message.Message;
import com.smancode.smanagent.model.part.Part;
import com.smancode.smanagent.model.part.ToolPart;
import com.smancode.smanagent.model.session.Session;
import com.smancode.smanagent.tools.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 文件变更追踪器
 * <p>
 * 从会话中提取 agent 通过工具修改的文件
 */
@Component
public class FileChangeTracker {

    private static final Logger logger = LoggerFactory.getLogger(FileChangeTracker.class);

    /**
     * 从会话中提取所有文件变更
     * 只统计 agent 通过工具修改的文件
     * 只提取上次 commit 之后的新变更
     *
     * @param session 会话
     * @return 文件变更列表
     */
    public List<FileChange> extractFileChanges(Session session) {
        List<FileChange> changes = new ArrayList<>();

        if (session == null || session.getMessages() == null) {
            logger.warn("会话为空或没有消息，返回空变更列表");
            return changes;
        }

        java.time.Instant lastCommitTime = session.getLastCommitTime();
        logger.info("【文件变更追踪】开始提取文件变更: sessionId={}, 消息数={}, lastCommitTime={}",
                session.getId(), session.getMessages().size(), lastCommitTime);

        // 遍历会话中的所有消息
        for (Message message : session.getMessages()) {
            // 只处理 assistant 消息（agent 的响应）
            if (!message.isAssistantMessage()) {
                continue;
            }

            // 只提取上次 commit 之后的消息
            if (lastCommitTime != null && !message.getCreatedTime().isAfter(lastCommitTime)) {
                continue;
            }

            // 提取 ToolPart
            for (Part part : message.getParts()) {
                if (!(part instanceof ToolPart)) {
                    continue;
                }

                ToolPart toolPart = (ToolPart) part;
                FileChange change = extractFromFileChangeTool(toolPart);

                if (change != null) {
                    changes.add(change);
                    logger.debug("【文件变更追踪】发现变更: toolName={}, path={}, type={}",
                            toolPart.getToolName(), change.getRelativePath(), change.getType());
                }
            }
        }

        logger.info("【文件变更追踪】完成提取: 总共 {} 个文件变更", changes.size());
        return changes;
    }

    /**
     * 从工具调用中提取文件变更
     *
     * @param toolPart 工具 Part
     * @return 文件变更，如果不是文件变更工具则返回 null
     */
    private FileChange extractFromFileChangeTool(ToolPart toolPart) {
        String toolName = toolPart.getToolName();

        return switch (toolName) {
            case "apply_change" -> {
                String path = getPathFromParams(toolPart.getParameters());
                String summary = getSummaryFromResult(toolPart.getResult());
                yield new FileChange(path, FileChange.ChangeType.MODIFY, summary);
            }
            case "create_file" -> {
                String path = getPathFromParams(toolPart.getParameters());
                String summary = getSummaryFromResult(toolPart.getResult());
                yield new FileChange(path, FileChange.ChangeType.ADD, summary);
            }
            case "delete_file" -> {
                String path = getPathFromParams(toolPart.getParameters());
                String summary = getSummaryFromResult(toolPart.getResult());
                yield new FileChange(path, FileChange.ChangeType.DELETE, summary);
            }
            default -> null;
        };
    }

    /**
     * 从工具参数中提取文件路径
     *
     * @param parameters 工具参数
     * @return 文件相对路径
     */
    private String getPathFromParams(Map<String, Object> parameters) {
        if (parameters == null) {
            return null;
        }
        return (String) parameters.get("relativePath");
    }

    /**
     * 从工具结果中提取变更摘要
     * 优先从 metadata 中获取详细的改动信息，否则使用 displayContent
     *
     * @param result 工具执行结果
     * @return 变更摘要
     */
    private String getSummaryFromResult(ToolResult result) {
        if (result == null) {
            return "";
        }

        logger.debug("【FileChangeTracker】开始提取变更摘要: hasMetadata={}, hasChangeSummary={}, hasData={}, displayContent={}",
                result.getMetadata() != null,
                result.getMetadata() != null && result.getMetadata().containsKey("changeSummary"),
                result.getData() != null,
                result.getDisplayContent() != null ? result.getDisplayContent().substring(0, Math.min(50, result.getDisplayContent().length())) : "null");

        // 优先从 metadata 中获取 changeSummary（最详细的变更说明）
        if (result.getMetadata() != null && result.getMetadata().containsKey("changeSummary")) {
            Object changeSummary = result.getMetadata().get("changeSummary");
            if (changeSummary != null) {
                String summary = changeSummary.toString();
                logger.info("【FileChangeTracker】使用 metadata.changeSummary: {}", summary);
                return summary;
            }
        }

        // 如果有 description，使用 description
        if (result.getMetadata() != null && result.getMetadata().containsKey("description")) {
            Object description = result.getMetadata().get("description");
            if (description != null) {
                String desc = description.toString();
                if (!desc.isEmpty()) {
                    logger.info("【FileChangeTracker】使用 metadata.description: {}", desc);
                    return desc;
                }
            }
        }

        // 如果有 searchContent，构建摘要
        if (result.getMetadata() != null && result.getMetadata().containsKey("searchContent")) {
            Object searchContent = result.getMetadata().get("searchContent");
            if (searchContent != null) {
                String content = searchContent.toString();
                if (!content.isEmpty()) {
                    // 提取前几行作为摘要
                    String[] lines = content.split("\n");
                    StringBuilder preview = new StringBuilder();
                    preview.append("修改位置:\n");
                    for (int i = 0; i < Math.min(3, lines.length); i++) {
                        preview.append("  ").append(lines[i]).append("\n");
                    }
                    if (lines.length > 3) {
                        preview.append("  ...\n");
                    }
                    logger.info("【FileChangeTracker】使用 metadata.searchContent 构建摘要");
                    return preview.toString();
                }
            }
        }

        // 如果没有 changeSummary，使用 displayContent
        String displayContent = result.getDisplayContent();
        if (displayContent != null && !displayContent.isEmpty()) {
            logger.info("【FileChangeTracker】使用 displayContent: {}", displayContent);
            return displayContent;
        }

        // 最后使用 displayTitle
        String displayTitle = result.getDisplayTitle();
        if (displayTitle != null && !displayTitle.isEmpty()) {
            logger.info("【FileChangeTracker】使用 displayTitle: {}", displayTitle);
            return displayTitle;
        }

        logger.warn("【FileChangeTracker】无法提取变更摘要，返回空字符串");
        return "";
    }
}
