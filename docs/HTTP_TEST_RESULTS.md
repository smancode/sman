# HTTP API 测试结果报告

**测试时间**: 2026-01-31 24:30
**项目**: autoloop

## 当前状态

### 分析数据 ✅ 已完成
```
~/.smanunion/autoloop/analysis.mv.db (20KB)
```

### VerificationWebService ⚠️ 运行中但有问题

**服务状态**: 运行在 http://localhost:8080
**问题**: HTTP 500 错误

### 根本原因

Spring Boot 创建了两个数据源：
1. **Pool-1 (错误)**: `jdbc:h2:/Users/liuchao/.smanunion/verification-service/analysis.mv.db`
2. **Pool-2 (正确)**: `jdbc:h2:/Users/liuchao/.smanunion/autoloop/analysis`

某些 API 使用了错误的数据源（Pool-1），导致 "Table not found" 错误。

### 原因分析

1. `VerificationConfig.dataSource()` Bean 配置正确（连接到 autoloop）
2. `TieredVectorStore` 在 `VectorSearchApi` 中被创建时，使用了 `VectorDatabaseConfig.create(projectKey = "verification-service")`
3. `TieredVectorStore` 创建了自己的 H2 数据源连接到 `verification-service`

### 解决方案

需要统一数据源配置，有两个选择：

**方案 A**: 禁用 `TieredVectorStore` 的数据源，让它使用注入的 `DataSource`
**方案 B**: 修改 `VectorSearchApi` 使用正确的 `projectKey`

## 建议的测试方法

由于 VerificationWebService 需要重构数据源配置，**建议使用以下方式测试**：

### 方法 1: 使用 IDEA 数据库工具

1. 在 IDEA 中打开 Database 工具窗口
2. 添加 H2 数据源：
   - URL: `jdbc:h2:/Users/liuchao/.smanunion/autoloop/analysis`
   - Driver: H2 Database
3. 执行查询：
   ```sql
   -- 查看所有表
   SELECT * FROM information_schema.tables;

   -- 查询分析步骤
   SELECT * FROM analysis_step WHERE project_key = 'autoloop';

   -- 查询项目结构
   SELECT * FROM analysis_results WHERE project_key = 'autoloop' AND module = 'project_structure';

   -- 查询技术栈
   SELECT * FROM analysis_results WHERE project_key = 'autoloop' AND module = 'tech_stack';
   ```

### 方法 2: 使用 H2 命令行客户端

```bash
# 启动 H2 控制台
java -cp ~/.gradle/caches/modules-2/files-2.1/com.h2database/h2/2.2.224/*/h2-2.2.224.jar org.h2.tools.Console -url "jdbc:h2:/Users/liuchao/.smanunion/autoloop/analysis" -driver org.h2.Driver
```

### 方法 3: 检查 IDEA 分析结果面板

在 IDEA 中，分析完成后应该可以在 SmanAgent 工具窗口中查看分析结果。

## 数据库表结构

### analysis_results 表
```sql
CREATE TABLE analysis_results (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_key VARCHAR,
    module VARCHAR,
    data CLOB,
    created_at TIMESTAMP
);
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

### vectors 表
```sql
CREATE TABLE vectors (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_key VARCHAR,
    fragment_id VARCHAR,
    vector BLOB,
    metadata CLOB,
    created_at TIMESTAMP
);
```

### projects 表
```sql
CREATE TABLE projects (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_key VARCHAR PRIMARY KEY,
    project_name VARCHAR,
    analysis_time TIMESTAMP,
    status VARCHAR
);
```

### analysis_step 表
```sql
CREATE TABLE analysis_step (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_key VARCHAR,
    step_name VARCHAR,
    status VARCHAR,
    start_time TIMESTAMP,
    end_time TIMESTAMP,
    error_message CLOB
);
```

## 下一步

1. **优先**: 在 IDEA 中直接查看分析结果
2. **可选**: 修复 VerificationWebService 的数据源配置问题

---

**生成时间**: 2026-01-31 24:30
**生成工具**: Claude Code
