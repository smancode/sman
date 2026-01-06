# IDE Plugin 开发指南 - 多轮对话实现

**文档版本**: v1.0
**创建日期**: 2026-01-05
**用途**: IDE Plugin 前端开发参考

---

## 1. 多轮对话核心要求

### 1.1 ⭐ SessionId 必须是有效 UUID

**错误示例**（会导致请求失败）：
```json
{
  "sessionId": "multi-turn-test-001",      // ❌ 错误
  "sessionId": "session-123",               // ❌ 错误
  "sessionId": "mytest-autoloop-001"        // ❌ 错误
}
```

**正确示例**：
```json
{
  "sessionId": "8A7F9E2C-3B4D-4F6E-8A9B-1C2D3E4F5A6B"  // ✅ 正确
}
```

**UUID 格式要求**：
- 长度：36 个字符（包含 4 个连字符）
- 格式：`xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx`
- 示例：`f47ac10b-58cc-4372-a567-0e02b2c3d479`

---

### 1.2 前端 UUID 生成代码

#### Kotlin/IntelliJ 插件

```kotlin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object SessionManager {
    // 存储活跃的 sessionId
    private val activeSessions = ConcurrentHashMap<String, SessionInfo>()

    /**
     * 生成新的 UUID（用于新对话）
     */
    fun generateSessionId(): String {
        return UUID.randomUUID().toString()
    }

    /**
     * 创建新会话
     */
    fun createSession(projectKey: String): String {
        val sessionId = generateSessionId()
        activeSessions[sessionId] = SessionInfo(
            sessionId = sessionId,
            projectKey = projectKey,
            createdAt = System.currentTimeMillis()
        )
        return sessionId
    }

    /**
     * 获取现有会话
     */
    fun getSession(sessionId: String): SessionInfo? {
        return activeSessions[sessionId]
    }

    data class SessionInfo(
        val sessionId: String,
        val projectKey: String,
        val createdAt: Long
    )
}
```

**使用示例**：
```kotlin
// 新建对话
val sessionId = SessionManager.createSession("autoloop")

// 继续对话（使用相同的 sessionId）
val existingSession = SessionManager.getSession(sessionId)
if (existingSession != null) {
    // 继续使用
} else {
    // 创建新会话
    val newSessionId = SessionManager.createSession("autoloop")
}
```

---

#### JavaScript (Web 前端)

**方法 1：使用 uuid 库（推荐）**
```javascript
// 安装：npm install uuid
import { v4 as uuidv4 } from 'uuid';

// 生成 UUID
const sessionId = uuidv4();  // 例如：f47ac10b-58cc-4372-a567-0e02b2c3d479
```

**方法 2：原生实现**
```javascript
function generateUUID() {
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function(c) {
        const r = Math.random() * 16 | 0;
        const v = c === 'x' ? r : (r & 0x3 | 0x8);
        return v.toString(16);
    });
}

// 使用
const sessionId = generateUUID();
```

---

### 1.3 API 调用示例

#### Kotlin (IntelliJ 插件)

```kotlin
val sessionId = SessionManager.generateSessionId()

val request = mapOf(
    "message" to "分析文件过滤的代码",
    "projectKey" to "autoloop",
    "sessionId" to sessionId  // ⭐ 必须是 UUID
)

val response = httpClient.post()
    .url("http://localhost:8080/api/analysis/chat")
    .body(Json.encodeToString(request))
    .asString()
```

---

## 2. Worker 进程与会话记忆的关系

### 2.1 重要发现：会话记忆不依赖 Worker

**从日志分析**：
```
# 第1轮
worker-a99ee285 → sessionId=f47ac10b-58cc-4372-a567-0e02b2c3d479
Claude 说："已记住：您最喜欢的颜色是青色"

# 第2轮
worker-ce2ea37f → sessionId=f47ac10b-58cc-4372-a567-0e02b2c3d479（相同）
Claude 说："这个问题不在我的专业领域范围内"  # ❌ 没记住！
```

**问题分析**：
- ✅ sessionId 相同
- ❌ Worker 进程不同
- ❌ **没有记住之前的对话**

---

### 2.2 根本原因：会话文件路径问题

**会话文件路径**：
```bash
~/.claude/projects/-<项目路径>/<sessionId>.jsonl
```

**问题**：
- 每个 Worker 有**不同的工作目录**：
  ```
  /Users/liuchao/projects/sman/agent/data/claude-code-workspaces/worker-a99ee285/
  /Users/liuchao/projects/sman/agent/data/claude-code-workspaces/worker-ce2ea37f/
  ```

- Claude Code 在**当前工作目录**下查找会话文件：
  ```
  worker-a99ee285 的工作目录/.claude/projects/.../f47ac10b...jsonl  # 第1轮创建
  worker-ce2ea37f 的工作目录/.claude/projects/.../f47ac10b...jsonl  # 第2轮找不到！
  ```

**结论**：
- ⚠️ 不同 Worker 进程**无法共享会话文件
- ⚠️ 每个进程在自己的工作目录下查找会话文件

---

### 2.3 解决方案

#### 方案 A：固定工作目录（推荐）⭐

**修改 `ClaudeCodeProcessPool.java`**，让所有 Worker 使用**同一个工作目录**：

```java
private ClaudeCodeWorker createWorker() throws IOException {
    String workerId = "worker-" + UUID.randomUUID().toString().substring(0, 8);

    // ⭐ 所有 Worker 使用同一个工作目录
    String workDir = workDirBase;  // 例如：/Users/liuchao/projects/sman/agent/data/claude-code-workspaces

    // 创建工作目录（只创建一次）
    File dir = new File(workDir);
    if (!dir.exists()) {
        dir.mkdirs();
    }

    // ... 后续代码
}
```

**优势**：
- ✅ 所有 Worker 共享同一个工作目录
- ✅ 会话文件路径相同
- ✅ 支持多轮对话

---

#### 方案 B：使用全局会话目录

**修改 `/tmp/claude-code-stdio` 脚本**：

```bash
#!/bin/bash
# Claude Code CLI - stdio mode wrapper with session support

# ⭐ 使用全局会话目录
export HOME="$HOME/.claude-global-sessions"

# 使用原生的 claude-code-cli
if command -v claude &> /dev/null; then
    CLAUDE_BIN="$(command -v claude)"
else
    CLAUDE_BIN="$HOME/.vscode/extensions/anthropic.claude-code-2.0.75-darwin-arm64/resources/native-binary/claude"
fi

# 读取 sessionId
read SESSION_ID

# ⭐ 切换到固定的工作目录
cd "$HOME/.claude-global-sessions"

exec "$CLAUDE_BIN" \
  --print \
  --session-id "$SESSION_ID" \
  --output-format text \
  --input-format text \
  --disallowedTools "Read,Edit,Write,Bash,Prompt" \
  --dangerously-skip-permissions
```

---

#### 方案 C：后端管理会话（复杂度最高）

**实现思路**：
1. 后端维护 sessionId ↔ workerId 的映射
2. 将同一 sessionId 的请求路由到同一个 Worker
3. 类似"会话粘性"（Session Affinity）

**代码示例**：
```java
@Service
public class SessionRouter {
    private final Map<String, String> sessionToWorker = new ConcurrentHashMap<>();

    public ClaudeCodeWorker acquireWorker(String sessionId) {
        // 检查是否已有 Worker
        String workerId = sessionToWorker.get(sessionId);

        if (workerId != null) {
            // 返回同一个 Worker
            return processPool.getWorker(workerId);
        } else {
            // 分配新 Worker 并记录
            ClaudeCodeWorker worker = processPool.acquireWorker();
            sessionToWorker.put(sessionId, worker.getWorkerId());
            return worker;
        }
    }
}
```

---

## 3. 推荐实施步骤

### 阶段 1：UUID 生成（立即实施）

1. ✅ **前端生成 UUID**
2. ✅ **在 ide-plugin 文档中记录**

### 阶段 2：修复多轮对话（高优先级）

**推荐顺序**：
1. ⭐ **方案 A**：固定工作目录（最简单）
2. **方案 B**：全局会话目录（次选）
3. **方案 C**：会话路由（复杂，暂缓）

---

## 4. 验证方法

### 4.1 检查会话文件

```bash
# 查找会话文件
find ~/.claude/projects -name "f47ac10b-58cc-4372-a567-0e02b2c3d479.jsonl"

# 预期结果（应该只有1个文件）
~/.claude/projects/-Users-liuchao-projects-sman-agent/f47ac10b-58cc-4372-a567-0e02b2c3d479.jsonl
```

### 4.2 测试多轮对话

```bash
# 第1轮：建立记忆
curl -X POST http://localhost:8080/api/analysis/chat \
  -H "Content-Type: application/json" \
  -d '{
    "message": "记住：我最喜欢的颜色是青色",
    "projectKey": "autoloop",
    "sessionId": "f47ac10b-58cc-4372-a567-0e02b2c3d479"
  }'

# 第2轮：验证记忆
curl -X POST http://localhost:8080/api/analysis/chat \
  -H "Content-Type: application/json" \
  -d '{
    "message": "我最喜欢的颜色是什么？",
    "projectKey": "autoloop",
    "sessionId": "f47ac10b-58cc-4372-a567-0e02b2c3d479"
  }'

# ✅ 期望结果：Claude 回复"青色"
```

---

## 5. 总结

| 问题 | 解决方案 | 优先级 |
|------|---------|--------|
| **sessionId 格式** | 前端生成 UUID | ⚠️ **立即修复** |
| **多轮对话失败** | 固定工作目录 | ⚠️ **高优先级** |
| **会话无法共享** | 全局会话目录 | ⚠️ 中优先级 |

**下一步行动**：
1. ✅ 已创建文档 `docs/md/ide-plugin-multi-turn.md`
2. ✅ 已实施方案 A（固定工作目录）
3. ⚠️ **发现新问题：Claude Code CLI 原生限制**

---

## 6. ⚠️ 重要发现：Claude Code CLI 原生限制

### 6.1 问题描述

**实施方案A后**，虽然所有worker共享同一工作目录，但仍遇到错误：

```
Error: Session ID 8a7f9e2c-3b4d-4f6e-8a9b-1c2d3e4f5a6b is already in use.
```

### 6.2 测试结果

| 轮次 | Worker ID | 结果 |
|------|-----------|------|
| 第1轮 | worker-6e6e60d7 | ✅ 成功，创建会话文件 |
| 第2轮 | worker-5e5eed0d | ❌ Session ID already in use |
| 第3轮 | worker-cfa4c6c9 | ❌ Session ID already in use |

### 6.3 根本原因

**Claude Code CLI 原生不支持多进程同时访问同一sessionId**

**证据**：
1. 会话文件存在且正常：`8a7f9e2c-3b4d-4f6e-8a9b-1c2d3e4f5a6b.jsonl` (3222字节)
2. 会话文件包含2条消息（user + assistant）
3. 等待5秒后重试，仍然报错
4. 不同worker进程尝试访问同一sessionId时，CLI拒绝服务

**结论**：
- ✅ 方案A成功实现：worker共享工作目录
- ❌ 新限制：Claude Code CLI内部有会话锁机制
- ⚠️ **多进程无法同时使用同一个sessionId**

---

## 7. 最终方案：必须实施方案C（会话路由）

### 7.1 为什么方案A和方案B都不够？

| 方案 | 问题 | 结论 |
|------|------|------|
| **方案A：固定工作目录** | Claude Code CLI内部锁，多进程无法共享同一sessionId | ❌ **无法解决** |
| **方案B：全局会话目录** | 同样的CLI内部锁问题 | ❌ **无法解决** |
| **方案C：会话路由** | 确保同一sessionId始终路由到同一个worker | ✅ **唯一可行方案** |

### 7.2 方案C核心设计

**原则**：Session Affinity（会话粘性）

```java
@Service
public class SessionRouter {
    private final Map<String, String> sessionToWorker = new ConcurrentHashMap<>();
    private final ClaudeCodeProcessPool processPool;

    /**
     * 获取worker（确保同一sessionId使用同一worker）
     */
    public ClaudeCodeWorker acquireWorker(String sessionId) throws InterruptedException {
        // 1. 检查是否已有绑定
        String workerId = sessionToWorker.get(sessionId);

        if (workerId != null) {
            // 2. 返回绑定的worker
            ClaudeCodeWorker worker = processPool.getWorker(workerId);
            if (worker != null && worker.isAlive() && worker.isReady()) {
                log.debug("♻️ 复用已绑定的worker: {} for sessionId: {}", workerId, sessionId);
                worker.setBusy(true);
                return worker;
            } else {
                // Worker已失效，清除绑定
                log.warn("⚠️ 已绑定的worker {} 失效，清除绑定", workerId);
                sessionToWorker.remove(sessionId);
            }
        }

        // 3. 分配新worker并绑定
        ClaudeCodeWorker worker = processPool.acquireWorker();
        sessionToWorker.put(sessionId, worker.getWorkerId());
        log.info("✅ 新绑定: sessionId={} → workerId={}", sessionId, worker.getWorkerId());
        return worker;
    }

    /**
     * 释放worker（保持绑定，不清除）
     */
    public void releaseWorker(String sessionId, ClaudeCodeWorker worker) {
        worker.setBusy(false);
        // ⚠️ 不清除绑定，下次复用
        log.debug("🔄 Worker释放但保持绑定: sessionId={}, workerId={}",
                  sessionId, worker.getWorkerId());
    }

    /**
     * 清除会话绑定（会话结束时调用）
     */
    public void clearSession(String sessionId) {
        String workerId = sessionToWorker.remove(sessionId);
        log.info("🗑️ 清除会话绑定: sessionId={}, workerId={}", sessionId, workerId);
    }
}
```

### 7.3 修改Controller

**QuickAnalysisController.java**：

```java
@RestController
@RequestMapping("/api/analysis")
public class QuickAnalysisController {

    @Autowired
    private SessionRouter sessionRouter;  // ⭐ 使用SessionRouter

    @PostMapping("/chat")
    public ResponseEntity<ChatResponse> chat(@RequestBody ChatRequest request) {
        String sessionId = request.getSessionId();

        // ⭐ 使用SessionRouter获取worker（确保同一sessionId使用同一worker）
        ClaudeCodeWorker worker = sessionRouter.acquireWorker(sessionId);

        try {
            // ... 发送消息给worker
            String response = worker.sendAndReceive(claudeMessage, sessionId, 120);

            // ⭐ 释放worker但保持绑定
            sessionRouter.releaseWorker(sessionId, worker);

            return ResponseEntity.ok(new ChatResponse(response, sessionId));

        } catch (Exception e) {
            sessionRouter.releaseWorker(sessionId, worker);
            throw e;
        }
    }
}
```

### 7.4 预期效果

| 轮次 | Worker ID | 结果 |
|------|-----------|------|
| 第1轮 | worker-6e6e60d7 | ✅ 绑定：session→worker-6e6e60d7 |
| 第2轮 | worker-6e6e60d7 | ✅ 复用同一worker，记住上下文 |
| 第3轮 | worker-6e6e60d7 | ✅ 继续复用，多轮对话成功 |

---

## 8. 下一步实施计划

### 阶段 1：回滚方案A（立即）

**回滚原因**：方案A虽然让worker共享工作目录,但无法解决CLI内部锁问题,反而可能引入新的并发问题。

**回滚步骤**：
```java
// 恢复 createWorker() 方法
private ClaudeCodeWorker createWorker() throws IOException {
    String workerId = "worker-" + UUID.randomUUID().toString().substring(0, 8);
    String workDir = workDirBase + "/" + workerId;  // 恢复独立目录

    // ... 后续代码
}
```

### 阶段 2：实施方案C（高优先级）

1. **创建 SessionRouter.java**
   - 实现 sessionId ↔ workerId 绑定
   - 实现 acquireWorker() 方法
   - 实现 releaseWorker() 方法
   - 实现 clearSession() 方法

2. **修改 QuickAnalysisController.java**
   - 注入 SessionRouter
   - 使用 sessionRouter.acquireWorker() 替代 processPool.acquireWorker()

3. **测试多轮对话**
   - 验证同一sessionId路由到同一worker
   - 验证多轮对话记忆功能

---

## 9. 总结

| 问题 | 解决方案 | 状态 |
|------|---------|------|
| **sessionId格式** | 前端生成UUID | ✅ 已完成 |
| **多轮对话记忆** | 方案C：会话路由 | ⏳ 待实施 |
| **CLI会话锁限制** | Session Affinity设计 | ⏳ 待实施 |

**关键发现**：
- ⚠️ Claude Code CLI **不支持多进程共享同一sessionId**
- ✅ 唯一可行方案：**Session Affinity（会话路由）**
- 📋 需要实施：方案C - 后端管理会话映射
