package com.smancode.smanagent.ide

import org.junit.Test
import java.util.regex.Pattern

class RegexTest {

    @Test
    fun testClassNamePattern() {
        // 测试最后一个模式（匹配简单类名）
        val pattern = Pattern.compile("\\b([A-Z][a-zA-Z0-9_]*)(\\.[a-z]+)?\\b")

        val text1 = "FileFilterUtil"
        val matcher1 = pattern.matcher(text1)
        println("测试1: '$text1'")
        println("  匹配: ${matcher1.find()}")
        if (matcher1.find()) {
            println("  完整匹配: ${matcher1.group(0)}")
            println("  类名: ${matcher1.group(1)}")
            println("  扩展名: ${matcher1.group(2)}")
        }

        val text2 = "我来帮你查看 FileFilterUtil 的实现"
        val matcher2 = pattern.matcher(text2)
        println("\n测试2: '$text2'")
        println("  匹配: ${matcher2.find()}")
        if (matcher2.find()) {
            println("  完整匹配: ${matcher2.group(0)}")
            println("  类名: ${matcher2.group(1)}")
        }

        val text3 = "Abc.java 和 Def.java 两个文件"
        val matcher3 = pattern.matcher(text3)
        println("\n测试3: '$text3'")
        val matches3 = mutableListOf<String>()
        while (matcher3.find()) {
            matches3.add(matcher3.group(0))
        }
        println("  匹配结果: $matches3")
    }
}
