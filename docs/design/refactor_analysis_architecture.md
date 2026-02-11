# 项目分析架构重构方案

> **核心理念**: 用 ReAct Loop + 提示词替代写死的 Pipeline 步骤

## 架构变更概览

### 旧架构（当前）

```
ProjectAnalysisPipeline (写死的步骤)
├── AnalysisStep 接口
├── 9 个固定 Step 实现
├── ProjectAnalysisRepository
└── 手动触发
```

### 新架构（目标）

```
ProjectAnalysisScheduler (后台循环)
├── AnalysisTaskExecutor (ReAct Loop 执行器)
├── 提示词驱动的分析类型
├── MD 文件存储
└── 自动后台分析
```

---

## 一、保留的基础组件

| 组件 | 文件路径 | 用途 |
|------|----------|------|
| `H2DatabaseService` | `analysis/database/H2DatabaseService.kt` | 向量片段持久化 |
| `TieredVectorStore` | `analysis/database/TieredVectorStore.kt` | 三层向量存储 |
| `JVectorStore` | `analysis/database/JVectorStore.kt` | HNSW 索引 |
| `BgeM3Client` | `analysis/vectorization/BgeM3Client.kt` | BGE 客户端 |
| `RerankerClient` | `analysis/vectorization/RerankerClient.kt` | 重排序客户端 |
| `StorageService` | `ide/service/StorageService.kt` | 配置持久化 |
| `SmanAgentLoop` | `smancode/core/SmanLoop.kt` | ReAct 循环核心 |

---

## 二、需要删除的组件

### 完全删除的目录和文件

```
analysis/pipeline/
├── ProjectAnalysisPipeline.kt

analysis/step/
├── AnalysisStep.kt
├── ApiEntryScanningStep.kt
├── CommonClassScanningStep.kt
├── DbEntityDetectionStep.kt
├── EnumScanningStep.kt
├── ExternalApiScanningStep.kt
├── LlmCodeVectorizationStep.kt
├── ProjectStructureStep.kt
├── TechStackDetectionStep.kt
└── XmlCodeScanningStep.kt

analysis/scanner/ (空目录)

analysis/repository/
├── ProjectAnalysisRepository.kt

analysis/service/
└── (相关服务)
```

---

## 三、新增组件设计

### 3.1 ProjectAnalysisScheduler (后台调度器)

**文件**: `analysis/scheduler/ProjectAnalysisScheduler.kt`

```kotlin
class ProjectAnalysisScheduler(
    private val project: Project,
    private val intervalMs: Long = 300_000  // 默认 5 分钟
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var enabled = true  // 默认开启

    fun start() { /* 后台循环 */ }
    fun stop() = { enabled = false }
    fun toggle() = { enabled = !enabled }
}
```

### 3.2 ProjectMapManager (项目状态管理)

**文件**: `analysis/model/ProjectMapManager.kt`

**ProjectMap JSON 结构** (`~/.sman/project_map.json`):

```json
{
  "version": "1.0",
  "lastUpdate": "2025-01-15T10:30:00",
  "projects": {
    "smanunion": {
      "path": "/Users/liuchao/projects/smanunion",
      "lastAnalyzed": "2025-01-15T10:25:00",
      "projectMd5": "abc123...",
      "analysisStatus": {
        "project_structure": "completed",
        "tech_stack": "completed",
        "api_entries": "pending"
      }
    }
  }
}
```

### 3.3 AnalysisTaskExecutor (分析执行器)

**文件**: `analysis/executor/AnalysisTaskExecutor.kt`

```kotlin
class AnalysisTaskExecutor(
    private val project: Project,
    private val llmService: LlmService
) {
    suspend fun executeAnalysisTask(
        projectKey: String,
        analysisType: AnalysisType
    ): AnalysisResult {
        // 1. 构建专用提示词
        // 2. 创建临时会话
        // 3. 执行 ReAct Loop
        // 4. 保存为 MD
        // 5. 同步到 H2
    }
}
```

### 3.4 AnalysisType (分析类型枚举)

**文件**: `analysis/model/AnalysisType.kt`

```kotlin
enum class AnalysisType(
    val key: String,
    val displayName: String,
    val fileName: String,
    val promptPath: String
) {
    PROJECT_STRUCTURE("project_structure", "项目结构分析", "01_project_structure.md"),
    TECH_STACK("tech_stack", "技术栈识别", "02_tech_stack.md"),
    API_ENTRIES("api_entries", "API 入口扫描", "03_api_entries.md"),
    DB_ENTITIES("db_entities", "数据库实体分析", "04_db_entities.md"),
    ENUMS("enums", "枚举类分析", "05_enums.md"),
    CONFIG_FILES("config_files", "配置文件分析", "06_config_files.md")
}
```

---

## 四、提示词模板

**目录**: `src/main/resources/prompts/analysis/`

| 提示词文件 | 分析类型 | 说明 |
|----------|----------|------|
| `01_project_structure.md` | 项目结构 | 目录结构、模块划分 |
| `02_tech_stack.md` | 技术栈 | 框架、数据库、中间件 |
| `03_api_entries.md` | API 入口 | PSI 扫描 @RestController |
| `04_db_entities.md` | DB 实体 | 扫描 @Entity/@Table |
| `05_enums.md` | 枚举 | 扫描 enum class |
| `06_config_files.md` | 配置文件 | 扫描 xml/properties/yml |

---

## 五、MD 文件组织

```
{projectRoot}/.sman/
├── base/                    # 基础信息分析 (6 个文件)
│   ├── 01_project_structure.md
│   ├── 02_tech_stack.md
│   ├── 03_api_entries.md
│   ├── 04_db_entities.md
│   ├── 05_enums.md
│   └── 06_config_files.md
├── md/                      # 类和方法分析
│   ├── UserService.md
│   └── OrderController.md
├── sessions/                 # ReAct Loop 会话
│   └── {sessionId}.json
└── cache/                   # MD5 追踪
    └── md5_cache.json
```

---

## 六、UI 改动

### SettingsDialog 修改

**删除**:
- "项目分析" 按钮
- "定时分析" 按钮
- 项目分析配置对话框

**新增**:
- "自动分析" 开关 (JToggleButton，默认打开)
- 显示后台分析状态

### SmanPlugin 修改

插件启动时启动后台调度器（检查 API Key 后）。

---

## 七、配置项

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `analysis.auto.enabled` | `true` | 是否启用自动分析 |
| `analysis.interval.minutes` | `5` | 分析间隔 (分钟) |

---

## 八、执行流程

### 8.1 后台分析循环

```
while (enabled) {
    1. 加载 project_map.json
    2. 检查每个项目的 MD5 和分析状态
    3. 对需要分析的项目执行任务
    4. 更新 project_map.json
    5. 等待 5 分钟
}
```

### 8.2 单个分析任务执行

```
1. 加载分析类型对应的提示词
2. 创建临时 ReAct Loop 会话
3. 执行分析 (LLM 调用工具)
4. 保存 Markdown 到 .sman/base/
5. 解析 Markdown 并向量化
6. 存储到 H2 数据库
```

---

## 九、实施步骤

### Phase 1: 清理旧代码
- 删除 `analysis/pipeline/`
- 删除 `analysis/step/`
- 删除 `analysis/scanner/`
- 删除 `analysis/repository/`

### Phase 2: 创建新组件
- `ProjectAnalysisScheduler.kt`
- `AnalysisTaskExecutor.kt`
- `ProjectMapManager.kt`
- `AnalysisType.kt`
- `MdParser.kt`
- 分析提示词模板 (6 个 .md 文件)

### Phase 3: 修改 UI
- 修改 `SettingsDialog.kt`
- 修改 `SmanPlugin.kt`

### Phase 4: H2 同步
- `MdToH2SyncService.kt`

---

## 十、核心优势

| 旧架构 | 新架构 |
|--------|--------|
| 写死的 9 个步骤 | 提示词驱动，灵活扩展 |
| 手动触发 | 自动后台分析 |
| 代码实现复杂 | LLM + 工具搞定 |
| 难以维护 | 修改提示词即可 |
