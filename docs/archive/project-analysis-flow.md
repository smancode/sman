# 项目分析执行流程

## 概述

Sman 项目分析功能通过后台调度器 `ProjectAnalysisScheduler` 自动执行，包含 12 种分析模块，定期（每 5 分钟）检查并执行待分析任务。

## 分析模块

| # | 模块 | 功能描述 | 生成文件 |
|---|------|----------|-----------|
| 1 | ProjectStructureScanner | 扫描项目目录结构 | `projectStructure.md` |
| 2 | TechStackDetector | 识别技术栈和框架 | `techStack.md` |
| 3 | PsiAstScanner | 提取类、方法、字段信息 | `astAnalysis/` 目录 |
| 4 | DbEntityScanner | 识别数据库实体 | `dbEntities/` 目录 |
| 5 | ApiEntryScanner | 识别 HTTP/API 入口 | `apiEntries/` 目录 |
| 6 | ExternalApiScanner | 识别 Feign/Retrofit/HTTP 客户端 | `externalApis/` 目录 |
| 7 | EnumScanner | 提取枚举定义 | `enums/` 目录 |
| 8 | CommonClassScanner | 识别工具类和帮助类 | `commonClasses/` 目录 |
| 9 | XmlCodeScanner | 解析 MyBatis 等 XML | `xmlConfigs/` 目录 |
| 10 | CaseSopGenerator | 生成标准操作流程 | `caseSop/` 目录 |
| 11 | CodeVectorizationService | LLM 生成业务描述 | `codeVectorization/` 目录 |
| 12 | CodeWalkthroughGenerator | 生成架构分析报告 | `codeWalkthrough/` 目录 |

## 执行流程

### 1. 启动流程

```
┌─────────────────────────────────────────────────────────────┐
│ IDE 启动                                            │
│   ↓                                                     │
│   ProjectAnalysisScheduler.start()                      │
│   ↓                                                     │
│   检查项目和配置                                    │
│   ├─ 项目是否已注册 (ProjectMapManager)          │
│   ├─ API Key 是否配置 (StorageService)            │
│   └─ 自动分析是否启用 (SmanConfig)           │
│   ↓                                                     │
│   创建调度任务 (每 5 分钟)                     │
│   ↓                                                     │
│   checkAndExecuteAnalysis()                           │
│   ├─ 检查 pending 分析任务                       │
│   │   └─ 遍历所有分析类型                          │
│       ├─ ProjectStructureScanner?                      │
│       ├─ TechStackDetector?                         │
│       ├─ PsiAstScanner?                             │
│       ├─ DbEntityScanner?                            │
│       ├─ ApiEntryScanner?                             │
│       ├─ ExternalApiScanner?                          │
│       ├─ EnumScanner?                              │
│       ├─ CommonClassScanner?                           │
│       ├─ XmlCodeScanner?                             │
│       ├─ CaseSopGenerator?                           │
│       ├─ CodeVectorizationService? (可选)              │
│       └─ CodeWalkthroughGenerator?                     │
│   ↓                                                     │
│   根据类型执行分析器                             │
│   └─ AnalysisTaskExecutor.execute(type)               │
│       ├─ 创建执行上下文                             │
│       ├─ 调用相应的扫描器                           │
│       ├─ 等待分析完成                               │
│       └─ 返回结果                                    │
│   ↓                                                     │
│   更新 ProjectEntry 状态                          │
│   └─ ProjectMapManager.updateEntry()                  │
│       ├─ projectStructure: PENDING → RUNNING          │
│       ├─ techStack: PENDING → RUNNING              │
│       ├─ astScan: PENDING → COMPLETED             │
│       ├─ dbEntities: PENDING → COMPLETED             │
│       ├─ apiEntries: PENDING → COMPLETED             │
│       ├─ externalApis: PENDING → COMPLETED          │
│       ├─ enums: PENDING → COMPLETED               │
│       ├─ commonClasses: PENDING → COMPLETED          │
│       ├─ xmlConfigs: PENDING → COMPLETED          │
│       ├─ caseSop: PENDING → COMPLETED          │
│       ├─ codeVectorization: PENDING → COMPLETED        │
│       └─ codeWalkthrough: PENDING → COMPLETED          │
│   ↓                                                     │
│   保存分析结果到文件                               │
│   └─ 生成/更新 MD5 文件                          │
│       ├─ ~/.sman/{projectKey}/base/                  │
│       │   ├─ projectStructure.md                    │
│       │   ├─ techStack.md                        │
│       │   ├─ astAnalysis/                       │
│       │   │   ├─ dbEntities/                       │
│       │   │   ├─ apiEntries/                       │
│       │   │   ├─ externalApis/                      │
│       │   │   ├─ enums/                           │
│       │   │   ├─ commonClasses/                    │
│       │   │   ├─ xmlConfigs/                      │
│       │   │   ├─ caseSop/                        │
│       │   │   ├─ codeVectorization/                │
│       │   │   └─ codeWalkthrough/                 │
│       └─ 更新 ProjectEntry 状态（ALL COMPLETED）        │
└─────────────────────────────────────────────────────────────┘
```

### 2. 数据存储位置

**项目注册文件**（全局）：`~/.sman/project_map.json`
- 存储所有已注册项目的信息（projectKey、路径、MD5、分析状态）

**分析数据文件**（项目特定）：`{projectRoot}/.sman/` 目录
- 各项目的分析数据存储在各自项目目录的 `.sman/ 文件夹中
- 例如：`~/projects/autoloop/.sman/`

**生成的文件结构**：
```
{projectRoot}/.sman/
├── md/                          # Markdown 分析结果
│   ├── projectStructure.md         # 项目结构
│   └── techStack.md              # 技术栈
├── astAnalysis/                  # AST 分析结果
│   ├── dbEntities/               # 数据库实体
│   ├── apiEntries/               # API 入口
│   ├── externalApis/             # 外部 API
│   ├── enums/                   # 枚举类
│   ├── commonClasses/            # 工具类
│   └── xmlConfigs/              # XML 配置
├── caseSop/                     # 案例 SOP
├── codeVectorization/             # 代码向量化
└── codeWalkthrough/             # 代码走读
```

### 文件内容说明

1. **projectStructure.md**
   - 项目目录树结构
   - 模块列表
   - 依赖关系（如果可识别）

2. **techStack.md**
   - 识别的框架（Spring Boot、MyBatis-Plus 等）
   - 版本信息
   - 主要依赖

3. **astAnalysis/ 目录**
   - 每个类的 AST 分析结果
   - 类继承关系
   - 方法签名
   - 字段信息

4. **dbEntities/ 目录**
   - 每个实体类的分析结果
   - 表名、字段、关系
   - 索引信息

5. **apiEntries/ 目录**
   - 每个 API 入口的分析
   - 路径、方法、参数、返回类型

6. **externalApis/ 目录**
   - 每个 HTTP 客户的分析
   - 端点 URL、调用方法

7. **enums/ 目录**
   - 每个枚举类的定义
   - 常量和使用

8. **commonClasses/ 目录**
   - 工具类和帮助类的分析

9. **xmlConfigs/ 目录**
   - MyBatis Mapper 配置
   - SQL 映射文件

10. **caseSop/ 目录**
   - 每个功能模块的 SOP 文档
   - 操作步骤、注意事项

11. **codeVectorization/ 目录**
   - LLM 生成的业务描述
   - 按代码块分组

12. **codeWalkthrough/ 目录**
   - 整体架构分析
   - 设计模式识别
   - 核心逻辑流程

### 如何触发分析

1. **自动触发**：IDE 启动后，`ProjectAnalysisScheduler` 每 5 分钟自动检查并执行

2. **手动触发**：通过设置对话框中的"强制刷新项目分析"选项（`analysis.force.refresh` 配置项）

3. **配置检查**：
   - API Key 已配置
   - 自动分析开关已启用
   - 项目已打开并注册

### 监控和日志

所有分析操作都会记录日志，可以通过查看日志了解执行情况：
- 启动日志："启动项目分析调度器: project={}"
- 分析日志："执行分析任务: type=xxx"
- 完成日志："分析已完成: type=xxx, status=COMPLETED"
- 错误日志："执行分析失败: {}"
