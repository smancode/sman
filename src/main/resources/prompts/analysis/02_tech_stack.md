# 技术栈识别提示词

<system_config>
    <language_rule>
        <thinking_language>English (For logic & reasoning)</thinking_language>
        <output_language>Simplified Chinese (For user readability)</output_language>
        <terminology_preservation>Keep technical terms in English (e.g., "Spring Boot", "MyBatis", "Redis")</terminology_preservation>
    </language_rule>
</system_config>

# ⚠️ 强制执行协议（CRITICAL）

## 🔴 重要：这是无人值守的自动化任务

**没有用户交互！不要说"你好"、"请问"、"我可以帮你"！**

## 🚫 禁止行为（违反将导致任务失败）

```
❌ 你好，我是架构师...
❌ 请问你想了解项目使用了哪些技术？
❌ 我可以帮你分析技术栈
❌ 让我来为你分析...
❌ 我将按照以下步骤执行...
❌ 需要我详细分析哪个技术？
```

## ✅ 正确行为（必须执行）

**步骤 1**: 调用 `find_file(filePattern="build.gradle*")` 或 `find_file(filePattern="pom.xml")`
**步骤 2**: 调用 `read_file` 读取构建配置文件
**步骤 3**: 调用 `find_file(filePattern="**/application*.yml")` 或 `find_file(filePattern="**/application*.properties")`
**步骤 4**: 调用 `grep_file(pattern="@SpringBootApplication")` 查找启动类
**步骤 5**: 直接输出 Markdown 格式的分析报告

---

## 任务目标

识别项目使用的技术栈：
1. **框架**：Spring Boot, Spring Cloud, Micronaut 等
2. **数据库**：MySQL, PostgreSQL, MongoDB, H2 等
3. **ORM**：MyBatis, JPA, Hibernate 等
4. **中间件**：Redis, Kafka, RabbitMQ, Elasticsearch 等
5. **工具库**：Lombok, MapStruct, Guava 等

**重要**：结合已有的项目结构分析结果，验证技术栈与业务模块的匹配度。

## 核心要求

### 技术栈与业务匹配

- 根据模块结构推断技术栈的合理性
- 识别不同模块是否使用不同的技术选型

### 主启动类识别

- 找出 @SpringBootApplication 启动类
- 识别服务端口配置

## 执行步骤

### Step 1: 读取构建配置
使用 `find_file` 查找 pom.xml 或 build.gradle 文件。

### Step 2: 分析依赖
从构建配置中提取：
- Spring Boot 版本
- 数据库驱动
- 中间件客户端
- 工具库

### Step 3: 扫描注解使用
使用 `grep_file` 搜索 @SpringBootApplication、@RestController、@Entity 等注解。

### Step 4: 读取应用配置
查找 application.yml 或 application.properties 文件。

### Step 5: 识别主启动类
找到 @SpringBootApplication 类，确定：
- 启动类全限定名
- 扫描的包路径
- 服务端口

## 输出格式（必须使用 Markdown）

```markdown
# 技术栈识别报告

## 概述
[核心框架、主要技术选型]

## 项目信息
| 项目 | 信息 |
|------|------|
| 主启动类 | com.xxx.XxxApplication |
| 服务端口 | 8080 |
| 扫描包 | com.xxx |

## 框架层
| 框架 | 版本 | 用途 |
|------|------|------|
| Spring Boot | 3.x | 核心框架 |
| ... | ... | ... |

## 数据层
| 组件 | 类型 | 版本 |
|------|------|------|
| H2 | 内存数据库 | 2.2.x |
| MyBatis | ORM | 3.0.x |

## 中间件
| 组件 | 用途 | 配置 |
|------|------|------|
| ... | ... | ... |

## 工具库
| 库名 | 用途 |
|------|------|
| Lombok | 代码简化 |

## 技术栈评估
- [技术选型的合理性分析]
- [版本是否过时的风险]
- [是否存在安全漏洞]
```

## 注意事项

- 准确识别主启动类和端口
- 注意版本冲突风险
- 识别是否使用过时的依赖
- 注意安全漏洞的库版本

---

**再次提醒**：立即调用工具开始分析，不要输出任何对话式内容！
