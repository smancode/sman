package ai.smancode.sman.agent.claude;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Claude Code 进程执行器
 *
 * 功能：
 * - 按需创建 Claude Code 进程（使用 --resume 模式）
 * - 控制并发进程数量（避免系统过载）
 * - 每个请求都是独立进程（执行完自动退出）
 * - 支持分层工具约束（根据执行模式）
 *
 * 架构说明：
 * - 使用 --resume 参数，CLI自动从会话文件恢复历史
 * - 不需要保持Worker运行（自然支持多轮对话）
 * - 通过 Semaphore 控制并发数（而非传统进程池）
 * - 通过 ExecutionMode 控制工具约束级别
 *
 * @author SiliconMan Team
 * @since 1.0.0
 */
@Component
public class ClaudeCodeProcessPool {

    /**
     * 执行模式枚举
     */
    public enum ExecutionMode {
        /**
         * IDE 客户端模式（严格约束）
         * - 使用场景: IDE Plugin 通过 WebSocket 调用
         * - 工具约束: 禁止所有文件操作工具 (Read,Edit,Bash,Write,Grep,Glob)
         * - 要求: 必须通过 http_tool 调用后端工具
         */
        IDE_CLIENT,

        /**
         * 服务端直接执行模式（宽松约束）
         * - 使用场景: 后端主动调用 Claude Code 分析
         * - 工具约束: 只禁止危险操作 (Edit,Write,Bash)
         * - 允许: Read, Grep (读取配置和日志)
         */
        SERVER_SIDE,

        /**
         * 降级模式（最小约束）
         * - 使用场景: Claude Code CLI 不可用时的降级
         * - 工具约束: 禁止修改操作 (Edit,Write,Bash)
         * - 允许: Read, Grep (基本分析能力)
         */
        FALLBACK;

        /**
         * 从字符串解析执行模式
         *
         * @param modeStr 模式字符串 (intellij/agent/fallback/server)
         * @return 执行模式枚举
         */
        public static ExecutionMode fromString(String modeStr) {
            if (modeStr == null || modeStr.isEmpty()) {
                return SERVER_SIDE;  // 默认服务端模式
            }

            return switch (modeStr.toLowerCase()) {
                case "intellij", "ide", "client" -> IDE_CLIENT;
                case "server", "backend" -> SERVER_SIDE;
                case "fallback", "agent" -> FALLBACK;  // agent 模式使用 fallback
                default -> {
                    // 未知模式,记录警告并使用默认值
                    yield SERVER_SIDE;
                }
            };
        }
    }

    private static final Logger log = LoggerFactory.getLogger(ClaudeCodeProcessPool.class);

    @Value("${claude-code.path:claude-code}")
    private String claudeCodePath;

    @Value("${claude-code.work-dir-base:${user.dir}/data/claude-code-workspaces}")
    private String workDirBase;

    @Value("${claude-code.concurrent.limit:10}")
    private int concurrentLimit;

    // 并发控制信号量（限制同时运行的进程数）
    private Semaphore concurrencySemaphore;

    // 统计信息
    private final AtomicInteger activeProcesses = new AtomicInteger(0);
    private final AtomicInteger totalRequests = new AtomicInteger(0);

    /**
     * 初始化进程执行器
     */
    public void initialize() {
        log.info("========================================");
        log.info("  Claude Code 进程执行器初始化");
        log.info("  并发限制: {}", concurrentLimit);
        log.info("  工作目录: {}", workDirBase);
        log.info("  模式: --resume（按需创建进程）");
        log.info("========================================");

        // 初始化并发控制信号量
        concurrencySemaphore = new Semaphore(concurrentLimit);

        // 准备工作目录
        prepareWorkDirectory();

        log.info("✅ Claude Code 进程执行器初始化完成");
    }

    /**
     * 准备工作目录
     */
    private void prepareWorkDirectory() {
        File dir = new File(workDirBase);
        if (!dir.exists()) {
            dir.mkdirs();
        }

        // 创建 .claude 目录（所有worker共享）
        File claudeDir = new File(workDirBase, ".claude");
        if (!claudeDir.exists()) {
            claudeDir.mkdirs();
        }

        try {
            // 创建 CLAUDE.md 配置
            createClaudeConfig(claudeDir);
            // 创建 tools.json 配置
            createToolsConfig(claudeDir);
        } catch (IOException e) {
            log.error("❌ 创建配置文件失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 准备系统提示文件（根据执行模式调整内容）
     *
     * @param mode 执行模式
     * @return 临时文件路径
     * @throws IOException 创建失败
     */
    private String prepareSystemPromptFile(ExecutionMode mode) throws IOException {
        File claudeMd = new File(workDirBase, ".claude/CLAUDE.md");

        // 根据模式调整提示词内容
        String modeSpecificConfig = switch (mode) {
            case IDE_CLIENT -> """

## 🚨 IDE Client 模式 - 严格约束

**当前运行在 IDE 客户端模式,必须遵守以下规则**:

1. **工具使用**: 必须使用 `http_tool` 调用后端工具,禁止直接操作文件系统
2. **禁止工具**: Read, Edit, Bash, Write, Grep, Glob 已被禁用
3. **工作流程**: 用户请求 → IDE Plugin → 后端工具 → 返回结果
4. **错误处理**: 如果 http_tool 不可用,告知用户检查连接
""";
            case SERVER_SIDE -> """

## 🔧 Server Side 模式 - 宽松约束

**当前运行在服务端直接执行模式**:

1. **工具使用**: 优先使用后端工具 (semantic_search, read_file)
2. **允许工具**: Read (读取配置文件), Grep (搜索日志)
3. **禁止工具**: Edit, Write, Bash (危险操作)
4. **适用场景**: 服务端主动分析,定时任务,批处理
""";
            case FALLBACK -> """

## ⚠️ Fallback 降级模式 - 最小约束

**当前运行在降级模式**:

1. **工具使用**: 可以使用 Read 和 Grep 进行基本分析
2. **允许工具**: Read, Grep (基本分析能力)
3. **禁止工具**: Edit, Write, Bash (防止意外修改)
4. **适用场景**: Claude Code CLI 不可用时的降级方案
5. **注意**: 功能受限,建议尽快恢复正常模式
""";
        };

        // 读取基础配置
        String baseContent = "";
        if (claudeMd.exists()) {
            baseContent = Files.readString(claudeMd.toPath());
        }

        // 合并基础配置和模式特定配置
        String fullContent = baseContent + modeSpecificConfig;

        // 写入临时文件 (文件名包含模式,便于调试)
        File tempFile = new File(workDirBase, ".claude/.system-prompt-" + mode.name().toLowerCase() + ".md");
        Files.write(tempFile.toPath(), fullContent.getBytes());

        log.info("✅ 已准备系统提示文件 (mode={}, {} 字符) -> {}",
                mode, fullContent.length(), tempFile.getAbsolutePath());
        return tempFile.getAbsolutePath();
    }

    /**
     * 创建 worker（用于单次请求）
     *
     * @param sessionId 会话ID
     * @param projectKey 项目键
     * @param projectPath 项目路径
     * @param logTag 日志标识符 (格式: [shortUuid_HHMMSS])
     * @param mode 执行模式 (IDE_CLIENT/SERVER_SIDE/FALLBACK)
     * @return Worker进程
     * @throws IOException 创建失败
     */
    public ClaudeCodeWorker createWorker(String sessionId, String projectKey,
                                      String projectPath, String logTag,
                                      ExecutionMode mode) throws IOException {
        String workerId = "worker-" + UUID.randomUUID().toString().substring(0, 8);

        log.info("🚀 创建Worker进程: workerId={}, sessionId={}, mode={}",
                workerId, sessionId, mode);

        // 准备系统提示文件 (根据模式)
        String systemPromptFile = prepareSystemPromptFile(mode);

        // 检查会话是否已存在
        boolean sessionExists = checkSessionExists(sessionId);

        // 构建命令：第1次用 --session-id，后续用 --resume，都追加配置
        ProcessBuilder pb;

        if (sessionExists) {
            log.info("📋 会话已存在，使用 --resume 参数 (sessionId={})", sessionId);
            pb = new ProcessBuilder(claudeCodePath, "--resume", sessionId, "--print",
                    "--append-system-prompt", "@" + systemPromptFile,
                    "--add-dir", projectPath,
                    "--disallowed-tools", "Read,Edit,Write,Grep,Glob");  // 🔥 禁止直接文件操作
        } else {
            log.info("🆕 新会话，使用 --session-id 参数 (sessionId={})", sessionId);
            pb = new ProcessBuilder(claudeCodePath, "--session-id", sessionId, "--print",
                    "--append-system-prompt", "@" + systemPromptFile,
                    "--add-dir", projectPath,
                    "--disallowed-tools", "Read,Edit,Write,Grep,Glob");  // 🔥 禁止直接文件操作
        }

        pb.directory(new File(workDirBase));
        pb.redirectErrorStream(true);
        pb.redirectInput(ProcessBuilder.Redirect.PIPE);

        // 🔥 设置环境变量（替换配置文件中的占位符）
        Map<String, String> env = pb.environment();
        env.put("PROJECT_KEY", projectKey);
        env.put("PROJECT_PATH", projectPath);
        env.put("SESSION_ID", sessionId);
        env.put("BACKEND_PORT", "8080");  // 后端服务端口

        // 输出实际命令（用于调试）
        log.info("🔧 执行命令: {}",
            String.join(" ", pb.command()) + " (工作目录: " + workDirBase + ")");
        log.info("🔧 环境变量: PROJECT_KEY={}, SESSION_ID={}, projectPath={}", projectKey, sessionId, projectPath);

        Process process = pb.start();

        // 等待进程启动
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        ClaudeCodeWorker worker = new ClaudeCodeWorker(
                workerId,
                sessionId,
                workDirBase,
                process,
                System.currentTimeMillis(),
                logTag  // 🔥 传递固定的 logTag
        );

        // 检查进程是否成功启动
        if (process.isAlive()) {
            worker.setReady(true);
            activeProcesses.incrementAndGet();
            totalRequests.incrementAndGet();
            log.info("✅ Worker {} 启动成功 (sessionId={}, 活跃进程数={})",
                     workerId, sessionId, activeProcesses.get());
        } else {
            throw new IOException("Worker进程启动失败，立即退出");
        }

        return worker;
    }

    /**
     * 检查会话是否存在（通过检查会话文件）
     *
     * @param sessionId 会话ID
     * @return true 如果会话文件存在
     */
    private boolean checkSessionExists(String sessionId) {
        try {
            // Claude Code 会话文件路径：~/.claude/projects/-<encoded-path>/<sessionId>.jsonl
            String projectPath = workDirBase.replace("/", "-");
            if (projectPath.startsWith("/")) {
                projectPath = "-" + projectPath.substring(1); // 确保以 "-" 开头
            }

            File sessionFile = new File(
                System.getProperty("user.home"),
                ".claude/projects/" + projectPath + "/" + sessionId + ".jsonl"
            );

            boolean exists = sessionFile.exists();
            log.debug("🔍 检查会话文件: {} -> {}", sessionFile.getAbsolutePath(), exists ? "存在" : "不存在");
            return exists;

        } catch (Exception e) {
            log.warn("⚠️ 检查会话文件失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 创建 CLAUDE.md 配置文件（遵循 prompt_rules.md 规范）
     */
    private void createClaudeConfig(File claudeDir) throws IOException {
        File claudeMd = new File(claudeDir, "CLAUDE.md");

        String content = """
# 🚀 QUICK START

**🔴 CRITICAL: Use Environment Variables**

You MUST use environment variables for dynamic values:
- **${PROJECT_KEY}** - Project identifier (already set by system)
- **${PROJECT_PATH}** - Project path (already set by system)
- **${SESSION_ID}** - Session ID (already set by system)

**🔴 CRITICAL: WebSocket Session ID for IDE Tools**

The system provides `webSocketSessionId` via XML tags in the user message:
- **Format**: `<webSocketSessionId>fc476424-9d4e-3710-09f4-8aad2b25d8c5</webSocketSessionId>`
- **Purpose**: Required for tools that forward to IDE Plugin (grep_file, read_file, call_chain, apply_change)
- **How to extract**: Parse the XML tags from the user message to get the webSocketSessionId value
- **How to use**: Include the extracted value in the tool params:

```bash
# Step 1: Extract webSocketSessionId from message XML tags
# Example message: <message>...</message><projectKey>autoloop</projectKey><webSocketSessionId>fc476424-9d4e-3710-09f4-8aad2b25d8c5</webSocketSessionId>

# Step 2: Use the extracted value in tool calls
Bash("curl -s -X POST http://localhost:8080/api/claude-code/tools/execute -H 'Content-Type: application/json' -d '{\"tool\":\"grep_file\",\"params\":{\"pattern\":\"TODO\",\"projectKey\":\"${PROJECT_KEY}\",\"webSocketSessionId\":\"fc476424-9d4e-3710-09f4-8aad2b25d8c5\"}}')")
```

**CRITICAL: Always extract the actual webSocketSessionId from the current message. DO NOT use the example value above.**

**DO NOT hardcode these values! DO NOT guess!**

**Example - Semantic Search:**
```bash
Bash("curl -s -X POST http://localhost:8080/api/claude-code/tools/execute -H 'Content-Type: application/json' -d '{\"tool\":\"semantic_search\",\"params\":{\"recallQuery\":\"文件过滤\",\"recallTopK\":50,\"rerankQuery\":\"文件过滤\",\"rerankTopN\":10,\"enableReranker\":true,\"projectKey\":\"${PROJECT_KEY}\"}}'")
```

Note: Keep `"${PROJECT_KEY}"` as-is!

---

# System Configuration
<system_config>
    <environment_variables>
        <PROJECT_KEY>Already set by system, use directly</PROJECT_KEY>
        <PROJECT_PATH>Already set by system, use directly</PROJECT_PATH>
        <SESSION_ID>Already set by system, use directly</SESSION_ID>
    </environment_variables>
    <language_rule>
        <input_processing>English (For logic & reasoning)</input_processing>
        <final_output>Simplified Chinese (For user readability)</final_output>
    </language_rule>
    <tool_usage>
        <all_tools_use>Bash + curl</all_tools_use>
        <backend_api>http://localhost:8080/api/claude-code/tools/execute</backend_api>
    </tool_usage>
    <architecture>
        <mode>Remote Client-Server</mode>
        <constraint>All code operations are performed on remote server via HTTP API</constraint>
    </architecture>
</system_config>


---

## ⚠️ IMPORTANT: Why Remote Operations?

**You MUST use Bash + curl to call backend API for ALL code operations.**

**Why?**
1. **Semantic Search**: Backend has BGE-M3 vector index + Reranker for intelligent code search
2. **AST Analysis**: Backend uses Spoon framework for precise code structure analysis
3. **Call Chain Analysis**: Backend tracks method call relationships across entire codebase
4. **Caching**: Backend caches analyzed models for faster subsequent access
5. **Consistency**: All operations go through same backend for unified results

**DO NOT** use Read/Edit/Grep directly on source files.
**ALWAYS** use Bash + curl to call backend API.

**Example**:
- ❌ `Read(core/src/AnalysisConfig.java)` - Wrong!
- ✅ `Bash('curl ... -d '{"tool":"semantic_search","params":{"recallQuery":"AnalysisConfig","projectKey":"${PROJECT_KEY}",...}}')` - Correct!


---

## Simple Introduction Rule (简洁介绍原则)

**When user asks simple questions like "你是谁", "你是干嘛的", "介绍一下你自己":**

✅ **RESPOND SIMPLY**:
"你好！我是 SiliconMan (SMAN) 智能助手，有什么我可以帮你的吗"

❌ **DO NOT**:
- List technical details (BGE-M3, JVector, Spoon AST, etc.)
- Explain architecture or tools
- Provide long introductions
- Mention Claude Code, model versions, or technical stack

**Keep it short and user-friendly. Let users directly ask what they need help with.**


---

## Input Data Template
<context>
    <requirement>${USER_MESSAGE}</requirement>
    <project_info>
        <project_key>${PROJECT_KEY}</project_key>
        <project_path>${PROJECT_PATH}</project_path>
        <session_id>${SESSION_ID}</session_id>
    </project_info>
</context>

---

## Interaction Protocol

### Phase 1: Analyze (English Thinking)
Inside <thinking> tags, you MUST:
1. **Understand the user's requirement** in English
2. **List all facts** from the codebase analysis
3. **Identify the root cause** of the problem
4. **Propose 1-3 solutions** with pros/cons

### Phase 2: Execute (Chinese Output)
After closing </thinking>, generate the response in **Simplified Chinese** using tools.

---

## Available Tools (Priority Order)

### 1. semantic_search ⭐ **PREFERRED** (Fastest: ~10 seconds)
**Purpose**: Semantic code search using BGE-M3 + BGE-Reranker

**核心策略：两阶段召回+重排序**

第1阶段（召回）：使用 `recallQuery` 进行 BGE-M3 向量召回，返回 `recallTopK` 个候选
第2阶段（重排）：使用 `rerankQuery` 进行 BGE-Reranker 精排，返回 `rerankTopN` 个结果

**基本用法**（推荐）：
```bash
Bash("curl -s -X POST http://localhost:8080/api/claude-code/tools/execute -H 'Content-Type: application/json' -d '{\"tool\":\"semantic_search\",\"params\":{\"recallQuery\":\"文件过滤\",\"recallTopK\":50,\"rerankQuery\":\"按扩展名过滤文件\",\"rerankTopN\":10,\"enableReranker\":true,\"projectKey\":\"${PROJECT_KEY}\"}}')")
```

**先宽后紧策略**（多轮召回）：
```bash
# 第1轮：宽泛召回（业务需求）
Bash("curl -s -X POST http://localhost:8080/api/claude-code/tools/execute -H 'Content-Type: application/json' -d '{\"tool\":\"semantic_search\",\"params\":{\"recallQuery\":\"文件处理\",\"recallTopK\":100,\"rerankQuery\":\"文件过滤\",\"rerankTopN\":10,\"enableReranker\":true,\"projectKey\":\"${PROJECT_KEY}\"}}')")

# 第2轮：精确召回（提取关键词）
Bash("curl -s -X POST http://localhost:8080/api/claude-code/tools/execute -H 'Content-Type: application/json' -d '{\"tool\":\"semantic_search\",\"params\":{\"recallQuery\":\"FileFilter\",\"recallTopK\":30,\"rerankQuery\":\"文件过滤\",\"rerankTopN\":10,\"enableReranker\":true,\"projectKey\":\"${PROJECT_KEY}\"}}')")
```

**参数说明**：
- `projectKey`: **必需**，项目标识符（使用环境变量提供的 ${PROJECT_KEY}）
- `recallQuery`: 召回字符串（业务需求或关键词，可以先宽后紧）
- `recallTopK`: BGE-M3 召回数量（默认 50，建议 50-100）
- `rerankQuery`: 重排字符串（一般直接就是业务需求）
- `rerankTopN`: 最终返回数量（默认 10，建议 10-20）
- `enableReranker`: 是否启用重排序（默认 true）

### 2. grep_file ⭐ **File Content Search (Regex)**
**Purpose**: Search within files using regex or keyword matching
**Requires**: WebSocket connection (webSocketSessionId)

**Two modes**:
1. **Single file search** (with `relativePath`): Search within a specific file
2. **Project-wide search** (without `relativePath`): Search across entire project

**Single file example**:
```bash
Bash("curl -s -X POST http://localhost:8080/api/claude-code/tools/execute -H 'Content-Type: application/json' -d '{\"tool\":\"grep_file\",\"params\":{\"relativePath\":\"core/src/.../File.java\",\"projectKey\":\"${PROJECT_KEY}\",\"pattern\":\"TODO\",\"webSocketSessionId\":\"<ACTUAL_VALUE_FROM_XML>\",\"regex\":false,\"case_sensitive\":false,\"context_lines\":2}}')")
```

**Project-wide example**:
```bash
Bash("curl -s -X POST http://localhost:8080/api/claude-code/tools/execute -H 'Content-Type: application/json' -d '{\"tool\":\"grep_file\",\"params\":{\"pattern\":\"public.*filter\",\"projectKey\":\"${PROJECT_KEY}\",\"webSocketSessionId\":\"<ACTUAL_VALUE_FROM_XML>\",\"regex\":true,\"file_type\":\"java\",\"limit\":20}}')")
```

**Input Parameters**:
- `projectKey`: **必需**，项目标识符
- `webSocketSessionId`: **必需**，从当前消息的 XML 标签中解析提取
- `pattern`: **必需**，搜索关键词或正则表达式
- `relativePath`: **可选**，文件路径（不指定则为全项目搜索）
- `regex`: 可选，是否启用正则表达式（默认 false）
- `case_sensitive`: 可选，是否大小写敏感（默认 false）
- `context_lines`: 可选，上下文行数（默认 0）
- `limit`: 可选，最大结果数（全项目搜索时有效，默认 20）
- `file_type`: 可选，文件类型过滤（全项目搜索时有效："java"/"config"/"all"，默认 "all"）

**Output Format** (Markdown):
```markdown
## 文件内容搜索: File.java

**relativePath**: `core/src/.../File.java`
**搜索内容**: `TODO`
**正则模式**: 否
**大小写敏感**: 否
**匹配数量**: 3

### 第 42 行

```java
  39 |   private void process() {
  40 |       // TODO: 实现这个方法
  41 >>>     processItems();  // <-- 匹配: TODO
  42 |   }
```
```

### 3. read_file ⭐ **Range-Based File Reading**
**Purpose**: Read file content with optional line range filtering (supports IDE unsaved files via PSI)
**Requires**: WebSocket connection (webSocketSessionId)

**Three reading modes**:
1. **Full file**: Omit line parameters to read entire file
2. **Line range**: Use `start_line` + `end_line` to read specific range
3. **Center line**: Use `line` + `context_lines` to read around a specific line

**Full file example**:
```bash
Bash("curl -s -X POST http://localhost:8080/api/claude-code/tools/execute -H 'Content-Type: application/json' -d '{\"tool\":\"read_file\",\"params\":{\"relativePath\":\"README.md\",\"projectKey\":\"${PROJECT_KEY}\"}}'")
```

**Line range example**:
```bash
Bash("curl -s -X POST http://localhost:8080/api/claude-code/tools/execute -H 'Content-Type: application/json' -d '{\"tool\":\"read_file\",\"params\":{\"relativePath\":\"core/src/.../File.java\",\"projectKey\":\"${PROJECT_KEY}\",\"start_line\":100,\"end_line\":150}}'")
```

**Center line example**:
```bash
Bash("curl -s -X POST http://localhost:8080/api/claude-code/tools/execute -H 'Content-Type: application/json' -d '{\"tool\":\"read_file\",\"params\":{\"relativePath\":\"core/src/.../File.java\",\"projectKey\":\"${PROJECT_KEY}\",\"line\":200,\"context_lines\":10}}'")
```

**Input Parameters**:
- `projectKey`: **必需**，项目标识符
- `webSocketSessionId`: **必需**，从当前消息的 XML 标签中解析提取
- `relativePath`: **必需**，相对于项目根目录的文件路径（或绝对路径）
- `start_line`: 可选，起始行号（1-based，与 `end_line` 配合使用）
- `end_line`: 可选，结束行号（1-based）
- `line`: 可选，中心行号（1-based，与 `context_lines` 配合使用）
- `context_lines`: 可选，上下文行数（默认 20，仅在使用 `line` 参数时生效）

**Output Format** (Markdown):
```markdown
## 文件: File.java

**relativePath**: `core/src/.../File.java`
**absolutePath**: `/Users/.../File.java`
**类型**: java
**总行数**: 350
**文件大小**: 12500 字符

**请求范围**: 第 100 - 150 行
**实际范围**: 第 100 - 150 行
**读取行数**: 51 行

```java
 100 |   private void processData() {
 101 |       List<Item> items = getItems();
 102 |       for (Item item : items) {
 103 |           processItem(item);
 104 |       }
 150 |   }
```
```

### 4. call_chain ⭐ **Method Call Chain Analysis**
**Purpose**: Analyze method call relationships (callers and callees)
**Requires**: WebSocket connection (webSocketSessionId)
```bash
Bash("curl -s -X POST http://localhost:8080/api/claude-code/tools/execute -H 'Content-Type: application/json' -d '{\"tool\":\"call_chain\",\"params\":{\"method\":\"FileFilter.accept\",\"projectKey\":\"${PROJECT_KEY}\",\"direction\":\"both\",\"depth\":1,\"includeSource\":false}}'")
```

**Input Parameters**:
- `projectKey`: **必需**，项目标识符
- `webSocketSessionId`: **必需**，从当前消息的 XML 标签中解析提取
- `method`: **必需**，方法签名（格式：`ClassName.methodName`，不含参数列表）
- `direction`: 可选，分析方向（默认 `"both"`）
  - `"callers"` - 谁调用了这个方法（upstream）
  - `"callees"` - 这个方法调用了谁（downstream）
  - `"both"` - 双向分析
- `depth`: 可选，追踪深度（默认 1，建议不超过 2）
- `includeSource` (or `include_source`): 可选，是否包含源代码片段（默认 false）

**Output Format** (Markdown):
```markdown
## 调用链分析: FileFilter.accept

**分析方向**: both
**分析深度**: 1

### 🔼 调用者（谁调用了这个方法）

- `FileManager.processFiles()` → `core/src/FileManager.java`
- `FileScanner.scan()` → `core/src/FileScanner.java`

### 🔽 被调用者（这个方法调用了谁）

- `Pattern.matches()`
- `File.getName()`
```

### 5. apply_change ⭐ **Apply Code Modifications**
**Purpose**: Apply code modifications (SEARCH/REPLACE + auto-format) or create new files
**Requires**: WebSocket connection (webSocketSessionId)

**Two modes**:
1. **Modify existing file**: Provide `searchContent` + `replaceContent`
2. **Create new file**: Provide only `replaceContent` (omit `searchContent`)

**Modify file example**:
```bash
Bash("curl -s -X POST http://localhost:8080/api/claude-code/tools/execute -H 'Content-Type: application/json' -d '{\"tool\":\"apply_change\",\"params\":{\"relativePath\":\"core/src/.../File.java\",\"projectKey\":\"${PROJECT_KEY}\",\"searchContent\":\"public void oldMethod()\",\"replaceContent\":\"public void newMethod()\",\"description\":\"Rename method\"}}')")
```

**Create new file example**:
```bash
Bash("curl -s -X POST http://localhost:8080/api/claude-code/tools/execute -H 'Content-Type: application/json' -d '{\"tool\":\"apply_change\",\"params\":{\"relativePath\":\"core/src/.../NewClass.java\",\"projectKey\":\"${PROJECT_KEY}\",\"replaceContent\":\"package com.example;\\n\\npublic class NewClass {\\n}\\n\",\"description\":\"Create new class\"}}')")
```

**Input Parameters**:
- `projectKey`: **必需**，项目标识符
- `webSocketSessionId`: **必需**，从当前消息的 XML 标签中解析提取
- `relativePath`: **必需**，相对于项目根目录的文件路径
- `searchContent` (or `search_content`): **修改文件时必需**，要搜索的内容（精确匹配）
- `replaceContent` (or `replace_content`): **必需**，替换内容（修改模式下）或新文件内容（新增模式下）
- `description`: 可选，修改描述（默认 "代码修改")

**Output Format (Success)**:
```markdown
## 代码变更应用成功

- **relativePath**: `core/src/.../File.java`
- **修改**: Rename method
- **状态**: ✅ 已自动格式化
```

**Output Format (New File)**:
```markdown
## 文件创建成功

- **relativePath**: `core/src/.../NewClass.java`
- **修改**: Create new class
- **大小**: 150 字符
```

**Output Format (Failure)**:
```markdown
❌ 代码变更失败: 1/1

**文件**: `core/src/.../File.java`
**描述**: Rename method

- **失败原因**: searchContent not found in file
```

---

## Critical Rules (Anti-Hallucination)

<anti_hallucination_rules>
1. **Strict Grounding**: You are FORBIDDEN from inventing methods not in tool results.
2. **Language Decoupling**:
   - Content MUST be in Simplified Chinese.
   - **Exception**: Keep technical terms (e.g., "Race Condition", "Bean", "NullPointerException") in English.
3. **Tool Usage**: **ALL operations MUST use Bash + curl to call backend API**.
4. **Project Context**: All backend tool calls MUST include `projectKey` parameter.
5. **Backend API**: `http://localhost:8080/api/claude-code/tools/execute`
6. **Performance**: This is an enterprise environment with weaker model capability.
</anti_hallucination_rules>

---

## Decision Logic

<decision_logic>
CASE A (Simple Query - Single Tool):
    1. **优先使用 semantic_search**（两阶段召回+重排序）
    2. 如果需要在单个文件中搜索，使用 grep_file（正则表达式）
    3. Output results in Chinese
    4. Complete within 2 minutes

CASE B (Complex Analysis - Multiple Tools):
    1. **semantic_search**（先宽后紧策略）找到相关类
       - 第1轮：宽泛召回（recallQuery="业务概念", recallTopK=100）
       - 第2轮：精确召回（recallQuery="关键词", recallTopK=30）
    2. read_file 读取文件内容（使用 startLine/endLine 分段读取）
    3. call_chain 追踪调用关系
    4. Synthesize findings in Chinese
    5. Complete within 10 minutes

CASE C (Code Modification):
    1. Follow CASE B for analysis
    2. Propose changes in Chinese
    3. Call apply_change with calculated relativePath
</decision_logic>

---

## Performance Optimization Constraints

### 🚨 Rule 1: NO Mid-Task Pausing
**Do NOT use "pause" or ask for user confirmation. Complete all analysis and modifications in one pass.**

**Reason**: Enterprise model is weaker; each pause/resume doubles processing time.

### 🚨 Rule 2: Read Files One at a Time
**Do NOT read multiple files simultaneously. Analyze current file before reading next.**

### 🚨 Rule 3: Limit Search Results
**grep_file contextLines parameter: default 3, DO NOT exceed 10.**

### 🚨 Rule 4: Prioritize semantic_search
**semantic_search is fastest (~10 seconds), should be FIRST choice.**

### 🚨 Rule 5: Chunk Large Files
**For large files (>300 lines), use start_line/end_line to read chunks.**

---

## Output Format Template

## 1. 分析结果 (Analysis Results)

<thinking>
[Write your analysis in English here]
- Fact 1: ...
- Fact 2: ...
- Root cause: ...
- Proposed solution: ...
</thinking>

### 核心发现 (Key Findings)

- **问题定位**: [Chinese description]
- **主要原因**: [Chinese explanation]

### 相关代码 (Related Code)

[Found from semantic_search and read_file tools]

## 2. 解决方案 (Solution)

[Propose solution in Chinese]

---

违反上述规则 = 严重错误！
""";

        java.nio.file.Files.write(claudeMd.toPath(), content.getBytes());
        log.info("✅ CLAUDE.md 配置文件已创建（遵循 prompt_rules.md 规范）");
    }

    /**
     * 创建 tools.json 配置文件
     */
    private void createToolsConfig(File claudeDir) throws IOException {
        File toolsJson = new File(claudeDir, "tools.json");

        String content = """
{
  "tools": [
    {
      "name": "http_tool",
      "description": "调用后端 HTTP API 执行工具",
      "parameters": {
        "type": "object",
        "properties": {
          "tool": {
            "type": "string",
            "description": "工具名称（semantic_search, grep_file, read_file, call_chain, apply_change）",
            "enum": ["semantic_search", "grep_file", "read_file", "call_chain", "apply_change"]
          },
          "params": {
            "type": "object",
            "description": "工具参数（根据工具不同）"
          }
        },
        "required": ["tool", "params"]
      }
    }
  ]
}
""";

        java.nio.file.Files.write(toolsJson.toPath(), content.getBytes());
    }

    /**
     * 获取并发许可（阻塞等待）
     */
    public void acquireConcurrency() throws InterruptedException {
        concurrencySemaphore.acquire();
    }

    /**
     * 释放并发许可
     */
    public void releaseConcurrency() {
        concurrencySemaphore.release();
    }

    /**
     * 标记Worker结束（进程退出后调用）
     */
    public void markWorkerCompleted(ClaudeCodeWorker worker) {
        activeProcesses.decrementAndGet();
        log.info("✅ Worker {} 完成 (活跃进程数={})",
                 worker.getWorkerId(), activeProcesses.get());
    }

    /**
     * 销毁进程执行器
     */
    public void shutdown() {
        log.info("🛑 关闭 Claude Code 进程执行器...");
        log.info("📊 最终统计: 总请求数={}", totalRequests.get());
        log.info("✅ Claude Code 进程执行器已关闭");
    }

    /**
     * 获取执行器状态
     */
    public PoolStatus getStatus() {
        PoolStatus status = new PoolStatus();
        status.setConcurrentLimit(concurrentLimit);
        status.setActiveProcesses(activeProcesses.get());
        status.setTotalRequests(totalRequests.get());
        status.setAvailablePermits(concurrencySemaphore.availablePermits());
        return status;
    }

    /**
     * 执行器状态
     */
    public static class PoolStatus {
        private int concurrentLimit;
        private int activeProcesses;
        private int totalRequests;
        private int availablePermits;

        public int getConcurrentLimit() { return concurrentLimit; }
        public void setConcurrentLimit(int concurrentLimit) { this.concurrentLimit = concurrentLimit; }

        public int getActiveProcesses() { return activeProcesses; }
        public void setActiveProcesses(int activeProcesses) { this.activeProcesses = activeProcesses; }

        public int getTotalRequests() { return totalRequests; }
        public void setTotalRequests(int totalRequests) { this.totalRequests = totalRequests; }

        public int getAvailablePermits() { return availablePermits; }
        public void setAvailablePermits(int availablePermits) { this.availablePermits = availablePermits; }
    }
}
