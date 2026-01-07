package ai.smancode.sman.agent.claude;

import ai.smancode.sman.agent.utils.PathUtils;
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
            // 创建 skills 目录
            createSkills(claudeDir);
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

## IDE Client 模式 - 强制工具调用

### 🚨 核心规则

**用户询问任何代码问题时，必须使用 Bash 工具调用脚本，绝对禁止不调用工具直接回答！**


### 📋 工具调用示例（严格模仿格式）

#### 示例 1：搜索类名
当用户问"AnalysisConfig 是做什么的？"时：

```bash
Bash("bash .claude/skills/sman-tools/scripts/grep_file.sh '{"pattern": "AnalysisConfig", "fileType": "java", "limit": 20, "projectKey": "${PROJECT_KEY}", "webSocketSessionId": "3658af12-ad70-9a34-da84-3b57d98ba4d6"}'")
```

#### 示例 2：语义搜索功能
当用户问"文件过滤是怎么实现的？"时：

```bash
Bash("bash .claude/skills/sman-tools/scripts/semantic_search.sh '{"recallQuery": "文件过滤", "rerankQuery": "文件过滤", "recallTopK": 50, "rerankTopN": 10, "enableReranker": true, "projectKey": "${PROJECT_KEY}"}'")
```

#### 示例 3：读取文件内容
当需要读取具体文件时：

```bash
Bash("bash .claude/skills/sman-tools/scripts/read_file.sh '{"relativePath": "core/src/main/java/FileFilter.java", "projectKey": "${PROJECT_KEY}", "webSocketSessionId": "3658af12-ad70-9a34-da84-3b57d98ba4d6"}'")
```

### ⚠️ 重要提示

1. **JSON 格式必须正确**：确保 '{' 和 '}' 成对，'"' 正确配对
2. **从用户消息中提取 webSocketSessionId**：格式为 `<webSocketSessionId>uuid</webSocketSessionId>`
3. **参数值不要省略**：所有必需参数都必须提供

### ⚠️ 禁止行为

- ❌ 禁止不调用 Bash 工具直接回答代码问题
- ❌ 禁止使用 Read/Edit/Write/Grep/Glob（已被禁用）
- ❌ 禁止说"通常"、"一般"、"可能"（必须基于实际代码）
""";
            case SERVER_SIDE -> """

## Server Side 模式

使用 **sman-tools** skill 提供的工具进行代码分析。

- ✅ 使用 skill 中的工具（semantic_search, grep_file, read_file, call_chain, apply_change）
- ⚠️ 允许使用 Read 读取配置文件
""";
            case FALLBACK -> """

## Fallback 降级模式

使用 **sman-tools** skill 提供的工具进行代码分析。

- ✅ 使用 skill 中的工具（如果可用）
- ⚠️ 功能受限，建议尽快恢复正常模式
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

        // 🔥 企业内部环境：禁用 Pre-flight check
        // Pre-flight check 是 Claude Code 每次调用 Bash 工具时检查命令注入的安全性
        // 在企业内网可能因防火墙/代理导致慢，禁用该检查以提高响应速度
        env.put("CLAUDE_CODE_DISABLE_COMMAND_INJECTION_CHECK", "1");  // 禁用 Pre-flight check

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
            // 🔥 使用 PathUtils 统一处理 CLI 会话路径编码
            String sessionFilePath = PathUtils.buildCliSessionFilePath(workDirBase, sessionId);
            File sessionFile = new File(sessionFilePath);

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
     *
     * 每次启动时重新创建，确保使用最新配置
     */
    private void createClaudeConfig(File claudeDir) throws IOException {
        File claudeMd = new File(claudeDir, "CLAUDE.md");

        // 如果已存在，先删除（确保版本更新）
        if (claudeMd.exists()) {
            claudeMd.delete();
        }

        String content = """
# SiliconMan Agent

你是一个代码分析助手，使用以下工具分析 Java 代码库。

## 🔧 环境配置

**禁用 Bash Pre-flight Check**：
- 当前运行在企业内网环境，Bash 工具的 Pre-flight check 会因网络策略导致延迟
- **禁止**等待 Pre-flight check 完成，直接执行 Bash 命令
- **禁止**显示 "Pre-flight check is taking longer than expected" 警告
- 所有 Bash 命令都应立即执行，无需等待安全检查

---

## 🚨 CRITICAL: 代码问题强制规则

**当用户询问任何代码相关问题时，你必须首先调用工具搜索，绝对不能凭空回答！**

### 正确流程

1. **用户问代码问题** → 立即调用 `grep_file` 搜索类名/方法名 或 `semantic_search` 语义搜索
2. **等待工具返回结果** → 基于实际的代码内容分析
3. **给出准确答案** → 引用工具返回的代码片段

### ❌ 严格禁止的行为

- **禁止不调用工具直接回答** - 这是编造内容！
- **禁止使用训练的知识猜测** - 你的知识可能过时或不匹配！
- **禁止说"通常"、"一般"、"可能"、"应该"、"或许"** - 必须基于实际代码！
- **禁止编造不存在的类名、方法名、文件路径** - 没找到就是没找到！

### 示例对比

**用户问**："文件过滤是怎么实现的？"

❌ **错误**（编造）：
> 文件过滤通常通过 FilenameFilter 或使用 endsWith() 方法检查扩展名来实现，可能还会涉及到正则表达式...

✅ **正确**（调用工具）：
> 首先调用 `grep_file` 搜索 "FileFilter" 或 `semantic_search` 搜索 "文件过滤"，
> 然后基于返回的实际代码分析。

### 如果你不知道答案

- **不知道就是不知道**，说"我没有在代码中找到相关实现"
- **绝对不要编造**任何代码或功能

---

## 💬 自我介绍

当用户询问"你是谁"、"你能做什么"、"介绍一下自己"等问题时，**仅**回答：

**你好！我是 SiliconMan 智能助手，有什么可以帮你的？**

**禁止添加任何其他内容**，例如：
- ❌ 不要说"我可以帮你搜索代码..."
- ❌ 不要列举你能做什么
- ❌ 不要说"根据系统指令..."
- ❌ 不要添加任何表情符号或列表
- ✅ 只回答那一句话！

## 🚀 必须使用的工具

**所有代码分析操作都必须使用以下工具**（通过 Bash 调用脚本）：

### 1. semantic_search - 语义搜索（推荐优先使用）

**用途**：按功能语义搜索代码

**参数**：
- `recallQuery` (string, 必需): 召回查询
- `rerankQuery` (string, 必需): 重排查询
- `recallTopK` (number, 必需): 召回数量（50）
- `rerankTopN` (number, 必需): 返回数量（10）
- `enableReranker` (boolean, 必需): 启用重排
- `projectKey` (string, 必需): 项目标识符

**调用方式**：
```bash
Bash("bash .claude/skills/sman-tools/scripts/semantic_search.sh '{\\"recallQuery\\": \\"文件过滤\\", \\"rerankQuery\\": \\"文件过滤\\", \\"recallTopK\\": 50, \\"rerankTopN\\": 10, \\"enableReranker\\": true, \\"projectKey\\": \\"${PROJECT_KEY}\\"}'")
```

### 2. grep_file - 精确搜索

**用途**：搜索类名、方法名、变量名

**参数**：
- `pattern` (string, 必需): 搜索关键词
- `fileType` (string, 可选): 文件类型（默认 "all"）
- `limit` (number, 可选): 最大结果数（20）
- `projectKey` (string, 必需): 项目标识符
- `webSocketSessionId` (string, 必需): 从用户消息 XML 标签提取

**调用方式**：
```bash
Bash("bash .claude/skills/sman-tools/scripts/grep_file.sh '{\\"pattern\\": \\"FileFilterUtil\\", \\"fileType\\": \\"java\\", \\"limit\\": 20, \\"projectKey\\": \\"${PROJECT_KEY}\\", \\"webSocketSessionId\\": \\"<从XML提取>\\"}'")
```

### 3. read_file - 读取文件

**参数**：
- `relativePath` (string, 必需): 文件路径
- `projectKey` (string, 必需): 项目标识符
- `webSocketSessionId` (string, 必需): 从用户消息 XML 标签提取

### 4. call_chain - 调用链分析

**参数**：
- `method` (string, 必需): 方法签名（ClassName.methodName）
- `direction` (string, 可选): 方向（默认 "both"）
- `depth` (number, 可选): 深度（默认 1）
- `projectKey` (string, 必需): 项目标识符
- `webSocketSessionId` (string, 必需): 从用户消息 XML 标签提取

### 5. apply_change - 代码修改

**参数**：
- `relativePath` (string, 必需): 文件路径
- `searchContent` (string, 可选): 搜索内容
- `replaceContent` (string, 必需): 替换内容
- `description` (string, 可选): 修改描述
- `projectKey` (string, 必需): 项目标识符
- `webSocketSessionId` (string, 必需): 从用户消息 XML 标签提取

## ⚠️ 重要约束

- **禁止使用** Read/Edit/Write/Grep/Glob 等内置工具
- **必须使用** 上述 5 个工具进行所有代码分析操作
- 用户询问任何代码问题时，**必须先使用 semantic_search 或 grep_file 搜索**

## 环境变量

- `${PROJECT_KEY}` - 项目标识符 (已自动设置)
- `${PROJECT_PATH}` - 项目路径 (已自动设置)
- `${SESSION_ID}` - 会话 ID (已自动设置)
- `${BACKEND_PORT}` - 后端端口 (已自动设置，默认 8080)

## WebSocket Session ID

从用户消息中提取：
```xml
<webSocketSessionId>fc476424-9d4e-3710-09f4-8aad2b25d8c5</webSocketSessionId>
```

## 语言规则

- **思考**: 英文 (在 `<thinking>` 标签内)
- **输出**: 简体中文
- **例外**: 技术术语保留英文
""";

        java.nio.file.Files.write(claudeMd.toPath(), content.getBytes());
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
     * 创建 sman-tools skill 到 workDirBase/.claude/skills/
     *
     * 如果已存在，先删除再创建，确保版本更新时使用最新的 skill
     *
     * @param claudeDir .claude 目录
     * @throws IOException 创建失败
     */
    private void createSkills(File claudeDir) throws IOException {
        File skillDir = new File(claudeDir, "skills/sman-tools");

        // 如果已存在，先删除（确保版本更新）
        if (skillDir.exists()) {
            log.debug("🗑️ 删除旧的 sman-tools skill: {}", skillDir.getAbsolutePath());
            deleteDirectory(skillDir);
        }

        // 创建新目录
        File skillsDir = new File(skillDir, "scripts");
        skillsDir.mkdirs();

        // 创建 SKILL.md (使用单独的资源文件更简单，这里先硬编码)
        File skillMd = new File(skillDir, "SKILL.md");
        StringBuilder skillContent = new StringBuilder();
        skillContent.append("---\n");
        skillContent.append("name: sman-tools\n");
        skillContent.append("description: 代码分析工具。当用户询问 Java 代码相关问题、搜索类名/方法名、理解代码功能、分析调用关系、或修改代码时使用。提供语义搜索、精确搜索、文件读取、调用链分析和代码修改功能。\n");
        skillContent.append("allowed-tools: Bash\n");
        skillContent.append("---\n\n");
        skillContent.append("# SiliconMan Agent 代码分析工具\n\n");
        skillContent.append("使用后端 HTTP API 进行 Java 代码分析。\n\n");
        skillContent.append("## 环境变量（自动注入）\n\n");
        skillContent.append("- `${PROJECT_KEY}` - 项目标识符\n");
        skillContent.append("- `${PROJECT_PATH}` - 项目路径\n");
        skillContent.append("- `${SESSION_ID}` - 会话 ID\n");
        skillContent.append("- `${BACKEND_PORT}` - 后端端口（默认 8080）\n\n");
        skillContent.append("## 工具列表\n\n");
        skillContent.append("### 1. semantic_search - 语义搜索\n\n");
        skillContent.append("**用途**：按功能语义搜索代码\n\n");
        skillContent.append("**参数**：\n");
        skillContent.append("- `recallQuery` (string, 必需): 召回查询字符串\n");
        skillContent.append("- `rerankQuery` (string, 必需): 重排查询字符串\n");
        skillContent.append("- `recallTopK` (number, 必需): 召回数量（推荐 50）\n");
        skillContent.append("- `rerankTopN` (number, 必需): 返回数量（推荐 10）\n");
        skillContent.append("- `enableReranker` (boolean, 必需): 是否启用重排序\n");
        skillContent.append("- `projectKey` (string, 必需): 项目标识符\n\n");
        skillContent.append("**示例**：\n");
        skillContent.append("```bash\n");
        skillContent.append("bash .claude/skills/sman-tools/scripts/semantic_search.sh '{\n");
        skillContent.append("  \"recallQuery\": \"文件过滤\",\n");
        skillContent.append("  \"rerankQuery\": \"按扩展名过滤文件\",\n");
        skillContent.append("  \"recallTopK\": 50,\n");
        skillContent.append("  \"rerankTopN\": 10,\n");
        skillContent.append("  \"enableReranker\": true,\n");
        skillContent.append("  \"projectKey\": \"${PROJECT_KEY}\"\n");
        skillContent.append("}'\n");
        skillContent.append("```\n\n");
        skillContent.append("### 2. grep_file - 精确搜索\n\n");
        skillContent.append("**用途**：搜索类名、方法名、变量名\n\n");
        skillContent.append("**参数**：\n");
        skillContent.append("- `pattern` (string, 必需): 搜索关键词\n");
        skillContent.append("- `relativePath` (string, 可选): 文件路径（不指定则全项目搜索）\n");
        skillContent.append("- `fileType` (string, 可选): 文件类型（默认 \"all\"）\n");
        skillContent.append("- `limit` (number, 可选): 最大结果数（默认 20）\n");
        skillContent.append("- `projectKey` (string, 必需): 项目标识符\n");
        skillContent.append("- `webSocketSessionId` (string, 必需): WebSocket Session ID\n\n");
        skillContent.append("**示例**：\n");
        skillContent.append("```bash\n");
        skillContent.append("bash .claude/skills/sman-tools/scripts/grep_file.sh '{\n");
        skillContent.append("  \"pattern\": \"PutOutHandler\",\n");
        skillContent.append("  \"fileType\": \"java\",\n");
        skillContent.append("  \"limit\": 20,\n");
        skillContent.append("  \"projectKey\": \"${PROJECT_KEY}\",\n");
        skillContent.append("  \"webSocketSessionId\": \"<从XML提取>\"\n");
        skillContent.append("}'\n");
        skillContent.append("```\n\n");
        skillContent.append("### 3. read_file - 读取文件\n\n");
        skillContent.append("**用途**：读取文件内容\n\n");
        skillContent.append("**参数**：\n");
        skillContent.append("- `relativePath` (string, 必需): 文件相对路径\n");
        skillContent.append("- `startLine` (number, 可选): 起始行号（1-based）\n");
        skillContent.append("- `endLine` (number, 可选): 结束行号（1-based）\n");
        skillContent.append("- `line` (number, 可选): 中心行号（1-based）\n");
        skillContent.append("- `contextLines` (number, 可选): 上下文行数（默认 20）\n");
        skillContent.append("- `projectKey` (string, 必需): 项目标识符\n");
        skillContent.append("- `webSocketSessionId` (string, 必需): WebSocket Session ID\n\n");
        skillContent.append("**示例**：\n");
        skillContent.append("```bash\n");
        skillContent.append("bash .claude/skills/sman-tools/scripts/read_file.sh '{\n");
        skillContent.append("  \"relativePath\": \"core/src/main/java/FileFilter.java\",\n");
        skillContent.append("  \"projectKey\": \"${PROJECT_KEY}\",\n");
        skillContent.append("  \"webSocketSessionId\": \"<从XML提取>\"\n");
        skillContent.append("}'\n");
        skillContent.append("```\n\n");
        skillContent.append("### 4. call_chain - 调用链分析\n\n");
        skillContent.append("**用途**：分析方法调用关系\n\n");
        skillContent.append("**参数**：\n");
        skillContent.append("- `method` (string, 必需): 方法签名（格式：ClassName.methodName）\n");
        skillContent.append("- `direction` (string, 可选): 方向（默认 \"both\"：callers/callees/both）\n");
        skillContent.append("- `depth` (number, 可选): 追踪深度（默认 1）\n");
        skillContent.append("- `projectKey` (string, 必需): 项目标识符\n");
        skillContent.append("- `webSocketSessionId` (string, 必需): WebSocket Session ID\n\n");
        skillContent.append("**示例**：\n");
        skillContent.append("```bash\n");
        skillContent.append("bash .claude/skills/sman-tools/scripts/call_chain.sh '{\n");
        skillContent.append("  \"method\": \"FileFilter.accept\",\n");
        skillContent.append("  \"direction\": \"both\",\n");
        skillContent.append("  \"depth\": 2,\n");
        skillContent.append("  \"projectKey\": \"${PROJECT_KEY}\",\n");
        skillContent.append("  \"webSocketSessionId\": \"<从XML提取>\"\n");
        skillContent.append("}'\n");
        skillContent.append("```\n\n");
        skillContent.append("### 5. apply_change - 应用代码修改\n\n");
        skillContent.append("**用途**：修改代码或创建新文件\n\n");
        skillContent.append("**参数**：\n");
        skillContent.append("- `relativePath` (string, 必需): 文件相对路径\n");
        skillContent.append("- `searchContent` (string, 可选): 搜索内容（修改现有文件时必需）\n");
        skillContent.append("- `replaceContent` (string, 必需): 替换内容\n");
        skillContent.append("- `description` (string, 可选): 修改描述\n");
        skillContent.append("- `projectKey` (string, 必需): 项目标识符\n");
        skillContent.append("- `webSocketSessionId` (string, 必需): WebSocket Session ID\n\n");
        skillContent.append("**示例**：\n");
        skillContent.append("```bash\n");
        skillContent.append("bash .claude/skills/sman-tools/scripts/apply_change.sh '{\n");
        skillContent.append("  \"relativePath\": \"core/src/main/java/FileFilter.java\",\n");
        skillContent.append("  \"searchContent\": \"public boolean accept(File file) {\",\n");
        skillContent.append("  \"replaceContent\": \"public boolean accept(File file) {\\\\n    // TODO: 增加日志\",\n");
        skillContent.append("  \"description\": \"添加日志注释\",\n");
        skillContent.append("  \"projectKey\": \"${PROJECT_KEY}\",\n");
        skillContent.append("  \"webSocketSessionId\": \"<从XML提取>\"\n");
        skillContent.append("}'\n");
        skillContent.append("```\n\n");
        skillContent.append("## 语言规则\n\n");
        skillContent.append("- **思考**: 英文（在 `<thinking>` 标签内）\n");
        skillContent.append("- **输出**: 简体中文\n");
        skillContent.append("- **例外**: 技术术语保留英文\n\n");
        skillContent.append("## WebSocket Session ID 提取\n\n");
        skillContent.append("部分工具需要 `webSocketSessionId`，需从用户消息的 XML 标签中提取：\n");
        skillContent.append("```xml\n");
        skillContent.append("<webSocketSessionId>fc476424-9d4e-3710-09f4-8aad2b25d8c5</webSocketSessionId>\n");
        skillContent.append("```\n\n");
        skillContent.append("提取方法：\n");
        skillContent.append("1. 检查用户消息是否包含 `<webSocketSessionId>` 标签\n");
        skillContent.append("2. 提取标签内的 UUID\n");
        skillContent.append("3. 在工具调用时传入该参数\n");
        java.nio.file.Files.write(skillMd.toPath(), skillContent.toString().getBytes());

        // 创建 semantic_search.sh
        File semanticSearchScript = new File(skillsDir, "semantic_search.sh");
        String semanticSearchContent = "#!/bin/bash\n" +
            "set -euo pipefail\n" +
            "INPUT=\"${1:-$(cat)}\"\n" +
            "curl -s -X POST \"http://localhost:${BACKEND_PORT:-8080}/api/claude-code/tools/execute\" \\\n" +
            "  -H 'Content-Type: application/json' \\\n" +
            "  -d \"{\\\"tool\\\": \\\"semantic_search\\\", \\\"params\\\": ${INPUT}}\"\n";
        java.nio.file.Files.write(semanticSearchScript.toPath(), semanticSearchContent.getBytes());
        semanticSearchScript.setExecutable(true);

        // 创建 grep_file.sh
        File grepFileScript = new File(skillsDir, "grep_file.sh");
        String grepFileContent = "#!/bin/bash\n" +
            "set -euo pipefail\n" +
            "INPUT=\"${1:-$(cat)}\"\n" +
            "curl -s -X POST \"http://localhost:${BACKEND_PORT:-8080}/api/claude-code/tools/execute\" \\\n" +
            "  -H 'Content-Type: application/json' \\\n" +
            "  -d \"{\\\"tool\\\": \\\"grep_file\\\", \\\"params\\\": ${INPUT}}\"\n";
        java.nio.file.Files.write(grepFileScript.toPath(), grepFileContent.getBytes());
        grepFileScript.setExecutable(true);

        // 创建 read_file.sh
        File readFileScript = new File(skillsDir, "read_file.sh");
        String readFileContent = "#!/bin/bash\n" +
            "set -euo pipefail\n" +
            "INPUT=\"${1:-$(cat)}\"\n" +
            "curl -s -X POST \"http://localhost:${BACKEND_PORT:-8080}/api/claude-code/tools/execute\" \\\n" +
            "  -H 'Content-Type: application/json' \\\n" +
            "  -d \"{\\\"tool\\\": \\\"read_file\\\", \\\"params\\\": ${INPUT}}\"\n";
        java.nio.file.Files.write(readFileScript.toPath(), readFileContent.getBytes());
        readFileScript.setExecutable(true);

        // 创建 call_chain.sh
        File callChainScript = new File(skillsDir, "call_chain.sh");
        String callChainContent = "#!/bin/bash\n" +
            "set -euo pipefail\n" +
            "INPUT=\"${1:-$(cat)}\"\n" +
            "curl -s -X POST \"http://localhost:${BACKEND_PORT:-8080}/api/claude-code/tools/execute\" \\\n" +
            "  -H 'Content-Type: application/json' \\\n" +
            "  -d \"{\\\"tool\\\": \\\"call_chain\\\", \\\"params\\\": ${INPUT}}\"\n";
        java.nio.file.Files.write(callChainScript.toPath(), callChainContent.getBytes());
        callChainScript.setExecutable(true);

        // 创建 apply_change.sh
        File applyChangeScript = new File(skillsDir, "apply_change.sh");
        String applyChangeContent = "#!/bin/bash\n" +
            "set -euo pipefail\n" +
            "INPUT=\"${1:-$(cat)}\"\n" +
            "curl -s -X POST \"http://localhost:${BACKEND_PORT:-8080}/api/claude-code/tools/execute\" \\\n" +
            "  -H 'Content-Type: application/json' \\\n" +
            "  -d \"{\\\"tool\\\": \\\"apply_change\\\", \\\"params\\\": ${INPUT}}\"\n";
        java.nio.file.Files.write(applyChangeScript.toPath(), applyChangeContent.getBytes());
        applyChangeScript.setExecutable(true);

        log.info("✅ 已创建 sman-tools skill: {}", skillDir.getAbsolutePath());
    }

    /**
     * 递归删除目录
     */
    private void deleteDirectory(File directory) {
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    deleteDirectory(file);
                } else {
                    file.delete();
                }
            }
        }
        directory.delete();
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
