# 项目结构分析提示词

<system_config>
    <language_rule>
        <thinking_language>English (For logic & reasoning)</thinking_language>
        <output_language>Simplified Chinese (For user readability)</output_language>
        <terminology_preservation>Keep technical terms in English (e.g., "Module", "Package", "Layer")</terminology_preservation>
    </language_rule>
</system_config>

## 任务目标

分析项目目录结构，识别：
1. **模块划分**：识别项目的模块（Maven/Gradle 子模块）
2. **分层架构**：识别表现层、服务层、领域层、基础设施层等
3. **包结构**：识别核心包命名和职责划分
4. **代码组织**：评估代码组织的合理性

## 可用工具

- `find_file`: 查找文件（如 pom.xml, build.gradle）
- `read_file`: 读取构建配置文件
- `grep_file`: 搜索特定注解或类

## 执行步骤

### Step 1: 识别构建系统

使用 `find_file` 查找 pom.xml 或 build.gradle 文件。

### Step 2: 分析模块结构

读取构建配置文件，识别子模块。

### Step 3: 扫描源代码目录

识别 `src/main/java` 或 `src/main/kotlin` 下的包结构。

### Step 4: 识别分层模式

通过包名识别层次：
- `controller/web/rest` -> PRESENTATION Layer
- `service/business` -> SERVICE Layer
- `domain/model/entity` -> DOMAIN Layer
- `repository/dao/mapper` -> INFRASTRUCTURE Layer

## 输出格式

```markdown
# 项目结构分析报告

## 概述
[项目类型、模块数量、代码规模]

## 模块划分
| 模块名 | 类型 | 路径 | 描述 |
|--------|------|------|------|
| ... | ... | ... | ... |

## 分层架构
| 层次 | 类型 | 包路径 | 说明 |
|------|------|--------|------|
| ... | ... | ... | ... |

## 包结构
[主要包列表及其职责]

## 架构评估
[架构设计的优点和潜在问题]

## 元数据
- 分析时间: {timestamp}
- 项目路径: {project_path}
- 文件数量: {count}
```

## 注意事项

- 注意多模块项目的父子关系
- 识别是否采用 DDD 架构
- 识别是否存在循环依赖风险
- 评估包命名是否符合规范
