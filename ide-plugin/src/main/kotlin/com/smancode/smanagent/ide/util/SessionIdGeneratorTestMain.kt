package com.smancode.smanagent.ide.util

object SessionIdGeneratorTest {
    @JvmStatic
    fun main(args: Array<String>) {
        println("=== SessionIdGenerator 测试 ===\n")

        // 测试1：生成单个 SessionId
        println("测试1：生成单个 SessionId")
        val sessionId1 = SessionIdGenerator.generate()
        println("生成的 SessionId: $sessionId1")

        // 验证格式：MMdd_HHmmss_XXXXXXXX
        val pattern = Regex("^\\d{4}_\\d{6}_[0-9A-Z]{8}$")
        if (sessionId1.matches(pattern)) {
            println("✓ 格式正确\n")
        } else {
            println("✗ 格式错误: $sessionId1\n")
            return
        }

        // 测试2：生成多个唯一 SessionId
        println("测试2：生成多个唯一 SessionId")
        val ids = mutableSetOf<String>()

        repeat(10) { index ->
            val sessionId = SessionIdGenerator.generate()
            println("[$index] $sessionId")

            // 验证格式
            if (!sessionId.matches(pattern)) {
                println("✗ 格式错误: $sessionId")
                return
            }

            // 验证唯一性
            if (ids.contains(sessionId)) {
                println("✗ 重复的 SessionId: $sessionId")
                return
            }
            ids.add(sessionId)
        }

        println("\n成功生成 ${ids.size} 个唯一 SessionId")

        // 测试3：验证随机字符
        println("\n测试3：验证随机字符范围")
        val allowedChars = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ".toSet()

        repeat(5) {
            val sessionId = SessionIdGenerator.generate()
            val randomPart = sessionId.substringAfterLast("_")

            randomPart.forEach { char ->
                if (char !in allowedChars) {
                    println("✗ 非法字符 '$char' 在: $sessionId")
                    return
                }
            }
        }

        println("✓ 所有随机字符都在允许范围内")
        println("\n=== 测试完成 ===")
    }
}
