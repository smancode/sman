package com.smancode.sman.analysis

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

@DisplayName("autoloop L0 测试")
class AutoLoopL0Test {
    @Test
    fun testAutoLoop() {
        val result = L0StructureAnalyzer("/Users/nasakim/projects/autoloop").analyze()
        println("=== Sman 分析 autoloop ===")
        println("模块: ${result.modules.map { "${it.name}(${it.type})" }}")
        println("语言: ${result.techStack.languages}")
        println("框架: ${result.techStack.frameworks}")
        println("数据库: ${result.techStack.databases}")
        println("入口点: ${result.entryPoints.size}")
        println("代码统计: ${result.statistics}")
        assertTrue(result.modules.isNotEmpty())
    }
}
