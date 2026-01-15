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

        logger.info("【文件变更追踪】开始提取文件变更: sessionId={}, 消息数={}",
                session.getId(), session.getMessages().size());

        // 遍历会话中的所有消息
        for (Message message : session.getMessages()) {
            // 只处理 assistant 消息（agent 的响应）
            if (!message.isAssistantMessage()) {
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
     *
     * @param result 工具执行结果
     * @return 变更摘要
     */
    private String getSummaryFromResult(ToolResult result) {
        if (result == null) {
            return "";
        }

        String displayContent = result.getDisplayContent();
        if (displayContent != null && !displayContent.isEmpty()) {
            return displayContent;
        }

        // 如果没有 displayContent，使用 displayTitle
        String displayTitle = result.getDisplayTitle();
        if (displayTitle != null && !displayTitle.isEmpty()) {
            return displayTitle;
        }

        return "";
    }
}
