plugins {
    kotlin("jvm") version "1.9.20"
    id("org.jetbrains.intellij") version "1.17.2"
}

group = "com.smancode.smanagent"
version = "1.1.0"

repositories {
    mavenCentral()
}

dependencies {
    // Java-WebSocket 客户端（纯 Java 实现，无依赖冲突）
    implementation("org.java-websocket:Java-WebSocket:1.5.4")

    // Jackson JSON 处理（与后端一致）
    implementation("com.fasterxml.jackson.core:jackson-databind:2.16.0")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.16.0")

    // Markdown 渲染（flexmark-java）
    implementation("com.vladsch.flexmark:flexmark-all:0.64.8")

    // 日志
    implementation("org.slf4j:slf4j-api:2.0.9")
    implementation("ch.qos.logback:logback-classic:1.4.11")
}

// IntelliJ Platform 配置
intellij {
    version.set("2024.1")
    type.set("IC") // IntelliJ IDEA Community Edition
    plugins.set(listOf("java"))
}

tasks {
    // Kotlin 编译配置
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions {
            jvmTarget = "17"
        }
    }

    // Java 编译配置
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }

    // 禁用构建可搜索选项
    buildSearchableOptions {
        enabled = false
    }

    // 补丁插件 XML
    patchPluginXml {
        sinceBuild.set("241")
        untilBuild.set("253.*")
    }

    // 签名插件（可选）
    signPlugin {
        certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
        privateKey.set(System.getenv("PRIVATE_KEY"))
        password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
    }

    // 发布插件到 JetBrains Marketplace（可选）
    publishPlugin {
        token.set(System.getenv("PUBLISH_TOKEN"))
    }

    // 将后端 JAR 打包到插件中
    prepareSandbox {
        from("../agent/build/libs/smanagent-agent-1.0.0.jar") {
            into("${project.name}/lib")
        }
    }
}
