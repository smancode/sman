package com.smancode.sman.usage.converter

import com.smancode.sman.shared.model.Puzzle
import com.smancode.sman.shared.model.PuzzleStatus
import com.smancode.sman.shared.model.PuzzleType
import com.smancode.sman.skill.SkillInfo
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Puzzle → Skill 转换器
 *
 * 将已完成的 Puzzle 转换为 Skill 文件，供 LLM 使用。
 *
 * 转换规则：
 * - 只转换 COMPLETED 状态的 Puzzle
 * - PuzzleType 映射到 Skill name 前缀
 * - 自动生成描述
 * - 输出路径：{projectPath}/.sman/skills/{skill-name}/SKILL.md
 */
class PuzzleToSkillConverter(
    private val projectPath: String
) {
    companion object {
        private const val SKILLS_DIR = ".sman/skills"
        private const val SKILL_FILE = "SKILL.md"
        private const val FRONTMATTER_SEPARATOR = "---"

        /**
         * PuzzleType 到 Skill name 前缀的映射
         */
        private val TYPE_TO_SKILL_PREFIX = mapOf(
            PuzzleType.STRUCTURE to "project-structure",
            PuzzleType.TECH_STACK to "tech-stack",
            PuzzleType.API to "api-entry",
            PuzzleType.DATA to "data-model",
            PuzzleType.FLOW to "business-flow",
            PuzzleType.RULE to "business-rule"
        )

        /**
         * PuzzleType 到描述模板的映射
         */
        private val TYPE_TO_DESCRIPTION = mapOf(
            PuzzleType.STRUCTURE to "项目结构知识：模块划分、架构设计、包组织",
            PuzzleType.TECH_STACK to "技术栈知识：框架、依赖、中间件配置",
            PuzzleType.API to "API 入口知识：端点定义、接口规范、调用方式",
            PuzzleType.DATA to "数据模型知识：实体定义、表结构、字段说明",
            PuzzleType.FLOW to "业务流程知识：核心流程、调用链、状态转换",
            PuzzleType.RULE to "业务规则知识：校验逻辑、约束条件、计算规则"
        )
    }

    /**
     * 转换单个 Puzzle 为 Skill
     *
     * @param puzzle Puzzle 对象
     * @return SkillInfo 或 null（如果 Puzzle 未完成）
     */
    fun convert(puzzle: Puzzle): SkillInfo? {
        // 只转换已完成的 Puzzle
        if (puzzle.status != PuzzleStatus.COMPLETED) {
            return null
        }

        val skillName = generateSkillName(puzzle)
        val description = generateDescription(puzzle)
        val skillDir = getSkillDir(skillName)
        val skillFile = skillDir.resolve(SKILL_FILE)

        // 生成 Skill 内容
        val content = buildSkillContent(puzzle)

        return SkillInfo(
            name = skillName,
            description = description,
            location = skillFile.toString(),
            content = content,
            metadata = mapOf(
                "puzzleId" to puzzle.id,
                "puzzleType" to puzzle.type.name,
                "completeness" to puzzle.completeness.toString(),
                "confidence" to puzzle.confidence.toString(),
                "generatedAt" to System.currentTimeMillis().toString()
            )
        )
    }

    /**
     * 批量转换 Puzzle 列表
     *
     * @param puzzles Puzzle 列表
     * @return 转换成功的 Skill 列表
     */
    fun convertAll(puzzles: List<Puzzle>): List<SkillInfo> {
        return puzzles.mapNotNull { convert(it) }
    }

    /**
     * 保存 Skill 到文件系统
     *
     * @param skill Skill 信息
     * @return 保存的文件路径
     */
    fun save(skill: SkillInfo): Path {
        val skillDir = getSkillDir(skill.name)
        Files.createDirectories(skillDir)

        val skillFile = skillDir.resolve(SKILL_FILE)
        val fullContent = buildFullSkillFile(skill)

        Files.writeString(skillFile, fullContent)

        return skillFile
    }

    /**
     * 转换并保存（组合操作）
     *
     * @param puzzle Puzzle 对象
     * @return 保存的文件路径，或 null（如果转换失败）
     */
    fun convertAndSave(puzzle: Puzzle): Path? {
        val skill = convert(puzzle) ?: return null
        return save(skill)
    }

    /**
     * 批量转换并保存
     *
     * @param puzzles Puzzle 列表
     * @return 保存的文件路径列表
     */
    fun convertAndSaveAll(puzzles: List<Puzzle>): List<Path> {
        return puzzles.mapNotNull { convertAndSave(it) }
    }

    /**
     * 删除 Skill 文件
     *
     * @param skillName Skill 名称
     * @return 是否删除成功
     */
    fun delete(skillName: String): Boolean {
        val skillDir = getSkillDir(skillName)
        if (!Files.exists(skillDir)) {
            return false
        }

        return try {
            skillDir.toFile().deleteRecursively()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 获取已存在的 Skill 列表
     *
     * @return Skill 名称列表
     */
    fun listExisting(): List<String> {
        val skillsDir = File(projectPath, SKILLS_DIR)
        if (!skillsDir.exists()) {
            return emptyList()
        }

        return skillsDir.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { dir ->
                val skillFile = File(dir, SKILL_FILE)
                if (skillFile.exists()) dir.name else null
            }
            ?: emptyList()
    }

    // ========== 私有方法 ==========

    private fun generateSkillName(puzzle: Puzzle): String {
        val prefix = TYPE_TO_SKILL_PREFIX[puzzle.type] ?: puzzle.type.name.lowercase()
        // 使用 puzzle id 的后缀作为区分（如果有多个同类型）
        // 移除 "PUZZLE_" 前缀和类型名前缀
        val afterPuzzlePrefix = puzzle.id.removePrefix("PUZZLE_")
        val suffix = afterPuzzlePrefix.removePrefix("${puzzle.type.name}_")

        // 如果 suffix 等于类型名本身，说明没有额外后缀，只返回 prefix
        // 如果 suffix 不等于 afterPuzzlePrefix，说明有额外后缀
        return if (suffix.isNotBlank() && suffix != afterPuzzlePrefix) {
            "$prefix-${suffix.lowercase()}"
        } else {
            prefix
        }
    }

    private fun generateDescription(puzzle: Puzzle): String {
        val baseDesc = TYPE_TO_DESCRIPTION[puzzle.type] ?: "项目知识"
        val completeness = (puzzle.completeness * 100).toInt()
        val confidence = (puzzle.confidence * 100).toInt()
        return "$baseDesc（完成度 $completeness%，置信度 $confidence%）"
    }

    private fun getSkillDir(skillName: String): Path {
        return Path.of(projectPath, SKILLS_DIR, skillName)
    }

    private fun buildSkillContent(puzzle: Puzzle): String {
        val sb = StringBuilder()

        // 添加概述
        sb.appendLine("# ${getSkillTitle(puzzle.type)}")
        sb.appendLine()
        sb.appendLine("> 来源：${puzzle.id} | 完成度：${(puzzle.completeness * 100).toInt()}% | 置信度：${(puzzle.confidence * 100).toInt()}%")
        sb.appendLine()

        // 添加正文
        sb.append(puzzle.content)

        return sb.toString()
    }

    private fun buildFullSkillFile(skill: SkillInfo): String {
        return buildString {
            appendLine(FRONTMATTER_SEPARATOR)
            appendLine("name: ${skill.name}")
            appendLine("description: ${skill.description}")
            appendLine("puzzleId: ${skill.metadata["puzzleId"]}")
            appendLine("puzzleType: ${skill.metadata["puzzleType"]}")
            appendLine("generatedAt: ${skill.metadata["generatedAt"]}")
            appendLine(FRONTMATTER_SEPARATOR)
            appendLine()
            append(skill.content)
        }
    }

    private fun getSkillTitle(type: PuzzleType): String {
        return when (type) {
            PuzzleType.STRUCTURE -> "项目结构"
            PuzzleType.TECH_STACK -> "技术栈"
            PuzzleType.API -> "API 入口"
            PuzzleType.DATA -> "数据模型"
            PuzzleType.FLOW -> "业务流程"
            PuzzleType.RULE -> "业务规则"
        }
    }
}
