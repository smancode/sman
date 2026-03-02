package com.smancode.sman.analysis

import java.io.File

/**
 * 枚举解析器
 * 
 * 解析 Java 枚举类，提取：
 * - 枚举值列表
 * - Javadoc 注释
 * - 业务含义
 * - 方法定义
 */
class EnumParser {

    data class EnumInfo(
        val name: String,
        val values: List<String> = emptyList(),
        val docs: Map<String, String> = emptyMap(),
        val businessMeaning: Map<String, String> = emptyMap(),
        val methods: List<String> = emptyList()
    )

    /**
     * 解析枚举文件
     */
    fun parse(file: File): EnumInfo {
        val content = file.readText()
        val name = file.nameWithoutExtension

        return EnumInfo(
            name = name,
            values = extractValues(content),
            docs = extractDocs(content),
            businessMeaning = extractBusinessMeaning(content),
            methods = extractMethods(content)
        )
    }

    /**
     * 提取枚举值
     */
    private fun extractValues(content: String): List<String> {
        val values = mutableListOf<String>()
        
        // 匹配枚举值定义: VALUE_NAME 或 VALUE_NAME("label")
        val pattern = Regex("""(\w+)\s*(?:\([^)]*\))?\s*[;,]""")
        val matches = pattern.findAll(content)
        
        for (match in matches) {
            val value = match.groupValues[1]
            // 过滤关键字
            if (value !in listOf("private", "public", "protected", "static", "final", "enum") && value.first().isUpperCase()) {
                values.add(value)
            }
        }
        
        return values.distinct()
    }

    /**
     * 提取 Javadoc 注释
     */
    private fun extractDocs(content: String): Map<String, String> {
        val docs = mutableMapOf<String, String>()
        
        // 匹配 Javadoc 注释
        val docPattern = Regex("""/\*\*\s*\n\s*\*\s*([^*]+).*?\n\s*(\w+)\s*[,;]""", RegexOption.DOT_MATCHES_ALL)
        
        for (match in docPattern.findAll(content)) {
            val doc = match.groupValues[1].trim()
            val enumValue = match.groupValues[2].trim()
            docs[enumValue] = doc
        }
        
        return docs
    }

    /**
     * 提取业务含义（从注释中）
     */
    private fun extractBusinessMeaning(content: String): Map<String, String> {
        val meanings = mutableMapOf<String, String>()
        
        // 匹配中文注释作为业务含义
        val pattern = Regex("""/\*\*\s*\n\s*\*\s*([\u4e00-\u9fa5]+.*?)\n\s*(\w+)\s*[,;]""", RegexOption.DOT_MATCHES_ALL)
        
        for (match in pattern.findAll(content)) {
            val meaning = match.groupValues[1].trim()
            val enumValue = match.groupValues[2].trim()
            meanings[enumValue] = meaning
        }
        
        return meanings
    }

    /**
     * 提取枚举方法
     */
    private fun extractMethods(content: String): List<String> {
        val methods = mutableListOf<String>()
        
        // 匹配方法定义
        val pattern = Regex("""(public|private|protected)?\s+\w+\s+(\w+)\s*\(""")
        
        for (match in pattern.findAll(content)) {
            val methodName = match.groupValues[2]
            if (methodName != "EnumParser") { // 排除构造方法
                methods.add(methodName)
            }
        }
        
        return methods.distinct()
    }
}
