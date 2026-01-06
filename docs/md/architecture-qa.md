# SiliconMan Agent 架构详解与配置说明

## 目录
1. [Claude Code进程池架构原理](#1-claude-code进程池架构原理)
2. [Agent与Claude Code通信机制](#2-agent与claude-code通信机制)
3. [SessionId与会话管理](#3-sessionid与会话管理)
4. [项目配置与初始化](#4-项目配置与初始化)

---

## 1. Claude Code进程池架构原理

### 1.1 为什么Claude Code可以作为进程池资源？

**核心设计思想**：
Claude Code CLI本质是一个**长期运行的交互式进程**，类似于数据库连接。它可以：
- ✅ 持续接收stdin输入
- ✅ 持续输出到stdout
- ✅ 保持上下文（工作目录、环境变量、已加载的配置）

**类比理解**：
```
Claude Code Worker ≈ 数据库连接池
- 每个Worker = 一个Claude Code CLI进程
- 进程池 = 预先启动N个进程，避免重复启动开销
- acquireWorker() = 从池中获取空闲进程
- releaseWorker() = 归还进程到池中
```

**性能优势**：
1. **启动开销巨大**：Claude Code CLI启动需要2-3秒
2. **上下文保持**：进程保持加载的模型、配置、工作目录
3. **并发支持**：3个worker = 3个请求可以并行处理

### 1.2 进程池生命周期

```java
// 1. 启动阶段（预热）
ClaudeCodeProcessPool.initialize()
  ├─> 启动3个Worker进程（本地开发配置）
  ├─> 每个Worker执行：claude-code-mock脚本
  ├─> 等待进程输出"Claude Code Ready"
  └─> 标记worker.setReady(true)

// 2. 运行阶段（处理请求）
QuickAnalysisController.chat()
  ├─> processPool.acquireWorker()  // 获取空闲worker
  ├─> worker.sendAndReceive(message, 120)  // stdin发送，stdout读取
  ├─> 解析Claude Code的响应
  └─> processPool.releaseWorker(worker)  // 归还worker

// 3. 监控阶段（自动恢复）
ProcessMonitor.waitFor()
  ├─> 检测进程退出（exitCode）
  ├─> 自动重启worker
  └─> 重新标记为ready
```

**关键配置**（application.yml）：
```yaml
claude-code:
  pool:
    size: 3                    # 本地开发3个worker足够
    warmup: true               # 启动时预热所有worker
    max-lifetime: 1800000      # 30分钟后重启worker（防止内存泄漏）
```

---

## 2. Agent与Claude Code通信机制

### 2.1 完整通信流程

```
┌─────────┐      ┌──────────────┐      ┌─────────────────┐
│ 前端    │ ───> │ Agent Controller│ ───> │ Claude Code Worker│
│ (Vue)   │      │ (Spring Boot)  │      │ (Process 1/2/3)  │
└─────────┘      └──────────────┘      └─────────────────┘
                       │                        │
                       │  1. 获取worker         │
                       │  acquireWorker()      │
                       │  <─────────────────────┤
                       │                        │
                       │  2. 发送消息           │
                       │  worker.sendAndReceive│
                       │  ─────────────────────>│
                       │  (stdin: 用户需求)     │
                       │                        │
                       │  3. AI推理             │
                       │  <─────────────────────┤
                       │  (stdout: 分析结果)    │
                       │                        │
                       │  4. 工具回调           │
                       │  <─────────────────────┼─────────> 向量搜索
                       │    (curl请求)          │                   API
                       │  ─────────────────────>│
                       │                        │
                       │  5. 最终响应           │
                       │  <─────────────────────┤
                       │  (答案)                │
                       │                        │
                       │  6. 释放worker         │
                       │  releaseWorker()      │
                       │  ─────────────────────>│
```

### 2.2 stdin/stdout通信协议

**发送给Claude Code（stdin）**：
```
## 用户需求

读取文件异常了增加重试1次的功能

## 项目信息
- projectKey: autoloop
- sessionId: test-session-123
- agentApiUrl: http://localhost:8080/api/claude-code/tools/execute

## 工具使用说明
1. **vector_search**: 向量搜索相关代码
   调用: curl -X POST http://localhost:8080/api/claude-code/tools/execute ...
2. **apply_change**: 应用代码修改
   调用: curl -X POST http://localhost:8080/api/claude-code/tools/execute ...

## 重要提示
1. 必须使用上述HTTP API调用工具
2. 禁止使用Read、Edit、Bash、Write等内置工具
```

**Claude Code响应（stdout）**：
```
## 【分析问题】

我理解您的需求：文件读取异常处理

### 🔍 步骤 1: 搜索相关代码

调用 vector_search 工具...
（此处会调用 Agent 的 HTTP API）

### 📊 分析结果

**建议**：在 FileReader.readLines() 的 catch 块中增加重试逻辑
**重试次数**：1 次

✅ 分析完成
=====END_OF_RESPONSE=====
```

### 2.3 关键技术细节

**1. IO流管理（避免竞争）**：
```java
// ❌ 错误：预先创建IO流导致竞争
private BufferedReader stdinReader;  // monitor线程和sendAndReceive竞争
private BufferedWriter stdoutWriter;

// ✅ 正确：按需创建，用完即关闭
public String sendAndReceive(String message, long timeout) {
    final BufferedReader[] readerHolder = new BufferedReader[1];
    try {
        readerHolder[0] = new BufferedReader(
            new InputStreamReader(process.getInputStream()));
        // ... 通信逻辑
    } finally {
        if (readerHolder[0] != null) readerHolder[0].close();
    }
}
```

**2. 进程监控（不读取stdout）**：
```java
// 监控线程只关心进程存活状态
private void startProcessMonitor(ClaudeCodeWorker worker) {
    Thread monitor = new Thread(() -> {
        Process process = worker.getProcess();
        int exitCode = process.waitFor();  // 等待进程退出
        log.warn("Worker {} 退出，退出码: {}", workerId, exitCode);
        worker.setAlive(false);
        restartWorker(worker);  // 自动重启
    });
    monitor.start();
}
```

**3. Ready状态检测**：
```java
// 简化：进程存活 = ready
if (process.isAlive()) {
    worker.setReady(true);  // 创建后2秒进程还活着，标记为ready
}
```

---

## 3. SessionId与会话管理

### 3.1 SessionId来源

**前端提供**（首次请求）：
```javascript
// 前端代码
const sessionId = `session-${Date.now()}`;  // 前端生成唯一ID
axios.post('/api/analysis/chat', {
    sessionId: sessionId,
    message: '读取文件异常了增加重试1次的功能',
    projectKey: 'autoloop'
});
```

**后端接收**（QuickAnalysisController.java:36）：
```java
String sessionId = (String) request.get("sessionId");
```

### 3.2 会话存储机制

**当前实现**（内存存储）：
```java
// QuickAnalysisController.java:32
private final Map<String, List<Message>> sessions = new HashMap<>();

// 每次对话后保存
List<Message> history = sessions.computeIfAbsent(sessionId, k -> new ArrayList<>());
history.add(new Message("user", message));
history.add(new Message("assistant", claudeResponse));
```

**问题**：
- ❌ 内存存储，重启丢失
- ❌ 未实现持久化到`data/sessions`目录
- ❌ 无法跨服务器共享会话

**改进方案**（待实现）：
```java
// 建议实现
@Component
public class SessionManager {
    @Value("${data.sessions.path:data/sessions}")
    private String sessionsPath;

    public void saveSession(String sessionId, List<Message> messages) {
        // 保存到文件：data/sessions/{sessionId}.json
        Path sessionFile = Paths.get(sessionsPath, sessionId + ".json");
        Files.write(sessionFile, toJSON(messages));
    }

    public List<Message> loadSession(String sessionId) {
        // 从文件加载
        Path sessionFile = Paths.get(sessionsPath, sessionId + ".json");
        if (Files.exists(sessionFile)) {
            return fromJSON(Files.readAllBytes(sessionFile));
        }
        return new ArrayList<>();
    }
}
```

### 3.3 多轮对话实现

**会话历史传递**：
```java
// 构建消息时附带历史
private String buildClaudeMessage(String userMessage, String projectKey, String sessionId) {
    List<Message> history = sessions.get(sessionId);

    StringBuilder sb = new StringBuilder();
    sb.append("## 用户需求\n\n").append(userMessage).append("\n\n");

    // 如果有历史，附加上下文
    if (history != null && !history.isEmpty()) {
        sb.append("## 对话历史\n\n");
        for (Message msg : history) {
            sb.append(msg.getRole()).append(": ").append(msg.getContent()).append("\n\n");
        }
    }

    return sb.toString();
}
```

---

## 4. 项目配置与初始化

### 4.1 AutoLoop项目配置

**参考bank-core-analysis-agent配置**：
```yaml
# bank-core-analysis-agent/application.yml:263
bank:
  analysis:
    static:
      projectpath:
        projects:
          autoloop: ${AUTOLOOP_PROJECT_PATH:/Users/liuchao/projects/autoloop}
```

**当前sman配置**：
```yaml
# agent/src/main/resources/application.yml:120-122
project:
  default-path: /Users/liuchao/projects/autoloop
  projects:
    autoloop:
      path: /Users/liuchao/projects/autoloop
      enabled: true
```

### 4.2 项目初始化实现

**需要添加ProjectInitializer组件**：
```java
@Component
public class ProjectInitializer {
    @Value("${project.projects}")
    private Map<String, ProjectConfig> projects;

    @Autowired
    private VectorIndexService vectorIndexService;

    @PostConstruct
    public void initializeProjects() {
        projects.forEach((projectKey, config) -> {
            if (config.isEnabled()) {
                log.info("🔧 初始化项目: {}", projectKey);

                // 1. 构建向量索引
                if (vectorIndexService.isEnabled()) {
                    vectorIndexService.buildIndex(config.getPath());
                }

                // 2. 初始化方法调用索引
                // methodCallIndexService.buildIndex(config.getPath());

                log.info("✅ 项目 {} 初始化完成", projectKey);
            }
        });
    }
}
```

### 4.3 测试配置

**使用真实autoloop项目测试**：
```bash
# 1. 启动bge-m3和bge-reranker（如果需要向量搜索）
# 假设已在localhost:8000和localhost:8001启动

# 2. 启动agent
cd /Users/liuchao/projects/sman/agent
./gradlew bootRun

# 3. 发送测试请求
curl -X POST http://localhost:8080/api/analysis/chat \
  -H "Content-Type: application/json" \
  -d '{
    "sessionId": "test-autoloop-001",
    "message": "分析autoloop项目中的JobScheduler实现",
    "projectKey": "autoloop"
  }'
```

**预期日志输出**：
```
📨 收到分析请求
  sessionId: test-autoloop-001
  message: 分析autoloop项目中的JobScheduler实现
  projectKey: autoloop

🔄 从进程池获取 Claude Code Worker...
✅ 获取 Worker 成功: worker-abc123

📤 Worker worker-abc123 发送消息给 Claude Code:
========================================
## 用户需求
分析autoloop项目中的JobScheduler实现
## 项目信息
- projectKey: autoloop
- agentApiUrl: http://localhost:8080/api/claude-code/tools/execute
## 工具使用说明
...
========================================

🔵 Claude Code [worker-abc123]: Claude Code Ready - Worker: worker-abc123
🔵 Claude Code [worker-abc123]: 调用 vector_search 工具...
🔵 Claude Code [worker-abc123]: 找到 JobScheduler 类
🔵 Claude Code [worker-abc123]: =====END_OF_RESPONSE=====
```

---

## 5. 配置文件完整对比

### 5.1 进程池配置
```yaml
claude-code:
  pool:
    size: 3                    # 本地开发3个worker
    warmup: true               # 启动时预热
    max-lifetime: 1800000      # 30分钟重启
```

### 5.2 项目配置
```yaml
project:
  default-path: /Users/liuchao/projects/autoloop
  projects:
    autoloop:
      path: /Users/liuchao/projects/autoloop
      enabled: true
```

### 5.3 会话配置
```yaml
data:
  sessions:
    path: data/sessions        # 待实现持久化
    max-size: 1000
    ttl: 86400000              # 24小时过期
```

### 5.4 向量搜索配置
```yaml
vector:
  bge-m3:
    endpoint: http://localhost:8000
  bge-reranker:
    endpoint: http://localhost:8001
  index:
    path: data/vector-index
    auto-build: true
```

---

## 6. 总结

### 关键设计决策
1. **进程池**：避免Claude Code CLI重复启动开销（2-3秒）
2. **stdin/stdout通信**：简单可靠的进程间通信方式
3. **Ready检测简化**：进程存活=ready，避免复杂的stdout解析
4. **IO流按需创建**：避免流竞争问题

### 后续改进点
1. ✅ 会话持久化到`data/sessions`
2. ✅ 项目初始化时自动构建向量索引
3. ✅ 会话历史传递给Claude Code
4. ✅ 支持多项目配置

### 测试验证
```bash
# 1. 启动agent（3个worker）
./gradlew bootRun

# 2. 验证进程池状态
curl http://localhost:8080/api/claude-code/pool/status

# 3. 发送真实项目分析请求
curl -X POST http://localhost:8080/api/analysis/chat \
  -H "Content-Type: application/json" \
  -d '{
    "sessionId": "test-autoloop-001",
    "message": "分析autoloop项目中的JobScheduler实现",
    "projectKey": "autoloop"
  }'
```
