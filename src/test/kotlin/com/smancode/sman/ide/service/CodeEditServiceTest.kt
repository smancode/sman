package com.smancode.sman.ide.service

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * CodeEditService 测试
 *
 * TDD: 测试多策略匹配编辑
 */
@DisplayName("代码编辑服务测试")
class CodeEditServiceTest {

    @Test
    @DisplayName("精确匹配")
    fun testExactMatch() {
        val fileContent = """fun hello() {
    println("Hello")
}"""
        val searchContent = """println("Hello")"""
        
        val matcher = CodeEditMatcher()
        val result = matcher.findMatch(fileContent, searchContent)

        assertTrue(result is CodeEditMatcher.MatchResult.Success)
    }

    @Test
    @DisplayName("空白归一化")
    fun testWhitespaceNormalized() {
        val fileContent = """fun hello() {
    println(  "Hello"  )
}"""
        val searchContent = """println("Hello")"""
        
        val matcher = CodeEditMatcher()
        val result = matcher.findMatch(fileContent, searchContent)

        assertTrue(result is CodeEditMatcher.MatchResult.Success)
    }

    @Test
    @DisplayName("缩进灵活匹配")
    fun testIndentationFlexible() {
        val fileContent = """fun hello() {
println("Hello")
}"""
        val searchContent = """    println("Hello")"""
        
        val matcher = CodeEditMatcher()
        val result = matcher.findMatch(fileContent, searchContent)

        assertTrue(result is CodeEditMatcher.MatchResult.Success)
    }

    @Test
    @DisplayName("多行匹配")
    fun testMultiLine() {
        val fileContent = """fun hello() {
    println("Hello")
    println("World")
}"""
        val searchContent = """println("Hello")
    println("World")"""
        
        val matcher = CodeEditMatcher()
        val result = matcher.findMatch(fileContent, searchContent)

        assertTrue(result is CodeEditMatcher.MatchResult.Success)
    }

    @Test
    @DisplayName("锚点匹配")
    fun testAnchor() {
        val fileContent = """class Main {
    fun start() {
        init()
    }
    
    fun init() {
        println("init")
    }
}"""
        val searchContent = "fun init()"
        
        val matcher = CodeEditMatcher()
        val result = matcher.findMatch(fileContent, searchContent)

        assertTrue(result is CodeEditMatcher.MatchResult.Success)
    }

    @Test
    @DisplayName("找不到匹配")
    fun testNoMatch() {
        val fileContent = """fun hello() {
    println("Hello")
}"""
        val searchContent = """println("NotExists")"""
        
        val matcher = CodeEditMatcher()
        val result = matcher.findMatch(fileContent, searchContent)

        assertTrue(result is CodeEditMatcher.MatchResult.Failure)
    }
}
