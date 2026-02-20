package com.smancode.sman.skill

/**
 * Skill 信息模型
 *
 * 代表一个可加载的专业化技能，提供领域特定的指令和工作流。
 *
 * @property name Skill 名称（唯一标识），1-64 字符，小写字母数字，单连字符分隔
 * @property description 描述信息，用于 LLM 决策，1-1024 字符
 * @property location SKILL.md 文件的绝对路径
 * @property content Markdown 内容（不含 YAML frontmatter）
 * @property metadata 可选的元数据（license, author, version 等）
 */
data class SkillInfo(
    val name: String,
    val description: String,
    val location: String,
    val content: String,
    val metadata: Map<String, String> = emptyMap()
) {
    /**
     * 获取 Skill 的基础目录（SKILL.md 所在目录）
     */
    val baseDirectory: String
        get() = java.io.File(location).parentFile?.absolutePath ?: ""

    /**
     * 验证 Skill 信息是否有效
     */
    fun isValid(): Boolean {
        return name.isNotBlank() &&
                description.isNotBlank() &&
                location.isNotBlank() &&
                content.isNotBlank()
    }

    companion object {
        /**
         * Skill 名称验证正则：小写字母数字，单连字符分隔
         */
        val NAME_PATTERN = Regex("^[a-z0-9]+(-[a-z0-9]+)*$")

        /**
         * 名称最大长度
         */
        const val MAX_NAME_LENGTH = 64

        /**
         * 描述最大长度
         */
        const val MAX_DESCRIPTION_LENGTH = 1024
    }
}
