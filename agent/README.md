# Agent - 后端分析服务

SiliconMan Agent 是基于 Spring Boot 的代码分析后端服务。

## 模块简介

本模块提供以下核心能力：
- 深度代码分析（基于 Spoon AST 和 Neo4j）
- AI 驱动的智能任务处理
- 业务映射和知识图谱构建
- MCP 协议支持

## 技术栈

- Java 21
- Spring Boot 3.2.5
- MySQL 9.1.0
- Neo4j 4.0.10
- MyBatis 3.0.3
- Spoon 11.0.0
- DeepSeek API

## 快速开始

```bash
# 构建项目
./gradlew build

# 启动服务
./start.sh

# 健康检查
curl http://localhost:8080/api/test/health
```

## 核心功能

- 双分支智能处理架构（闲聊分支 + 分析分支）
- 分层任务处理（TaskList → SubTaskList → TaskResultList）
- 代码知识图谱构建
- 业务标签提取和映射
- AI 辅助编码

## API 文档

详细 API 接口文档将在代码迁移完成后补充。

## 开发说明

本模块代码计划从 `../bank-core-analysis-agent` 迁移过来，迁移工作将在后续进行。
