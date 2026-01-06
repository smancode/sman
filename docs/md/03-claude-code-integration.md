# Claude Code 集成与 HTTP Tool API 规范

**文档版本**: v1.0
**创建日期**: 2025-01-05
**协议**: HTTP (RESTful API)
**端口**: 8080
**Base Path**: /api/claude-code

---

## 1. 概述

### 1.1 架构

```
Claude Code CLI
    ↓ HTTP Request (调用工具)
Agent 后端 HTTP Tool API
    ↓ 执行工具
- vector_search (直接执行)
- call_chain (直接执行)
- read_class (转发给 IDE Plugin)
- apply_change (转发给 IDE Plugin)
```

---

### 1.2 接口列表

| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/claude-code/tools/execute` | POST | 执行工具（通用接口） |
| `/api/claude-code/pool/status` | GET | 查看进程池状态 |
| `/api/claude-code/health` | GET | 健康检查 |

---

## 2. 工具执行接口

### 2.1 通用工具执行

**URL**: `POST /api/claude-code/tools/execute`

**请求头**：

```
Content-Type: application/json
```

**请求体**：

```json
{
  "toolName": "vector_search",  // 工具名称
  "params": {                   // 工具参数（根据工具不同）
    "query": "文件过滤",
    "top_k": 10
  },
  "callId": "call-123"          // 可选，调用 ID
}
```

**响应**（成功）：

```json
{
  "success": true,
  "result": "## 向量搜索结果\n\n找到 10 个相关代码...",  // 工具执行结果
  "executionTime": 1234          // 执行耗时（毫秒）
}
```

**响应**（失败）：

```json
{
  "success": false,
  "error": "向量搜索失败：索引未初始化",
  "executionTime": 56
}
```

---

### 2.2 支持的工具列表

#### 2.2.1 vector_search（向量搜索）

**说明**：使用 BGE-M3 向量模型进行语义搜索

**参数**：

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `query` | string | 是 | 搜索查询（业务术语或功能描述） |
| `top_k` | number | 否 | 返回结果数量（默认 10） |
| `filter` | object | 否 | 过滤条件（可选） |

**示例**：

```json
{
  "toolName": "vector_search",
  "params": {
    "query": "文件过滤",
    "top_k": 10
  }
}
```

**返回**：

```json
{
  "success": true,
  "result": "## 向量搜索结果: 文件过滤\n\n找到 10 个相关结果\n\n### 1. FileFilter.java\n- **相关性**: 0.85\n- **路径**: `core/src/.../FileFilter.java`..."
}
```

---

#### 2.2.2 read_class（读取类）

**说明**：读取 Java 类的结构（通过 IDE Plugin 执行）

**参数**：

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `className` | string | 是 | 类名（简单名或全限定名） |
| `mode` | string | 否 | 读取模式（structure/full/imports_fields，默认 structure） |

**示例**：

```json
{
  "toolName": "read_class",
  "params": {
    "className": "FileFilter",
    "mode": "structure"
  }
}
```

**返回**：

```json
{
  "success": true,
  "result": "## FileFilter.java\n\n- **类名**: `FileFilter`\n- **路径**: `core/src/.../FileFilter.java`\n\n### 类结构\n\n```java\npublic class FileFilter {\n  private String pattern;\n  \n  public boolean accept(File file) {\n    ...\n  }\n}\n```"
}
```

---

#### 2.2.3 call_chain（调用链分析）

**说明**：分析方法的调用关系（支持 Spoon 调用链）

**参数**：

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `method` | string | 是 | 方法签名（ClassName.methodName） |
| `direction` | string | 否 | 分析方向（both/callees/callers，默认 both） |
| `depth` | number | 否 | 分析深度（默认 1） |

**示例**：

```json
{
  "toolName": "call_chain",
  "params": {
    "method": "FileFilter.accept",
    "direction": "both",
    "depth": 2
  }
}
```

**返回**：

```json
{
  "success": true,
  "result": "## 调用链分析: FileFilter.accept\n\n### 🔽 被调用者\n\n- `FileManager.listFiles()` → line 45\n- `FileScanner.scan()` → line 78\n\n### 🔼 调用者\n\n- `Files.walk()` → line 123\n"
}
```

---

#### 2.2.4 find_usages（查找引用）

**说明**：查找类或方法的引用位置

**参数**：

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `target` | string | 是 | 目标（ClassName.methodName 或 ClassName） |
| `maxResults` | number | 否 | 最大结果数（默认 30） |

**示例**：

```json
{
  "toolName": "find_usages",
  "params": {
    "target": "FileFilter.accept",
    "maxResults": 30
  }
}
```

---

#### 2.2.5 apply_change（应用代码修改）

**说明**：应用代码修改（通过 IDE Plugin 执行）

**参数**：

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `relativePath` | string | 是 | 文件相对路径（从项目根目录） |
| `searchContent` | string | 否 | 要搜索的内容（空表示新增文件） |
| `replaceContent` | string | 是 | 要替换的内容（新增时为文件内容） |
| `description` | string | 是 | 修改说明 |

**示例**（修改文件）：

```json
{
  "toolName": "apply_change",
  "params": {
    "relativePath": "core/src/.../FileFilter.java",
    "searchContent": "public boolean accept(File file) {\n  return file.getName().endsWith(\".java\");\n}",
    "replaceContent": "public boolean accept(File file) {\n  return file.getName().endsWith(\".java\") || file.isDirectory();\n}",
    "description": "支持目录过滤"
  }
}
```

**示例**（新增文件）：

```json
{
  "toolName": "apply_change",
  "params": {
    "relativePath": "core/src/.../NewFile.java",
    "searchContent": "",
    "replaceContent": "public class NewFile {\n  // 新文件内容\n}",
    "description": "创建新文件"
  }
}
```

---

## 3. 进程池管理

### 3.1 查看进程池状态

**URL**: `GET /api/claude-code/pool/status`

**响应**：

```json
{
  "poolSize": 15,           // 进程池总大小
  "activeWorkers": 5,       // 活跃进程数
  "idleWorkers": 10,        // 空闲进程数
  "totalRequests": 1234,    // 总请求数
  "avgResponseTime": 3500,  // 平均响应时间（毫秒）
  "workers": [              // 进程详情
    {
      "workerId": "worker-abc123",
      "busy": true,
      "requestId": "req-123",
      "lastUsed": 1704438400000,
      "uptime": 1800000     // 运行时长（毫秒）
    }
  ]
}
```

---

### 3.2 健康检查

**URL**: `GET /api/claude-code/health`

**响应**：

```json
{
  "status": "UP",           // UP, DOWN, DEGRADED
  "components": {
    "processPool": {
      "status": "UP",
      "details": {
        "healthyWorkers": 14,
        "unhealthyWorkers": 1
      }
    },
    "vectorIndex": {
      "status": "UP",
      "details": {
        "indexedClasses": 12345,
        "lastRefresh": "2025-01-05T10:00:00Z"
      }
    }
  }
}
```

---

## 4. 降级模式（Fallback Mode）

### 4.1 什么是降级模式

降级模式是指当 Claude Code CLI 不可用时，系统自动切换到本地模式，通过直接调用后端工具来提供基本的代码分析能力。

### 4.2 降级触发条件

| 条件 | 说明 | 降级类型 |
|------|------|----------|
| Claude Code CLI 未安装 | 执行 `claude-code --version` 失败 | 永久降级 |
| Claude Code 调用失败 | 进程启动失败、超时或崩溃 | 临时降级（5分钟） |
| 资源不足 | 内存不足 500MB 或磁盘不足 1GB | 临时降级 |

### 4.3 降级模式下的可用工具

| 工具 | 正常模式 | 降级模式 | 说明 |
|------|----------|----------|------|
| `vector_search` | ✅ Claude AI 分析 | ✅ 直接搜索 | 功能不变 |
| `read_class` | ✅ Claude AI 分析 | ✅ 直接读取 | 功能不变 |
| `call_chain` | ✅ Claude AI 分析 | ✅ 直接分析 | 功能不变 |
| `find_usages` | ✅ Claude AI 分析 | ✅ 直接查找 | 功能不变 |
| `apply_change` | ✅ 智能重构 | ⚠️ 简单替换 | 功能受限 |

**限制**:
- ❌ 无 AI 推理能力
- ❌ 无法理解复杂需求
- ⚠️ 代码修改仅支持简单替换

### 4.4 降级检测 API

**查看降级状态**:

```bash
GET /api/fallback/status
```

**响应**:

```json
{
  "inFallbackMode": true,
  "claudeCodeAvailable": false,
  "fallbackDuration": 5,
  "elapsedMinutes": 2,
  "remainingMinutes": 3
}
```

### 4.5 手动控制降级

**启用降级**:

```bash
POST /api/fallback/enable
```

**退出降级**:

```bash
POST /api/fallback/disable
```

### 4.6 配置文件

**位置**: `agent/src/main/resources/application.yml`

```yaml
agent:
  fallback:
    enabled: true              # 是否启用降级模式
    auto-detect: true          # 是否自动检测并降级
    duration-minutes: 5        # 临时降级持续时间

  # projectKey → projectPath 映射（降级模式需要）
  projects:
    bank-core:
      project-path: /Users/user/projects/bank-core
      description: "银行核心系统"
```

**重要性**: 降级模式依赖 `projectKey → projectPath` 配置来定位项目文件，因此必须正确配置。

### 4.7 降级模式工作流程

```
1. 前端发送 AGENT_CHAT 请求
   ↓
2. 后端检测 Claude Code 可用性
   ↓
3. Claude Code 不可用 → 启用降级模式
   ↓
4. 降级规则引擎分析用户意图
   ↓
5. 直接调用后端工具（vector_search, read_class 等）
   ↓
6. 组装响应（带降级提示）
   ↓
7. 返回 AGENT_RESPONSE
```

**响应示例**（降级模式）:

```markdown
## ⚠️ 降级模式提示

当前系统运行在**降级模式**，Claude Code CLI 不可用。
以下结果由**规则引擎**生成，功能可能受限。

---

**分析类型**: 语义搜索

## 向量搜索结果: 文件过滤

找到 10 个相关结果

### 1. FileFilter.java
- **相关性**: 0.85
- **路径**: `core/src/.../FileFilter.java`

---

### 💡 建议

1. 检查 Claude Code CLI 是否正确安装
2. 查看后端日志了解降级原因
3. 联系管理员恢复 Claude Code 服务
```

### 4.8 降级恢复机制

- **自动恢复**: 5 分钟后自动尝试恢复（检查 Claude Code 是否可用）
- **手动恢复**: 调用 `POST /api/fallback/disable` 立即恢复
- **恢复条件**: Claude Code CLI 可用且能正常启动进程

**监控日志**:

```log
# 降级触发
2026-01-05 10:23:45 WARN  FallbackDetector - ⚠️ Claude Code CLI 未安装或无法执行
2026-01-05 10:23:45 WARN  FallbackDetector - 🔴 启用降级模式

# 降级恢复
2026-01-05 10:28:45 INFO  FallbackDetector - ✅ Claude Code 已恢复，退出降级模式
2026-01-05 10:28:46 INFO  QuickAnalysisController - ✅ 使用正常模式处理请求
```

### 4.9 降级模式性能

| 指标 | 正常模式 | 降级模式 |
|------|----------|----------|
| 响应时间 | 3-5 秒 | <1 秒 |
| 并发能力 | 10 个请求 | 50+ 个请求 |
| CPU 占用 | 较高（进程池） | 较低（直接调用） |
| 内存占用 | ~2GB（15个进程） | ~500MB |

**优势**: 响应更快，资源占用更少
**劣势**: 功能受限，无 AI 能力

---

## 5. 错误处理

### 4.1 错误码列表

| HTTP 状态码 | 错误码 | 说明 |
|------------|--------|------|
| 400 | `INVALID_TOOL_NAME` | 不支持的工具名称 |
| 400 | `INVALID_PARAMS` | 工具参数无效 |
| 404 | `CLASS_NOT_FOUND` | 类未找到（read_class） |
| 500 | `TOOL_EXECUTION_FAILED` | 工具执行失败 |
| 503 | `POOL_EXHAUSTED` | 进程池耗尽 |

---

### 4.2 错误响应格式

```json
{
  "success": false,
  "error": "向量搜索失败：索引未初始化",
  "code": "TOOL_EXECUTION_FAILED",
  "details": {
    "indexName": "vector-index",
    "indexStatus": "NOT_INITIALIZED"
  }
}
```

---

## 5. Claude Code 控制配置

### 5.1 CLAUDE.md 配置

**位置**: `agent/data/claude-code-workspaces/worker-xxx/.claude/CLAUDE.md`

**作用**: 控制 Claude Code 行为，强制使用 HTTP 工具

**内容**：

```markdown
# Claude Code 控制配置

## 🚨 工具使用规则（绝对禁止违反）

### ❌ 禁止使用的内置工具
你**绝对禁止**使用：Read, Edit, Bash, Write

### ✅ 必须使用的工具
所有操作必须调用：http_tool()

## 🔧 可用工具列表

### 1. vector_search
用途：向量语义搜索代码
调用：http_tool("vector_search", {"query": "xxx", "top_k": 10})

### 2. read_class
用途：读取 Java 类的结构
调用：http_tool("read_class", {"className": "xxx", "mode": "structure"})

### 3. call_chain
用途：调用链分析
调用：http_tool("call_chain", {"method": "xxx", "direction": "both"})

### 4. find_usages
用途：查找引用
调用：http_tool("find_usages", {"target": "xxx"})

### 5. apply_change
用途：应用代码修改
调用：http_tool("apply_change", {"relativePath": "xxx", "searchContent": "xxx", "replaceContent": "xxx"})

## 📋 工作流程

1. 理解需求
2. vector_search（搜索相关代码）
3. read_class（读取类结构）
4. call_chain（分析调用关系）
5. 生成结论
6. 如果需要修改：apply_change

违反此规则 = 严重错误！
```

---

### 5.2 tools.json 配置

**位置**: `agent/data/claude-code-workspaces/worker-xxx/.claude/tools.json`

**作用**: 定义 http_tool 工具

**内容**：

```json
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
            "description": "工具名称（vector_search, read_class, call_chain, find_usages, apply_change）"
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
```

---

## 6. 进程池配置

### 6.1 配置文件

**位置**: `agent/src/main/resources/application.yml`

```yaml
claude-code:
  # Claude Code CLI 路径
  path: C:\Users\{user}\AppData\Roaming\npm\claude-code.cmd

  # 工作目录基础路径
  work-dir-base: ${user.dir}/data/claude-code-workspaces

  # 进程池配置
  pool:
    size: 15                      # 进程池大小（16核机器）
    max-lifetime: 1800000         # 进程最大生命周期（30分钟，毫秒）
    health-check-interval: 60000  # 健康检查间隔（1分钟，毫秒）
    warmup: true                  # 启动时预热（创建所有进程）

  # HTTP 工具 API 配置
  http-api:
    enabled: true
    port: 8080
    endpoint: /api/claude-code/tools/execute
```

---

### 6.2 环境变量

| 变量名 | 说明 | 默认值 |
|--------|------|--------|
| `CLAUDE_CODE_PATH` | Claude Code CLI 路径 | `claude-code` |
| `CLAUDE_CODE_WORK_DIR` | 工作目录基础路径 | `./data/claude-code-workspaces` |
| `CLAUDE_CODE_POOL_SIZE` | 进程池大小 | `15` |

---

## 7. 监控和日志

### 7.1 日志级别

| 级别 | 用途 |
|------|------|
| `DEBUG` | 工具执行详情、参数传递 |
| `INFO` | 进程启动/停止、健康检查 |
| `WARN` | 进程异常、工具执行失败 |
| `ERROR` | 进程崩溃、API 调用失败 |

---

### 7.2 关键日志

**进程启动**：

```
2025-01-05 10:00:00 INFO  ClaudeCodeProcessPool - ✅ Pre-started Claude Code worker-abc123
```

**工具调用**：

```
2025-01-05 10:00:05 DEBUG ClaudeCodeOrchestrator - 🧧 HTTP tool called: vector_search, params={"query":"文件过滤"}
```

**进程健康检查**：

```
2025-01-05 10:01:00 INFO  ClaudeCodeProcessPool - 🔍 Running health check...
2025-01-05 10:01:00 INFO  ClaudeCodeProcessPool - ✅ Health check completed: pool=15, active=5
```

---

## 8. 性能指标

### 8.1 目标性能

| 指标 | 目标值 |
|------|--------|
| **并发能力** | 15-20 个并发请求 |
| **响应时间** | <5 秒（进程已启动） |
| **向量搜索** | <2 秒 |
| **调用链分析** | <3 秒 |
| **进程启动时间** | <3 秒（预热阶段） |

---

### 8.2 监控指标

| 指标 | 说明 |
|------|------|
| **进程池使用率** | 活跃进程数 / 总进程数 |
| **平均等待时间** | 获取 worker 的等待时间 |
| **进程重启频率** | 异常退出的进程数 / 小时 |
| **工具成功率** | 成功的工具调用 / 总调用数 |

---

## 9. 故障排查

### 9.1 常见问题

#### 问题 1：工具调用失败

**症状**：

```json
{
  "success": false,
  "error": "Tool execution failed: 500 Internal Server Error"
}
```

**排查**：

1. 检查工具名称是否正确
2. 检查工具参数是否符合规范
3. 查看后端日志：`logs/sman-agent.log`

---

#### 问题 2：进程池耗尽

**症状**：

```json
{
  "success": false,
  "error": "Process pool exhausted",
  "code": "POOL_EXHAUSTED"
}
```

**排查**：

1. 查看进程池状态：`GET /api/claude-code/pool/status`
2. 检查是否有进程卡死（长时间 busy）
3. 考虑增大进程池大小

---

#### 问题 3：Claude Code 不调用工具

**症状**：Claude Code 直接返回结果，没有调用任何工具

**排查**：

1. 检查 `CLAUDE.md` 是否生效
2. 检查 `tools.json` 是否正确
3. 查看 Claude Code 日志（stderr）

---

## 10. 版本历史

| 版本 | 日期 | 变更内容 |
|------|------|----------|
| v1.0 | 2025-01-05 | 初始版本 |
| v1.1 | 2026-01-05 | 添加降级模式章节（第4节），支持 Claude Code 不可用时的自动降级 |

---

**文档结束**
