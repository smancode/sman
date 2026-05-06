# Sman MCP 工具 API

> 通过 HTTP 接口调用 Sman 中已加载的业务系统工具（Skills 和地球路径）

## 前置条件

- Sman 桌面端已启动，目标项目已加载
- 获取认证 Token（仅限本机访问）：

```bash
curl http://localhost:5880/api/auth/token
```

所有 `/api/` 请求需在 Header 中携带：

```
Authorization: Bearer <token>
```

---

## 1. 列出工具

**POST** `/api/mcp/tools/list`

查询指定项目（workspace）下可用的工具列表。

### 请求 Body

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `workspaces` | `string[]` | 否 | 要查询的项目路径数组。不传则返回所有已打开项目的工具 |

### 请求示例

```bash
# 查询指定项目
curl -X POST http://localhost:5880/api/mcp/tools/list \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <token>" \
  -d '{
    "workspaces": ["/Users/xxx/projects/my-project"]
  }'

# 查询所有已打开项目
curl -X POST http://localhost:5880/api/mcp/tools/list \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <token>" \
  -d '{}'
```

### 响应示例

```json
{
  "tools": [
    {
      "id": "caijing",
      "name": "caijing",
      "description": "财经新闻自动拉取。触发方式：/caijing fetch",
      "type": "skill",
      "workspace": "/Users/xxx/projects/H5"
    },
    {
      "id": "73f1bbfb-a732-41b9-888c-90e08eeb6cf9",
      "name": "月度汇总",
      "description": "按月汇总数据并生成报告",
      "type": "path",
      "workspace": "/Users/xxx/projects/H5"
    }
  ]
}
```

### 响应字段说明

| 字段 | 说明 |
|------|------|
| `id` | 工具唯一标识，调用时使用 |
| `name` | 工具名称 |
| `description` | 工具描述 |
| `type` | `skill`（技能）或 `path`（地球路径） |
| `workspace` | 所属项目路径 |

---

## 2. 调用工具

**POST** `/api/mcp/tools/invoke`

执行指定工具，等执行完成后返回结果。

### 请求 Body

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `workspace` | `string` | 是 | 项目路径，定位到具体 workspace |
| `toolType` | `string` | 是 | `"skill"` 或 `"path"` |
| `toolId` | `string` | 是 | 工具 ID，从 `/tools/list` 获取 |
| `parameters` | `string` | 否 | 工具参数，会拼接为 `/<toolId> <parameters>` 发给 AI 执行 |

### 请求示例

```bash
# 调用 skill（带参数）
curl -X POST http://localhost:5880/api/mcp/tools/invoke \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <token>" \
  -d '{
    "workspace": "/Users/xxx/projects/H5",
    "toolType": "skill",
    "toolId": "caijing",
    "parameters": "fetch"
  }'

# 调用 skill（参数可以包含空格）
curl -X POST http://localhost:5880/api/mcp/tools/invoke \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <token>" \
  -d '{
    "workspace": "/Users/xxx/projects/H5",
    "toolType": "skill",
    "toolId": "stock",
    "parameters": "贵州茅台 --days 7"
  }'

# 调用 skill（不带参数）
curl -X POST http://localhost:5880/api/mcp/tools/invoke \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <token>" \
  -d '{
    "workspace": "/Users/xxx/projects/H5",
    "toolType": "skill",
    "toolId": "caijing"
  }'

# 调用地球路径
curl -X POST http://localhost:5880/api/mcp/tools/invoke \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <token>" \
  -d '{
    "workspace": "/Users/xxx/projects/H5",
    "toolType": "path",
    "toolId": "73f1bbfb-a732-41b9-888c-90e08eeb6cf9"
  }'
```

### 成功响应（skill）

```json
{
  "status": "success",
  "result": "已完成财经新闻拉取，共导入 15 条新闻...",
  "skillId": "caijing",
  "skillName": "caijing"
}
```

### 成功响应（path）

```json
{
  "status": "success",
  "result": "Path \"月度汇总\" completed successfully",
  "pathId": "73f1bbfb-a732-41b9-888c-90e08eeb6cf9",
  "referencesCount": 3
}
```

### 错误响应

```json
// 400 - 参数缺失
{ "error": "Missing required parameters: workspace, toolType, toolId" }

// 400 - 无效的 toolType
{ "error": "Invalid toolType: xxx. Must be 'skill' or 'path'" }

// 404 - 工具不存在
{ "error": "Skill not found: xxx" }

// 401 - 未认证
{ "error": "Unauthorized" }

// 500 - 执行失败
{ "status": "error", "error": "具体错误信息" }
```

---

## 典型调用流程

```
1. GET  /api/auth/token                    → 获取 Token
2. POST /api/mcp/tools/list                → 查看可用工具
3. POST /api/mcp/tools/invoke              → 调用工具，等待结果
```

## 注意事项

- 接口是同步阻塞的，工具执行完成后才返回响应（skill 执行可能较久）
- Token 每次启动 Sman 会重新生成
- Sman 关闭后接口不可用
