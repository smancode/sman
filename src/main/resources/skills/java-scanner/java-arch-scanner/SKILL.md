---
name: java-arch-scanner
description: 快速了解项目的整体架构，包括模块划分、依赖关系、技术栈和包结构分层。
license: MIT
---

# Java 项目架构扫描器（元 Skill）

> **这是元 Skill**：提供扫描指导原则，边扫描边学习，发现项目特有模式后生成项目专属 Skill。

## 作用
快速了解项目的整体架构：模块划分、依赖关系、技术栈。

## 目标
生成项目架构概览，包括：
- 模块/子项目结构（Maven多模块或Gradle多项目）
- 主要依赖和技术栈
- 包结构分层（如 controller/service/repository/domain）

## 边扫描边学习（重要！）

扫描过程中，**发现任何项目特有的模式都要记录**：

### 需要关注的发现
1. **自定义模块划分规则** - 如按业务域划分、按技术层划分
2. **内部框架/组件** - 公司自研的 starter、common 库
3. **特殊的包结构约定** - 与标准 Spring 结构不同的地方
4. **技术选型偏好** - 如统一使用某个 ORM、缓存方案
5. **模块间依赖规则** - 哪些模块可以依赖哪些模块

### 发现后立即写入项目 Skill
在项目目录下创建或更新 `project-architecture.md`，记录：
- 该项目的模块职责划分
- 内部框架使用方式
- 包结构和分层约定
- 技术栈清单及版本

## 执行策略（避免 Token 爆炸）

> **重要**：存量 Java 项目类成千上万，LLM 上下文窗口有限，必须分批执行！

### 分批扫描原则
1. **按模块分批** - 每次只分析一个模块/子项目
2. **按文件类型分批** - pom.xml → application.yml → Java 源码
3. **增量记录** - 每批扫描完立即写入项目 Skill

### 推荐执行方式
```
第1轮：分析根 pom.xml → 记录模块列表和依赖版本
第2轮：分析 application.yml → 记录关键配置
第3轮：扫描各模块包结构 → 记录分层约定
...以此类推
```

### 优先级
1. 先看构建文件（pom.xml/build.gradle）- 快速了解全貌
2. 再看配置文件 - 了解技术栈
3. 最后看源码结构 - 了解分层

## 扫描步骤

### 1. 识别项目类型和构建工具
- 查找 `pom.xml` (Maven) 或 `build.gradle` / `build.gradle.kts` (Gradle)
- 如果是多模块项目，解析 `<modules>` 或 `include` 获取子模块列表

### 2. 提取技术栈
- 读取根 pom.xml 或 build.gradle 的 dependencies
- 识别关键框架：Spring Boot、Spring Cloud、MyBatis、Hibernate、Dubbo、Feign 等
- 记录版本号

### 3. 分析包结构
- 在 src/main/java 下遍历目录
- 识别常见分层：controller、service、repository/dao、model/entity/domain、config、util、common
- 统计每个包下的类数量

### 4. 识别模块职责
- 根据包名和类名推断模块功能
- 如：user-*、order-*、payment-* 表示用户、订单、支付模块

## 输出格式

```markdown
## 项目概览
- 构建工具: Maven/Gradle
- 项目类型: 单模块/多模块
- 主要框架: Spring Boot 2.7.x, MyBatis Plus, Redis...

## 模块结构
| 模块名 | 路径 | 职责 |
|--------|------|------|
| core | /core | 核心通用 |
| user | /user-service | 用户服务 |
| order | /order-service | 订单服务 |

## 包结构分层
| 层级 | 包路径 | 类数量 |
|------|--------|--------|
| controller | com.xxx.controller | 23 |
| service | com.xxx.service | 45 |
| repository | com.xxx.repository | 30 |

## 技术栈清单
- Web框架: Spring Boot 2.7.18
- ORM: MyBatis Plus 3.5.3
- 数据库: MySQL 8.0
- 缓存: Redis
- 消息: RocketMQ
- RPC: Dubbo 3.0
```

## 注意事项
- 忽略 test 目录下的代码
- 关注 src/main/java 为主
- 多模块项目要逐个模块分析
- 配置文件中的自定义配置项可记录
