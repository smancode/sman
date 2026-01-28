plugins {
    kotlin("jvm") version "1.9.20"
    id("org.jetbrains.intellij") version "1.17.3"
}

group = "com.smancode.sman"
version = "2.0.0"

repositories {
    mavenCentral()
}

dependencies {
    // Markdown 渲染（使用必要模块以减少包大小）
    implementation("com.vladsch.flexmark:flexmark:0.64.8") {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    }
    implementation("com.vladsch.flexmark:flexmark-profile-pegdown:0.64.8") {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    }
    implementation("com.vladsch.flexmark:flexmark-ext-tables:0.64.8")
    implementation("com.vladsch.flexmark:flexmark-ext-gfm-strikethrough:0.64.8")
    implementation("com.vladsch.flexmark:flexmark-ext-autolink:0.64.8")

    // HTTP 客户端
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Spring Web (用于 LLM 调用)
    implementation("org.springframework.boot:spring-boot-starter-web:3.2.0") {
        exclude(group = "org.springframework.boot", module = "spring-boot-starter-tomcat")
    }

    // WebSocket 客户端（纯 Java 实现）
    implementation("org.java-websocket:Java-WebSocket:1.5.4")

    // JSON 处理（使用 Jackson 与后端保持一致）
    implementation("com.fasterxml.jackson.core:jackson-databind:2.16.0")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.16.0")

    // 备用 JSON 库（向后兼容）
    implementation("org.json:json:20231013")

    // 日志
    implementation("org.slf4j:slf4j-api:2.0.9")
    implementation("ch.qos.logback:logback-classic:1.4.11")

    // 测试依赖
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testImplementation("org.jetbrains.kotlin:kotlin-test:1.9.20")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:1.9.20")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.1.0")
    testImplementation("io.mockk:mockk:1.13.8")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("org.springframework.boot:spring-boot-starter-test:3.2.0") {
        exclude(group = "org.springframework.boot", module = "spring-boot-starter-tomcat")
    }
}

// IntelliJ Platform 配置
intellij {
    version.set("2024.1")
    type.set("IC") // IntelliJ IDEA Community Edition
    plugins.set(listOf("java"))

    // JavaFX支持配置
    downloadSources.set(true)
    updateSinceUntilBuild.set(false)
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

    // 禁用构建可搜索选项（加快构建速度）
    buildSearchableOptions {
        enabled = false
    }

    // 测试配置
    test {
        useJUnitPlatform()
        // 测试输出
        testLogging {
            events("passed", "skipped", "failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            showExceptions = true
            showCauses = true
            showStackTraces = true
        }
    }

    // 补丁插件 XML
    patchPluginXml {
        sinceBuild.set("241")
        untilBuild.set("253.*")

        // 更新插件描述
        pluginDescription.set("""
            SmanAgent 统一插件 - 整合了智能代码分析、AI 对话、代码编辑等功能。

            核心功能：
            - 🤖 AI 驱动的代码分析和需求理解
            - 💬 多轮对话支持
            - 🔧 12+ 本地工具集成（read_class, text_search, call_chain等）
            - 📊 代码结构分析和调用链可视化
            - 🎯 三阶段工作流（Analyze → Plan → Execute）
            - 🛡️ 降级模式支持
            - 🔌 WebSocket 实时通信
            - ✏️ 代码编辑和重构支持
        """.trimIndent())

        changeNotes.set("""
            <h3>2.0.0</h3>
            <ul>
                <li>🚀 重大整合：合并 SiliconMan、SmanAgent、SmanCode 三个项目</li>
                <li>✨ 统一架构：采用 com.smancode.sman 包结构</li>
                <li>🔧 功能整合：整合所有核心功能到单一插件</li>
                <li>📦 依赖优化：统一依赖版本，减少冲突</li>
            </ul>
        """)
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
