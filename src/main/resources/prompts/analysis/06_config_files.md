# 配置文件分析提示词

<system_config>
    <language_rule>
        <thinking_language>English (For logic & reasoning)</thinking_language>
        <output_language>Simplified Chinese (For user readability)</output_language>
        <terminology_preservation>Keep technical terms in English (e.g., "application.yml", "pom.xml")</terminology_preservation>
    </language_rule>
</system_config>

## 任务目标

扫描项目配置文件：
1. **构建配置**：pom.xml, build.gradle, settings.gradle
2. **应用配置**：application.yml, application.properties
3. **Spring 配置**：beans.xml, applicationContext.xml
4. **MyBatis 配置**：mapper XML, mybatis-config.xml
5. **日志配置**：logback.xml, log4j2.xml
6. **环境配置**：dev, test, prod 环境差异

## 可用工具

- `find_file`: 查找配置文件
- `read_file`: 读取配置内容
- `extract_xml`: 提取 XML 配置

## 执行步骤

### Step 1: 查找配置文件

使用 `find_file` 查找 pom.xml, build.gradle, application*.yml, *.properties 等文件。

### Step 2: 分析构建配置

从 pom.xml 或 build.gradle 中提取依赖版本、插件配置、子模块定义。

### Step 3: 分析应用配置

从 application.yml 或 application.properties 中提取数据源配置、中间件配置、服务端口、环境变量。

### Step 4: 分析 XML 配置

使用 `extract_xml` 提取 mapper、bean、configuration 等标签。

## 输出格式

```markdown
# 配置文件分析报告

## 概述
[配置文件数量、类型分布]

## 构建配置
| 文件 | 类型 | 主要内容 |
|------|------|----------|
| ... | ... | ... |

## 应用配置
| 文件 | 环境 | 关键配置项 |
|------|------|-------------|
| ... | ... | ... |

### 数据源配置
[数据库连接配置详情]

### 中间件配置
[Redis, Kafka 等配置详情]

## XML 配置
| 文件 | 类型 | 描述 |
|------|------|------|
| ... | ... | ... |

## 环境差异
[dev/test/prod 环境配置差异]

## 配置评估
[配置管理的规范性分析]

## 元数据
- 分析时间: {timestamp}
- 项目路径: {project_path}
- 配置文件数: {count}
```

## 注意事项

- 注意敏感信息是否硬编码（密码、密钥）
- 注意配置是否外部化
- 注意环境配置是否分离
- 注意配置文件命名是否符合规范
- 注意是否有冗余配置
