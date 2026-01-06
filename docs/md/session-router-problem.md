# SessionRouter 实现总结与待解决问题

**文档版本**: v1.0
**创建日期**: 2026-01-05
**状态**: ⚠️ 实现完成但存在问题

---

## 1. 已完成工作

### 1.1 ✅ SessionRouter 实现

**文件**: `SessionRouter.java`

**功能**：
- 维护 `sessionId → workerId` 的映射关系（Session Affinity）
- H2数据库持久化（内存 + 数据库双存储）
- 启动时从数据库加载会话映射
- 每次绑定/清除时同步写入数据库
- 24小时TTL自动清理过期会话

**关键特性**：
```java
// 数据库表结构
CREATE TABLE session_mappings (
    session_id VARCHAR(255) PRIMARY KEY,
    worker_id VARCHAR(255) NOT NULL,
    project_key VARCHAR(255),
    created_at BIGINT NOT NULL,
    last_used_at BIGINT NOT NULL
)

// 内存缓存：快速访问
Map<String, String> sessionCache

// Session Affinity：确保同一sessionId使用同一worker
public ClaudeCodeWorker acquireWorker(String sessionId)
```

### 1.2 ✅ 修改 Controller

**文件**: `QuickAnalysisController.java`

**修改**：
```java
// 旧代码
ClaudeCodeWorker worker = processPool.acquireWorker();

// 新代码
ClaudeCodeWorker worker = sessionRouter.acquireWorker(sessionId);
```

### 1.3 ✅ 修改 ProcessPool

**文件**: `ClaudeCodeProcessPool.java`

**新增方法**：
```java
public ClaudeCodeWorker getWorkerById(String workerId) {
    return workers.get(workerId);
}
```

---

## 2. ⚠️ 当前问题

### 2.1 问题描述

**现象**：多轮对话仍然失败，错误信息：`"Session ID is already in use."`

**测试结果**：

| 轮次 | Worker ID | 结果 |
|------|-----------|------|
| 第1轮 | worker-d73f1162 | ✅ 成功，建立记忆 |
| 第2轮 | worker-fe460332 | ❌ Session ID already in use |

### 2.2 日志分析

```
# 第1轮
✅ 新绑定: sessionId=9a8b7c6d-5e4f-3a2b-1c9d-8e7f6a5b4c3d → workerId=worker-d73f1162
Worker回复: "我记住了，你最喜欢的颜色是蓝色"
⚠️ Worker worker-d73f1162 进程结束，退出码: 0
🔄 重启 worker: worker-d73f1162

# 第2轮
⚠️ 已绑定的worker worker-d73f1162 失效，清除绑定
🗑️ 清除会话绑定: sessionId=9a8b7c6d-5e4f-3a2b-1c9d-8e7f6a5b4c3d, workerId=worker-d73f1162
✅ 新绑定: sessionId=9a8b7c6d-5e4f-3a2b-1c9d-8e7f6a5b4c3d → workerId=worker-fe460332
❌ Error: Session ID 9a8b7c6d-5e4f-3a2b-1c9d-8e7f6a5b4c3d is already in use.
```

### 2.3 根本原因

**问题链条**：

1. **Worker进程设计问题**：
   - `ClaudeCodeWorker.sendAndReceive()` 方法中关闭了writer
   - 导致Claude Code CLI收到EOF信号后退出
   - Worker进程在每次请求后都会退出（退出码: 0）

2. **Claude Code CLI锁定机制**：
   - 即使worker进程退出，sessionId仍然被"锁定"
   - 新的worker进程无法使用同一个sessionId
   - 错误信息：`"Session ID is already in use."`

3. **SessionRouter无法解决**：
   - SessionRouter只能确保同一sessionId路由到同一worker
   - 但如果worker进程不断重启，sessionId会不断被锁定
   - 最终导致所有worker都无法使用该sessionId

---

## 3. 解决方案

### 3.1 修改Worker进程模型（推荐）⭐

**核心思路**：Worker进程不应该在每次请求后退出，而应该保持运行并处理多个请求。

**修改文件**: `ClaudeCodeWorker.java`

**当前实现问题**：
```java
// 当前代码在 sendAndReceive() 方法中
writer.close();  // ← 关闭writer，导致Claude Code CLI退出
```

**新设计**：
```java
// 方案A：不关闭writer，保持进程运行
public String sendAndReceive(String message, String sessionId, long timeout) {
    // 发送消息
    writer.write(sessionId);
    writer.newLine();
    writer.write(message);
    writer.newLine();
    writer.flush();

    // 读取响应（不关闭writer）
    String response = readResponse(timeout);

    return response;
}
```

**但需要注意**：
- Claude Code CLI 可能是为单次请求设计的
- 需要验证CLI是否支持多轮请求模式
- 可能需要使用长连接或WebSocket

### 3.2 使用进程池内的Worker隔离（备选）

**核心思路**：为每个session创建独立的、不会重启的worker。

**实现**：
```java
public ClaudeCodeWorker acquireWorker(String sessionId) {
    // 检查是否已有专属worker
    ClaudeCodeWorker worker = dedicatedWorkers.get(sessionId);

    if (worker == null || !worker.isAlive()) {
        // 创建新的专属worker（不自动重启）
        worker = createDedicatedWorker(sessionId);
        dedicatedWorkers.put(sessionId, worker);
    }

    return worker;
}
```

**问题**：
- 需要管理大量worker进程
- 资源消耗大

### 3.3 使用文件会话而非CLI会话（备选）

**核心思路**：不使用Claude Code CLI的 `--session-id`，自己管理会话文件。

**实现**：
1. 移除 `--session-id` 参数
2. 自己维护 `~/.claude/projects/.../<sessionId>.jsonl`
3. 每次请求时手动拼接历史消息

**问题**：
- 无法利用CLI原生的会话管理
- Token消耗高（每次发送完整历史）

---

## 4. 下一步行动

### 方案1：修改Worker进程模型 ⭐

1. **研究Claude Code CLI行为**：
   ```bash
   # 测试CLI是否支持多轮请求
   (echo "session-1"; echo "消息1"; sleep 2;
    echo "消息2"; sleep 2) | claude --print --session-id "test-uuid"
   ```

2. **如果支持，修改 `ClaudeCodeWorker.sendAndReceive()`**：
   - 不关闭writer
   - 保持进程运行
   - 实现真正的多路复用

3. **如果不支持，考虑备选方案**

### 方案2：创建专属Worker池

```java
@Service
public class DedicatedWorkerPool {
    // 为每个session创建专属worker
    private Map<String, ClaudeCodeWorker> dedicatedWorkers;

    public ClaudeCodeWorker acquireWorker(String sessionId) {
        // 创建或返回专属worker
    }
}
```

---

## 5. 总结

| 组件 | 状态 | 问题 |
|------|------|------|
| **SessionRouter** | ✅ 已实现 | 无 |
| **H2持久化** | ✅ 已实现 | 无 |
| **会话绑定** | ✅ 已实现 | 无 |
| **Worker进程模型** | ⚠️ 需要修改 | 每次请求后退出，导致sessionId被锁定 |

**最终结论**：
- ✅ SessionRouter实现成功，可以正确维护sessionId→workerId映射
- ❌ 但由于Worker进程模型问题，仍无法实现多轮对话
- ⏳ **下一步**：修改Worker进程模型，确保进程不会在每次请求后退出

---

**是否立即实施方案1（修改Worker进程模型）？**
