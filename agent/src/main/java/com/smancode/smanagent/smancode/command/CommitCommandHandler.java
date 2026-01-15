package com.smancode.smanagent.smancode.command;

import com.smancode.smanagent.model.session.Session;
import com.smancode.smanagent.service.SessionFileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Commit 命令处理器
 * <p>
 * 处理 /commit 命令，生成 commit message 和文件列表
 */
@Component
public class CommitCommandHandler {

    private static final Logger logger = LoggerFactory.getLogger(CommitCommandHandler.class);

    @Autowired
    private FileChangeTracker fileChangeTracker;

    @Autowired
    private CommitMessageGenerator messageGenerator;

    @Autowired
    private SessionFileService sessionFileService;

    /**
     * 处理 /commit 命令
     *
     * @param sessionId 会话 ID
     * @return commit 命令结果
     */
    public CommitCommandResult handle(String sessionId) {
        logger.info("【Commit命令处理】开始处理: sessionId={}", sessionId);

        // 1. 加载会话
        Session session = sessionFileService.loadSession(sessionId);
        if (session == null) {
            logger.warn("【Commit命令处理】会话不存在: sessionId={}", sessionId);
            return createEmptyResult();
        }

        logger.info("【Commit命令处理】会话已加载: sessionId={}, 消息数={}",
                sessionId, session.getMessages() != null ? session.getMessages().size() : 0);

        // 2. 提取文件变更
        List<FileChange> changes = fileChangeTracker.extractFileChanges(session);

        if (changes.isEmpty()) {
            logger.info("【Commit命令处理】没有文件变更，返回空结果");
            return createEmptyResult();
        }

        logger.info("【Commit命令处理】提取到 {} 个文件变更", changes.size());

        // 3. 生成 commit message
        String commitMessage = messageGenerator.generate(changes, session);
        logger.info("【Commit命令处理】生成commit message: {}", commitMessage);

        // 4. 按类型分组文件路径
        List<String> addFiles = new ArrayList<>();
        List<String> modifyFiles = new ArrayList<>();
        List<String> deleteFiles = new ArrayList<>();

        for (FileChange change : changes) {
            switch (change.getType()) {
                case ADD -> {
                    if (change.getRelativePath() != null) {
                        addFiles.add(change.getRelativePath());
                    }
                }
                case MODIFY -> {
                    if (change.getRelativePath() != null) {
                        modifyFiles.add(change.getRelativePath());
                    }
                }
                case DELETE -> {
                    if (change.getRelativePath() != null) {
                        deleteFiles.add(change.getRelativePath());
                    }
                }
            }
        }

        CommitCommandResult result = new CommitCommandResult(commitMessage, addFiles, modifyFiles, deleteFiles);

        logger.info("【Commit命令处理】命令处理完成: commitMessage={}, add={}, modify={}, delete={}",
                commitMessage, addFiles.size(), modifyFiles.size(), deleteFiles.size());

        return result;
    }

    /**
     * 创建空结果
     */
    private CommitCommandResult createEmptyResult() {
        return new CommitCommandResult(
                "#AI commit# 无变更",
                List.of(),
                List.of(),
                List.of()
        );
    }
}
