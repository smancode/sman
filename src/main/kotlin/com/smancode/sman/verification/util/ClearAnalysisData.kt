package com.smancode.sman.verification.util

import java.sql.DriverManager

/**
 * 清空 H2 数据库中的旧分析数据
 */
fun main() {
    val url = "jdbc:h2:/Users/liuchao/.sman/autoloop/analysis"
    val user = "sa"
    val password = ""

    try {
        DriverManager.getConnection(url, user, password).use { conn ->
            println("连接到 H2 数据库成功")

            // 查询当前数据
            val selectSql = "SELECT STEP_NAME, STATUS FROM ANALYSIS_STEP WHERE PROJECT_KEY = ?"
            conn.prepareStatement(selectSql).use { stmt ->
                stmt.setString(1, "autoloop")
                stmt.executeQuery().use { rs ->
                    println("\n=== 当前数据库中的数据 ===")
                    while (rs.next()) {
                        val stepName = rs.getString("STEP_NAME")
                        val status = rs.getString("STATUS")
                        println("  步骤: $stepName, 状态: $status")
                    }
                }
            }

            // 清空指定步骤的数据
            val deleteSql = "DELETE FROM ANALYSIS_STEP WHERE PROJECT_KEY = ? AND STEP_NAME IN (?, ?)"
            conn.prepareStatement(deleteSql).use { stmt ->
                stmt.setString(1, "autoloop")
                stmt.setString(2, "common_class_scanning")
                stmt.setString(3, "external_api_scanning")
                val rows = stmt.executeUpdate()
                println("\n已删除 $rows 行数据 (common_class_scanning, external_api_scanning)")
            }

            println("\n=== 操作完成 ===")
            println("请在 IntelliJ IDEA 中重新运行项目分析以获取新的数据")
        }
    } catch (e: Exception) {
        println("错误: ${e.message}")
        e.printStackTrace()
    }
}
