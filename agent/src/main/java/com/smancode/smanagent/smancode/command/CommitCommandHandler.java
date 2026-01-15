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

        logger.info("【Commit命令处理】会话已加载: sessionId={}, 消息数={}, lastCommitTime={}",
                sessionId, session.getMessages() != null ? session.getMessages().size() : 0, session.getLastCommitTime());

        // 2. 提取文件变更
        List<FileChange> changes = fileChangeTracker.extractFileChanges(session);

        if (changes.isEmpty()) {
            logger.info("【Commit命令处理】没有新文件变更，返回空结果");
            return createEmptyResult();
        }

        logger.info("【Commit命令处理】提取到 {} 个文件变更", changes.size());

        // 3. 生成 commit message
        String commitMessage = messageGenerator.generate(changes, session);
        logger.info("【Commit命令处理】生成commit message: {}", commitMessage);

        // 4. 按类型分组文件路径（去重）
        java.util.Set<String> addFilesSet = new java.util.HashSet<>();
        java.util.Set<String> modifyFilesSet = new java.util.HashSet<>();
        java.util.Set<String> deleteFilesSet = new java.util.HashSet<>();

        for (FileChange change : changes) {
            // 移除可能的 ~ 前缀
            String relativePath = change.getRelativePath();
            if (relativePath != null && relativePath.startsWith("~")) {
                relativePath = relativePath.substring(1);
            }

            switch (change.getType()) {
                case ADD -> {
                    if (relativePath != null) {
                        addFilesSet.add(relativePath);
                    }
                }
                case MODIFY -> {
                    if (relativePath != null) {
                        modifyFilesSet.add(relativePath);
                    }
                }
                case DELETE -> {
                    if (relativePath != null) {
                        deleteFilesSet.add(relativePath);
                    }
                }
            }
        }

        // 转换为 List 并排序
        List<String> addFiles = new java.util.ArrayList<>(addFilesSet);
        List<String> modifyFiles = new java.util.ArrayList<>(modifyFilesSet);
        List<String> deleteFiles = new java.util.ArrayList<>(deleteFilesSet);
        java.util.Collections.sort(addFiles);
        java.util.Collections.sort(modifyFiles);
        java.util.Collections.sort(deleteFiles);

        // 5. 更新 lastCommitTime（记录当前时间，表示本次 commit 已处理到此时间点）
        session.setLastCommitTime(java.time.Instant.now());
        sessionFileService.saveSession(session);
        logger.info("【Commit命令处理】已更新 lastCommitTime: {}", session.getLastCommitTime());

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
