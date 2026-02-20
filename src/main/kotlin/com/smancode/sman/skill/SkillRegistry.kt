package com.smancode.sman.skill

import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Skill 注册中心
 *
 * 管理所有已加载的 Skill，提供查询和获取功能。
 * 使用 ConcurrentHashMap 保证线程安全。
 */
class SkillRegistry {

    private val logger = LoggerFactory.getLogger(SkillRegistry::class.java)

    /**
     * Skill 名称 -> SkillInfo 映射
     */
    private val skillMap: ConcurrentHashMap<String, SkillInfo> = ConcurrentHashMap()

    /**
     * Skill 加载器
     */
    private val skillLoader = SkillLoader()

    /**
     * 是否已初始化
     */
    @Volatile
    private var initialized = false

    /**
     * 初始化 Skill 注册中心
     *
     * @param projectPath 项目路径
     */
    fun initialize(projectPath: String) {
        if (initialized) {
            logger.warn("SkillRegistry 已初始化，跳过重复初始化")
            return
        }

        logger.info("开始初始化 SkillRegistry, projectPath={}", projectPath)

        val skills = skillLoader.loadAll(projectPath)
        skills.forEach { register(it) }

        initialized = true
        logger.info("SkillRegistry 初始化完成，共注册 {} 个 Skill", skillMap.size)
    }

    /**
     * 注册 Skill
     *
     * @param skill Skill 信息
     */
    fun register(skill: SkillInfo) {
        if (!skill.isValid()) {
            logger.warn("尝试注册无效的 Skill: name={}", skill.name)
            return
        }

        val existing = skillMap.put(skill.name, skill)
        if (existing != null) {
            logger.debug("覆盖已存在的 Skill: name={}", skill.name)
        } else {
            logger.debug("注册 Skill: name={}", skill.name)
        }
    }

    /**
     * 批量注册 Skill
     *
     * @param skills Skill 列表
     */
    fun registerAll(skills: List<SkillInfo>) {
        skills.forEach { register(it) }
        logger.info("批量注册完成，共 {} 个 Skill，当前总数：{}", skills.size, skillMap.size)
    }

    /**
     * 根据名称获取 Skill
     *
     * @param name Skill 名称
     * @return Skill 信息，不存在返回 null
     */
    fun get(name: String): SkillInfo? {
        return skillMap[name]
    }

    /**
     * 获取所有 Skill
     *
     * @return 所有 Skill 列表
     */
    fun getAll(): List<SkillInfo> {
        return skillMap.values.toList()
    }

    /**
     * 获取所有 Skill 名称
     *
     * @return 所有 Skill 名称列表
     */
    fun getSkillNames(): List<String> {
        return skillMap.keys.toList()
    }

    /**
     * 检查 Skill 是否存在
     *
     * @param name Skill 名称
     * @return 是否存在
     */
    fun hasSkill(name: String): Boolean {
        return skillMap.containsKey(name)
    }

    /**
     * 获取 Skill 数量
     *
     * @return Skill 数量
     */
    fun size(): Int {
        return skillMap.size
    }

    /**
     * 清空所有 Skill（用于重新加载）
     */
    fun clear() {
        skillMap.clear()
        initialized = false
        logger.info("SkillRegistry 已清空")
    }

    /**
     * 重新加载 Skill
     *
     * @param projectPath 项目路径
     */
    fun reload(projectPath: String) {
        logger.info("重新加载 Skill...")
        clear()
        initialize(projectPath)
    }

    /**
     * 检查是否已初始化
     *
     * @return 是否已初始化
     */
    fun isInitialized(): Boolean {
        return initialized
    }

    /**
     * 获取 Skill 加载器
     *
     * @return Skill 加载器
     */
    fun getLoader(): SkillLoader {
        return skillLoader
    }
}
