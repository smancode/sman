# SiliconMan (SMAN)

SiliconMan 是一个智能代码分析和开发助手系统，包含后端分析服务和 IntelliJ IDEA 插件。

## 项目概述

SiliconMan 通过 AI 驱动的代码分析引擎，为开发者提供智能代码理解、业务映射和自动化编码辅助。系统采用前后端分离架构：

- **Agent（后端）**: 基于 Spring Boot 的代码分析服务，提供深度代码分析、知识图谱构建和 AI 辅助能力
- **IDE Plugin（前端）**: IntelliJ IDEA 插件，提供便捷的 IDE 内集成体验

## 项目结构

```
sman/
├── README.md           # 项目总入口（本文件）
├── LICENSE             # MIT 开源协议
├── docs/               # 全局文档
│   └── README.md       # 文档索引
│
├── agent/              # 【后端】核心分析服务
│   ├── src/            # Java 源码
│   └── README.md       # 后端详细文档（API 接口、架构设计等）
│
├── ide-plugin/         # 【前端】IntelliJ 插件
│   ├── src/            # Kotlin 源码
│   ├── build.gradle.kts
│   └── README.md       # 插件使用指南
│
└── shared/             # 共享定义
    └── api-schema/     # API 协议定义（可选）
```

## 快速开始

### 环境要求

**Agent 后端：**
- Java 21+
- Gradle 8.0+
- Claude Code CLI (通过 npm 安装)

**IDE 插件：**
- IntelliJ IDEA 2024.1+
- Kotlin 1.9.20

### 安装运行

#### 1. 启动后端服务

```bash
cd agent
./gradlew build
./start.sh
```

访问健康检查：http://localhost:8080/api/test/health

#### 2. 安装 IDE 插件

```bash
cd ide-plugin
./gradlew buildPlugin
./gradlew runIde
```

或从 `build/distributions/` 安装插件包到 IDEA。

## 核心功能

### Agent 后端
- 🔍 **深度代码分析**: 基于 Spoon AST 的代码结构分析
- 🔗 **调用链分析**: 基于正则匹配的方法调用关系分析
- 🧠 **AI 驱动**: 集成 Claude Code CLI,提供智能分析能力
- 💬 **多轮对话**: 支持 `--resume` 参数,自动记忆对话历史
- 🗺️ **业务映射**: 建立业务需求与代码实现的精确映射
- 🔍 **向量搜索**: 基于 JVector 的语义代码搜索

### IDE 插件
- 💬 **AI 对话**: 自然语言交互界面
- 🤖 **RPA 自动化**: IDE 操作自动化执行
- 🎭 **多角色模式**: 需求(ask)、设计(plan)、开发(agent)
- 📝 **Markdown 渲染**: 丰富的消息展示

## 技术栈

| 模块 | 技术栈 |
|------|--------|
| Agent | Java 21, Spring Boot 3.2.5, Spoon 11.0.0, JVector 3.0.6, Claude Code CLI |
| IDE Plugin | Kotlin 1.9.20, IntelliJ Platform SDK, OkHttp |

## 文档导航

- [后端详细文档](agent/README.md) - API 接口、架构设计
- [插件使用指南](ide-plugin/README.md) - 安装配置、功能说明
- [全局文档](docs/README.md) - 设计文档、开发指南
- [多轮对话实现](docs/md/multi_turn.md) - 多轮对话技术方案

## 开发路线

当前项目处于初始化阶段，代码迁移工作将在后续进行。

## 贡献指南

欢迎贡献代码、报告问题或提出建议。

## 许可证

本项目采用 [MIT License](LICENSE) 开源协议。

## 联系方式

如有问题或建议，请联系开发团队。
