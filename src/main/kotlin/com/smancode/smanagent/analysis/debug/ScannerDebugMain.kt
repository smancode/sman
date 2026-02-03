package com.smancode.smanagent.analysis.debug

import com.smancode.smanagent.analysis.common.CommonClassScanner
import com.smancode.smanagent.analysis.external.ExternalApiScanner

/**
 * 扫描器调试主程序
 *
 * 直接运行此程序来测试扫描器是否正常工作
 */
fun main() {
    val autoloopPath = java.nio.file.Paths.get("/Users/liuchao/projects/autoloop")

    println("========== 调试 CommonClassScanner ==========")
    try {
        val commonClassScanner = CommonClassScanner()
        val commonClasses = commonClassScanner.scan(autoloopPath)

        println("\n结果: 检测到 ${commonClasses.size} 个公共类")
        commonClasses.take(10).forEach { cls ->
            println("  - ${cls.qualifiedName}")
        }

        val fileFilterUtil = commonClasses.find { it.className == "FileFilterUtil" }
        if (fileFilterUtil != null) {
            println("\n✓ 成功检测到 FileFilterUtil!")
            println("  包名: ${fileFilterUtil.packageName}")
            println("  方法数: ${fileFilterUtil.methods.size}")
        } else {
            println("\n✗ 未检测到 FileFilterUtil")
        }
    } catch (e: Exception) {
        println("错误: ${e.message}")
        e.printStackTrace()
    }

    println("\n========== 调试 ExternalApiScanner ==========")
    try {
        val externalApiScanner = ExternalApiScanner()
        val apis = externalApiScanner.scan(autoloopPath)

        println("\n结果: 检测到 ${apis.size} 个外调接口")
        apis.take(10).forEach { api ->
            println("  - ${api.apiName} (${api.apiType})")
        }

        val restClientApis = apis.filter { it.apiType.name == "REST_CLIENT" }
        if (restClientApis.isNotEmpty()) {
            println("\n✓ 成功检测到 ${restClientApis.size} 个 RestClient!")
            restClientApis.forEach { api ->
                println("  - ${api.apiName}")
                api.methods.take(3).forEach { method ->
                    println("    ${method.httpMethod} ${method.path}")
                }
            }
        } else {
            println("\n✗ 未检测到 RestClient")
        }
    } catch (e: Exception) {
        println("错误: ${e.message}")
        e.printStackTrace()
    }

    println("\n========== 调试完成 ==========")
}
