# Claude Code 降级策略

**版本**: 1.0
**更新日期**: 2026-01-05
**状态**: 设计中

---

## 📋 概述

降级策略（Fallback Strategy）是指当 Claude Code CLI 不可用时，系统自动切换到本地模式，通过直接调用后端工具来提供基本的代码分析能力。

### 核心目标

- 🛡️ **可用性保障**: 即使 Claude Code CLI 不可用，核心功能仍能运行
- 🚀 **快速响应**: 降级模式下响应更快（无需启动进程）
- 🔄 **自动恢复**: Claude Code CLI 恢复后自动切回
- 🎯 **功能降级**: 在降级模式下明确告知用户功能限制

---

## 🔴 降级触发条件

### 1. Claude Code CLI 未安装

**检测方式**:

```java
private boolean isClaudeCodeInstalled() {
    try {
        ProcessBuilder pb = new ProcessBuilder(claudeCodePath, "--version");
        Process process = pb.start();
        return process.waitFor() == 0;
    } catch (Exception e) {
        return false;
    }
}
```

**降级行为**: 永久降级（需要管理员配置）

---

### 2. Claude Code CLI 调用失败

**失败场景**:

| 场景 | 检测方式 | 降级类型 |
|------|----------|----------|
| 进程启动失败 | `process.isAlive() == false` | 临时降级 |
| 进程响应超时 | `sendAndReceive() > 120s` | 临时降级 |
| 进程崩溃退出 | 进程退出码 != 0 | 临时降级 |
| 会话锁冲突 | `--resume` 失败（会话被占用） | 重试（不降级） |

**临时降级持续时间**: 5 分钟（之后尝试恢复）

---

### 3. 网络或资源问题

**检测方式**:

```java
private boolean hasResourceIssue() {
    // 检查内存是否充足（需要至少 500MB 可用内存）
    if (getAvailableMemory() < 500 * 1024 * 1024) {
        return true;
    }

    // 检查磁盘空间（需要至少 1GB 可用空间）
    if (getAvailableDiskSpace() < 1024 * 1024 * 1024) {
        return true;
    }

    return false;
}
```

---

## ⚙️ 降级模式工作原理

### 正常模式 vs 降级模式

| 特性 | 正常模式 (Claude Code) | 降级模式 (本地) |
|------|----------------------|----------------|
| Agent 引擎 | Claude Code CLI | 后端规则引擎 |
| 推理能力 | ✅ AI 智能推理 | ⚠️ 基于规则的模式匹配 |
| 工具调用 | 前端工具 (12个) | 后端工具 (5个) |
| 多轮对话 | ✅ 支持 | ⚠️ 有限支持 |
| 代码修改 | ✅ 智能重构 | ⚠️ 简单替换 |
| 响应时间 | 3-5 秒 | <1 秒 |
| 并发能力 | 10 个请求 | 50+ 个请求 |

---

### 降级模式架构

```
前端 (WebSocket)
    ↓ AGENT_CHAT
后端: 检测到降级触发条件
    ↓
后端: 降级规则引擎 (FallbackOrchestrator)
    ↓
后端: 直接调用工具 API
    ↓ TOOL_CALL
前端: 执行本地工具
    ↓ TOOL_RESULT
后端: 规则引擎组装响应
    ↓ AGENT_RESPONSE
前端: 显示结果（带降级提示）
```

---

## 🏗️ 降级模式实现

### 1. 降级检测器

**文件**: `agent/src/main/java/ai/smancode/sman/agent/fallback/FallbackDetector.java`

```java
@Component
public class FallbackDetector {

    private static final Logger log = LoggerFactory.getLogger(FallbackDetector.class);

    @Value("${claude-code.path:claude-code}")
    private String claudeCodePath;

    @Value("${agent.fallback.enabled:true}")
    private boolean fallbackEnabled;

    @Value("${agent.fallback.auto-detect:true}")
    private boolean autoDetect;

    // 降级状态
    private volatile boolean inFallbackMode = false;
    private volatile long fallbackStartTime = 0;
    private final Duration fallbackDuration = Duration.ofMinutes(5);

    /**
     * 检查是否应该启用降级模式
     */
    public boolean shouldEnableFallback() {
        if (!fallbackEnabled || !autoDetect) {
            return false;
        }

        // 如果已经在降级模式，检查是否应该恢复
        if (inFallbackMode) {
            return shouldContinueFallback();
        }

        // 检查 Claude Code 是否可用
        return !isClaudeCodeAvailable();
    }

    /**
     * 检查 Claude Code 是否可用
     */
    private boolean isClaudeCodeAvailable() {
        try {
            // 1. 检查 CLI 是否安装
            ProcessBuilder pb = new ProcessBuilder(claudeCodePath, "--version");
            Process process = pb.start();
            int exitCode = process.waitFor(10, TimeUnit.SECONDS);

            if (exitCode != 0) {
                log.warn("⚠️ Claude Code CLI 未安装或无法执行");
                return false;
            }

            // 2. 检查会话锁是否正常（尝试创建测试会话）
            // (简化版，实际应该检查更多条件)

            return true;

        } catch (Exception e) {
            log.warn("⚠️ Claude Code 可用性检查失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 检查是否应该继续降级模式
     */
    private boolean shouldContinueFallback() {
        long elapsed = System.currentTimeMillis() - fallbackStartTime;

        // 降级时间未到，继续降级
        if (elapsed < fallbackDuration.toMillis()) {
            return true;
        }

        // 尝试恢复（检查 Claude Code 是否恢复）
        if (isClaudeCodeAvailable()) {
            log.info("✅ Claude Code 已恢复，退出降级模式");
            inFallbackMode = false;
            return false;
        }

        // 未恢复，延长降级时间
        log.info("⏳ Claude Code 仍未恢复，继续降级模式");
        fallbackStartTime = System.currentTimeMillis();
        return true;
    }

    /**
     * 手动触发降级
     */
    public void enableFallback() {
        log.warn("🔴 手动启用降级模式");
        inFallbackMode = true;
        fallbackStartTime = System.currentTimeMillis();
    }

    /**
     * 手动恢复
     */
    public void disableFallback() {
        log.info("🟢 手动退出降级模式");
        inFallbackMode = false;
    }

    /**
     * 获取降级状态信息
     */
    public FallbackStatus getStatus() {
        FallbackStatus status = new FallbackStatus();
        status.setInFallbackMode(inFallbackMode);
        status.setClaudeCodeAvailable(isClaudeCodeAvailable());
        status.setFallbackDuration(fallbackDuration.toMinutes());

        if (inFallbackMode) {
            long elapsed = System.currentTimeMillis() - fallbackStartTime;
            status.setElapsedMinutes(elapsed / 60000);
            status.setRemainingMinutes(
                Math.max(0, fallbackDuration.toMillis() - elapsed) / 60000
            );
        }

        return status;
    }
}
```

---

### 2. 降级编排器

**文件**: `agent/src/main/java/ai/smancode/sman/agent/fallback/FallbackOrchestrator.java`

```java
@Component
public class FallbackOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(FallbackOrchestrator.class);

    @Autowired
    private FallbackDetector fallbackDetector;

    @Autowired
    private VectorSearchService vectorSearchService;

    @Autowired
    private SpoonAstService spoonAstService;

    @Autowired
    private CallChainService callChainService;

    /**
     * 处理用户请求（降级模式）
     */
    public String processRequest(String userMessage, String projectKey, String sessionId) {
        log.info("🔴 降级模式处理请求: {}", userMessage);

        // 1. 分析用户意图（基于关键词匹配）
        Intent intent = analyzeIntent(userMessage);

        // 2. 根据意图调用对应工具
        String result = executeTool(intent, userMessage, projectKey);

        // 3. 组装响应（带降级提示）
        return buildResponse(result, intent);
    }

    /**
     * 分析用户意图
     */
    private Intent analyzeIntent(String message) {
        String lowerMessage = message.toLowerCase();

        // 搜索相关代码
        if (lowerMessage.contains("搜索") || lowerMessage.contains("查找") ||
            lowerMessage.contains("相关") || lowerMessage.contains("有没有")) {
            return Intent.SEARCH;
        }

        // 分析类结构
        if (lowerMessage.contains("类") && lowerMessage.contains("结构") ||
            lowerMessage.contains("类") && lowerMessage.contains("方法")) {
            return Intent.READ_CLASS;
        }

        // 调用链分析
        if (lowerMessage.contains("调用") || lowerMessage.contains("谁调用") ||
            lowerMessage.contains("调用链")) {
            return Intent.CALL_CHAIN;
        }

        // 查找引用
        if (lowerMessage.contains("引用") || lowerMessage.contains("在哪用")) {
            return Intent.FIND_USAGES;
        }

        // 默认：搜索
        return Intent.SEARCH;
    }

    /**
     * 执行工具
     */
    private String executeTool(Intent intent, String message, String projectKey) {
        try {
            switch (intent) {
                case SEARCH:
                    // 提取搜索关键词
                    String query = extractQuery(message);
                    return vectorSearchService.search(projectKey, query, 10);

                case READ_CLASS:
                    // 提取类名
                    String className = extractClassName(message);
                    if (className != null) {
                        return spoonAstService.readClass(projectKey, className, "structure");
                    }
                    break;

                case CALL_CHAIN:
                    // 提取方法签名
                    String method = extractMethod(message);
                    if (method != null) {
                        return callChainService.analyze(projectKey, method, "both", 2);
                    }
                    break;

                case FIND_USAGES:
                    // 提取目标
                    String target = extractTarget(message);
                    if (target != null) {
                        return callChainService.findUsages(projectKey, target, 30);
                    }
                    break;
            }

            // 无法识别，执行默认搜索
            return vectorSearchService.search(projectKey, message, 10);

        } catch (Exception e) {
            log.error("❌ 降级模式工具执行失败", e);
            return "❌ 工具执行失败: " + e.getMessage();
        }
    }

    /**
     * 提取搜索关键词
     */
    private String extractQuery(String message) {
        // 简单实现：移除常用词
        return message
            .replaceAll("搜索|查找|相关|代码|类|方法", "")
            .trim();
    }

    /**
     * 提取类名
     */
    private String extractClassName(String message) {
        // 使用正则表达式提取类名
        Pattern pattern = Pattern.compile("([A-Z][a-zA-Z0-9]*)\\.([A-Z][a-zA-Z0-9]*)");
        Matcher matcher = pattern.matcher(message);

        if (matcher.find()) {
            return matcher.group(1) + "." + matcher.group(2);
        }

        return null;
    }

    /**
     * 提取方法签名
     */
    private String extractMethod(String message) {
        // 简单实现
        return null; // TODO: 实现方法名提取
    }

    /**
     * 提取目标
     */
    private String extractTarget(String message) {
        // 简单实现
        return null; // TODO: 实现目标提取
    }

    /**
     * 组装响应
     */
    private String buildResponse(String result, Intent intent) {
        StringBuilder sb = new StringBuilder();

        // 降级提示
        sb.append("## ⚠️ 降级模式提示\n\n");
        sb.append("当前系统运行在**降级模式**，Claude Code CLI 不可用。\n");
        sb.append("以下结果由**规则引擎**生成，功能可能受限。\n\n");

        // 意图说明
        sb.append("**分析类型**: ").append(intent.getDescription()).append("\n\n");

        // 结果
        sb.append("---\n\n");
        sb.append(result);

        // 建议
        sb.append("\n\n---\n\n");
        sb.append("### 💡 建议\n\n");
        sb.append("1. 检查 Claude Code CLI 是否正确安装\n");
        sb.append("2. 查看后端日志了解降级原因\n");
        sb.append("3. 联系管理员恢复 Claude Code 服务\n");

        return sb.toString();
    }

    /**
     * 用户意图枚举
     */
    public enum Intent {
        SEARCH("语义搜索"),
        READ_CLASS("类结构分析"),
        CALL_CHAIN("调用链分析"),
        FIND_USAGES("查找引用");

        private final String description;

        Intent(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }
}
```

---

### 3. 降级模式控制器

**文件**: `agent/src/main/java/ai/smancode/sman/agent/fallback/FallbackController.java`

```java
@RestController
@RequestMapping("/api/fallback")
public class FallbackController {

    @Autowired
    private FallbackDetector fallbackDetector;

    @Autowired
    private FallbackOrchestrator fallbackOrchestrator;

    /**
     * 获取降级状态
     */
    @GetMapping("/status")
    public Map<String, Object> getStatus() {
        return fallbackDetector.getStatus().toMap();
    }

    /**
     * 手动启用降级
     */
    @PostMapping("/enable")
    public Map<String, Object> enableFallback() {
        fallbackDetector.enableFallback();

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "已启用降级模式");
        response.put("status", fallbackDetector.getStatus().toMap());

        return response;
    }

    /**
     * 手动退出降级
     */
    @PostMapping("/disable")
    public Map<String, Object> disableFallback() {
        fallbackDetector.disableFallback();

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "已退出降级模式");
        response.put("status", fallbackDetector.getStatus().toMap());

        return response;
    }

    /**
     * 测试降级模式
     */
    @PostMapping("/test")
    public Map<String, Object> testFallback(@RequestBody Map<String, String> request) {
        String message = request.get("message");
        String projectKey = request.getOrDefault("projectKey", "test");
        String sessionId = request.getOrDefault("sessionId", "test-session");

        String result = fallbackOrchestrator.processRequest(message, projectKey, sessionId);

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("result", result);
        response.put("status", fallbackDetector.getStatus().toMap());

        return response;
    }
}
```

---

## 🔧 projectKey → projectPath 配置

### 配置文件

**文件**: `agent/src/main/resources/application.yml`

```yaml
agent:
  # 降级配置
  fallback:
    enabled: true
    auto-detect: true
    duration-minutes: 5

  # 项目映射配置
  projects:
    # 示例 1: 银行核心系统
    bank-core:
      project-path: /Users/user/projects/bank-core
      description: "银行核心系统"
      language: "java"
      version: "1.0.0"

    # 示例 2: 支付系统
    payment-system:
      project-path: /Users/user/projects/payment-system
      description: "支付系统"
      language: "java"
      version: "2.1.0"

    # 示例 3: 用户中心
    user-center:
      project-path: /Users/user/projects/user-center
      description: "用户中心"
      language: "java"
      version: "1.5.0"
```

---

### 配置服务

**文件**: `agent/src/main/java/ai/smancode/sman/agent/config/ProjectConfigService.java`

```java
@Service
@ConfigurationProperties(prefix = "agent")
public class ProjectConfigService {

    private Map<String, ProjectConfig> projects;

    public String getProjectPath(String projectKey) {
        ProjectConfig config = projects.get(projectKey);

        if (config == null) {
            throw new IllegalArgumentException(
                "未找到 projectKey 映射: " + projectKey + "\n" +
                "请检查 application.yml 中的 agent.projects 配置"
            );
        }

        return config.getProjectPath();
    }

    public ProjectConfig getProjectConfig(String projectKey) {
        return projects.get(projectKey);
    }

    public boolean hasProject(String projectKey) {
        return projects.containsKey(projectKey);
    }

    public List<String> getAllProjectKeys() {
        return new ArrayList<>(projects.keySet());
    }

    // Getters and Setters
    public Map<String, ProjectConfig> getProjects() {
        return projects;
    }

    public void setProjects(Map<String, ProjectConfig> projects) {
        this.projects = projects;
    }

    /**
     * 项目配置
     */
    public static class ProjectConfig {
        private String projectPath;
        private String description;
        private String language;
        private String version;

        // Getters and Setters
        public String getProjectPath() { return projectPath; }
        public void setProjectPath(String projectPath) { this.projectPath = projectPath; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }

        public String getLanguage() { return language; }
        public void setLanguage(String language) { this.language = language; }

        public String getVersion() { return version; }
        public void setVersion(String version) { this.version = version; }
    }
}
```

---

### 动态配置管理

**文件**: `agent/src/main/java/ai/smancode/sman/agent/config/ProjectConfigController.java`

```java
@RestController
@RequestMapping("/api/config/projects")
public class ProjectConfigController {

    @Autowired
    private ProjectConfigService projectConfigService;

    /**
     * 获取所有项目配置
     */
    @GetMapping
    public Map<String, Object> getAllProjects() {
        Map<String, Object> response = new HashMap<>();
        response.put("projects", projectConfigService.getProjects());
        response.put("count", projectConfigService.getAllProjectKeys().size());
        return response;
    }

    /**
     * 获取单个项目配置
     */
    @GetMapping("/{projectKey}")
    public Map<String, Object> getProject(@PathVariable String projectKey) {
        ProjectConfigService.ProjectConfig config =
            projectConfigService.getProjectConfig(projectKey);

        if (config == null) {
            throw new IllegalArgumentException("未找到项目: " + projectKey);
        }

        Map<String, Object> response = new HashMap<>();
        response.put("projectKey", projectKey);
        response.put("config", config);
        return response;
    }

    /**
     * 添加/更新项目配置
     */
    @PostMapping("/{projectKey}")
    public Map<String, Object> upsertProject(
        @PathVariable String projectKey,
        @RequestBody ProjectConfigService.ProjectConfig config
    ) {
        // TODO: 实现配置持久化（写入文件或数据库）

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "项目配置已更新");
        response.put("projectKey", projectKey);
        response.put("config", config);

        return response;
    }

    /**
     * 删除项目配置
     */
    @DeleteMapping("/{projectKey}")
    public Map<String, Object> deleteProject(@PathVariable String projectKey) {
        // TODO: 实现配置删除

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "项目配置已删除");
        response.put("projectKey", projectKey);

        return response;
    }
}
```

---

## 🔄 降级模式下的完整流程

### 场景 1: 降级模式处理请求

```
1. 前端发送 AGENT_CHAT
   ↓
2. 后端 QuickAnalysisController 接收请求
   ↓
3. 调用 FallbackDetector.shouldEnableFallback()
   ↓
4. 检测到 Claude Code CLI 不可用
   ↓
5. 标记为降级模式 (inFallbackMode = true)
   ↓
6. 调用 FallbackOrchestrator.processRequest()
   ↓
7. 分析用户意图（Intent.SEARCH）
   ↓
8. 调用 VectorSearchService.search()
   ↓
9. 组装响应（带降级提示）
   ↓
10. 返回 AGENT_RESPONSE 给前端
```

---

### 场景 2: 降级模式自动恢复

```
1. 5 分钟后收到新请求
   ↓
2. 调用 FallbackDetector.shouldEnableFallback()
   ↓
3. 检查 inFallbackMode == true
   ↓
4. 检查 fallbackDuration 是否已过
   ↓
5. 调用 isClaudeCodeAvailable()
   ↓
6. Claude Code CLI 已恢复
   ↓
7. 设置 inFallbackMode = false
   ↓
8. 使用正常模式（Claude Code）处理请求
   ↓
9. 返回正常响应（无降级提示）
```

---

## 🧪 测试与验证

### 手动触发降级

```bash
# 1. 启用降级模式
curl -X POST http://localhost:8080/api/fallback/enable

# 2. 查看降级状态
curl http://localhost:8080/api/fallback/status

# 3. 测试降级模式
curl -X POST http://localhost:8080/api/fallback/test \
  -H "Content-Type: application/json" \
  -d '{
    "message": "搜索 BankService 类",
    "projectKey": "bank-core",
    "sessionId": "test-session"
  }'

# 4. 退出降级模式
curl -X POST http://localhost:8080/api/fallback/disable
```

---

### 自动降级测试

**测试步骤**:

1. **正常模式测试**:
   ```bash
   # 确保 Claude Code CLI 正常
   claude-code --version

   # 发送请求
   curl -X POST http://localhost:8080/api/analysis/chat \
     -H "Content-Type: application/json" \
     -d '{
       "sessionId": "test-auto-fallback",
       "message": "分析 BankService 类",
       "projectKey": "bank-core"
     }'
   ```

2. **模拟 Claude Code 故障**:
   ```bash
   # 临时重命名 claude-code 命令
   sudo mv /usr/local/bin/claude-code /usr/local/bin/claude-code.bak
   ```

3. **触发自动降级**:
   ```bash
   # 再次发送请求（应该自动降级）
   curl -X POST http://localhost:8080/api/analysis/chat \
     -H "Content-Type: application/json" \
     -d '{
       "sessionId": "test-auto-fallback",
       "message": "分析 BankService 类",
       "projectKey": "bank-core"
     }'
   ```

4. **验证降级响应**:
   - 响应中应包含 "⚠️ 降级模式提示"
   - 响应由规则引擎生成（非 AI）
   - 功能受限（仅支持基本搜索和读取）

5. **恢复 Claude Code**:
   ```bash
   # 恢复 claude-code 命令
   sudo mv /usr/local/bin/claude-code.bak /usr/local/bin/claude-code
   ```

6. **等待自动恢复** (5分钟后):
   ```bash
   # 查看降级状态
   curl http://localhost:8080/api/fallback/status

   # 发送新请求（应自动恢复正常）
   curl -X POST http://localhost:8080/api/analysis/chat \
     -H "Content-Type: application/json" \
     -d '{
       "sessionId": "test-auto-recovery",
       "message": "分析 AccountService 类",
       "projectKey": "bank-core"
     }'
   ```

---

## 📊 降级模式监控

### 监控指标

| 指标 | 说明 | 告警阈值 |
|------|------|----------|
| 降级触发次数 | 累计降级次数 | >10 次/天 |
| 降级持续时间 | 单次降级时长 | >30 分钟 |
| 降级请求占比 | 降级模式请求占比 | >50% |
| Claude Code 可用性 | CLI 正常运行时间 | <99% |

---

### 日志监控

**关键日志**:

```log
# 降级触发
2026-01-05 10:23:45 WARN  FallbackDetector - ⚠️ Claude Code CLI 未安装或无法执行
2026-01-05 10:23:45 WARN  FallbackDetector - 🔴 启用降级模式

# 降级处理
2026-01-05 10:23:46 INFO  FallbackOrchestrator - 🔴 降级模式处理请求: 搜索 BankService 类
2026-01-05 10:23:46 INFO  FallbackOrchestrator - 分析用户意图: SEARCH
2026-01-05 10:23:47 INFO  VectorSearchService - 向量搜索完成，找到 10 个结果

# 降级恢复
2026-01-05 10:28:45 INFO  FallbackDetector - ✅ Claude Code 已恢复，退出降级模式
2026-01-05 10:28:46 INFO  QuickAnalysisController - ✅ 使用正常模式处理请求
```

---

## 🚨 限制与建议

### 降级模式限制

| 功能 | 正常模式 | 降级模式 |
|------|----------|----------|
| 智能推理 | ✅ Claude AI | ❌ 无 |
| 复杂分析 | ✅ 支持 | ⚠️ 有限 |
| 代码重构 | ✅ 支持 | ❌ 不支持 |
| 多轮对话 | ✅ 支持 | ⚠️ 有限 |
| 工具调用 | 12 个前端工具 | 5 个后端工具 |

---

### 使用建议

1. **生产环境**: 建议启用降级模式（`fallback.enabled=true`）
2. **开发环境**: 可关闭降级模式（`fallback.enabled=false`）以便调试
3. **监控告警**: 配置降级监控，及时发现问题
4. **定期测试**: 定期测试降级和恢复机制
5. **文档更新**: 及时更新项目配置（projectKey → projectPath 映射）

---

## 📚 相关文档

- [WebSocket API v2](./05-websocket-api-v2.md) - 降级模式下的消息格式
- [前端工具清单](./06-frontend-tools.md) - 降级模式可用的后端工具
- [Claude Code 集成](./03-claude-code-integration.md) - 正常模式下的工作原理
- [多轮对话实现](./multi_turn.md) - --resume 参数详解

---

**文档版本历史**:

| 版本 | 日期 | 变更说明 |
|------|------|----------|
| 1.0 | 2026-01-05 | 初始版本，定义降级策略和实现方案 |
