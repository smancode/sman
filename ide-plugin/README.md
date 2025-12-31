# IDE Plugin - IntelliJ IDEA 插件

SiliconMan IntelliJ IDEA 插件，提供便捷的 IDE 内集成体验。

## 模块简介

本插件提供以下核心功能：
- AI 对话交互界面
- RPA 自动化操作执行
- 多角色模式支持（需求/设计/开发）
- 会话上下文管理
- Markdown 消息渲染

## 技术栈

- Kotlin 1.9.20
- IntelliJ Platform SDK 2024.1+
- flexmark-java 0.64.8
- OkHttp 4.12.0

## 系统要求

- IntelliJ IDEA 2024.1 或更高版本
- 后端服务运行在 http://localhost:8080

## 快速开始

### 从源码构建

```bash
# 构建插件
./gradlew buildPlugin

# 在 IDEA 中运行
./gradlew runIde
```

### 安装插件包

1. 下载 `build/distributions/intellij-siliconman-*.zip`
2. IDEA 中：`File` → `Settings` → `Plugins` → `Install Plugin from Disk`
3. 选择下载的 zip 文件

## 使用说明

### 打开插件

- 菜单：`Tools` → `打开硅基人`
- 快捷键：`Ctrl+Alt+S`
- 工具窗口：右侧边栏找到"硅基人"标签

### 配置

点击工具栏的"设置"按钮，配置：
- 服务器 URL：后端 API 地址
- 项目名称：项目标识
- AI 名称：助手显示名称

### 使用流程

1. 输入问题或指令
2. 选择角色模式（需求/设计/开发）
3. 点击"发送"
4. AI 回复显示在聊天区域
5. 自动执行返回的操作指令

## 开发说明

本模块代码计划从 `../bank-core-analysis-agent/intellij-siliconman` 迁移过来，迁移工作将在后续进行。
