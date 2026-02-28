package com.smancode.sman.domain.puzzle

import com.smancode.sman.shared.model.Puzzle
import org.slf4j.LoggerFactory
import java.io.File
import java.security.MessageDigest
import java.time.Instant

/**
 * 知识库版本存储服务
 *
 * 负责：
 * - 版本的创建和管理
 * - 快照的保存和加载
 * - 变更检测
 *
 * 存储结构：
 * ```
 * .sman/versions/
 * ├── index.json           # 版本索引
 * └── snapshots/
 *     └── v1-xxx.json      # 快照文件
 * ```
 */
class KnowledgeBaseVersionStore(
    private val projectPath: String
) {
    private val logger = LoggerFactory.getLogger(KnowledgeBaseVersionStore::class.java)

    companion object {
        private const val VERSIONS_DIR = ".sman/versions"
        private const val SNAPSHOTS_DIR = "snapshots"
        private const val INDEX_FILE = "index.json"
    }

    /**
     * 创建新版本
     *
     * @param puzzles 当前所有拼图
     * @param trigger 触发类型
     * @param description 版本描述
     * @return 创建的版本
     * @throws IllegalArgumentException 如果参数不合法
     */
    fun createVersion(
        puzzles: List<Puzzle>,
        trigger: VersionTrigger,
        description: String?
    ): KnowledgeBaseVersion {
        require(puzzles.isNotEmpty() || trigger != VersionTrigger.ITERATION) {
            "ITERATION 触发必须有拼图数据"
        }

        val index = loadIndex()
        val versionNumber = index.currentVersion + 1
        val checksum = calculateChecksum(puzzles)
        val now = Instant.now()
        val id = KnowledgeBaseVersion.generateId(versionNumber, now)

        val version = KnowledgeBaseVersion(
            id = id,
            versionNumber = versionNumber,
            createdAt = now,
            puzzleCount = puzzles.size,
            checksum = checksum,
            description = description,
            trigger = trigger
        )

        saveSnapshot(version, puzzles)

        val newEntry = VersionEntry(
            id = version.id,
            versionNumber = version.versionNumber,
            createdAt = version.createdAt,
            puzzleCount = version.puzzleCount,
            checksum = version.checksum
        )
        val newIndex = index.copy(
            currentVersion = versionNumber,
            versions = index.versions + newEntry
        )
        saveIndex(newIndex)

        logger.info("创建版本: id={}, puzzleCount={}, trigger={}", id, puzzles.size, trigger)

        return version
    }

    /**
     * 加载快照
     *
     * @param versionId 版本 ID
     * @return 快照数据，不存在返回 null
     * @throws IllegalArgumentException 如果 versionId 为空
     */
    fun loadSnapshot(versionId: String): KnowledgeBaseSnapshot? {
        require(versionId.isNotBlank()) { "versionId 不能为空" }

        val file = File(getSnapshotsDir(), "$versionId.json")
        if (!file.exists()) {
            return null
        }

        val content = file.readText()
        return try {
            VersionStoreSerializer.parseSnapshot(content)
        } catch (e: Exception) {
            logger.error("解析快照失败: versionId={}", versionId, e)
            null
        }
    }

    /**
     * 列出所有版本
     */
    fun listVersions(): List<VersionEntry> {
        return loadIndex().versions
    }

    /**
     * 获取当前版本号
     */
    fun getCurrentVersion(): Int {
        return loadIndex().currentVersion
    }

    /**
     * 获取最新版本的 checksum
     */
    fun getLatestChecksum(): String {
        val index = loadIndex()
        return index.versions.lastOrNull()?.checksum ?: ""
    }

    /**
     * 检测是否有变更
     *
     * @param lastChecksum 上次版本的 checksum
     * @param puzzles 当前所有拼图
     * @return true 如果有变更
     */
    fun hasChangesSince(lastChecksum: String, puzzles: List<Puzzle>): Boolean {
        if (lastChecksum.isEmpty()) {
            return true
        }
        val currentChecksum = calculateChecksum(puzzles)
        return currentChecksum != lastChecksum
    }

    // ========== 私有方法 ==========

    private fun getVersionsDir(): File = File(projectPath, VERSIONS_DIR)

    private fun getSnapshotsDir(): File = File(getVersionsDir(), SNAPSHOTS_DIR)

    private fun getIndexFile(): File = File(getVersionsDir(), INDEX_FILE)

    private fun loadIndex(): VersionIndex {
        val file = getIndexFile()
        if (!file.exists()) {
            logger.debug("版本索引文件不存在，返回空索引")
            return VersionIndex()
        }

        val content = file.readText()
        return VersionStoreSerializer.parseIndex(content)
    }

    private fun saveIndex(index: VersionIndex) {
        val dir = getVersionsDir()
        dir.mkdirs()
        getIndexFile().writeText(VersionStoreSerializer.serializeIndex(index))
    }

    private fun saveSnapshot(version: KnowledgeBaseVersion, puzzles: List<Puzzle>) {
        val dir = getSnapshotsDir()
        dir.mkdirs()
        val file = File(dir, "${version.id}.json")
        file.writeText(VersionStoreSerializer.serializeSnapshot(version, puzzles))
    }

    private fun calculateChecksum(puzzles: List<Puzzle>): String {
        if (puzzles.isEmpty()) {
            return "empty"
        }

        val md = MessageDigest.getInstance("MD5")
        puzzles.sortedBy { it.id }.forEach { puzzle ->
            md.update(puzzle.id.toByteArray(Charsets.UTF_8))
            md.update(puzzle.content.toByteArray(Charsets.UTF_8))
            md.update(puzzle.type.name.toByteArray(Charsets.UTF_8))
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
