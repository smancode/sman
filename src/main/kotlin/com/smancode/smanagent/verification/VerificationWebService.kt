package com.smancode.smanagent.verification

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * SmanAgent 验证服务
 *
 * 独立的 Web 服务，用于验证分析结果的正确性
 *
 * 端口: 8080 (可配置)
 * 启动脚本: scripts/verification-web.sh
 */
@SpringBootApplication(
    scanBasePackages = ["com.smancode.smanagent"]
)
open class VerificationWebService

fun main(args: Array<String>) {
    System.setProperty("server.port", System.getProperty("server.port", "8080"))
    System.setProperty("spring.main.web-application-type", "servlet")
    runApplication<VerificationWebService>(*args)
}
