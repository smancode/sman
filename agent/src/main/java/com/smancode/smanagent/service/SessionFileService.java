package com.smancode.smanagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.smancode.smanagent.model.session.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 会话文件服务
 * <p>
 * 负责将会话数据持久化到文件系统。
 */
@Service
public class SessionFileService {

    private static final Logger logger = LoggerFactory.getLogger(SessionFileService.class);

    private final ObjectMapper objectMapper;

    public SessionFileService() {
        this.objectMapper = new ObjectMapper();
        // 注册 JavaTimeModule 以支持 Instant 等类型
        this.objectMapper.registerModule(new JavaTimeModule());
        // 禁用将日期写为时间戳
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        // 美化输出
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * 会话存储目录
     */
    private static final String SESSION_DIR = System.getProperty("user.home") + File.separator + ".smanagent" + File.separator + "sessions";

    /**
     * 加载会话
     *
     * @param sessionId 会话 ID
     * @return 会话，如果不存在返回 null
     */
    public Session loadSession(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return null;
        }

        try {
            Path sessionPath = Paths.get(SESSION_DIR, sessionId + ".json");
            if (!Files.exists(sessionPath)) {
                logger.debug("会话文件不存在: {}", sessionPath);
                return null;
            }

            return objectMapper.readValue(sessionPath.toFile(), Session.class);
        } catch (IOException e) {
            logger.error("加载会话失败: sessionId={}", sessionId, e);
            return null;
        }
    }

    /**
     * 保存会话
     *
     * @param session 会话
     */
    public void saveSession(Session session) {
        if (session == null || session.getId() == null || session.getId().isEmpty()) {
            logger.warn("会话或会话 ID 为空，无法保存");
            return;
        }

        try {
            // 确保目录存在
            Path dirPath = Paths.get(SESSION_DIR);
            if (!Files.exists(dirPath)) {
                Files.createDirectories(dirPath);
            }

            // 保存会话
            Path sessionPath = Paths.get(SESSION_DIR, session.getId() + ".json");
            objectMapper.writeValue(sessionPath.toFile(), session);

            logger.debug("保存会话成功: sessionId={}", session.getId());
        } catch (IOException e) {
            logger.error("保存会话失败: sessionId={}", session.getId(), e);
        }
    }

    /**
     * 删除会话
     *
     * @param sessionId 会话 ID
     */
    public void deleteSession(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return;
        }

        try {
            Path sessionPath = Paths.get(SESSION_DIR, sessionId + ".json");
            if (Files.exists(sessionPath)) {
                Files.delete(sessionPath);
                logger.debug("删除会话成功: sessionId={}", sessionId);
            }
        } catch (IOException e) {
            logger.error("删除会话失败: sessionId={}", sessionId, e);
        }
    }

    /**
     * 检查会话是否存在
     *
     * @param sessionId 会话 ID
     * @return 是否存在
     */
    public boolean exists(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return false;
        }

        Path sessionPath = Paths.get(SESSION_DIR, sessionId + ".json");
        return Files.exists(sessionPath);
    }
}
