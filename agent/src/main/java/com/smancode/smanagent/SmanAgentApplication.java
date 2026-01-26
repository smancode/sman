package com.smancode.smanagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ComponentScan;

/**
 * SmanAgent - 代码分析助手后端服务启动类
 */
@SpringBootApplication
@ComponentScan(basePackages = "com.smancode.smanagent")
public class SmanAgentApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(SmanAgentApplication.class);
        ConfigurableApplicationContext context = app.run(args);

        // 启动成功后打印醒目标志
        printStartedBanner();
    }

    /**
     * 打印启动成功的醒目标志
     */
    private static void printStartedBanner() {
        System.out.println("""

 █████╗  ██████╗ ███████╗███╗   ██╗████████╗        ███████╗████████╗ █████╗ ██████╗ ████████╗███████╗██████╗
██╔══██╗██╔════╝ ██╔════╝████╗  ██║╚══██╔══╝        ██╔════╝╚══██╔══╝██╔══██╗██╔══██╗╚══██╔══╝██╔════╝██╔══██╗
███████║██║  ███╗█████╗  ██╔██╗ ██║   ██║           ███████╗   ██║   ███████║██████╔╝   ██║   █████╗  ██║  ██║
██╔══██║██║   ██║██╔══╝  ██║╚██╗██║   ██║           ╚════██║   ██║   ██╔══██║██╔══██╗   ██║   ██╔══╝  ██║  ██║
██║  ██║╚██████╔╝███████╗██║ ╚████║   ██║           ███████║   ██║   ██║  ██║██║  ██║   ██║   ███████╗██████╔╝
╚═╝  ╚═╝ ╚═════╝ ╚══════╝╚═╝  ╚═══╝   ╚═╝           ╚══════╝   ╚═╝   ╚═╝  ╚═╝╚═╝  ╚═╝   ╚═╝   ╚══════╝╚═════╝



                """);
    }
}
