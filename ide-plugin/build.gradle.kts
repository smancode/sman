plugins {
    kotlin("jvm") version "1.9.20"
    id("org.jetbrains.intellij") version "1.17.2"
}

group = "ai.smancode.sman"
version = "3.3.0"

repositories {
    mavenCentral()
}

dependencies {
    // Markdown 渲染
    implementation("com.vladsch.flexmark:flexmark-all:0.64.8")

    // HTTP 客户端
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // JSON 处理
    implementation("org.json:json:20230227")
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
}
