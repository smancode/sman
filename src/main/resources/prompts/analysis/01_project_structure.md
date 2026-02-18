# 项目结构分析提示词

<system_config>
    <language_rule>
        <thinking_language>English (For logic & reasoning)</thinking_language>
        <output_language>Simplified Chinese (For user readability)</output_language>
        <terminology_preservation>Keep technical terms in English (e.g., "Module", "Package", "Layer")</terminology_preservation>
    </language_rule>
</system_config>

# ⚠️ 执行协议（必须严格遵守）

## 第一阶段：工具扫描（必须执行）

**在输出任何文字之前，你必须先调用工具扫描项目代码。**

根据任务目标，选择合适的工具进行扫描：
- `find_file`: 按文件名模式查找文件
- `grep_file`: 搜索注解、关键字、类名等
- `read_file`: 读取具体文件内容

**如果你没有调用任何工具就输出文字，你的分析结果将被拒绝。**

## 第二阶段：输出报告

完成工具扫描后，按照下方格式输出 Markdown 分析报告。

## 禁止行为（违反将被拒绝）

- ❌ 输出"你好"、"请问"、"请告诉我"等问候语
- ❌ 输出"需要我做什么"、"请问你想了解什么"等等待用户的内容
- ❌ 没有调用工具就直接输出分析结果

---

## 任务目标

分析项目目录结构，识别：
1. **模块划分**：识别项目的模块（Maven/Gradle 子模块），**每个模块都要说清楚是做什么业务的**
2. **分层架构**：识别表现层、服务层、领域层、基础设施层等
3. **包结构**：识别核心包命名和职责划分
4. **代码组织**：评估代码组织的合理性

## 关键要求

### 模块业务理解（核心！）

对于每个识别到的模块（module），必须给出：
- **模块名称**：如 common, core, web, loan 等
- **业务含义**：这个模块承载什么业务功能？是公共组件还是具体业务？
- **核心类/接口**：列出关键的类名
- **依赖关系**：模块之间的依赖

**示例**：
| 模块名 | 业务含义 | 关键类 | 依赖 |
|--------|----------|--------|------|
| loan | 贷款核心业务模块 | DisburseHandler, RepayHandler, RepaymentScheduleEngine | common |
| core | 项目分析核心功能 | ProjectAnalysisService, JavaParserService | common |

### 分层架构识别

通过包名识别层次：
- `controller/web/rest` -> PRESENTATION Layer
- `service/business` -> SERVICE Layer
- `domain/model/entity` -> DOMAIN Layer
- `repository/dao/mapper` -> INFRASTRUCTURE Layer
- `handler` -> 业务处理器层

## 可用工具

- `find_file`: 查找文件（如 pom.xml, build.gradle）
- `read_file`: 读取构建配置文件和源代码
- `grep_file`: 搜索特定注解或类

## 执行步骤

### Step 1: 识别构建系统
使用 `find_file` 查找 pom.xml 或 build.gradle 文件。

### Step 2: 分析模块结构
读取 settings.gradle 或 pom.xml，识别子模块名称。

### Step 3: 扫描每个模块的源代码
对于每个模块，扫描 `src/main/java` 下的包结构，识别：
- 主要的包（package）
- 包下的核心类
- 类的命名模式（推断业务含义）

### Step 4: 识别分层模式
通过包名识别层次结构。

### Step 5: 归纳业务模块职责
**重要**：根据模块名、包结构、核心类名，综合判断每个模块的业务含义。

## 输出格式

```markdown
# 项目结构分析报告

## 概述
[项目类型、模块数量、代码规模]

## 模块划分（核心！）
| 模块名 | 路径 | 业务含义 | 关键类/接口 | 依赖 |
|--------|------|----------|-------------|------|
| ... | ... | ... | ... | ... |

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

- **每个模块必须给出业务含义**，不能只写"公共模块"
- 注意多模块项目的父子关系
- 识别是否采用 DDD 架构
- 识别是否存在循环依赖风险
- 评估包命名是否符合规范
