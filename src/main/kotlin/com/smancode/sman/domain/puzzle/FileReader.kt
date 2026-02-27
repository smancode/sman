package com.smancode.sman.domain.puzzle

import java.io.File

/**
 * 文件读取器接口
 *
 * 用于读取文件内容并构建分析上下文
 */
interface FileReader {
    /**
     * 读取文件并构建上下文
     *
     * @param target 目标文件路径
     * @return 分析上下文
     */
    fun readWithContext(target: String): AnalysisContext
}

/**
 * 默认文件读取器实现
 */
class DefaultFileReader(
    private val projectPath: String
) : FileReader {

    override fun readWithContext(target: String): AnalysisContext {
        val file = File(projectPath, target)

        if (!file.exists()) {
            return AnalysisContext.empty()
        }

        val relatedFiles = mutableMapOf<String, String>()

        // 读取目标文件
        if (file.isFile) {
            relatedFiles[target] = file.readText()
        } else if (file.isDirectory) {
            // 如果是目录，读取目录下的关键文件（限制数量避免上下文爆炸）
            file.walkTopDown()
                .take(10) // 最多读取 10 个文件
                .filter { it.isFile && isRelevantFile(it) }
                .forEach { f ->
                    val relativePath = f.relativeTo(File(projectPath)).path
                    relatedFiles[relativePath] = f.readText()
                }
        }

        return AnalysisContext(
            relatedFiles = relatedFiles,
            existingPuzzles = emptyList(),
            userQuery = null
        )
    }

    /**
     * 判断文件是否相关（应该被分析）
     */
    private fun isRelevantFile(file: File): Boolean {
        val name = file.name.lowercase()
        val path = file.path.lowercase()

        // 排除隐藏文件、构建产物、测试文件等
        if (name.startsWith(".")) return false
        if (path.contains("/build/")) return false
        if (path.contains("/target/")) return false
        if (path.contains("/node_modules/")) return false
        if (path.contains(".test.") || path.contains(".spec.")) return false

        // 只包含源代码文件
        return name.endsWith(".kt") ||
                name.endsWith(".java") ||
                name.endsWith(".xml") ||
                name.endsWith(".yml") ||
                name.endsWith(".yaml") ||
                name.endsWith(".properties")
    }
}
