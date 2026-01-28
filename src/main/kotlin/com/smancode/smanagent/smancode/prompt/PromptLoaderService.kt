package com.smancode.smanagent.smancode.prompt

import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/**
 * 提示词加载服务
 *
 * 负责从 resources/prompts 目录加载 .md 文件，
 * 支持变量替换和缓存。
 * （移除 Spring @Service 依赖）
 */
class PromptLoaderService {

    private val logger = LoggerFactory.getLogger(PromptLoaderService::class.java)

    /**
     * 提示词缓存
     */
    private val promptCache: MutableMap<String, String> = ConcurrentHashMap()

    /**
     * 提示词基础路径
     */
    private val promptsBasePath: String = "prompts/"

    /**
     * 加载提示词（带缓存）
     *
     * @param promptPath 提示词路径，如 "common/system-header.md"
     * @return 提示词内容
     */
    fun loadPrompt(promptPath: String): String {
        return promptCache.computeIfAbsent(promptPath) { loadPromptFromFile(it) }
    }

    /**
     * 加载提示词并替换变量（无缓存）
     *
     * @param promptPath 提示词路径
     * @param variables 变量映射
     * @return 替换后的提示词
     */
    fun loadPromptWithVariables(promptPath: String, variables: Map<String, String>?): String {
        val prompt = loadPromptFromFile(promptPath)
        return replaceVariables(prompt, variables)
    }

    /**
     * 从文件加载提示词
     *
     * @param promptPath 提示词路径
     * @return 提示词内容
     */
    private fun loadPromptFromFile(promptPath: String): String {
        return try {
            // 使用 ClassPath 资源加载（插件环境兼容）
            val resourcePath = "$promptsBasePath$promptPath"
            val inputStream = javaClass.classLoader.getResourceAsStream(resourcePath)
                ?: throw IOException("资源未找到: $resourcePath")

            inputStream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                val content = reader.readText()
                logger.debug("加载提示词: {}, 长度: {} 字符", resourcePath, content.length)
                content
            }
        } catch (e: IOException) {
            logger.error("加载提示词失败: {}", promptPath, e)
            throw RuntimeException("加载提示词失败: $promptPath", e)
        }
    }

    /**
     * 替换提示词中的变量
     *
     * 支持 {{VARIABLE_NAME}} 格式的变量替换
     *
     * @param prompt    提示词模板
     * @param variables 变量映射
     * @return 替换后的提示词
     */
    private fun replaceVariables(prompt: String, variables: Map<String, String>?): String {
        if (variables.isNullOrEmpty()) {
            return prompt
        }

        var result = prompt
        for ((key, value) in variables) {
            val placeholder = "{$key}"
            result = result.replace(placeholder, value)
        }

        return result
    }

    /**
     * 清除缓存
     */
    fun clearCache() {
        promptCache.clear()
        logger.info("提示词缓存已清除")
    }

    /**
     * 预加载所有提示词（可选）
     */
    fun preloadPrompts() {
        logger.info("开始预加载提示词...")
        val promptsToPreload = arrayOf(
            "common/system-header.md",
            "phases/01-intent-recognition.md",
            "phases/02-requirement-planning.md",
            "phases/03-execution.md",
            "tools/tool-introduction.md"
        )

        for (promptPath in promptsToPreload) {
            try {
                loadPrompt(promptPath)
            } catch (e: Exception) {
                logger.warn("预加载提示词失败: {}", promptPath, e)
            }
        }

        logger.info("提示词预加载完成，缓存数量: {}", promptCache.size)
    }
}
