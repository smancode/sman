package com.smancode.sman.ide.service

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.smancode.sman.model.session.Session
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * 会话文件服务
 *
 * 负责将会话数据持久化到文件系统。
 * 会话按 projectKey 隔离存储：~/.smanunion/sessions/{projectKey}/{sessionId}.json
 */
object SessionFileService {

    private val logger = LoggerFactory.getLogger(SessionFileService::class.java)

    private val objectMapper: ObjectMapper = ObjectMapper().apply {
        // 注册 JavaTimeModule 以支持 Instant 等类型
        registerModule(JavaTimeModule())
        // 禁用将日期写为时间戳
        disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        // 美化输出
        enable(SerializationFeature.INDENT_OUTPUT)
        // 忽略未知属性（兼容旧版本会话文件）
        disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
    }

    /**
     * 会话存储目录（按项目隔离）
     */
    private fun getSessionDir(projectKey: String): String {
        val baseDir = System.getProperty("user.home") +
            File.separator + ".smanunion" +
            File.separator + "sessions"

        // 按 projectKey 创建子目录
        val projectDir = File(baseDir, projectKey)

        // 如果目录不存在，创建它
        if (!projectDir.exists()) {
            projectDir.mkdirs()
            logger.info("创建项目会话目录: {}", projectDir.absolutePath)
        }

        return projectDir.absolutePath
    }

    /**
     * 加载会话
     *
     * @param sessionId 会话 ID
     * @param projectKey 项目 key
     * @return 会话，如果不存在返回 null
     */
    fun loadSession(sessionId: String?, projectKey: String): Session? {
        if (sessionId.isNullOrEmpty()) {
            return null
        }

        return try {
            val sessionDir = getSessionDir(projectKey)
            val sessionPath = Paths.get(sessionDir, "$sessionId.json")
            if (!Files.exists(sessionPath)) {
                logger.debug("会话文件不存在: {}", sessionPath)
                return null
            }

            val session = objectMapper.readValue(sessionPath.toFile(), Session::class.java)
            logger.info("成功加载会话: projectKey={}, sessionId={}, 消息数={}",
                projectKey, sessionId, session.messages.size)
            session
        } catch (e: Exception) {
            logger.error("加载会话失败: projectKey={}, sessionId={}, {}",
                projectKey, sessionId, e.message, e)
            null
        }
    }

    /**
     * 保存会话
     *
     * @param session 会话
     * @param projectKey 项目 key
     */
    fun saveSession(session: Session?, projectKey: String) {
        if (session == null || session.id.isNullOrEmpty()) {
            logger.warn("会话或会话 ID 为空，无法保存")
            return
        }

        try {
            val sessionDir = getSessionDir(projectKey)
            val sessionPath = Paths.get(sessionDir, "${session.id}.json")
            objectMapper.writeValue(sessionPath.toFile(), session)

            logger.debug("保存会话成功: projectKey={}, sessionId={}, 消息数={}",
                projectKey, session.id, session.messages.size)
        } catch (e: Exception) {
            logger.error("保存会话失败: projectKey={}, sessionId={}, {}",
                projectKey, session.id, e.message, e)
        }
    }

    /**
     * 删除会话
     *
     * @param sessionId 会话 ID
     * @param projectKey 项目 key
     */
    fun deleteSession(sessionId: String?, projectKey: String) {
        if (sessionId.isNullOrEmpty()) {
            return
        }

        try {
            val sessionDir = getSessionDir(projectKey)
            val sessionPath = Paths.get(sessionDir, "$sessionId.json")
            if (Files.exists(sessionPath)) {
                Files.delete(sessionPath)
                logger.info("删除会话成功: projectKey={}, sessionId={}", projectKey, sessionId)
            }
        } catch (e: Exception) {
            logger.error("删除会话失败: projectKey={}, sessionId={}, {}",
                projectKey, sessionId, e.message, e)
        }
    }

    /**
     * 检查会话是否存在
     *
     * @param sessionId 会话 ID
     * @param projectKey 项目 key
     * @return 是否存在
     */
    fun exists(sessionId: String?, projectKey: String): Boolean {
        if (sessionId.isNullOrEmpty()) {
            return false
        }

        val sessionPath = Paths.get(getSessionDir(projectKey), "$sessionId.json")
        return Files.exists(sessionPath)
    }

    /**
     * 获取指定项目的所有会话 ID
     *
     * @param projectKey 项目 key
     * @return 会话 ID 列表
     */
    fun getAllSessionIds(projectKey: String): List<String> {
        return try {
            val sessionDir = File(getSessionDir(projectKey))
            if (!sessionDir.exists()) {
                return emptyList()
            }

            sessionDir.listFiles()
                ?.mapNotNull { file ->
                    if (file.extension == "json") {
                        file.nameWithoutExtension
                    } else {
                        null
                    }
                }
                ?.sorted()
                ?: emptyList()
        } catch (e: Exception) {
            logger.error("获取会话列表失败: projectKey={}, {}", projectKey, e.message, e)
            emptyList()
        }
    }

    /**
     * 加载指定项目的所有会话
     *
     * @param projectKey 项目 key
     * @return 会话列表
     */
    fun loadAllSessions(projectKey: String): List<Session> {
        val sessionIds = getAllSessionIds(projectKey)
        return sessionIds.mapNotNull { loadSession(it, projectKey) }
    }
}
