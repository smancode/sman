# SmanAgent 验证服务 API 文档

## 概述

SmanAgent 验证服务是一个独立的 Web 服务，用于验证分析结果的正确性。该服务提供 RESTful API，支持专家咨询、向量搜索、分析结果查询等功能。

**基础信息**:
- 基础 URL: `http://localhost:8080`
- 协议: HTTP (POST)
- 数据格式: JSON
- 字符编码: UTF-8

**端点列表**:
- `POST /api/verify/expert_consult` - 专家咨询
- `POST /api/verify/semantic_search` - 向量搜索
- `POST /api/verify/analysis_results` - 分析结果查询
- `POST /api/verify/h2_query` - H2 数据库查询

---

## 1. 专家咨询 API

### 1.1 端点信息

**URL**: `/api/verify/expert_consult`

**方法**: `POST`

**描述**: 直接使用 LLM 查询，不走 ReAct 循环。根据问题和项目上下文，返回专家级的答案。

### 1.2 请求参数

| 参数名 | 类型 | 必填 | 默认值 | 说明 |
|--------|------|------|--------|------|
| `question` | String | 是 | - | 问题内容，不能为空 |
| `projectKey` | String | 是 | - | 项目标识符，用于定位项目上下文 |
| `topK` | Integer | 否 | 10 | 召回相关上下文的数量，必须大于 0 |
| `enableRerank` | Boolean | 否 | true | 是否启用重排序 |

**请求示例**:
```json
{
    "question": "放款入口是哪个？",
    "projectKey": "loan-system",
    "topK": 10,
    "enableRerank": true
}
```

### 1.3 响应格式

| 字段名 | 类型 | 说明 |
|--------|------|------|
| `answer` | String | LLM 生成的答案 |
| `sources` | Array | 来源信息列表 |
| `confidence` | Double | 置信度 (0.0 - 1.0) |
| `processingTimeMs` | Long | 处理耗时（毫秒） |

**SourceInfo 结构**:
| 字段名 | 类型 | 说明 |
|--------|------|------|
| `fragmentId` | String | 片段 ID |
| `fileName` | String | 文件名 |
| `lineNumber` | Integer | 行号（可能为 null） |
| `content` | String | 内容片段 |
| `score` | Double | 相似度分数 |
| `type` | String | 类型：`code`、`ast`、`doc` |

**响应示例**:
```json
{
    "answer": "放款入口是 LoanController.createLoan() 方法...",
    "sources": [
        {
            "fragmentId": "frag-001",
            "fileName": "LoanController.kt",
            "lineNumber": 42,
            "content": "@PostMapping\nfun createLoan(...)",
            "score": 0.95,
            "type": "code"
        }
    ],
    "confidence": 0.85,
    "processingTimeMs": 1234
}
```

### 1.4 错误码

| HTTP 状态码 | 错误信息 | 说明 |
|-------------|----------|------|
| 400 | `question 不能为空` | 问题参数缺失或为空 |
| 400 | `projectKey 不能为空` | 项目标识符缺失或为空 |
| 400 | `topK 必须大于 0` | topK 参数不合法 |
| 500 | `内部服务错误` | 服务器内部错误 |

---

## 2. 向量搜索 API

### 2.1 端点信息

**URL**: `/api/verify/semantic_search`

**方法**: `POST`

**描述**: 支持 BGE 召回 + Reranker 重排的向量搜索。根据查询语句，返回最相关的代码片段。

### 2.2 请求参数

| 参数名 | 类型 | 必填 | 默认值 | 说明 |
|--------|------|------|--------|------|
| `query` | String | 是 | - | 查询语句，不能为空 |
| `projectKey` | String | 否 | null | 项目标识符（可选，用于限定搜索范围） |
| `topK` | Integer | 否 | 10 | 召回数量，必须大于 0 |
| `enableRerank` | Boolean | 否 | true | 是否启用重排序 |
| `rerankTopN` | Integer | 否 | 5 | 重排序后保留的顶级结果数 |

**请求示例**:
```json
{
    "query": "用户登录验证",
    "projectKey": "auth-system",
    "topK": 10,
    "enableRerank": true,
    "rerankTopN": 5
}
```

### 2.3 响应格式

| 字段名 | 类型 | 说明 |
|--------|------|------|
| `query` | String | 查询语句 |
| `recallResults` | Array | 召回结果列表 |
| `rerankResults` | Array | 重排序结果列表（可能为 null） |
| `processingTimeMs` | Long | 处理耗时（毫秒） |

**SearchResult 结构**:
| 字段名 | 类型 | 说明 |
|--------|------|------|
| `fragmentId` | String | 片段 ID |
| `fileName` | String | 文件名 |
| `content` | String | 内容片段 |
| `score` | Double | 相似度分数 |
| `rank` | Integer | 排名 |

**响应示例**:
```json
{
    "query": "用户登录验证",
    "recallResults": [
        {
            "fragmentId": "frag-001",
            "fileName": "LoginController.kt",
            "content": "fun login(username: String, password: String)",
            "score": 0.89,
            "rank": 1
        },
        {
            "fragmentId": "frag-002",
            "fileName": "AuthService.kt",
            "content": "fun authenticate(token: String)",
            "score": 0.82,
            "rank": 2
        }
    ],
    "rerankResults": [
        {
            "fragmentId": "frag-001",
            "fileName": "LoginController.kt",
            "content": "fun login(username: String, password: String)",
            "score": 0.95,
            "rank": 1
        }
    ],
    "processingTimeMs": 567
}
```

### 2.4 错误码

| HTTP 状态码 | 错误信息 | 说明 |
|-------------|----------|------|
| 400 | `query 不能为空` | 查询参数缺失或为空 |
| 400 | `topK 必须大于 0` | topK 参数不合法 |
| 400 | `rerankTopN 必须大于 0` | rerankTopN 参数不合法 |
| 500 | `内部服务错误` | 服务器内部错误 |

---

## 3. 分析结果查询 API

### 3.1 端点信息

**URL**: `/api/verify/analysis_results`

**方法**: `POST`

**描述**: 查询 12 个分析模块的结果，支持分页和过滤。

### 3.2 请求参数

| 参数名 | 类型 | 必填 | 默认值 | 说明 |
|--------|------|------|--------|------|
| `module` | String | 是 | - | 模块名称（见下表） |
| `projectKey` | String | 是 | - | 项目标识符 |
| `filters` | Map | 否 | null | 过滤条件（可选） |
| `page` | Integer | 否 | 0 | 页码，从 0 开始 |
| `size` | Integer | 否 | 20 | 每页大小，最大 100 |

**支持的模块列表**:
| 模块名 | 说明 |
|--------|------|
| `project_structure` | 项目结构扫描 |
| `tech_stack` | 技术栈识别 |
| `ast_scan` | AST 扫描 |
| `db_entities` | 数据库实体扫描 |
| `api_entries` | API 入口扫描 |
| `external_apis` | 外调接口扫描 |
| `enums` | 枚举扫描 |
| `common_classes` | 公共类扫描 |
| `xml_codes` | XML 代码扫描 |
| `case_sop` | 案例 SOP 生成 |
| `vectorization` | 语义化向量化 |
| `code_walkthrough` | 代码走读 |

**请求示例**:
```json
{
    "module": "api_entries",
    "projectKey": "loan-system",
    "filters": {
        "path": "/api/loan"
    },
    "page": 0,
    "size": 20
}
```

### 3.3 响应格式

| 字段名 | 类型 | 说明 |
|--------|------|------|
| `module` | String | 模块名称 |
| `projectKey` | String | 项目标识符 |
| `data` | Array | 数据列表 |
| `total` | Integer | 总记录数 |
| `page` | Integer | 当前页码 |
| `size` | Integer | 每页大小 |

**响应示例**:
```json
{
    "module": "api_entries",
    "projectKey": "loan-system",
    "data": [
        {
            "path": "/api/loan/create",
            "method": "POST",
            "controller": "LoanController",
            "description": "创建贷款"
        }
    ],
    "total": 1,
    "page": 0,
    "size": 20
}
```

### 3.4 错误码

| HTTP 状态码 | 错误信息 | 说明 |
|-------------|----------|------|
| 400 | `module 不能为空` | 模块参数缺失或为空 |
| 400 | `projectKey 不能为空` | 项目标识符缺失或为空 |
| 400 | `page 不能小于 0` | 页码不合法 |
| 400 | `size 不能超过 100` | 每页大小超过限制 |
| 404 | `模块不存在` | 指定的模块不存在 |
| 500 | `内部服务错误` | 服务器内部错误 |

---

## 4. H2 数据库查询 API

### 4.1 端点信息

**URL**: `/api/verify/h2_query`

**方法**: `POST`

**描述**: 直接查询 H2 数据库中的原始数据。

### 4.2 请求参数

| 参数名 | 类型 | 必填 | 默认值 | 说明 |
|--------|------|------|--------|------|
| `sql` | String | 是 | - | SQL 查询语句 |
| `projectKey` | String | 是 | - | 项目标识符 |
| `limit` | Integer | 否 | 100 | 结果数量限制，最大 1000 |

**请求示例**:
```json
{
    "sql": "SELECT * FROM vector_fragments WHERE project_key = ? LIMIT 10",
    "projectKey": "loan-system",
    "limit": 10
}
```

### 4.3 响应格式

| 字段名 | 类型 | 说明 |
|--------|------|------|
| `results` | Array | 查询结果列表 |
| `columns` | Array | 列名列表 |
| `rowCount` | Integer | 行数 |
| `executionTimeMs` | Long | 执行耗时（毫秒） |

**响应示例**:
```json
{
    "results": [
        {
            "fragment_id": "frag-001",
            "project_key": "loan-system",
            "content": "class LoanController {...}"
        }
    ],
    "columns": ["fragment_id", "project_key", "content"],
    "rowCount": 1,
    "executionTimeMs": 23
}
```

### 4.4 错误码

| HTTP 状态码 | 错误信息 | 说明 |
|-------------|----------|------|
| 400 | `sql 不能为空` | SQL 语句缺失或为空 |
| 400 | `projectKey 不能为空` | 项目标识符缺失或为空 |
| 400 | `SQL 语法错误` | SQL 语句不合法 |
| 400 | `limit 不能超过 1000` | limit 超过限制 |
| 500 | `数据库错误` | 数据库执行失败 |

---

## 通用错误响应格式

所有 API 在发生错误时返回统一的错误响应格式：

```json
{
    "timestamp": "2026-01-31T12:34:56.789",
    "status": 400,
    "error": "Bad Request",
    "message": "question 不能为空",
    "path": "/api/verify/expert_consult"
}
```

---

## 性能指标

| API | 平均响应时间 | P95 响应时间 | QPS 上限 |
|-----|-------------|-------------|---------|
| 专家咨询 | 1-2s | 3s | 50 |
| 向量搜索 | 200-500ms | 1s | 100 |
| 分析结果查询 | 50-100ms | 200ms | 200 |
| H2 数据查询 | 10-50ms | 100ms | 500 |

---

## 版本历史

| 版本 | 日期 | 变更说明 |
|------|------|---------|
| 1.0.0 | 2026-01-31 | 初始版本 |

---

## 技术支持

- 问题反馈: [GitHub Issues](https://github.com/your-repo/issues)
- 文档: [项目 Wiki](https://github.com/your-repo/wiki)
