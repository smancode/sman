package com.smancode.sman.analysis.vectorization

import com.smancode.sman.analysis.model.VectorFragment
import com.smancode.sman.analysis.scanner.PsiAstScanner
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * 代码向量化服务
 *
 * 将代码片段向量化，支持语义搜索
 */
class CodeVectorizationService(
    private val bgeClient: BgeM3Client,
    private val psiScanner: PsiAstScanner
) {

    private val logger = LoggerFactory.getLogger(CodeVectorizationService::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 向量化类
     */
    fun vectorizeClass(file: Path): VectorFragment? {
        val ast = psiScanner.scanFile(file) ?: return null

        // 生成内容摘要
        val content = generateClassContent(ast)

        // 生成向量
        val vector = try {
            bgeClient.embed(content)
        } catch (e: Exception) {
            logger.error("Failed to generate vector for class: ${ast.className}", e)
            return null
        }

        return VectorFragment(
            id = "class:${ast.className}",
            title = ast.simpleName,
            content = content,
            fullContent = ast.toString(),
            tags = listOf("class", ast.packageName),
            metadata = mapOf(
                "type" to "class",
                "className" to ast.className,
                "packageName" to ast.packageName
            ),
            vector = vector
        )
    }

    /**
     * 向量化方法
     */
    fun vectorizeMethod(file: Path, methodName: String): VectorFragment? {
        val ast = psiScanner.scanFile(file) ?: return null
        val method = ast.methods.find { it.name == methodName } ?: return null

        // 生成内容摘要
        val content = generateMethodContent(ast, method)

        // 生成向量
        val vector = try {
            bgeClient.embed(content)
        } catch (e: Exception) {
            logger.error("Failed to generate vector for method: ${ast.className}.$methodName", e)
            return null
        }

        return VectorFragment(
            id = "method:${ast.className}.$methodName",
            title = "${ast.simpleName}.${method.name}()",
            content = content,
            fullContent = method.toString(),
            tags = listOf("method", ast.packageName),
            metadata = mapOf(
                "type" to "method",
                "className" to ast.className,
                "methodName" to method.name,
                "packageName" to ast.packageName
            ),
            vector = vector
        )
    }

    /**
     * 向量化枚举
     */
    fun vectorizeEnum(file: Path): VectorFragment? {
        val content = file.toFile().readText()

        // 提取枚举信息
        val enumPattern = Regex("enum\\s+class\\s+(\\w+)")
        val enumMatch = enumPattern.find(content) ?: return null
        val enumName = enumMatch.groupValues[1]

        // 生成向量
        val vector = try {
            bgeClient.embed(content)
        } catch (e: Exception) {
            logger.error("Failed to generate vector for enum: $enumName", e)
            return null
        }

        return VectorFragment(
            id = "enum:$enumName",
            title = enumName,
            content = "枚举 $enumName",
            fullContent = content,
            tags = listOf("enum"),
            metadata = mapOf(
                "type" to "enum",
                "enumName" to enumName
            ),
            vector = vector
        )
    }

    /**
     * 向量化 MyBatis Mapper XML
     */
    fun vectorizeMapperXml(file: Path): VectorFragment? {
        val content = file.toFile().readText()

        // 提取表名
        val tablePattern = Regex("(?:FROM|JOIN|UPDATE|INSERT\\s+INTO)\\s+([\\w_]+)")
        val tables = tablePattern.findAll(content)
            .map { it.groupValues[1] }
            .distinct()
            .toList()

        // 生成内容摘要
        val tablesStr = tables.joinToString(", ")
        val summary = "MyBatis Mapper ${file.fileName}, 涉及表: $tablesStr"

        // 生成向量
        val vector = try {
            bgeClient.embed(summary)
        } catch (e: Exception) {
            logger.error("Failed to generate vector for mapper: ${file.fileName}", e)
            return null
        }

        return VectorFragment(
            id = "mapper:${file.fileName}",
            title = file.fileName.toString(),
            content = summary,
            fullContent = content,
            tags = listOf("mapper", "xml") + tables.map { "table:$it" },
            metadata = mapOf(
                "type" to "mapper",
                "fileName" to file.fileName.toString(),
                "tables" to tablesStr
            ),
            vector = vector
        )
    }

    /**
     * 向量化配置 XML
     */
    fun vectorizeConfigXml(file: Path): VectorFragment? {
        val content = file.toFile().readText()

        // 生成内容摘要
        val summary = "配置文件 ${file.fileName}"

        // 生成向量
        val vector = try {
            bgeClient.embed(summary)
        } catch (e: Exception) {
            logger.error("Failed to generate vector for config: ${file.fileName}", e)
            return null
        }

        return VectorFragment(
            id = "config:${file.fileName}",
            title = file.fileName.toString(),
            content = summary,
            fullContent = content,
            tags = listOf("config", "xml"),
            metadata = mapOf(
                "type" to "config",
                "fileName" to file.fileName.toString()
            ),
            vector = vector
        )
    }

    /**
     * 生成类内容摘要
     */
    private fun generateClassContent(ast: com.smancode.sman.analysis.model.ClassAstInfo): String {
        val sb = StringBuilder()

        sb.append("类: ${ast.className}\n")
        sb.append("包名: ${ast.packageName}\n")
        sb.append("方法: ${ast.methods.map { it.name }.joinToString(", ")}\n")
        sb.append("字段: ${ast.fields.map { it.name }.joinToString(", ")}")

        return sb.toString()
    }

    /**
     * 生成方法内容摘要
     */
    private fun generateMethodContent(
        ast: com.smancode.sman.analysis.model.ClassAstInfo,
        method: com.smancode.sman.analysis.model.MethodInfo
    ): String {
        val sb = StringBuilder()

        sb.append("方法: ${ast.className}.${method.name}()\n")
        sb.append("返回类型: ${method.returnType}\n")
        sb.append("参数: ${method.parameters.joinToString(", ")}")

        return sb.toString()
    }

    /**
     * 关闭服务
     */
    fun close() {
        bgeClient.close()
    }
}

/**
 * 向量化缓存
 */
class VectorizationCache(
    private val maxSize: Int = 1000
) {
    private val cache: MutableMap<String, VectorFragment> = ConcurrentHashMap()

    /**
     * 获取向量片段
     */
    fun get(id: String): VectorFragment? {
        return cache[id]
    }

    /**
     * 添加向量片段
     */
    fun put(fragment: VectorFragment) {
        if (cache.size >= maxSize) {
            // LRU: 移除最早的条目
            val keyToRemove = cache.keys.first()
            cache.remove(keyToRemove)
        }
        cache[fragment.id] = fragment
    }

    /**
     * 批量添加
     */
    fun putAll(fragments: List<VectorFragment>) {
        fragments.forEach { put(it) }
    }

    /**
     * 清空缓存
     */
    fun clear() {
        cache.clear()
    }

    /**
     * 获取所有条目
     */
    fun getAll(): List<VectorFragment> {
        return cache.values.toList()
    }
}
