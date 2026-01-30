package com.smancode.smanagent.ide

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import com.smancode.smanagent.ide.service.StorageService
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SmanAgent 插件主入口
 * 负责启动后端 Agent 进程并初始化 WebSocket 连接
 */
class SmanAgentPlugin : StartupActivity {

    private val logger: Logger = LoggerFactory.getLogger(SmanAgentPlugin::class.java)
    private val backendStarted = AtomicBoolean(false)

    override fun runActivity(project: Project) {
        logger.info("SmanAgent Plugin started for project: {}", project.name)

        // 检查后端是否已运行
        if (isPortAvailable(8080)) {
            logger.info("端口 8080 可用，准备启动后端 Agent")
            startBackendAgent()
        } else {
            logger.info("端口 8080 已被占用，后端 Agent 可能已运行")
            backendStarted.set(true)
        }

        // 初始化 StorageService
        val storageService = project.service<StorageService>()
        logger.info("StorageService 已初始化")
    }

    /**
     * 检查端口是否可用
     */
    private fun isPortAvailable(port: Int): Boolean {
        return try {
            Socket("localhost", port).close()
            false // 端口已被占用
        } catch (e: Exception) {
            true // 端口可用
        }
    }

    /**
     * 启动后端 Agent 进程
     */
    private fun startBackendAgent() {
        if (!backendStarted.compareAndSet(false, true)) {
            return // 已经启动过
        }

        try {
            val agentJar = findAgentJar()
            if (agentJar == null || !agentJar.exists()) {
                logger.warn("找不到后端 Agent JAR 文件，跳过启动")
                return
            }

            logger.info("启动后端 Agent: {}", agentJar.absolutePath)

            val processBuilder = ProcessBuilder(
                "java",
                "-Xmx512m",
                "-Xms256m",
                "-jar",
                agentJar.absolutePath,
                "--server.port=8080"
            )

            processBuilder.redirectErrorStream(true)
            processBuilder.directory(File(System.getProperty("user.home")))

            val process = processBuilder.start()

            // 读取输出日志
            Thread {
                process.inputStream.bufferedReader().use { reader ->
                    reader.forEachLine { line ->
                        logger.debug("[Backend] $line")
                    }
                }
            }.start()

            // 等待后端启动（最多等待 30 秒）
            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < 30000) {
                Thread.sleep(500)
                if (!isPortAvailable(8080)) {
                    logger.info("后端 Agent 启动成功")
                    return
                }
            }

            logger.warn("后端 Agent 启动超时")

        } catch (e: Exception) {
            logger.error("启动后端 Agent 失败", e)
            backendStarted.set(false)
        }
    }

    /**
     * 查找后端 Agent JAR 文件
     */
    private fun findAgentJar(): File? {
        val pluginLib = File(File(javaClass.protectionDomain.codeSource.location.toURI()).parent, "lib")
        val jarFile = File(pluginLib, "smanagent-agent-1.0.0.jar")

        if (jarFile.exists()) {
            return jarFile
        }

        logger.warn("后端 JAR 文件不存在: {}", jarFile.absolutePath)
        return null
    }

    companion object {
        const val PLUGIN_NAME = "SmanAgent"
        const val VERSION = "1.1.0"
        const val DEFAULT_WS_URL = "ws://localhost:8080/ws/agent"
    }
}
