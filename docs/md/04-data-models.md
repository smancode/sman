# 数据模型规范

**文档版本**: v1.0
**创建日期**: 2025-01-05
**用途**: 定义前后端和 Claude Code 之间共享的数据结构

---

## 1. 数据模型总览

### 1.1 核心数据模型

| 模型 | 用途 | 文件位置 |
|------|------|----------|
| `WebSocketMessage` | WebSocket 通信消息 | `shared/api-schema/WebSocketMessage.kt` |
| `ToolRequest` | 工具调用请求 | `shared/api-schema/ToolRequest.kt` |
| `ToolResult` | 工具执行结果 | `shared/api-schema/ToolResult.kt` |
| `AnalysisRequest` | 分析请求 | `shared/api-schema/AnalysisRequest.kt` |
| `AnalysisResult` | 分析结果 | `shared/api-schema/AnalysisResult.kt` |
| `SessionInfo` | 会话信息 | `shared/api-schema/SessionInfo.kt` |

---

## 2. WebSocket 消息模型

### 2.1 WebSocketMessage

**用途**: 所有 WebSocket 通信的通用消息格式

**字段**：

```kotlin
data class WebSocketMessage(
    val type: MessageType,        // 消息类型
    val data: JsonObject,         // 消息数据（JSON 对象）
    val timestamp: Long = System.currentTimeMillis(),  // 时间戳
    val messageId: String? = null // 消息 ID（可选，用于关联）
)
```

**MessageType（枚举）**：

```kotlin
enum class MessageType {
    // Client → Server
    ANALYZE,           // 分析请求
    TOOL_RESULT,       // 工具执行结果
    PING,              // 心跳

    // Server → Client
    CONNECTED,         // 连接成功
    COMPLETE,          // 分析完成
    THINKING,          // 分析进度
    TOOL_CALL,         // 工具调用请求
    ERROR,             // 错误信息
    PONG               // 心跳响应
}
```

---

## 3. 分析请求和结果模型

### 3.1 AnalysisRequest

**用途**: IDE Plugin 发送的分析请求

```kotlin
data class AnalysisRequest(
    val requestId: String? = null,     // 多轮对话会话 ID（首次为空）
    val message: String,                // 用户输入
    val projectKey: String,             // 项目标识
    val projectPath: String,            // 项目根路径（本地绝对路径）
    val mode: String = "claude-code",   // 模式（claude-code 或 legacy）
    val sessionId: String               // WebSocket 会话 ID
)
```

**示例**：

```json
{
  "requestId": "req-123",
  "message": "分析文件过滤的代码",
  "projectKey": "autoloop",
  "projectPath": "/Users/xxx/project",
  "mode": "claude-code",
  "sessionId": "ws-20250105-150000-xxx"
}
```

---

### 3.2 AnalysisResult

**用途**: 分析完成后的结果

```kotlin
data class AnalysisResult(
    val requestId: String,       // 多轮对话会话 ID
    val result: String,          // 分析结果（Markdown 格式）
    val process: String? = null, // 分析过程摘要（可选）
    val timestamp: Long = System.currentTimeMillis()
)
```

**示例**：

```json
{
  "requestId": "req-123",
  "result": "# 文件过滤分析\n\n找到以下相关代码：\n\n## 1. FileFilter.java\n\n...",
  "process": "向量搜索 → 读取类 → 调用链分析 → 生成结论",
  "timestamp": 1704438400000
}
```

---

## 4. 工具调用模型

### 4.1 ToolRequest

**用途**: Claude Code 调用后端工具的请求

```kotlin
data class ToolRequest(
    val toolName: String,           // 工具名称
    val params: Map<String, Any?>,  // 工具参数
    val callId: String? = null       // 调用 ID（可选）
)
```

**工具名称常量**：

```kotlin
object ToolNames {
    const val VECTOR_SEARCH = "vector_search"
    const val READ_CLASS = "read_class"
    const val READ_METHOD = "read_method"
    const val CALL_CHAIN = "call_chain"
    const val FIND_USAGES = "find_usages"
    const val APPLY_CHANGE = "apply_change"
}
```

---

### 4.2 ToolResult

**用途**: 工具执行结果

```kotlin
data class ToolResult(
    val success: Boolean,           // 是否成功
    val result: String? = null,     // 执行结果（成功时）
    val error: String? = null,      // 错误信息（失败时）
    val executionTime: Long = 0,    // 执行耗时（毫秒）
    val callId: String? = null      // 调用 ID（对应 ToolRequest）
)
```

**工具结果格式化**：

```kotlin
object ToolResultFormatter {

    /**
     * 格式化向量搜索结果
     */
    fun formatVectorSearchResult(results: List<VectorSearchHit>): String {
        val sb = StringBuilder()
        sb.append("## 向量搜索结果\n\n")
        sb.append("找到 ${results.size} 个相关结果\n\n")

        results.forEachIndexed { index, hit ->
            sb.append("### ${index + 1}. ${hit.className}\n")
            sb.append("- **相关性**: ${String.format("%.2f", hit.score)}\n")
            sb.append("- **路径**: `${hit.relativePath}`\n")
            sb.append("- **摘要**: ${hit.summary}\n\n")
        }

        return sb.toString()
    }

    /**
     * 格式化类结构结果
     */
    fun formatClassStructure(classInfo: ClassStructure): String {
        val sb = StringBuilder()
        sb.append("## ${classInfo.className}\n\n")
        sb.append("- **路径**: `${classInfo.relativePath}`\n")
        sb.append("- **类型**: ${classInfo.type}\n\n")

        sb.append("### 类结构\n\n")
        sb.append("```java\n")
        classInfo.methods.forEach { method ->
            sb.append("${method.modifiers.joinToString(" ")} ${method.returnType} ${method.name}(${method.params.joinToString(", ")})\n")
        }
        sb.append("```\n")

        return sb.toString()
    }
}
```

---

## 5. 工具参数模型

### 5.1 VectorSearchParams

```kotlin
data class VectorSearchParams(
    val query: String,              // 搜索查询
    val topK: Int = 10,             // 返回结果数量
    val filter: Map<String, Any>? = null  // 过滤条件（可选）
)
```

---

### 5.2 ReadClassParams

```kotlin
data class ReadClassParams(
    val className: String,          // 类名
    val mode: String = "structure"  // 读取模式（structure/full/imports_fields）
)
```

---

### 5.3 CallChainParams

```kotlin
data class CallChainParams(
    val method: String,             // 方法签名（ClassName.methodName）
    val direction: String = "both", // 分析方向（both/callees/callers）
    val depth: Int = 1,            // 分析深度
    val includeSource: Boolean = false  // 是否包含源码
)
```

---

### 5.4 ApplyChangeParams

```kotlin
data class ApplyChangeParams(
    val relativePath: String,      // 文件相对路径
    val searchContent: String? = null,   // 要搜索的内容（空表示新增）
    val replaceContent: String,          // 要替换的内容
    val description: String              // 修改说明
)
```

---

## 6. 会话管理模型

### 6.1 SessionInfo

```kotlin
data class SessionInfo(
    val sessionId: String,         // WebSocket 会话 ID
    val requestId: String?,        // 多轮对话会话 ID
    val projectKey: String,        // 项目标识
    val projectPath: String,       // 项目根路径
    val createdAt: Long,           // 创建时间
    val lastActiveAt: Long,        // 最后活跃时间
    val messageCount: Int          // 消息数量
)
```

---

### 6.2 ChatMessage（历史消息）

```kotlin
data class ChatMessage(
    val role: String,              // 角色（user/assistant）
    val content: String,           // 消息内容
    val timestamp: Long = System.currentTimeMillis()
)
```

**历史消息存储**：

```json
{
  "requestId": "req-123",
  "sessionId": "ws-xxx",
  "messages": [
    {
      "role": "user",
      "content": "分析文件过滤的代码",
      "timestamp": 1704438400000
    },
    {
      "role": "assistant",
      "content": "# 分析结果\n\n...",
      "timestamp": 1704438460000
    }
  ],
  "createdAt": 1704438400000,
  "updatedAt": 1704438460000
}
```

---

## 7. 文件路径处理

### 7.1 PathUtils

**用途**: 统一的文件路径处理（支持 Windows、Linux、macOS）

**位置**: `shared/api-schema/utils/PathUtils.kt`

```kotlin
object PathUtils {

    /**
     * 规范化路径（统一使用 /，兼容 Windows）
     */
    fun normalizePath(path: String): String {
        return path.replace("\\", "/")
    }

    /**
     * 判断是否为绝对路径
     */
    fun isAbsolutePath(path: String): Boolean {
        val normalized = normalizePath(path)
        return normalized.startsWith("/") ||
               normalized.matches(Regex("[A-Za-z]:/.*"))
    }

    /**
     * 相对路径转绝对路径
     */
    fun toAbsolutePath(relativePath: String, basePath: String): String {
        val normalizedBase = normalizePath(basePath).removeSuffix("/")
        val normalizedRelative = normalizePath(relativePath)

        return if (isAbsolutePath(normalizedRelative)) {
            normalizedRelative
        } else {
            "$normalizedBase/$normalizedRelative"
        }
    }

    /**
     * 绝对路径转相对路径
     */
    fun toRelativePath(absolutePath: String, basePath: String): String {
        val normalizedAbsolute = normalizePath(absolutePath)
        val normalizedBase = normalizePath(basePath).removeSuffix("/")

        return if (normalizedAbsolute.startsWith(normalizedBase)) {
            normalizedAbsolute.removePrefix(normalizedBase).removePrefix("/")
        } else {
            absolutePath  // 不在 basePath 下，返回原路径
        }
    }

    /**
     * 获取文件扩展名
     */
    fun getExtension(path: String): String {
        val normalized = normalizePath(path)
        val lastDot = normalized.lastIndexOf('.')
        return if (lastDot > 0) normalized.substring(lastDot + 1) else ""
    }
}
```

**使用示例**：

```kotlin
// 规范化路径
val path1 = PathUtils.normalizePath("C:\\Users\\xxx\\project")  // "C:/Users/xxx/project"
val path2 = PathUtils.normalizePath("/Users/xxx/project")       // "/Users/xxx/project"

// 相对路径转绝对路径
val absPath = PathUtils.toAbsolutePath("core/src/File.java", "/Users/xxx/project")
// "/Users/xxx/project/core/src/File.java"

// 绝对路径转相对路径
val relPath = PathUtils.toRelativePath("/Users/xxx/project/core/src/File.java", "/Users/xxx/project")
// "core/src/File.java"
```

---

## 8. 错误模型

### 8.1 ErrorResponse

```kotlin
data class ErrorResponse(
    val code: String,              // 错误码
    val message: String,            // 错误信息
    val details: Map<String, Any?>? = null,  // 错误详情（可选）
    val timestamp: Long = System.currentTimeMillis()
)
```

**错误码常量**：

```kotlin
object ErrorCodes {
    const val PROJECT_NOT_FOUND = "PROJECT_NOT_FOUND"
    const val INVALID_REQUEST = "INVALID_REQUEST"
    const val ANALYSIS_FAILED = "ANALYSIS_FAILED"
    const val TOOL_EXECUTION_FAILED = "TOOL_EXECUTION_FAILED"
    const val CLASS_NOT_FOUND = "CLASS_NOT_FOUND"
    const val POOL_EXHAUSTED = "POOL_EXHAUSTED"
    const val MESSAGE_TOO_LARGE = "MESSAGE_TOO_LARGE"
    const val REQUEST_OVERRIDDEN = "REQUEST_OVERRIDDEN"
}
```

---

## 9. JSON 序列化配置

### 9.1 Jackson 配置（后端 Java）

```java
@Configuration
public class JacksonConfig {

    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();

        // 美化输出
        mapper.enable(SerializationFeature.INDENT_OUTPUT);

        // 忽略 null 值
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);

        // 日期格式化
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.setDateFormat(new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"));

        // 忽略未知属性
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        return mapper;
    }
}
```

---

### 9.2 Kotlinx Serialization 配置（前端 Kotlin）

```kotlin
// shared/api-schema/serialization.kt
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val json = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
    encodeDefaults = false
}

// 使用示例
@Serializable
data class WebSocketMessage(
    val type: String,
    val data: String,
    val timestamp: Long = System.currentTimeMillis()
)

val message = WebSocketMessage("ANALYZE", "...")
val jsonString = json.encodeToString(message)
```

---

## 10. 数据验证

### 10.1 请求验证注解（后端 Java）

```java
import jakarta.validation.constraints.*;

public class AnalysisRequest {

    @NotBlank(message = "message 不能为空")
    @Size(max = 10000, message = "message 长度不能超过 10000")
    private String message;

    @NotBlank(message = "projectKey 不能为空")
    private String projectKey;

    @NotBlank(message = "projectPath 不能为空")
    private String projectPath;

    @Pattern(regexp = "^(claude-code|legacy)$", message = "mode 必须是 claude-code 或 legacy")
    private String mode = "claude-code";
}
```

---

### 10.2 数据验证函数（前端 Kotlin）

```kotlin
object DataValidator {

    /**
     * 验证项目路径
     */
    fun validateProjectPath(path: String): Boolean {
        return PathUtils.isAbsolutePath(path) &&
               path.isNotBlank() &&
               path.length <= 500
    }

    /**
     * 验证工具参数
     */
    fun validateToolParams(toolName: String, params: Map<String, Any?>): Boolean {
        return when (toolName) {
            ToolNames.VECTOR_SEARCH -> {
                params["query"] is String &&
                (params["top_k"] as? Int)?.let { it > 0 } ?: true
            }
            ToolNames.READ_CLASS -> {
                params["className"] is String
            }
            else -> false
        }
    }
}
```

---

## 11. 数据转换工具

### 11.1 WebSocketMessage 转换

```kotlin
object MessageConverter {

    /**
     * 将 AnalysisRequest 转换为 WebSocketMessage
     */
    fun toWebSocketMessage(request: AnalysisRequest): WebSocketMessage {
        return WebSocketMessage(
            type = MessageType.ANALYZE.name,
            data = mapOf(
                "requestId" to request.requestId,
                "message" to request.message,
                "projectKey" to request.projectKey,
                "projectPath" to request.projectPath,
                "mode" to request.mode,
                "sessionId" to request.sessionId
            ).let { JsonObject(it) }  // 转换为 JsonObject
        )
    }

    /**
     * 从 WebSocketMessage 提取 AnalysisRequest
     */
    fun fromWebSocketMessage(message: WebSocketMessage): AnalysisRequest? {
        return if (message.type == MessageType.ANALYZE.name) {
            AnalysisRequest(
                requestId = message.data.getString("requestId"),
                message = message.data.getString("message"),
                projectKey = message.data.getString("projectKey"),
                projectPath = message.data.getString("projectPath"),
                mode = message.data.getString("mode") ?: "claude-code",
                sessionId = message.data.getString("sessionId")
            )
        } else {
            null
        }
    }
}
```

---

## 12. 版本历史

| 版本 | 日期 | 变更内容 |
|------|------|----------|
| v1.0 | 2025-01-05 | 初始版本 |

---

**文档结束**
