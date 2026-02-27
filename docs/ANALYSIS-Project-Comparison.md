# SmanCode 项目战略分析报告

> 生成时间：2026-02-23
> 目的：对比 smanunion / opencode / openclaw，制定智能化升级路线

---

## 一、三项目核心能力对比矩阵

### 1.1 Agent 架构对比

| 维度 | SmanCode (本项目) | OpenCode | OpenClaw |
|------|-------------------|----------|----------|
| **技术栈** | Kotlin + IntelliJ Plugin | TypeScript + Bun | TypeScript + Node.js |
| **运行环境** | IDE 内嵌 | CLI/Server | Gateway + Multi-Channel |
| **核心模式** | ReAct 循环 | ReAct + 多 Agent 模式 | ReAct + 消息路由 |
| **循环设计** | 单一主循环 | Primary + Subagent + Hidden | Session-based 多通道 |

### 1.2 记忆与学习能力

| 维度 | SmanCode | OpenCode | OpenClaw |
|------|----------|----------|----------|
| **记忆存储** | H2 数据库 | 会话级（内存） | Markdown 文件系统 |
| **向量索引** | ✅ JVector 三层缓存 | ❌ 无内置 | ✅ 混合搜索（向量+BM25） |
| **用户习惯学习** | ❌ 无 | ❌ 无 | ⚠️ 被动存储（需手动写入） |
| **知识沉淀** | ⚠️ 分析结果持久化 | ❌ 无 | ✅ MEMORY.md / AGENTS.md |
| **语义搜索** | ✅ BGE-M3 + Reranker | ⚠️ 外部 CodeSearch | ✅ 内置向量 + MMR |

### 1.3 工具与扩展能力

| 维度 | SmanCode | OpenCode | OpenClaw |
|------|----------|----------|----------|
| **工具系统** | 手动注册 | 手动注册 + MCP | 手动注册 + MCP |
| **MCP 支持** | ❌ 无 | ✅ 完整（local+remote+OAuth） | ✅ 支持 |
| **技能系统** | ✅ SKILL.md | ✅ SKILL.md 多路径 | ✅ Bundled + Managed |
| **Edit 容错** | ⚠️ 简单匹配 | ✅ 7 级多策略 | ⚠️ 简单 |
| **代码沙盒** | ❌ 无 | ❌ 无 | ✅ Docker 隔离 |
| **权限系统** | ⚠️ 基础 | ✅ 规则集 + 通配符 | ✅ Tool Policy |

### 1.4 交互与集成

| 维度 | SmanCode | OpenCode | OpenClaw |
|------|----------|----------|----------|
| **交互界面** | IDE 原生面板 | TUI / Web / Desktop | 12+ 消息通道 |
| **LSP 集成** | ✅ 依赖 IDE | ✅ 内置多语言 | ❌ 无 |
| **代码引用** | ✅ Ctrl+L 注入 | ⚠️ 手动 | ❌ 无 |
| **断点续传** | ✅ H2 持久化 | ❌ 无 | ⚠️ Session 持久化 |

### 1.5 自动化程度

| 维度 | SmanCode | OpenCode | OpenClaw |
|------|----------|----------|----------|
| **项目分析** | ✅ 自动后台调度 | ❌ 无 | ❌ 无 |
| **文件变更检测** | ✅ MD5 增量 | ⚠️ Git-based | ❌ 无 |
| **上下文压缩** | ✅ 自动阈值触发 | ✅ 自动 | ✅ 自动 |
| **循环检测** | ✅ Doom Loop 检测 | ✅ 3 次相同检测 | ⚠️ opt-in 工具 |
| **测试验证** | ❌ 无 | ❌ 无 | ❌ 无 |

---

## 二、核心能力雷达图

```
              代码理解
                 5
                 |
            4    |    4
                 |
学习记忆 ————————+——————— 扩展性
      2          |          5
                 |
            3    |    4
                 |
              自动化

SmanCode: 代码理解★★★★★ | 学习记忆★★☆☆☆ | 扩展性★★★★☆ | 自动化★★★☆☆
OpenCode: 代码理解★★★★☆ | 学习记忆★★☆☆☆ | 扩展性★★★★★ | 自动化★★★★☆
OpenClaw: 代码理解★★☆☆☆ | 学习记忆★★★☆☆ | 扩展性★★★★★ | 自动化★★★☆☆
```

---

## 三、各项目独特优势

### 3.1 SmanCode 独特优势

1. **三层缓存架构**：L1 内存 + L2 JVector + L3 H2，解决大型项目内存问题
2. **深度代码理解**：BGE-M3 向量化 + Reranker + 调用链分析
3. **IDE 原生集成**：代码引用、断点续传、后台分析
4. **增量更新**：基于 MD5 的文件变更检测
5. **语义搜索直接返回**：不经过 LLM 二次处理

### 3.2 OpenCode 独特优势

1. **多策略 Edit 工具**：7 级容错匹配，大幅提升编辑成功率
2. **完整的 MCP 支持**：local + remote + OAuth 认证
3. **Agent 模式分类**：Primary / Subagent / Hidden 三种模式
4. **配置分层加载**：组织级 → 用户级 → 项目级 → 托管级
5. **多前端支持**：TUI / Web / Desktop
6. **会话快照/回滚**：支持代码变更回滚

### 3.3 OpenClaw 独特优势

1. **多通道统一**：12+ 消息平台，DMs collapse to main session
2. **Markdown 记忆系统**：MEMORY.md / AGENTS.md / SOUL.md
3. **Docker 沙盒隔离**：per-session / per-agent 安全隔离
4. **Hooks 事件系统**：before_tool_call / after_tool_call 等
5. **ClawHub 技能市场**：自动发现和拉取新技能
6. **设备节点**：macOS/iOS/Android companion apps
7. **Canvas 可视化**：Agent 驱动的可视化 workspace

---

## 四、必须要的能力（综合判断）

### 4.1 必须保留的能力 ✅

| 能力 | 来源 | 理由 |
|------|------|------|
| **三层缓存架构** | SmanCode | 大型项目必需，防止 OOM |
| **语义代码搜索** | SmanCode | 业务人员描述需求 → 找到代码 |
| **IDE 原生集成** | SmanCode | 存量项目场景，IDE 是主战场 |
| **增量更新** | SmanCode | 效率关键，只分析变更部分 |
| **多策略 Edit** | OpenCode | 提升代码修改成功率 |
| **Markdown 记忆** | OpenClaw | 简单可靠，用户可见可编辑 |
| **Docker 沙盒** | OpenClaw | 安全执行用户代码 |
| **测试验证闭环** | 新增 | 交付质量保证 |

### 4.2 需要新增的能力 🆕

| 能力 | 理由 |
|------|------|
| **主动需求理解** | 用户只描述想法，Agent 主动澄清和细化 |
| **自动测试验证** | 代码交付前自动运行测试，确保可用 |
| **学习用户习惯** | 记录用户对方案的选择和修正 |
| **知识沉淀机制** | 项目特定知识、业务规则自动记录 |
| **任务规划能力** | 复杂需求自动分解为可执行步骤 |
| **进度可视化** | 用户可见的任务进度追踪 |
| **回滚机制** | 支持撤销错误的代码修改 |

---

## 五、核心理念：面向业务的智能 Agent

### 5.1 当前问题

| 问题 | 表现 |
|------|------|
| **交互门槛高** | MCP、Skill、Tool 对业务人员过于硬核 |
| **被动响应** | 用户说什么才做什么，缺乏主动性 |
| **无记忆** | 每次交互都从零开始，不学习用户习惯 |
| **无验证** | 生成的代码无法保证可用 |
| **知识流失** | 项目知识、业务规则无法沉淀 |

### 5.2 目标愿景

```
用户："我想加个登录功能"
      ↓
Agent：主动理解 → 澄清需求 → 规划任务 → 分步实现 → 自动测试 → 交付可用代码
      ↓
Agent：记录用户偏好（如：喜欢用 JWT 而非 Session）
      ↓
下次类似需求 → 自动应用偏好
```

### 5.3 两大核心理念

#### 理念一：一切基于 Markdown

**原因**：
- ✅ 人类可读可编辑
- ✅ Git 原生支持，可追踪历史
- ✅ LLM 友好，易于解析
- ✅ 无需数据库，降低复杂度
- ✅ 团队可共享知识

**文件结构**：
```
{projectPath}/.sman/
├── MEMORY.md              # 项目记忆（用户偏好、业务规则）
├── STATUS.md              # 分析状态追踪
├── PROJECT.md             # 项目元信息
├── analysis/              # 分析结果
│   ├── 01_project_structure.md
│   ├── 02_tech_stack.md
│   └── ...
├── tasks/                 # 任务追踪
│   ├── active.md          # 当前活跃任务
│   └── completed/         # 已完成任务归档
├── preferences/           # 用户偏好记录
│   ├── code-style.md      # 代码风格偏好
│   └── decisions.md       # 历史决策记录
└── cache/                 # 缓存（不纳入 Git）
    ├── md5.json           # MD5 缓存
    └── vectors/           # 向量索引
```

#### 理念二：代码沙盒自验证

**流程**：
```
代码生成 → 放入沙盒 → 自动运行测试 → 收集结果 → 失败则自动修复 → 交付可用代码
```

**安全隔离**：
- 进程级隔离（轻量）：独立进程 + 资源限制
- Docker 隔离（重量）：无网络 + 只读文件系统 + 能力限制

---

## 六、详细升级方案

### 6.1 Phase 1：Markdown 数据层重构（2-3 周）

**目标**：将 H2 数据库状态迁移到 Markdown 文件

| 任务 | 说明 |
|------|------|
| 1.1 设计 Markdown Schema | 定义各文件的 Frontmatter 和内容结构 |
| 1.2 实现 MarkdownRepository | 统一的 Markdown 读写服务 |
| 1.3 迁移 AnalysisState | `analysis_loop_state` → `STATUS.md` |
| 1.4 迁移 AnalysisResult | `analysis_result` → `analysis/*.md` Frontmatter |
| 1.5 新增 MEMORY.md | 项目记忆文件（用户偏好、业务规则） |
| 1.6 Git 集成 | 自动提交关键变更（可选） |

**关键代码**：

```kotlin
/**
 * Markdown 同步服务
 */
class MarkdownSyncService(
    private val projectPath: Path
) {
    /**
     * 读取并解析 Markdown 文件
     */
    fun <T> read(file: String, clazz: Class<T>): T? {
        val path = projectPath.resolve(".sman/$file")
        if (!path.exists()) return null

        val content = path.readText()
        val (frontmatter, body) = parseFrontmatter(content)

        return clazz.getDeclaredConstructor().newInstance().apply {
            // 反射填充字段
        }
    }

    /**
     * 写入 Markdown 文件（保留注释和格式）
     */
    fun <T> write(file: String, data: T, comment: String? = null) {
        val path = projectPath.resolve(".sman/$file")
        val frontmatter = serializeToFrontmatter(data)
        val content = "---\n$frontmatter\n---\n\n${comment ?: ""}"

        path.writeText(content)
    }
}
```

### 6.2 Phase 2：用户习惯学习系统（3-4 周）

**目标**：实现隐式/显式反馈收集 → 偏好提取 → 知识应用

#### 2.1 隐式反馈收集

| 场景 | 收集方式 | 数据点 |
|------|----------|--------|
| 用户修改 Agent 代码 | Git Diff 分析 | 代码风格、命名习惯 |
| 用户拒绝方案 | 会话中断检测 | 架构偏好、技术选型 |
| 用户重复修正 | 修正模式识别 | 参数偏好、输出格式 |

**关键组件**：

```kotlin
/**
 * 隐式反馈收集器
 */
class ImplicitFeedbackCollector {
    /**
     * 分析用户对生成代码的修改
     */
    fun analyzeCodeModification(
        original: String,
        modified: String
    ): List<UserPreference> {
        val preferences = mutableListOf<UserPreference>()

        // 1. 命名风格分析
        analyzeNamingDifference(original, modified)?.let {
            preferences.add(UserPreference(
                category = PreferenceCategory.NAMING_STYLE,
                content = it,
                confidence = 0.8
            ))
        }

        // 2. 代码结构分析
        analyzeStructureDifference(original, modified)?.let {
            preferences.add(UserPreference(
                category = PreferenceCategory.CODE_STRUCTURE,
                content = it,
                confidence = 0.7
            ))
        }

        return preferences
    }
}
```

#### 2.2 记忆刷新机制

**在上下文压缩前自动触发**：

```kotlin
// 在 SmanLoop.processWithLLM() 中
if (contextCompactor.needsCompaction(session)) {

    // 【新增】先执行记忆刷新
    if (memoryFlushService.needsFlush(session)) {
        val flushResult = memoryFlushService.flushMemory(session)
        logger.info("记忆刷新完成: extracted={}, merged={}",
            flushResult.preferencesExtracted,
            flushResult.preferencesMerged)
    }

    // 然后执行压缩
    contextCompactor.prune(session)
}
```

#### 2.3 偏好应用

**注入到 Prompt**：

```kotlin
class DynamicPromptInjector(
    private val preferenceStore: PreferenceStore
) {
    fun injectPreferences(sessionKey: String, projectKey: String?): String {
        if (projectKey.isNullOrEmpty()) return ""

        val preferences = preferenceStore.getTopPreferences(projectKey, limit = 10)

        return """
            ## 用户偏好（已学习）

            ### 代码风格
            ${preferences.filter { it.category == NAMING_STYLE }.joinToString { "- ${it.content}" }}

            ### 架构偏好
            ${preferences.filter { it.category == ARCHITECTURE_PATTERN }.joinToString { "- ${it.content}" }}

            请在生成代码时遵循以上偏好。
        """.trimIndent()
    }
}
```

### 6.3 Phase 3：代码沙盒与自动验证（3-4 周）

**目标**：实现代码生成 → 沙盒测试 → 自动修复闭环

#### 3.1 测试生成器

```kotlin
/**
 * 测试用例生成器
 */
class TestGenerator(
    private val llmService: LlmService
) {
    suspend fun generateTests(
        sourceFile: String,
        options: TestGenerationOptions = TestGenerationOptions()
    ): GeneratedTests {
        // 1. PSI 分析：提取类结构、方法签名
        val classInfo = analyzeClass(sourceFile)

        // 2. 构建提示词
        val prompt = """
            为以下类生成 JUnit5 测试用例：

            ${classInfo.sourceCode}

            要求：
            - 覆盖正常路径和边界条件
            - 包含异常测试
            - 使用 Given-When-Then 风格
        """.trimIndent()

        // 3. LLM 生成测试
        val testCode = llmService.generate(prompt)

        return GeneratedTests(testClass = testCode)
    }
}
```

#### 3.2 沙盒执行器

**方案 A：进程级隔离（推荐）**

```kotlin
/**
 * 进程级沙盒执行器
 */
class ProcessSandboxExecutor {
    suspend fun executeInSandbox(
        command: String,
        options: SandboxOptions
    ): SandboxResult {
        val safeCommand = buildSafeCommand(command, options)

        val process = ProcessBuilder(safeCommand)
            .directory(options.workingDir)
            .redirectErrorStream(true)
            .start()

        // 设置超时（默认 5 分钟）
        val completed = process.waitFor(options.timeoutSeconds, TimeUnit.SECONDS)

        if (!completed) {
            process.destroyForcibly()
            return SandboxResult.Timeout(options.timeoutSeconds)
        }

        return SandboxResult.Completed(
            exitCode = process.exitValue(),
            output = process.inputStream.bufferedReader().readText()
        )
    }
}
```

**方案 B：Docker 隔离（高风险场景）**

```kotlin
/**
 * Docker 沙盒配置（参考 OpenClaw）
 */
data class DockerSandboxConfig(
    val image: String = "sman-sandbox:latest",
    val readOnlyRoot: Boolean = true,
    val tmpfs: List<String> = listOf("/tmp", "/var/tmp"),
    val network: String = "none",  // 无网络访问
    val capDrop: List<String> = listOf("ALL"),
    val memoryLimit: String = "512m",
    val timeoutSeconds: Long = 300
)
```

#### 3.3 自动修复循环

```kotlin
/**
 * 自动修复器
 */
class AutoFixer(
    private val llmService: LlmService,
    private val codeEditService: CodeEditService
) {
    suspend fun autoFixLoop(
        sourceFile: String,
        testResults: TestResults,
        maxIterations: Int = 3
    ): FixResult {
        var currentResults = testResults
        var iterations = 0

        while (currentResults.hasFailures() && iterations < maxIterations) {
            iterations++

            // 1. 分析失败原因
            val analysis = analyzeFailures(currentResults.failures)

            // 2. 生成修复方案
            val fixPlan = generateFixPlan(sourceFile, analysis)

            // 3. 应用修复
            applyFix(sourceFile, fixPlan)

            // 4. 重新运行测试
            currentResults = runTests(sourceFile)
        }

        return FixResult(
            success = !currentResults.hasFailures(),
            iterations = iterations
        )
    }
}
```

### 6.4 Phase 4：多策略 Edit 工具（2 周）

**目标**：借鉴 OpenCode 的 7 级容错匹配

| 策略 | 说明 |
|------|------|
| SimpleReplacer | 精确匹配 |
| LineTrimmedReplacer | 行 trim 后匹配 |
| BlockAnchorReplacer | 首尾行锚点 + 相似度 |
| WhitespaceNormalizedReplacer | 空白归一化 |
| IndentationFlexibleReplacer | 缩进灵活匹配 |
| EscapeNormalizedReplacer | 转义字符处理 |
| ContextAwareReplacer | 上下文感知 |

### 6.5 Phase 5：主动服务能力（2-3 周）

**目标**：从被动响应转向主动服务

| 能力 | 说明 |
|------|------|
| **需求澄清** | 主动提问，理解用户真实意图 |
| **任务规划** | 复杂需求自动分解为步骤 |
| **进度可视化** | 实时展示任务进度 |
| **问题预警** | 主动发现代码问题并提醒 |

**需求澄清示例**：

```
用户：加个登录功能

Agent：我来帮你实现登录功能。为了更好地实现，我需要确认几点：

1. 认证方式：
   - [ ] JWT Token
   - [ ] Session
   - [ ] OAuth2

2. 登录方式：
   - [ ] 用户名+密码
   - [ ] 手机号+验证码
   - [ ] 第三方登录

3. 安全要求：
   - [ ] 需要验证码
   - [ ] 需要登录限流
   - [ ] 需要异地登录提醒

请选择或告诉我你的具体需求。
```

---

## 七、当前能力真实评估

### 7.1 SmanCode 当前实际能力

| 能力 | 宣称 | 实际 | 差距 |
|------|------|------|------|
| **深度代码理解** | BGE-M3 + 调用链 + 三层缓存 | ❌ **仅语义搜索** | 缺乏真正的代码理解 |
| **调用链分析** | ✅ 有 `call_chain` 工具 | ⚠️ 仅 PSI 级别浅层分析 | 没有深度调用图 |
| **项目分析** | 6 种分析类型 | ⚠️ 静态分析，结果存 MD | 无动态理解 |
| **语义搜索** | BGE-M3 + Reranker | ✅ 确实可用 | 这是真正可用的能力 |
| **三层缓存** | L1/L2/L3 | ✅ 确实存在 | 但缓存的是什么？只是向量 |

### 7.2 核心问题诊断

**问题一：没有真正的"深度代码理解"**

当前只是：
- 文本 → 向量化 → 语义搜索
- PSI → 简单的类/方法查找

缺乏：
- ❌ 代码执行路径分析
- ❌ 数据流分析
- ❌ 业务逻辑理解
- ❌ 代码意图推断

**问题二：分析结果没有被"理解"**

当前流程：
```
LLM 分析代码 → 输出 MD 文件 → 存到磁盘 → 没人看
```

应该的流程：
```
LLM 分析代码 → 结构化知识 → 注入到上下文 → Agent 能用
```

### 7.3 优先级调整

```
高价值 │ Phase 1        Phase 2
      │ Markdown 层    深度理解
      │                ↓
      │     ═════════════════════
      │
低价值 │ Phase 4        Phase 5（沙盒）
      │ 学习系统       最后考虑
      │                ↑
      └──────────────────────────
          低难度        高难度
```

### 7.4 修正后的推荐顺序

| 顺序 | Phase | 理由 | 时间 | 详细设计 |
|------|-------|------|------|----------|
| 1 | **Phase 1: Markdown 层** | 基础设施，后续依赖 | 2-3周 | 见 `设计方案-Markdown驱动架构.md` |
| 2 | **Phase 2: 自迭代项目理解** | **最核心能力，没有之一** | 4-5周 | 见 `核心能力-自迭代项目理解系统.md` |
| 3 | **Phase 3: 学习系统** | 增强体验，需要 Markdown 层 | 3-4周 | 见 `设计方案-用户习惯学习.md` |
| 4 | **Phase 4: Edit 容错** | 提升成功率 | 2周 | 借鉴 OpenCode 7 级匹配 |
| 5 | **Phase 5: 主动服务** | 锦上添花 | 2-3周 | 需求澄清、任务规划 |
| 6 | **Phase 6: 沙盒验证** | **优先级最低，最后考虑** | 3-4周 | 见 `设计方案-代码沙盒自验证.md` |

### 7.5 Phase 2 核心说明：自迭代项目理解

**这是 SmanCode 的核心差异化能力**，详见 `docs/核心能力-自迭代项目理解系统.md`。

**核心思路**：
- 不是用 Skill 封装业务场景（需要人工编写）
- 而是 Agent 自己理解项目，生成项目专属知识（自动生成）

**关键机制**：
```
项目代码 → 发现空白 → 执行分析 → 验证结果 → 更新拼图 → 发现新空白 → ...
                              ↑
                         后台持续运行
```

**拼图块类型**：
- 结构拼图（PROJECT_STRUCTURE）
- 技术拼图（TECH_STACK）
- 入口拼图（API_ENTRIES）
- 数据拼图（DB_ENTITIES + ER 图）
- **流程拼图**（FLOW_* - 核心拼图）
- 规则拼图（BUSINESS_RULES）
- 概念拼图（GLOSSARY）

---

## 八、深度代码理解设计方案（新增）

### 8.1 问题定义

**当前状态**：
- `expert_consult`：文本 → 向量化 → 语义搜索 → 返回相似代码片段
- `call_chain`：PSI 查找 → 简单的方法引用 → 返回调用者列表
- `项目分析`：LLM 生成 MD 文件 → 存磁盘 → 没有后续利用

**缺失的"深度理解"**：
1. **代码执行路径**：从入口到出口的完整路径
2. **数据流分析**：数据如何流转、变换
3. **业务逻辑理解**：代码解决什么业务问题
4. **意图推断**：开发者写这段代码的目的

### 8.2 深度理解三层架构

```
┌──────────────────────────────────────────────────────────────────┐
│                     深度代码理解三层架构                           │
├──────────────────────────────────────────────────────────────────┤
│                                                                  │
│  L3: 业务语义层                                                  │
│  ┌─────────────────────────────────────────────────────────────┐│
│  │  • 业务流程图（用户注册 → 身份验证 → 开户 → ...）            ││
│  │  • 业务规则提取（金额 > 1000 需审核）                        ││
│  │  • 领域概念映射（提额 = increaseCreditLimit）               ││
│  └─────────────────────────────────────────────────────────────┘│
│                              ↑                                   │
│  L2: 逻辑流层                                                    │
│  ┌─────────────────────────────────────────────────────────────┐│
│  │  • 调用图（Call Graph）：方法调用关系                        ││
│  │  • 数据流（Data Flow）：变量如何传递和变换                   ││
│  │  • 控制流（Control Flow）：条件分支、循环                    ││
│  │  • 状态机（State Machine）：状态转换                         ││
│  └─────────────────────────────────────────────────────────────┘│
│                              ↑                                   │
│  L1: 代码结构层（当前已有）                                       │
│  ┌─────────────────────────────────────────────────────────────┐│
│  │  • PSI 解析：类、方法、字段                                   ││
│  │  • 语义搜索：BGE-M3 向量化                                   ││
│  │  • 简单引用：谁调用了这个方法                                ││
│  └─────────────────────────────────────────────────────────────┘│
│                                                                  │
└──────────────────────────────────────────────────────────────────┘
```

### 8.3 实现方案

#### 8.3.1 L2 逻辑流层实现

**新增工具：`analyze_flow`**

```kotlin
/**
 * 代码流程分析工具
 *
 * 分析代码的调用图、数据流、控制流
 */
class AnalyzeFlowTool(
    private val project: Project
) : AbstractTool(), Tool {

    override fun getName() = "analyze_flow"

    override fun getDescription() = """
        深度分析代码流程：
        - call_graph: 方法调用图（谁调用了谁）
        - data_flow: 数据流分析（变量如何流转）
        - control_flow: 控制流分析（条件分支）
        - execution_path: 执行路径（从入口到出口）
    """.trimIndent()

    override fun getParameters() = mapOf(
        "action" to ParameterDef("action", String::class.java, true,
            "分析类型: call_graph, data_flow, control_flow, execution_path"),
        "target" to ParameterDef("target", String::class.java, true,
            "目标：类名、方法签名或文件路径"),
        "options" to ParameterDef("options", Map::class.java, false,
            "选项：depth, includeExternal, format")
    )

    override fun execute(projectKey: String, params: Map<String, Any>): ToolResult {
        val action = params["action"]?.toString()
            ?: return ToolResult.failure("缺少 action 参数")

        return when (action) {
            "call_graph" -> analyzeCallGraph(params)
            "data_flow" -> analyzeDataFlow(params)
            "control_flow" -> analyzeControlFlow(params)
            "execution_path" -> analyzeExecutionPath(params)
            else -> ToolResult.failure("未知操作: $action")
        }
    }

    /**
     * 分析调用图
     *
     * 示例输出：
     * UserService.register()
     *   → UserValidator.validate()
     *   → UserRepository.save()
     *   → NotificationService.sendWelcome()
     */
    private fun analyzeCallGraph(params: Map<String, Any>): ToolResult {
        val target = params["target"]?.toString()
            ?: return ToolResult.failure("缺少 target 参数")
        val depth = (params["options"] as? Map<*, *>)?.get("depth") as? Int ?: 3

        // 使用 IntelliJ PSI 分析调用关系
        val callGraph = buildCallGraph(target, depth)

        return ToolResult.success(formatCallGraph(callGraph))
    }

    /**
     * 分析数据流
     *
     * 示例输出：
     * 参数 userId: String
     *   → user = userRepo.findById(userId)  // User?
     *   → user!!.name                       // String
     *   → dto.userName = user.name          // String
     *   → return dto                        // UserDTO
     */
    private fun analyzeDataFlow(params: Map<String, Any>): ToolResult {
        val target = params["target"]?.toString()
            ?: return ToolResult.failure("缺少 target 参数")

        // 分析变量的数据流
        val dataFlow = traceDataFlow(target)

        return ToolResult.success(formatDataFlow(dataFlow))
    }

    /**
     * 分析执行路径
     *
     * 示例输出：
     * 路径 1: 正常流程
     *   register() → validate() → save() → sendNotification()
     *
     * 路径 2: 验证失败
     *   register() → validate() → throw ValidationException
     *
     * 路径 3: 重复用户
     *   register() → validate() → checkExists() → throw DuplicateException
     */
    private fun analyzeExecutionPath(params: Map<String, Any>): ToolResult {
        val target = params["target"]?.toString()
            ?: return ToolResult.failure("缺少 target 参数")

        // 分析所有可能的执行路径
        val paths = enumerateExecutionPaths(target)

        return ToolResult.success(formatExecutionPaths(paths))
    }
}
```

#### 8.3.2 L3 业务语义层实现

**利用现有 LLM 能力 + 结构化输出**

```kotlin
/**
 * 业务语义分析服务
 *
 * 将代码分析结果转化为业务语义
 */
class BusinessSemanticService(
    private val llmService: LlmService
) {
    /**
     * 分析代码的业务含义
     *
     * @param codeFlow 代码流程（从 L2 层获取）
     * @return 业务语义描述
     */
    suspend fun analyzeBusinessMeaning(codeFlow: CodeFlow): BusinessSemantics {
        val prompt = """
            分析以下代码流程的业务含义：

            ## 代码流程

            ${codeFlow.toMarkdown()}

            ## 分析要求

            1. 业务场景：这段代码解决什么业务问题？
            2. 业务流程：用业务语言描述流程（非技术术语）
            3. 业务规则：提取隐含的业务规则
            4. 异常场景：列出可能的异常情况

            ## 输出格式（JSON）

            {
                "businessScenario": "用户注册场景",
                "businessFlow": ["用户提交注册信息", "系统验证信息", "创建用户账号", "发送欢迎通知"],
                "businessRules": [
                    {"rule": "用户名不能重复", "condition": "user.username not exists"},
                    {"rule": "密码长度 >= 8", "condition": "password.length >= 8"}
                ],
                "exceptionScenarios": [
                    {"scenario": "用户名已存在", "handling": "提示用户更换用户名"},
                    {"scenario": "密码不符合规则", "handling": "提示密码要求"}
                ]
            }
        """.trimIndent()

        return llmService.analyze(prompt)
    }

    /**
     * 生成业务流程图（Mermaid 格式）
     */
    suspend fun generateBusinessFlowChart(semantics: BusinessSemantics): String {
        return buildString {
            appendLine("```mermaid")
            appendLine("flowchart TD")
            semantics.businessFlow.forEachIndexed { index, step ->
                if (index > 0) {
                    appendLine("    Step${index} --> Step${index + 1}")
                }
                appendLine("    Step${index + 1}[\"$step\"]")
            }
            appendLine("```")
        }
    }
}
```

#### 8.3.3 知识注入到上下文

**修改 `DynamicPromptInjector`**

```kotlin
class DynamicPromptInjector(
    private val promptLoader: PromptLoaderService,
    private val projectPath: Path,
    private val businessSemanticService: BusinessSemanticService  // 新增
) {
    /**
     * 构建业务上下文提示词
     */
    suspend fun buildBusinessContext(userQuery: String): String {
        // 1. 语义搜索相关代码
        val relatedCode = semanticSearch(userQuery)

        // 2. 分析相关代码的业务含义
        val semantics = businessSemanticService.analyzeBusinessMeaning(relatedCode)

        // 3. 构建上下文
        return buildString {
            append("## 业务上下文\n\n")
            append("用户的问题涉及以下业务场景：\n\n")
            append("**业务场景**: ${semantics.businessScenario}\n\n")
            append("**业务流程**:\n")
            semantics.businessFlow.forEachIndexed { index, step ->
                append("  ${index + 1}. $step\n")
            }
            append("\n**业务规则**:\n")
            semantics.businessRules.forEach { rule ->
                append("  - ${rule.rule}\n")
            }
        }
    }
}
```

### 8.4 与现有能力的整合

| 现有能力 | 整合方式 |
|----------|----------|
| `expert_consult` (语义搜索) | 作为 L1 层，提供候选代码片段 |
| `call_chain` (调用链) | 升级为 `analyze_flow` 的 call_graph 模式 |
| 项目分析 (6 种类型) | 分析结果注入 L3 业务语义层 |
| 三层缓存 | 缓存 L2/L3 分析结果，避免重复计算 |

### 8.5 实施步骤

| 步骤 | 内容 | 时间 |
|------|------|------|
| 1 | 实现 `analyze_flow` 工具（L2 层） | 1-2周 |
| 2 | 实现 `BusinessSemanticService`（L3 层） | 1周 |
| 3 | 修改 `DynamicPromptInjector` 注入业务上下文 | 1周 |
| 4 | 缓存机制：L2/L3 结果持久化 | 1周 |

---

## 十、总结与建议

### 10.1 核心差异定位

| 项目 | 定位 | 适合场景 |
|------|------|----------|
| **SmanCode** | IDE 内深度代码理解 | 存量大型项目、IDE 主战场 |
| **OpenCode** | 通用 AI 编程助手 | CLI 环境、多前端需求 |
| **OpenClaw** | 多通道个人助手 | 消息平台集成、全天候服务 |

### 10.2 SmanCode 升级核心（修正版）

1. **一切基于 Markdown** ⭐ 优先级 1
   - 数据存储从 H2 迁移到 Markdown
   - 用户可见、可编辑、可 Git 追踪

2. **深度代码理解** ⭐ 优先级 2（**核心短板，必须补齐**）
   - L1: 语义搜索（已有）
   - L2: 调用图、数据流、控制流（**需新增**）
   - L3: 业务语义理解（**需新增**）

3. **学习能力** ⭐ 优先级 3
   - 隐式收集用户修正
   - 记忆刷新机制
   - 下次自动应用偏好

4. **Edit 容错** ⭐ 优先级 4
   - 多策略匹配（借鉴 OpenCode）

5. **主动服务** ⭐ 优先级 5
   - 需求澄清、任务规划、进度可视化

6. **代码沙盒自验证** ⭐ 优先级 6（**最低优先级**）
   - 三个项目都没有此能力
   - 可作为未来差异化特性

### 10.3 下一步行动

1. **立即开始**：Phase 1 Markdown 层重构（2-3周）
2. **紧接着**：Phase 2 深度代码理解（3-4周）—— **这是核心短板**
3. **中期目标**：Phase 3 学习系统（3-4周）
4. **长期目标**：Phase 4-6 其他增强

---

## 十一、参考资源

- OpenCode 源码：`/Users/nasakim/projects/opencode`
- OpenClaw 源码：`/Users/nasakim/projects/openclaw`
- SmanCode 源码：`/Users/nasakim/projects/smanunion`
- 技术对比详细报告：`docs/技术对比-*.md`

