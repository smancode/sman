# SiliconMan Agent 工具测试指南

## 🚀 快速开始

### 1. 启动服务

```bash
cd /Users/liuchao/projects/sman
./gradlew :agent:bootRun
```

服务将在 `http://localhost:8080` 启动

### 2. 健康检查

```bash
curl http://localhost:8080/api/claude-code/health
# 预期输出: OK
```

### 3. 运行完整测试

```bash
cd /Users/liuchao/projects/sman/agent
./test-tools.sh
```

---

## 📋 可用工具列表

### 1. semantic_search ⭐ **向量语义搜索**

**功能**: 使用 BGE-M3 + BGE-Reranker 进行代码语义搜索

```bash
curl -s -X POST http://localhost:8080/api/claude-code/tools/execute \
  -H "Content-Type: application/json" \
  -d '{
    "tool": "semantic_search",
    "params": {
      "recallQuery": "文件过滤",
      "recallTopK": 50,
      "rerankQuery": "按扩展名过滤文件",
      "rerankTopN": 10,
      "enableReranker": false
    },
    "projectKey": "autoloop",
    "sessionId": "test-session"
  }' | jq '.'
```

**参数说明**:
- `recallQuery`: 召回查询（业务需求）
- `recallTopK`: 召回数量（建议 50-100）
- `rerankQuery`: 重排查询（精确需求）
- `rerankTopN`: 最终返回数量（建议 10-20）
- `enableReranker`: 是否启用重排序

---

### 2. vector_search (semantic_search 的别名)

**功能**: 简化版向量搜索（单查询）

```bash
curl -s -X POST http://localhost:8080/api/claude-code/tools/execute \
  -H "Content-Type: application/json" \
  -d '{
    "tool": "vector_search",
    "params": {
      "query": "FileFilter",
      "top_k": 10
    },
    "projectKey": "autoloop",
    "sessionId": "test-session"
  }' | jq '.'
```

**注意**: 实际上会转换为 `semantic_search`，需要提供 `recallQuery` 和 `rerankQuery`

---

### 3. read_class ⭐ **读取类结构**

**功能**: 获取 Java 类的结构信息

```bash
curl -s -X POST http://localhost:8080/api/claude-code/tools/execute \
  -H "Content-Type: application/json" \
  -d '{
    "tool": "read_class",
    "params": {
      "className": "FileFilter",
      "mode": "structure"
    },
    "projectKey": "autoloop",
    "sessionId": "test-session"
  }' | jq '.result'
```

**模式**:
- `structure`: 类结构（字段、方法）
- `full`: 完整源码
- `imports_fields`: 导入和字段

---

### 4. call_chain **调用链分析**

**功能**: 分析方法的调用关系

```bash
curl -s -X POST http://localhost:8080/api/claude-code/tools/execute \
  -H "Content-Type: application/json" \
  -d '{
    "tool": "call_chain",
    "params": {
      "method": "FileFilter.accept",
      "direction": "both",
      "depth": 2
    },
    "projectKey": "autoloop",
    "sessionId": "test-session"
  }' | jq '.'
```

**参数**:
- `method`: 方法签名（格式: ClassName.methodName）
- `direction`: `callers`(调用者) / `callees`(被调用) / `both`(双向)
- `depth`: 分析深度（默认 2）

---

### 5. text_search **文本搜索**

**功能**: 使用正则表达式搜索代码

```bash
curl -s -X POST http://localhost:8080/api/claude-code/tools/execute \
  -H "Content-Type: application/json" \
  -d '{
    "tool": "text_search",
    "params": {
      "keyword": "public.*filter",
      "regex": true,
      "limit": 20
    },
    "projectKey": "autoloop",
    "sessionId": "test-session"
  }' | jq '.'
```

**参数**:
- `keyword`: 搜索关键词或正则
- `regex`: 是否启用正则模式
- `limit`: 最大结果数（**不超过 50**）
- `file_type`: 文件类型（java/config/all）

---

### 6. apply_change **代码修改**

**功能**: 应用代码修改（SEARCH/REPLACE + 自动格式化）

```bash
curl -s -X POST http://localhost:8080/api/claude-code/tools/execute \
  -H "Content-Type: application/json" \
  -d '{
    "tool": "apply_change",
    "params": {
      "relativePath": "core/src/main/java/com/example/File.java",
      "searchContent": "public void oldMethod() {",
      "replaceContent": "public void newMethod() {"
    },
    "projectKey": "autoloop",
    "sessionId": "test-session"
  }' | jq '.'
```

---

## 🔧 高级测试

### 测试进程池状态

```bash
curl http://localhost:8080/api/claude-code/pool/status | jq '.'
```

### 测试文件变化检测

```bash
# 查看刷新统计
curl http://localhost:8080/api/scheduler/refresh-stats | jq '.'
```

---

## 📊 测试结果示例

### ✅ 成功响应

```json
{
  "success": true,
  "result": {
    "recallQuery": "文件过滤",
    "rerankQuery": "按扩展名过滤文件",
    "count": 5,
    "results": [
      {
        "className": "FileFilter",
        "relativePath": "core/src/.../FileFilter.java",
        "score": 0.89
      }
    ]
  }
}
```

### ❌ 失败响应

```json
{
  "success": false,
  "error": "方法未找到: FileFilter.accept"
}
```

---

## ⚠️ 注意事项

1. **向量索引为空**: 首次使用时向量索引为空是正常的，需要先构建索引
2. **项目配置**: 确保在 `application.yml` 中配置了 `projectKey` → `projectPath` 映射
3. **参数校验**: 所有工具调用必须包含 `projectKey` 参数
4. **结果限制**: `text_search` 的 `limit` 参数不要超过 50

---

## 🛠️ 故障排查

### 服务未启动

```bash
# 检查端口占用
lsof -i :8080

# 杀死占用进程
lsof -ti:8080 | xargs kill -9
```

### 查看日志

```bash
tail -f /tmp/sman-agent.log
```

---

## 📚 相关文档

- [架构设计](../sman/docs/md/01-architecture.md)
- [WebSocket API](../sman/docs/md/02-websocket-api.md)
- [HTTP Tool API](../sman/docs/md/03-claude-code-integration.md)
- [数据模型](../sman/docs/md/04-data-models.md)
