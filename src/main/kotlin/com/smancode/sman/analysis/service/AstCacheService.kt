package com.smancode.sman.analysis.service

import com.smancode.sman.analysis.model.ClassAstInfo
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * AST 缓存服务（三级缓存）
 *
 * L1: 热数据（内存，LRU）
 * L2: 温数据（内存映射）
 * L3: 冷数据（磁盘）
 *
 * @param astDir AST 存储目录
 * @param hotCacheSize 热数据缓存大小
 */
class AstCacheService(
    private val astDir: Path,
    private val hotCacheSize: Long = 50 * 1024 * 1024 // 50 MB
) {
    private val logger = LoggerFactory.getLogger(AstCacheService::class.java)

    // 使用结构化并发作用域
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // JSON 序列化配置
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    // L1: 热数据缓存（内存，LRU）
    private val hotCache = object : LinkedHashMap<String, ClassAstInfo>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ClassAstInfo>?): Boolean =
            estimateSize() > hotCacheSize
    }

    // L2: 温数据缓存（内存映射）
    private val warmCache = ConcurrentHashMap<String, ClassAstInfo>()

    init {
        // 确保目录存在
        Files.createDirectories(astDir)
    }

    /**
     * 获取类的 AST 信息
     *
     * @param className 完整类名
     * @return AST 信息，如果不存在返回 null
     */
    fun getClassAst(className: String): ClassAstInfo? {
        // L1: 检查热数据缓存
        hotCache[className]?.let { return it }

        // L2: 检查温数据缓存
        warmCache[className]?.let { ast ->
            // 提升到热数据缓存
            hotCache[className] = ast
            return ast
        }

        // L3: 从磁盘加载
        val file = astDir.resolve("${className.replace('.', '/')}.json")
        if (Files.exists(file)) {
            val ast = loadFromFile(file)
            // 提升到温数据缓存
            warmCache[className] = ast
            // 提升到热数据缓存
            hotCache[className] = ast
            return ast
        }

        return null
    }

    /**
     * 存储类的 AST 信息
     *
     * @param className 完整类名
     * @param ast AST 信息
     */
    fun putClassAst(className: String, ast: ClassAstInfo) {
        // 1. 存入热数据缓存
        hotCache[className] = ast

        // 2. 异步保存到磁盘
        scope.launch {
            try {
                val file = astDir.resolve("${className.replace('.', '/')}.json")
                Files.createDirectories(file.parent)
                saveToFile(file, ast)
            } catch (e: Exception) {
                logger.error("Failed to save AST to disk: $className", e)
            }
        }
    }

    /**
     * 从磁盘加载 AST 信息
     *
     * @param file 文件路径
     * @return AST 信息
     */
    private fun loadFromFile(file: Path): ClassAstInfo {
        val content = Files.readString(file)
        return content.decodeFromString<ClassAstInfo>()
    }

    /**
     * 保存 AST 信息到文件
     *
     * @param file 文件路径
     * @param ast AST 信息
     */
    private fun saveToFile(file: Path, ast: ClassAstInfo) {
        val json = json.encodeToString(ClassAstInfo.serializer(), ast)
        Files.writeString(file, json)
    }

    /**
     * 估算当前内存占用
     */
    private fun estimateSize(): Long =
        hotCache.values.sumOf { it.estimateSize() }

    /**
     * 预加载热点类
     */
    suspend fun preloadHotClasses(project: Project) {
        // TODO: 实现 PSI 扫描，识别入口类、Service 类
    }

    /**
     * 清空所有缓存
     */
    fun clearCache() {
        hotCache.clear()
        warmCache.clear()
        logger.info("AST cache cleared")
    }

    /**
     * 关闭服务
     */
    fun close() {
        hotCache.clear()
        warmCache.clear()
        logger.info("AstCacheService closed")
    }
}

/**
 * JSON 解码扩展函数
 */
private inline fun <reified T> String.decodeFromString(): T =
    Json.decodeFromString(this)
