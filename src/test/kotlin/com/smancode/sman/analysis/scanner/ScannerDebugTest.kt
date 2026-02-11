package com.smancode.sman.analysis.scanner

import com.smancode.sman.analysis.common.CommonClassScanner
import com.smancode.sman.analysis.external.ExternalApiScanner
import org.junit.Test
import java.nio.file.Paths
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 扫描器调试测试
 *
 * 用于验证 CommonClassScanner 和 ExternalApiScanner 是否正常工作
 */
class ScannerDebugTest {

    @Test
    fun testCommonClassScanner() {
        println("========== 测试 CommonClassScanner ==========")

        val autoloopPath = Paths.get("/Users/liuchao/projects/autoloop")
        println("项目路径: $autoloopPath")

        val scanner = CommonClassScanner()
        val commonClasses = scanner.scan(autoloopPath)

        println("检测到 ${commonClasses.size} 个公共类")
        commonClasses.forEach { cls ->
            println("  - ${cls.qualifiedName}")
        }

        // 验证是否检测到 FileFilterUtil
        val fileFilterUtil = commonClasses.find { it.className == "FileFilterUtil" }
        if (fileFilterUtil != null) {
            println("\n✓ 成功检测到 FileFilterUtil")
            println("  包名: ${fileFilterUtil.packageName}")
            println("  方法数: ${fileFilterUtil.methods.size}")
        } else {
            println("\n✗ 未检测到 FileFilterUtil")
        }

        // 至少应该检测到一些公共类
        assertTrue(commonClasses.isNotEmpty(), "应该检测到至少一个公共类")
    }

    @Test
    fun testExternalApiScanner() {
        println("\n========== 测试 ExternalApiScanner ==========")

        val autoloopPath = Paths.get("/Users/liuchao/projects/autoloop")
        println("项目路径: $autoloopPath")

        val scanner = ExternalApiScanner()
        val apis = scanner.scan(autoloopPath)

        println("检测到 ${apis.size} 个外调接口")
        apis.forEach { api ->
            println("  - ${api.apiName} (${api.apiType})")
            println("    方法数: ${api.methods.size}")
        }

        // 验证是否检测到 RestClient
        val restClientApis = apis.filter { it.apiType.name == "REST_CLIENT" }
        if (restClientApis.isNotEmpty()) {
            println("\n✓ 成功检测到 ${restClientApis.size} 个 RestClient")
            restClientApis.forEach { api ->
                println("  - ${api.apiName}")
                api.methods.forEach { method ->
                    println("    ${method.httpMethod} ${method.path}")
                }
            }
        } else {
            println("\n✗ 未检测到 RestClient")
        }

        // 至少应该检测到一些外调接口
        assertTrue(apis.isNotEmpty(), "应该检测到至少一个外调接口")
    }
}
