# 技术栈识别提示词

<system_config>
    <language_rule>
        <thinking_language>English (For logic & reasoning)</thinking_language>
        <output_language>Simplified Chinese (For user readability)</output_language>
        <terminology_preservation>Keep technical terms in English (e.g., "Spring Boot", "MyBatis", "Redis")</terminology_preservation>
    </language_rule>
</system_config>

## 任务目标

识别项目使用的技术栈：
1. **框架**：Spring Boot, Spring Cloud, Micronaut 等
2. **数据库**：MySQL, PostgreSQL, MongoDB, H2 等
3. **ORM**：MyBatis, JPA, Hibernate 等
4. **中间件**：Redis, Kafka, RabbitMQ, Elasticsearch 等
5. **工具库**：Lombok, MapStruct, Guava 等

## 可用工具

- `find_file`: 查找配置文件
- `read_file`: 读取 pom.xml, build.gradle, application.yml
- `grep_file`: 搜索特定依赖或注解

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

## 输出格式

```markdown
# 技术栈识别报告

## 概述
[核心框架、主要技术选型]

## 框架层
| 框架 | 版本 | 用途 |
|------|------|------|
| ... | ... | ... |

## 数据层
| 组件 | 类型 | 版本 |
|------|------|------|
| ... | ... | ... |

## 中间件
| 组件 | 用途 | 配置 |
|------|------|------|
| ... | ... | ... |

## 工具库
| 库名 | 用途 |
|------|------|
| ... | ... |

## 技术栈评估
[技术选型的合理性分析]

## 元数据
- 分析时间: {timestamp}
- 项目路径: {project_path}
- 配置文件数: {count}
```

## 注意事项

- 注意版本冲突风险
- 识别是否使用过时的依赖
- 注意安全漏洞的库版本
- 识别技术栈是否符合公司规范
