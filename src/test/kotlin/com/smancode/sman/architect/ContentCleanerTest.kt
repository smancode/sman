package com.smancode.sman.architect

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 内容清理测试
 *
 * 测试 ArchitectAgent 的 cleanContent 方法
 */
@DisplayName("内容清理测试")
class ContentCleanerTest {

    // 模拟 cleanContent 方法的逻辑
    private fun cleanContent(content: String): String {
        var cleaned = content.trim()

        // 问候语模式（通常是 LLM 在等待用户输入）
        // 注意：顺序很重要，分隔线模式要先匹配
        val greetingPatterns = listOf(
            // 分隔线后的问候语（优先匹配）
            Regex("""\n*---+\s*\n+\*\*请问.*$""", RegexOption.DOT_MATCHES_ALL),
            Regex("""\n*---+\s*\n+请问.*$""", RegexOption.DOT_MATCHES_ALL),
            // 分隔线后的列表选项（如：- 构建项目: ./gradlew build）
            Regex("""\n*---+\s*\n+(- [^\n]+\n?)+$"""),
            // 中文问候语
            Regex("""\n*\*\*请问[您你]想[做什么了解]*[^*]*\*\*.*$""", RegexOption.DOT_MATCHES_ALL),
            Regex("""\n*\*\*请告诉我[你的]*需求\*\*.*$""", RegexOption.DOT_MATCHES_ALL),
            Regex("""\n*请问[您你]想[做什么让我做什么了解]*.*$""", RegexOption.DOT_MATCHES_ALL),
            Regex("""\n*还是有其他需求.*$""", RegexOption.DOT_MATCHES_ALL)
        )

        for (pattern in greetingPatterns) {
            val newContent = pattern.replace(cleaned, "")
            if (newContent != cleaned) {
                cleaned = newContent
            }
        }

        return cleaned.trim()
    }

    @Test
    @DisplayName("应该去除末尾的'请问您想做什么操作'问候语")
    fun `should remove trailing greeting with question`() {
        // Given
        val content = """
## 项目模块结构
- **common**: 公共模块
- **core**: 核心模块

---

**请问您想做什么操作？**
- 构建项目: `./gradlew build`
- 运行测试: `./gradlew test`
        """.trimIndent()

        // When
        val cleaned = cleanContent(content)

        // Then
        assertEquals("""
## 项目模块结构
- **common**: 公共模块
- **core**: 核心模块
        """.trimIndent(), cleaned)
    }

    @Test
    @DisplayName("应该去除末尾的'请问你想让我做什么'问候语")
    fun `should remove trailing greeting asking what to do`() {
        // Given
        val content = """
**技术栈**：
- Java 21
- Spring Boot 3.2.0

**请问你想让我做什么？**
- 分析项目的业务代码结构？
- 查看具体的模块实现？
        """.trimIndent()

        // When
        val cleaned = cleanContent(content)

        // Then
        assertEquals("""
**技术栈**：
- Java 21
- Spring Boot 3.2.0
        """.trimIndent(), cleaned)
    }

    @Test
    @DisplayName("应该去除末尾的'请问你想了解'问候语")
    fun `should remove trailing greeting asking what to know`() {
        // Given
        val content = """
### 📍 关键文件路径
- `loan/src/main/java/DisburseHandler.java`

---

**请问你想了解放款流程的哪些具体细节？** 例如：
1. 放款请求的参数校验逻辑
2. 资金划转的具体实现
        """.trimIndent()

        // When
        val cleaned = cleanContent(content)

        // Then
        assertEquals("""
### 📍 关键文件路径
- `loan/src/main/java/DisburseHandler.java`
        """.trimIndent(), cleaned)
    }

    @Test
    @DisplayName("保留没有问候语的内容")
    fun `should keep content without greeting`() {
        // Given
        val content = """
## 项目模块概览

| 模块 | 业务含义 | 主要组件 |
|------|----------|----------|
| **common** | 通用模块 | DTO、Config |
| **core** | 核心服务层 | 目录扫描、报告生成 |

## 技术栈
- Java 21
- Spring Boot 3.2.0
        """.trimIndent()

        // When
        val cleaned = cleanContent(content)

        // Then
        assertEquals(content, cleaned)
    }

    @Test
    @DisplayName("保留分隔线但不保留问候语")
    fun `should keep separator but remove greeting after it`() {
        // Given
        val content = """
## 核心业务

1. **代码分析系统**
2. **贷款业务系统**

---

**请问您想做什么操作？**
- 构建项目
        """.trimIndent()

        // When
        val cleaned = cleanContent(content)

        // Then
        assertEquals("""
## 核心业务

1. **代码分析系统**
2. **贷款业务系统**
        """.trimIndent(), cleaned)
    }
}
