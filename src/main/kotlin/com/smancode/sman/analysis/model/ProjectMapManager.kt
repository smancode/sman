package com.smancode.sman.analysis.model

import kotlinx.serialization.SerializationException
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption

/**
 * 项目映射管理器
 *
 * 负责管理 ~/.sman/project_map.json 文件的读写
 */
object ProjectMapManager {
    private val logger = LoggerFactory.getLogger(ProjectMapManager::class.java)

    /**
     * 项目映射文件路径
     * ~/.sman/project_map.json
     */
    private val projectMapFile: Path
        get() = Paths.get(System.getProperty("user.home"), ".sman", "project_map.json")

    /**
     * Sman 根目录
     * ~/.sman/
     */
    private val smanRootDir: Path
        get() = Paths.get(System.getProperty("user.home"), ".sman")

    /**
     * 加载项目映射
     *
     * @return ProjectMap 对象，如果文件不存在或解析失败返回空 ProjectMap
     */
    fun loadProjectMap(): ProjectMap {
        return try {
            // 检查文件是否存在
            if (!Files.exists(projectMapFile)) {
                logger.debug("项目映射文件不存在: {}", projectMapFile)
                return ProjectMap.empty()
            }

            // 读取文件内容
            val content = Files.readString(projectMapFile)
            if (content.isBlank()) {
                logger.debug("项目映射文件为空")
                return ProjectMap.empty()
            }

            // 解析 JSON
            val projectMap = ProjectMap.fromJson(content)
            logger.debug("成功加载项目映射，项目数: {}", projectMap.projects.size)
            projectMap

        } catch (e: SerializationException) {
            logger.warn("解析项目映射文件失败，返回空映射: {}", e.message)
            ProjectMap.empty()
        } catch (e: Exception) {
            logger.error("加载项目映射文件出错", e)
            ProjectMap.empty()
        }
    }

    /**
     * 保存项目映射
     *
     * @param map 要保存的 ProjectMap 对象
     */
    fun saveProjectMap(map: ProjectMap) {
        try {
            // 确保目录存在
            ensureDirectoryExists(smanRootDir)

            // 转换为 JSON 并保存
            val jsonContent = map.withTimestamp().toJson()
            Files.writeString(
                projectMapFile,
                jsonContent,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            )

            logger.debug("项目映射已保存: {}", projectMapFile)

        } catch (e: Exception) {
            logger.error("保存项目映射文件失败", e)
            throw IllegalStateException("保存项目映射失败: ${e.message}", e)
        }
    }

    /**
     * 更新单个项目的分析状态
     *
     * @param projectKey 项目标识符
     * @param status 新的分析状态
     */
    fun updateProjectStatus(projectKey: String, status: AnalysisStatus) {
        try {
            val map = loadProjectMap()
            val existingEntry = map.projects[projectKey]

            val updatedEntry = if (existingEntry != null) {
                existingEntry.copy(analysisStatus = status)
            } else {
                throw IllegalArgumentException("项目不存在: $projectKey")
            }

            val updatedMap = map.copy(projects = map.projects + (projectKey to updatedEntry))
            saveProjectMap(updatedMap)

            logger.debug("项目状态已更新: projectKey={}, status={}", projectKey, status)

        } catch (e: Exception) {
            logger.error("更新项目状态失败: projectKey={}", projectKey, e)
            throw e
        }
    }

    /**
     * 更新单个项目的单个分析类型状态
     *
     * @param projectKey 项目标识符
     * @param type 分析类型
     * @param state 步骤状态
     */
    fun updateAnalysisStepState(projectKey: String, type: AnalysisType, state: StepState) {
        try {
            val map = loadProjectMap()
            val existingEntry = map.projects[projectKey]

            val updatedEntry = if (existingEntry != null) {
                existingEntry.withAnalysisStatus(type, state)
            } else {
                throw IllegalArgumentException("项目不存在: $projectKey")
            }

            val updatedMap = map.copy(projects = map.projects + (projectKey to updatedEntry))
            saveProjectMap(updatedMap)

            logger.debug("分析步骤状态已更新: projectKey={}, type={}, state={}", projectKey, type.key, state)

        } catch (e: Exception) {
            logger.error("更新分析步骤状态失败: projectKey={}, type={}", projectKey, type.key, e)
            throw e
        }
    }

    /**
     * 获取单个项目的信息
     *
     * @param projectKey 项目标识符
     * @return ProjectEntry 对象，如果不存在返回 null
     */
    fun getProjectEntry(projectKey: String): ProjectEntry? {
        val map = loadProjectMap()
        return map.projects[projectKey]
    }

    /**
     * 注册新项目
     *
     * @param projectKey 项目标识符
     * @param projectPath 项目路径
     * @param projectMd5 项目内容 MD5
     */
    fun registerProject(projectKey: String, projectPath: String, projectMd5: String) {
        try {
            val map = loadProjectMap()
            val newEntry = ProjectEntry(
                path = projectPath,
                lastAnalyzed = System.currentTimeMillis(),
                projectMd5 = projectMd5,
                analysisStatus = AnalysisStatus.pending()
            )

            val updatedMap = map.withProject(projectKey, newEntry).withTimestamp()
            saveProjectMap(updatedMap)

            logger.info("项目已注册: projectKey={}, path={}", projectKey, projectPath)

        } catch (e: Exception) {
            logger.error("注册项目失败: projectKey={}", projectKey, e)
            throw e
        }
    }

    /**
     * 移除项目
     *
     * @param projectKey 项目标识符
     */
    fun removeProject(projectKey: String) {
        try {
            val map = loadProjectMap()
            if (!map.projects.containsKey(projectKey)) {
                logger.warn("项目不存在，无法移除: {}", projectKey)
                return
            }

            val updatedMap = map.copy(projects = map.projects - projectKey)
            saveProjectMap(updatedMap)

            logger.info("项目已移除: {}", projectKey)

        } catch (e: Exception) {
            logger.error("移除项目失败: projectKey={}", projectKey, e)
            throw e
        }
    }

    /**
     * 更新项目的 MD5 值
     *
     * @param projectKey 项目标识符
     * @param md5 新的 MD5 值
     */
    fun updateProjectMd5(projectKey: String, md5: String) {
        try {
            val map = loadProjectMap()
            val existingEntry = map.projects[projectKey]

            val updatedEntry = if (existingEntry != null) {
                existingEntry.withProjectMd5(md5)
            } else {
                throw IllegalArgumentException("项目不存在: $projectKey")
            }

            val updatedMap = map.copy(projects = map.projects + (projectKey to updatedEntry))
            saveProjectMap(updatedMap)

            logger.debug("项目 MD5 已更新: projectKey={}, md5={}", projectKey, md5)

        } catch (e: Exception) {
            logger.error("更新项目 MD5 失败: projectKey={}", projectKey, e)
            throw e
        }
    }

    /**
     * 检查项目是否已注册
     *
     * @param projectKey 项目标识符
     * @return true 如果项目已存在
     */
    fun isProjectRegistered(projectKey: String): Boolean {
        val map = loadProjectMap()
        return map.projects.containsKey(projectKey)
    }

    /**
     * 获取所有已注册的项目
     *
     * @return 项目标识符列表
     */
    fun getAllProjectKeys(): List<String> {
        val map = loadProjectMap()
        return map.projects.keys.toList()
    }

    /**
     * 确保目录存在
     */
    private fun ensureDirectoryExists(directory: Path) {
        if (!Files.exists(directory)) {
            try {
                Files.createDirectories(directory)
                logger.debug("目录已创建: {}", directory)
            } catch (e: Exception) {
                logger.error("创建目录失败: {}", directory, e)
                throw e
            }
        }
    }
}
