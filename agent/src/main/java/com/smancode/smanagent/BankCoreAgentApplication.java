package com.smancode.smanagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ComponentScan;

/**
 * 银行核心系统 AI 分析助手 - 后端服务启动类
 */
@SpringBootApplication
@ComponentScan(basePackages = "com.smancode.smanagent")
public class BankCoreAgentApplication {

    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(BankCoreAgentApplication.class, args);

        // 打印显眼的启动成功消息
        System.out.println("\n\n");
        System.out.println("✅✅✅✅✅✅✅✅✅   AGENT STARTED   ✅✅✅✅✅✅✅✅✅");
        System.out.println("\n");
    }
}
