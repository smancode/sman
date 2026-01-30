package com.smancode.smanagent.analysis.util

import com.smancode.smanagent.analysis.model.FileSnapshot
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * MD5 文件追踪服务
 *
 * 参考 knowledge-graph-system 的 MD5 追踪机制
 *
 * @param dataDir 数据目录
 */
class Md5FileTracker(
    private val dataDir: Path
) {
    private val logger = LoggerFactory.getLogger(Md5FileTracker::class.java)
    private val cache: MutableMap<String, FileSnapshot> = ConcurrentHashMap()

    /**
     * 追踪单个文件
     *
     * @param file 文件路径
     * @return 文件快照
     */
    fun trackFile(file: Path): FileSnapshot {
        require(Files.exists(file)) { "File does not exist: $file" }

        val relativePath = getRelativePath(file, dataDir)
        val attributes = Files.readAttributes(file, BasicFileAttributes::class.java)
        val md5 = calculateMd5(file)

        val snapshot = FileSnapshot(
            relativePath = relativePath,
            fileName = file.fileName.toString(),
            size = Files.size(file),
            lastModified = attributes.lastModifiedTime().toMillis(),
            md5 = md5
        )

        // 更新缓存
        cache[relativePath] = snapshot

        return snapshot
    }

    /**
     * 批量追踪文件
     *
     * @param files 文件列表
     * @return 文件快照列表
     */
    fun trackFiles(files: List<Path>): List<FileSnapshot> {
        return files.mapNotNull { file ->
            try {
                trackFile(file)
            } catch (e: Exception) {
                logger.warn("Failed to track file: ${file}", e)
                null
            }
        }
    }

    /**
     * 获取文件快照
     *
     * @param file 文件路径
     * @return 文件快照，如果不存在返回 null
     */
    fun getSnapshot(file: Path): FileSnapshot? {
        val relativePath = getRelativePath(file, dataDir)
        return cache[relativePath]
    }

    /**
     * 检测文件是否变化
     *
     * @param file 文件路径
     * @return 如果文件变化返回 true
     */
    fun hasChanged(file: Path): Boolean {
        require(Files.exists(file)) { "File does not exist: $file" }

        val cached = getSnapshot(file)

        // 如果没有缓存,认为已变化
        if (cached == null) {
            return true
        }

        // 计算当前文件的 MD5
        val currentMd5 = calculateMd5(file)

        // 比较缓存和当前 MD5
        return cached.md5 != currentMd5
    }

    /**
     * 获取变化的文件
     *
     * @param files 文件列表
     * @return 变化的文件快照列表
     */
    fun getChangedFiles(files: List<Path>): List<FileSnapshot> {
        return files.filter { hasChanged(it) }
            .map { trackFile(it) }
    }

    /**
     * 保存缓存到磁盘
     *
     * @param cacheFile 缓存文件路径
     */
    fun saveCache(cacheFile: Path) {
        try {
            val json = Json { prettyPrint = true }
            val jsonData = json.encodeToString(cache)
            Files.createDirectories(cacheFile.parent)
            Files.writeString(cacheFile, jsonData)
            logger.info("MD5 cache saved: ${cacheFile.toAbsolutePath()}, entries: ${cache.size}")
        } catch (e: Exception) {
            logger.error("Failed to save MD5 cache", e)
            throw e
        }
    }

    /**
     * 从磁盘加载缓存
     *
     * @param cacheFile 缓存文件路径
     */
    fun loadCache(cacheFile: Path) {
        if (!Files.exists(cacheFile)) {
            logger.info("MD5 cache file not found, starting fresh: $cacheFile")
            return
        }

        try {
            val jsonData = Files.readString(cacheFile)
            val json = Json { ignoreUnknownKeys = true }
            val loadedCache = json.decodeFromString<Map<String, FileSnapshot>>(jsonData)
            cache.putAll(loadedCache)
            logger.info("MD5 cache loaded: ${cacheFile.toAbsolutePath()}, entries: ${cache.size}")
        } catch (e: Exception) {
            logger.error("Failed to load MD5 cache", e)
            throw e
        }
    }

    /**
     * 计算文件的 MD5 哈希
     *
     * @param file 文件路径
     * @return MD5 哈希值（十六进制）
     */
    private fun calculateMd5(file: Path): String {
        val digest = MessageDigest.getInstance("MD5")
        val bytes = Files.readAllBytes(file)
        val hash = digest.digest(bytes)

        return hash.joinToString("") { "%02x".format(it) }
    }

    /**
     * 清空缓存
     */
    fun clearCache() {
        cache.clear()
        logger.info("MD5 cache cleared")
    }

    /**
     * 获取相对路径
     *
     * @param file 文件路径
     * @param baseDir 基准目录
     * @return 相对路径字符串
     */
    private fun getRelativePath(file: Path, baseDir: Path): String {
        try {
            val relative = baseDir.relativize(file)
            return relative.toString().replace("\\", "/")
        } catch (e: IllegalArgumentException) {
            // 如果无法计算相对路径,返回绝对路径
            logger.warn("Cannot calculate relative path for $file, using absolute path")
            return file.toAbsolutePath().toString().replace("\\", "/")
        }
    }
}
