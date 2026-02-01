# VerificationWebService 当前状态报告

**生成时间**: 2026-01-31 24:00

## 已完成的工作

### 1. 脚本优化
- **文件**: `scripts/verification-web.sh`
- **修改**: 添加了自动检测 BGE 和 Reranker 服务的逻辑
  - 检测端口 8000（BGE-M3）和 8001（Reranker）
  - 如果服务运行，自动启用对应功能

### 2. Spring 配置类
- **文件**: `src/main/kotlin/com/smancode/smanagent/verification/config/VerificationConfig.kt`
- **功能**: 注册验证服务所需的 Spring Bean
  - `LlmService` - LLM 调用服务
  - `DataSource` - H2 数据源（连接到 autoloop 数据库）
  - `JdbcTemplate` - JDBC 模板
  - `BgeM3Client` - BGE-M3 向量化客户端
  - `RerankerClient` - Reranker 重排客户端
  - `TieredVectorStore` - 向量存储

### 3. API 控制器重构
- **文件**: `src/main/kotlin/com/smancode/smanagent/verification/api/VerificationApiControllers.kt`
- **修改**: `VectorSearchApi` 使用 Spring Bean 注入，为每个请求动态创建向量存储

### 4. 主类优化
- **文件**: `src/main/kotlin/com/smancode/smanagent/verification/VerificationWebService.kt`
- **修改**: 改用顶层函数风格（Spring Boot 推荐）

### 5. Gradle 任务
- **文件**: `build.gradle.kts`
- **新增**: `runVerification` 任务用于启动 VerificationWebService

## 当前问题

### 问题 1: 服务不稳定
**现象**: VerificationWebService 启动后频繁崩溃（退出码 137）

**原因**:
- 内存占用过高
- 每个请求创建新的 `TieredVectorStore` 实例

### 问题 2: 数据库连接复杂
**现象**: H2 查询返回 "Table not found" 错误

**原因**:
- `VectorSearchApi` 为每个请求创建独立的 `TieredVectorStore`
- `TieredVectorStore` 为每个项目创建独立的 H2 数据库连接
- Spring 配置的 `DataSource` 连接到 autoloop，但 API 创建的连接指向其他数据库

### 问题 3: BGE 端点配置
**现象**: 语义搜索返回 HTTP 404

**原因**:
- BGE 端点配置可能不正确（默认 `http://localhost:8000/v1`）
- 端点路径可能与实际 BGE 服务不匹配

## 建议的解决方案

### 方案 A: 在 IDEA 中运行测试（推荐）

1. **分析已完成**: autoloop 项目分析已在 IDEA 中成功完成
   - 9/9 分析步骤完成
   - 8/8 向量化步骤完成
   - 数据存储在 `~/.smanunion/autoloop/`

2. **直接在 IDEA 中测试**:
   ```
   打开 http/rest.http 文件
   在 IDEA 中右键每个测试用例 → Run
   ```

### 方案 B: 修复 VerificationWebService

需要重构的文件：
1. **`VectorSearchService.kt`**: 支持动态项目切换
2. **`TieredVectorStore.kt`**: 支持共享数据源
3. **`VerificationConfig.kt`**: 简化 Bean 配置

### 方案 C: 创建独立的查询工具

创建一个简单的命令行工具直接查询 H2 数据库，不依赖 Spring Boot：
```bash
./scripts/query-h2.sh --project autoloop --module project_structure
```

## 数据库信息

### autoloop 项目数据库
```
路径: ~/.smanunion/autoloop/analysis.mv.db
大小: 20KB
表: analysis_results, vectors, projects
```

### 可用的查询表
- `analysis_results` - 分析结果（9 个模块）
- `vectors` - 向量数据（1024 维）
- `projects` - 项目元数据

## 测试用例

### 可用的 HTTP 测试（http/rest.http）

#### 1. 健康检查
```http
GET http://localhost:8080/actuator/health
```

#### 2. 分析结果查询（9 个模块）
```http
POST http://localhost:8080/api/verify/analysis_results
Content-Type: application/json

{
  "module": "project_structure",
  "projectKey": "autoloop",
  "page": 0,
  "size": 10
}
```

支持的模块：
- `project_structure` - 项目结构
- `tech_stack` - 技术栈
- `ast_scanning` - AST 扫描
- `db_entities` - 数据库实体
- `api_entries` - API 入口
- `external_apis` - 外调接口
- `enums` - 枚举
- `common_classes` - 公共类
- `xml_code` - XML 代码

#### 3. 语义搜索
```http
POST http://localhost:8080/api/verify/semantic_search
Content-Type: application/json

{
  "query": "还款入口是哪个",
  "projectKey": "autoloop",
  "topK": 10,
  "enableRerank": false
}
```

#### 4. H2 数据库查询
```http
POST http://localhost:8080/api/verify/query_vectors
Content-Type: application/json

{
  "projectKey": "autoloop",
  "page": 0,
  "size": 10
}
```

## 总结

✅ **已完成**: autoloop 项目分析成功（9/9 步骤，8/8 向量化）

⏳ **待修复**: VerificationWebService 需要重构以支持多项目查询

📋 **建议**: 在 IDEA 中直接运行 `http/rest.http` 测试用例

---

**生成工具**: Claude Code
**上下文**: Session 53f41ae6-4c89-4bf7-b8c0-ada9b2dcd9da
