# 设计方案：Markdown 驱动架构

> 版本：1.0
> 日期：2026-02-23

---

## 一、设计目标

将 SmanCode 的数据存储从 H2 数据库迁移到 Markdown 文件系统，实现：

1. **人类可读可编辑**：用户可以直接查看和修改
2. **Git 原生支持**：可追踪历史、可回滚
3. **LLM 友好**：易于解析和理解
4. **降低复杂度**：无需数据库

---

## 二、目录结构设计

```
{projectPath}/.sman/
├── MEMORY.md                    # 项目记忆（用户偏好、业务规则）
├── STATUS.md                    # 分析状态追踪
├── PROJECT.md                   # 项目元信息
│
├── analysis/                    # 分析结果
│   ├── 01_project_structure.md
│   ├── 02_tech_stack.md
│   ├── 03_api_entries.md
│   ├── 04_db_entities.md
│   ├── 05_enums.md
│   └── 06_config_files.md
│
├── classes/                     # 类级分析（分模块存储）
│   ├── controller/
│   ├── service/
│   ├── repository/
│   └── model/
│
├── tasks/                       # 任务追踪
│   ├── active.md                # 当前活跃任务
│   ├── completed/               # 已完成任务归档
│   │   └── 2026-02-{id}.md
│   └── failed/                  # 失败任务记录
│
├── preferences/                 # 用户偏好记录
│   ├── code-style.md            # 代码风格偏好
│   ├── decisions.md             # 历史决策记录
│   └── feedback.md              # 用户反馈
│
└── cache/                       # 缓存（不纳入 Git）
    ├── md5.json                 # MD5 缓存
    └── vectors/                 # 向量索引
```

---

## 三、核心文件格式

### 3.1 MEMORY.md - 项目记忆

```markdown
---
version: 1.0.0
lastUpdated: 2026-02-23T10:30:00Z
projectKey: smanunion
---

# 项目记忆

> 此文件记录了 Agent 学习到的项目特定知识，您可以手动编辑。

## 业务领域

- 金融交易系统
- 支持多币种结算
- T+1 交易模式

## 核心概念

| 概念 | 说明 | 代码映射 |
|------|------|---------|
| 订单 | 交易的核心实体 | `Order` |
| 持仓 | 用户持有的资产 | `Position` |
| 清算 | 日终批处理 | `ClearingService` |

## 技术约定

- 所有金额使用 `BigDecimal`
- 状态变更必须记录日志
- 外部接口必须有熔断

## 用户偏好

### 代码风格
- 使用 data class 而非 POJO
- 优先使用表达式而非语句
- 函数参数不超过 4 个

### 命名规范
- Controller: `XxxController`
- Service: `XxxService`
- Repository: `XxxRepository`

## 历史决策

| 日期 | 决策 | 原因 |
|------|------|------|
| 2026-02-20 | 选择 Kotlin 而非 Java | 更简洁、空安全 |
| 2026-02-22 | 使用 JVector 而非 Milvus | 减少依赖 |
```

### 3.2 STATUS.md - 分析状态追踪

```markdown
---
version: 1.0.0
lastUpdated: 2026-02-23T10:30:00Z
currentPhase: ANALYZING
enabled: true
---

# 分析状态

## 概览

- **状态**: 分析中
- **当前任务**: API 入口扫描
- **完成率**: 4/6 (66.7%)
- **成功率**: 100%

## 任务列表

### 已完成

- [x] 项目结构分析 (2026-02-22, 完整度: 95%)
- [x] 技术栈识别 (2026-02-22, 完整度: 90%)
- [x] 数据库实体分析 (2026-02-23, 完整度: 88%)
- [x] 枚举分析 (2026-02-23, 完整度: 92%)

### 进行中

- [ ] API 入口扫描 (步骤 3/15)

### 待处理

- [ ] 配置文件分析

## TODO 汇总

| 优先级 | 内容 | 来源 |
|--------|------|------|
| HIGH | 补充 ExternalApi 的错误处理说明 | API_ENTRIES |
| MEDIUM | 完善枚举的业务含义 | ENUMS |
```

### 3.3 tasks/active.md - 当前活跃任务

```markdown
---
version: 1.0.0
lastUpdated: 2026-02-23T10:30:00Z
---

# 活跃任务

## 任务: 实现 BGE-M3 向量化

### 基本信息

- **ID**: task-2026-02-23-001
- **状态**: IN_PROGRESS
- **创建时间**: 2026-02-23 09:00:00
- **当前步骤**: 3/5

### 目标

为代码片段实现 BGE-M3 向量化，支持语义搜索。

### 步骤

- [x] 1. 搭建 BGE-M3 服务
- [x] 2. 实现向量化客户端
- [ ] 3. 集成到分析流程
- [ ] 4. 实现向量存储
- [ ] 5. 测试验证

### 上下文

- 相关文件: `BgeM3Client.kt`, `VectorizationService.kt`
- 依赖: Docker 环境

### 工具调用历史

1. `read_file`: 读取 BgeM3Client.kt
2. `grep_file`: 搜索 embed 函数调用
3. `apply_change`: 添加错误处理
```

### 3.4 preferences/code-style.md - 代码风格偏好

```markdown
---
version: 1.0.0
lastUpdated: 2026-02-23T10:30:00Z
---

# 代码风格偏好

> Agent 从用户交互中学习到的代码风格偏好

## 命名风格

| 偏好 | 置信度 | 学习来源 |
|------|--------|----------|
| 变量使用驼峰命名 | 0.9 | 用户修改代码 5 次 |
| 常量使用全大写下划线 | 0.8 | 用户修改代码 3 次 |
| 私有字段使用下划线前缀 | 0.6 | 用户修改代码 2 次 |

## 格式化

| 偏好 | 置信度 | 学习来源 |
|------|--------|----------|
| 缩进使用 4 空格 | 0.95 | 用户修改代码 10 次 |
| 方法间空一行 | 0.7 | 用户修改代码 4 次 |

## 注释风格

| 偏好 | 置信度 | 学习来源 |
|------|--------|----------|
| 使用 KDoc 而非 JavaDoc | 0.85 | 用户修改代码 6 次 |
| 公共方法必须有注释 | 0.6 | 用户显式反馈 |

## 错误处理

| 偏好 | 置信度 | 学习来源 |
|------|--------|----------|
| 使用 Result 模式而非异常 | 0.75 | 用户修改代码 4 次 |
| 必须有错误日志 | 0.9 | 用户修改代码 8 次 |
```

---

## 四、数据映射关系

| 现有数据（H2） | 新的 Markdown 表示 | 说明 |
|----------------|-------------------|------|
| `AnalysisLoopState` | `STATUS.md` | 分析循环状态 |
| `AnalysisResultEntity` | `analysis/*.md` Frontmatter | 分析结果 |
| `AnalysisTodo` | `STATUS.md` TODO 部分 | 待办事项 |
| `VectorFragment` | `classes/*.md` + JVector | 类/方法分析 |
| `ProjectEntry` | `PROJECT.md` | 项目元信息 |
| 会话数据 | `tasks/*.md` | 任务追踪 |
| **新增** | `MEMORY.md` | 用户习惯/偏好 |
| **新增** | `preferences/*.md` | 决策记录 |

---

## 五、核心组件设计

### 5.1 MarkdownRepository

```kotlin
/**
 * Markdown 文件仓库基类
 */
abstract class MarkdownRepository<T : Any>(
    protected val projectPath: Path,
    protected val fileName: String
) {
    /**
     * 读取数据
     */
    fun read(): T? {
        val path = projectPath.resolve(".sman/$fileName")
        if (!path.exists()) return null

        val content = path.readText()
        val (frontmatter, body) = parseFrontmatter(content)

        return deserialize(frontmatter, body)
    }

    /**
     * 写入数据（保留用户注释）
     */
    fun write(data: T, comment: String? = null) {
        val path = projectPath.resolve(".sman/$fileName")
        val (frontmatter, body) = serialize(data)

        val content = buildString {
            append("---\n")
            append(frontmatter)
            append("---\n\n")
            if (comment != null) {
                append("> $comment\n\n")
            }
            append(body)
        }

        // 确保目录存在
        path.parent.createDirectories()
        path.writeText(content)
    }

    protected abstract fun deserialize(frontmatter: String, body: String): T
    protected abstract fun serialize(data: T): Pair<String, String>
}
```

### 5.2 MemoryService

```kotlin
/**
 * 记忆服务
 */
class MemoryService(
    private val projectPath: Path
) {
    private val memoryFile = projectPath.resolve(".sman/MEMORY.md")

    /**
     * 读取项目记忆
     */
    fun loadMemory(): ProjectMemory {
        if (!memoryFile.exists()) {
            return ProjectMemory.empty()
        }

        val content = memoryFile.readText()
        return parseMemory(content)
    }

    /**
     * 保存项目记忆
     */
    fun saveMemory(memory: ProjectMemory) {
        val content = formatMemory(memory)
        memoryFile.writeText(content)
    }

    /**
     * 追加学习到的偏好
     */
    fun appendPreference(preference: UserPreference) {
        val memory = loadMemory()

        // 合并偏好
        val existing = memory.preferences.find { it.category == preference.category }
        if (existing != null) {
            memory.preferences.remove(existing)
            memory.preferences.add(existing.merge(preference))
        } else {
            memory.preferences.add(preference)
        }

        saveMemory(memory)
    }

    /**
     * 记录决策
     */
    fun recordDecision(decision: Decision) {
        val memory = loadMemory()
        memory.decisions.add(decision)
        saveMemory(memory)
    }
}
```

### 5.3 StatusRepository

```kotlin
/**
 * 分析状态仓库
 */
class StatusRepository(
    projectPath: Path
) : MarkdownRepository<AnalysisStatus>(projectPath, "STATUS.md") {

    override fun deserialize(frontmatter: String, body: String): AnalysisStatus {
        val metadata = Yaml.default.decodeFromString<Map<String, Any>>(frontmatter)

        return AnalysisStatus(
            version = metadata["version"] as? String ?: "1.0.0",
            lastUpdated = Instant.parse(metadata["lastUpdated"] as String),
            currentPhase = AnalysisPhase.valueOf(metadata["currentPhase"] as String),
            enabled = metadata["enabled"] as? Boolean ?: true,
            todos = parseTodosFromMarkdown(body)
        )
    }

    override fun serialize(status: AnalysisStatus): Pair<String, String> {
        val frontmatter = Yaml.default.encodeToString(mapOf(
            "version" to status.version,
            "lastUpdated" to status.lastUpdated.toString(),
            "currentPhase" to status.currentPhase.name,
            "enabled" to status.enabled
        ))

        val body = buildString {
            append("# 分析状态\n\n")
            append("## 任务列表\n\n")
            append("### 已完成\n\n")
            status.todos.filter { it.completed }.forEach { todo ->
                append("- [x] ${todo.description}\n")
            }
            append("\n### 进行中\n\n")
            status.todos.filter { !it.completed && it.inProgress }.forEach { todo ->
                append("- [ ] ${todo.description} (步骤 ${todo.currentStep}/${todo.totalSteps})\n")
            }
        }

        return frontmatter to body
    }
}
```

---

## 六、Git 集成

### 6.1 .gitignore 配置

```gitignore
# SmanCode 缓存（不纳入版本控制）
.sman/cache/
.sman/*.mv.db
.sman/*.trace.db

# 需要纳入版本控制的内容
# .sman/MEMORY.md
# .sman/STATUS.md
# .sman/PROJECT.md
# .sman/analysis/
# .sman/classes/
# .sman/tasks/
# .sman/preferences/
```

### 6.2 自动提交（可选）

```kotlin
/**
 * Git 集成服务
 */
class GitIntegrationService(
    private val projectPath: Path
) {
    /**
     * 自动提交关键变更
     */
    fun commitChanges(message: String, files: List<String>) {
        val git = Git.open(projectPath.toFile())

        files.forEach { file ->
            git.add()
                .addFilepattern(".sman/$file")
                .call()
        }

        git.commit()
            .setMessage("sman: $message")
            .call()
    }

    /**
     * 检查是否有未提交的变更
     */
    fun hasUncommittedChanges(): Boolean {
        val git = Git.open(projectPath.toFile())
        val status = git.status().call()
        return !status.isClean
    }
}
```

---

## 七、迁移计划

### 7.1 迁移步骤

1. **保留 H2，新增 Markdown 同步**
   - 所有写操作同时写入 H2 和 Markdown
   - 读操作优先从 Markdown 读取

2. **验证数据一致性**
   - 对比 H2 和 Markdown 数据
   - 修复不一致的数据

3. **Markdown 作为 Source of Truth**
   - 读操作只从 Markdown 读取
   - H2 作为可选的加速缓存

4. **完全移除 H2 状态存储**
   - 仅保留 JVector 向量索引
   - 向量索引可从 Markdown 重建

### 7.2 回滚方案

如果迁移过程中出现问题：

1. 从 Git 恢复 Markdown 文件
2. 重新启用 H2 作为主存储
3. 分析问题原因，修复后重新迁移

---

## 八、总结

Markdown 驱动架构的核心优势：

1. **人类可读**：用户可以直接查看和编辑
2. **Git 友好**：可追踪历史、可回滚
3. **LLM 友好**：天然的结构化格式
4. **降低复杂度**：无需数据库
5. **知识沉淀**：长期积累形成项目知识库

关键设计决策：

- **Markdown 为 Source of Truth**
- **JVector 作为加速层**（可重建）
- **Git 作为版本控制**（可选）
