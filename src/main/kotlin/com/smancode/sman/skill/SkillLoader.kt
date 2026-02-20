package com.smancode.sman.skill

import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Skill 加载器
 *
 * 负责扫描多个目录，解析 SKILL.md 文件，加载 Skill 信息。
 *
 * 扫描目录优先级（从低到高，后者覆盖前者）：
 * 1. ~/.claude/skills/<name>/SKILL.md（全局 Claude 兼容）
 * 2. ~/.agents/skills/<name>/SKILL.md（全局 Agent 兼容）
 * 3. <project>/.claude/skills/<name>/SKILL.md（项目 Claude 兼容）
 * 4. <project>/.agents/skills/<name>/SKILL.md（项目 Agent 兼容）
 * 5. <project>/.sman/skills/<name>/SKILL.md（项目 SmanCode 专用）
 */
class SkillLoader {

    private val logger = LoggerFactory.getLogger(SkillLoader::class.java)

    /**
     * 外部兼容目录（.claude, .agents）
     */
    private val externalDirs = listOf(".claude", ".agents")

    /**
     * Skill 文件名
     */
    private val skillFileName = "SKILL.md"

    /**
     * 加载所有 Skill
     *
     * @param projectPath 项目路径
     * @return 加载的 Skill 列表
     */
    fun loadAll(projectPath: String): List<SkillInfo> {
        val skills = mutableMapOf<String, SkillInfo>()

        // 1. 扫描全局目录
        val homeDir = System.getProperty("user.home")
        for (dir in externalDirs) {
            val globalSkillPath = Paths.get(homeDir, dir, "skills")
            loadFromDirectory(globalSkillPath, skills, "global")
        }

        // 2. 扫描项目目录
        for (dir in externalDirs) {
            val projectSkillPath = Paths.get(projectPath, dir, "skills")
            loadFromDirectory(projectSkillPath, skills, "project")
        }

        // 3. 扫描 .sman/skills 目录
        val smanSkillPath = Paths.get(projectPath, ".sman", "skills")
        loadFromDirectory(smanSkillPath, skills, "project")

        logger.info("Skill 加载完成: 共加载 {} 个 Skill", skills.size)
        return skills.values.toList()
    }

    /**
     * 从指定目录加载 Skill
     *
     * @param directory 目录路径
     * @param skills Skill 映射（用于去重和覆盖）
     * @param scope 作用域（global/project）
     */
    private fun loadFromDirectory(
        directory: Path,
        skills: MutableMap<String, SkillInfo>,
        scope: String
    ) {
        if (!Files.exists(directory) || !Files.isDirectory(directory)) {
            return
        }

        try {
            Files.walk(directory)
                .filter { it.fileName.toString() == skillFileName }
                .forEach { skillFile ->
                    loadSkillFile(skillFile, skills, scope)
                }
        } catch (e: Exception) {
            logger.error("扫描 Skill 目录失败: directory={}, error={}", directory, e.message)
        }
    }

    /**
     * 加载单个 Skill 文件
     *
     * @param skillFile SKILL.md 文件路径
     * @param skills Skill 映射
     * @param scope 作用域
     */
    private fun loadSkillFile(
        skillFile: Path,
        skills: MutableMap<String, SkillInfo>,
        scope: String
    ) {
        try {
            val content = Files.readString(skillFile)
            val skillInfo = parseSkillMarkdown(content, skillFile.toString())

            if (skillInfo == null) {
                logger.warn("解析 Skill 文件失败: {}", skillFile)
                return
            }

            if (!skillInfo.isValid()) {
                logger.warn("Skill 信息无效: {}", skillFile)
                return
            }

            // 检查名称格式
            if (!SkillInfo.NAME_PATTERN.matches(skillInfo.name)) {
                logger.warn("Skill 名称格式无效: name={}, path={}", skillInfo.name, skillFile)
                return
            }

            // 检查重复
            if (skills.containsKey(skillInfo.name)) {
                logger.info("Skill 名称重复，覆盖: name={}, existing={}, new={}",
                    skillInfo.name, skills[skillInfo.name]!!.location, skillFile)
            }

            skills[skillInfo.name] = skillInfo
            logger.debug("加载 Skill: name={}, scope={}, path={}", skillInfo.name, scope, skillFile)

        } catch (e: Exception) {
            logger.error("读取 Skill 文件失败: {}, error={}", skillFile, e.message)
        }
    }

    /**
     * 解析 Skill Markdown 文件
     *
     * 文件格式：
     * ```markdown
     * ---
     * name: skill-name
     * description: Skill description
     * license: MIT
     * ---
     *
     * ## Content here
     * ```
     *
     * @param content 文件内容
     * @param location 文件路径
     * @return 解析后的 SkillInfo，失败返回 null
     */
    fun parseSkillMarkdown(content: String, location: String): SkillInfo? {
        // 提取 YAML frontmatter
        val frontmatterRegex = Regex("^---\\s*\n([\\s\\S]*?)\n---\\s*\n?([\\s\\S]*)$")
        val match = frontmatterRegex.find(content)

        if (match == null) {
            logger.warn("Skill 文件缺少 YAML frontmatter: {}", location)
            return null
        }

        val frontmatter = match.groupValues[1]
        val body = match.groupValues[2].trim()

        // 解析 frontmatter
        val metadata = parseYamlFrontmatter(frontmatter)

        val name = metadata["name"] as? String
        val description = metadata["description"] as? String

        if (name.isNullOrBlank() || description.isNullOrBlank()) {
            logger.warn("Skill 文件缺少必需字段: name={}, description={}, path={}", name, description, location)
            return null
        }

        // 验证长度限制
        if (name.length > SkillInfo.MAX_NAME_LENGTH) {
            logger.warn("Skill 名称过长: name={}, length={}, max={}", name, name.length, SkillInfo.MAX_NAME_LENGTH)
            return null
        }

        if (description.length > SkillInfo.MAX_DESCRIPTION_LENGTH) {
            logger.warn("Skill 描述过长: name={}, length={}, max={}", name, description.length, SkillInfo.MAX_DESCRIPTION_LENGTH)
            // 不返回 null，只是截断
        }

        // 提取其他元数据
        val additionalMetadata = metadata.filterKeys { it != "name" && it != "description" }
            .mapValues { it.value.toString() }

        return SkillInfo(
            name = name,
            description = description.take(SkillInfo.MAX_DESCRIPTION_LENGTH),
            location = location,
            content = body,
            metadata = additionalMetadata
        )
    }

    /**
     * 解析 YAML frontmatter（简化实现）
     *
     * 支持简单的 key: value 格式，不支持嵌套
     */
    private fun parseYamlFrontmatter(frontmatter: String): Map<String, Any> {
        val result = mutableMapOf<String, Any>()

        frontmatter.lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                return@forEach
            }

            val colonIndex = trimmed.indexOf(':')
            if (colonIndex < 0) {
                return@forEach
            }

            val key = trimmed.substring(0, colonIndex).trim()
            var value = trimmed.substring(colonIndex + 1).trim()

            // 移除引号
            if ((value.startsWith("\"") && value.endsWith("\"")) ||
                (value.startsWith("'") && value.endsWith("'"))) {
                value = value.substring(1, value.length - 1)
            }

            result[key] = value
        }

        return result
    }

    /**
     * 获取 Skill 目录下的关联文件
     *
     * @param skillInfo Skill 信息
     * @param limit 最大文件数
     * @return 文件路径列表
     */
    fun getSkillFiles(skillInfo: SkillInfo, limit: Int = 10): List<String> {
        val baseDir = File(skillInfo.baseDirectory)
        if (!baseDir.exists() || !baseDir.isDirectory) {
            return emptyList()
        }

        val files = mutableListOf<String>()

        try {
            baseDir.walk()
                .filter { it.isFile && it.name != skillFileName }
                .take(limit)
                .forEach { files.add(it.absolutePath) }
        } catch (e: Exception) {
            logger.error("获取 Skill 文件失败: {}, error={}", skillInfo.name, e.message)
        }

        return files
    }
}
