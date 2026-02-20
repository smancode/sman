package com.smancode.sman.skill

import com.smancode.sman.tools.AbstractTool
import com.smancode.sman.tools.ParameterDef
import com.smancode.sman.tools.ToolResult
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Skill 工具
 *
 * 允许 LLM 加载专业化的技能，获取领域特定的指令和工作流。
 *
 * 工具描述动态生成，包含当前所有可用的 Skill 列表。
 */
class SkillTool(
    private val skillRegistry: SkillRegistry
) : AbstractTool() {

    private val logger = LoggerFactory.getLogger(SkillTool::class.java)

    companion object {
        const val TOOL_NAME = "skill"
    }

    override fun getName(): String = TOOL_NAME

    override fun getDescription(): String {
        return buildDynamicDescription()
    }

    override fun getParameters(): Map<String, ParameterDef> {
        return mapOf(
            "name" to ParameterDef(
                "name",
                String::class.java,
                true,
                "要加载的 Skill 名称（从可用列表中选择）"
            )
        )
    }

    override fun execute(projectKey: String, params: Map<String, Any>): ToolResult {
        val skillName = getReqString(params, "name")

        logger.info("执行 Skill 工具: name={}", skillName)

        val skill = skillRegistry.get(skillName)
        if (skill == null) {
            val availableSkills = skillRegistry.getSkillNames()
            val errorMsg = if (availableSkills.isEmpty()) {
                "Skill \"$skillName\" 不存在。当前没有可用的 Skill。"
            } else {
                "Skill \"$skillName\" 不存在。可用的 Skill: ${availableSkills.joinToString(", ")}"
            }
            logger.warn(errorMsg)
            return ToolResult.failure(errorMsg)
        }

        // 构建输出内容
        val output = buildSkillOutput(skill)

        logger.info("Skill 加载成功: name={}", skillName)

        return ToolResult.success(
            data = output,
            displayTitle = "已加载 Skill: ${skill.name}",
            displayContent = "✅ 已加载 Skill: ${skill.name}\n\n${skill.description}"
        )
    }

    /**
     * 构建动态工具描述
     *
     * 包含当前所有可用的 Skill 列表
     */
    private fun buildDynamicDescription(): String {
        val skills = skillRegistry.getAll()

        if (skills.isEmpty()) {
            return """
                加载专业化的技能，获取领域特定的指令和工作流。

                当前没有可用的 Skill。

                Skill 文件应放置在以下目录：
                - ~/.claude/skills/<name>/SKILL.md（全局）
                - <project>/.claude/skills/<name>/SKILL.md（项目级）
                - <project>/.sman/skills/<name>/SKILL.md（项目级）
            """.trimIndent()
        }

        val skillListXml = skills.joinToString("\n") { skill ->
            """  <skill>
    <name>${escapeXml(skill.name)}</name>
    <description>${escapeXml(skill.description)}</description>
    <location>${escapeXml(skill.location)}</location>
  </skill>"""
        }

        val examples = skills.take(3).map { "'${it.name}'" }.joinToString(", ")
        val hint = if (examples.isNotEmpty()) " (例如: $examples, ...)" else ""

        return """
            加载专业化的技能，获取领域特定的指令和工作流。

            当你识别到任务匹配以下可用技能时，使用此工具加载完整的技能指令。

            技能将注入详细的指令、工作流和关联资源（脚本、参考文档、模板）到对话上下文中。

            工具输出包含 `<skill_content name="...">` 块，其中包含加载的内容。

            以下技能为特定任务提供专业化的指令集，当任务匹配以下可用技能时调用：

            <available_skills>
$skillListXml
            </available_skills>

            参数 name$hint：从上面可用列表中选择要加载的技能名称。
        """.trimIndent()
    }

    /**
     * 构建 Skill 输出内容
     */
    private fun buildSkillOutput(skill: SkillInfo): String {
        val baseDir = File(skill.baseDirectory)
        val baseUrl = baseDir.toURI().toString()

        // 获取关联文件
        val files = skillRegistry.getLoader().getSkillFiles(skill, 10)
        val filesXml = if (files.isNotEmpty()) {
            files.joinToString("\n") { "  <file>$it</file>" }
        } else {
            "  <!-- 没有关联文件 -->"
        }

        return """
<skill_content name="${escapeXml(skill.name)}">
# Skill: ${skill.name}

${skill.content}

Base directory for this skill: $baseUrl
Relative paths in this skill (e.g., scripts/, reference/) are relative to this base directory.
Note: file list is sampled.

<skill_files>
$filesXml
</skill_files>
</skill_content>
        """.trimIndent()
    }

    /**
     * XML 转义
     */
    private fun escapeXml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
