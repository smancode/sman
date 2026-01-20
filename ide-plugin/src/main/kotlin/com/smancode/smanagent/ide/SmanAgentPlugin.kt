package com.smancode.smanagent.ide

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * SmanAgent 插件主入口
 */
class SmanAgentPlugin : StartupActivity {

    private val logger: Logger = LoggerFactory.getLogger(SmanAgentPlugin::class.java)

    override fun runActivity(project: Project) {
        logger.info("SmanAgent Plugin started for project: {}", project.name)
    }

    companion object {
        const val PLUGIN_NAME = "SmanAgent"
        const val VERSION = "1.1.0"
    }
}
