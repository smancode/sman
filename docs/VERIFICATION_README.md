# SmanAgent 验证服务文档索引

## 文档列表

### 1. [API 文档](./VERIFICATION_API.md)
**完整的 API 参考**

内容包括:
- API 概述
- 所有端点的详细说明
- 请求/响应格式
- 参数说明
- 错误码定义
- 性能指标

**适用人群**: 开发者、集成人员

---

### 2. [使用示例](./VERIFICATION_EXAMPLES.md)
**实际使用示例和最佳实践**

内容包括:
- 启动服务的详细步骤
- 每个 API 的 curl 示例
- 预期响应示例
- 集成测试说明
- 故障排查指南

**适用人群**: 开发者、测试人员

---

### 3. [启动脚本](../scripts/verification-web.sh)
**一键启动验证服务**

特性:
- 自动检查 Java 版本
- 自动检查端口占用
- 环境变量检查
- 健康检查
- 优雅关闭

**使用方法**:
```bash
# 使用默认端口 8080
./scripts/verification-web.sh

# 使用自定义端口
VERIFICATION_PORT=9090 ./scripts/verification-web.sh
```

---

### 4. [测试脚本](../scripts/test-verification-api.sh)
**自动化 API 测试**

特性:
- 测试所有 API 端点
- 验证返回格式
- 生成测试报告
- 计算通过率

**使用方法**:
```bash
# 使用默认端口 8080
./scripts/test-verification-api.sh

# 使用自定义端口
./scripts/test-verification-api.sh 9090
```

---

## 快速开始

### 1. 启动服务

```bash
./scripts/verification-web.sh
```

### 2. 测试 API

```bash
# 自动化测试
./scripts/test-verification-api.sh

# 手动测试
curl -X POST http://localhost:8080/api/verify/expert_consult \
  -H "Content-Type: application/json" \
  -d '{"question": "放款入口是哪个？", "projectKey": "test-project"}'
```

### 3. 查看文档

- API 参考: [docs/VERIFICATION_API.md](./VERIFICATION_API.md)
- 使用示例: [docs/VERIFICATION_EXAMPLES.md](./VERIFICATION_EXAMPLES.md)

---

## API 端点列表

| 端点 | 方法 | 描述 | 文档 |
|------|------|------|------|
| `/api/verify/expert_consult` | POST | 专家咨询 | [API 文档](./VERIFICATION_API.md#1-专家咨询-api) |
| `/api/verify/semantic_search` | POST | 向量搜索 | [API 文档](./VERIFICATION_API.md#2-向量搜索-api) |
| `/api/verify/analysis_results` | POST | 分析结果查询 | [API 文档](./VERIFICATION_API.md#3-分析结果查询-api) |
| `/api/verify/h2_query` | POST | H2 数据库查询 | [API 文档](./VERIFICATION_API.md#4-h2-数据库查询-api) |

---

## 环境配置

### 必需环境变量

```bash
# LLM 配置
export LLM_API_KEY=your_api_key_here
export LLM_BASE_URL=https://open.bigmodel.cn/api/paas/v4/chat/completions
export LLM_MODEL_NAME=glm-4-flash
```

### 可选环境变量

```bash
# BGE-M3 配置
export BGE_ENABLED=true
export BGE_ENDPOINT=your_bge_endpoint
export BGE_MODEL_NAME=bge-m3

# Reranker 配置
export RERANKER_ENABLED=true
export RERANKER_BASE_URL=your_reranker_endpoint
export RERANKER_API_KEY=your_reranker_api_key

# 服务配置
export VERIFICATION_PORT=8080
```

---

## 支持的分析模块

| 模块名 | 说明 | 文档 |
|--------|------|------|
| `project_structure` | 项目结构扫描 | [使用示例](./VERIFICATION_EXAMPLES.md#4-分析结果查询示例) |
| `tech_stack` | 技术栈识别 | [使用示例](./VERIFICATION_EXAMPLES.md#4-分析结果查询示例) |
| `ast_scan` | AST 扫描 | [使用示例](./VERIFICATION_EXAMPLES.md#4-分析结果查询示例) |
| `db_entities` | 数据库实体扫描 | [使用示例](./VERIFICATION_EXAMPLES.md#4-分析结果查询示例) |
| `api_entries` | API 入口扫描 | [使用示例](./VERIFICATION_EXAMPLES.md#4-分析结果查询示例) |
| `external_apis` | 外调接口扫描 | [使用示例](./VERIFICATION_EXAMPLES.md#4-分析结果查询示例) |
| `enums` | 枚举扫描 | [使用示例](./VERIFICATION_EXAMPLES.md#4-分析结果查询示例) |
| `common_classes` | 公共类扫描 | [使用示例](./VERIFICATION_EXAMPLES.md#4-分析结果查询示例) |
| `xml_codes` | XML 代码扫描 | [使用示例](./VERIFICATION_EXAMPLES.md#4-分析结果查询示例) |
| `case_sop` | 案例 SOP 生成 | [使用示例](./VERIFICATION_EXAMPLES.md#4-分析结果查询示例) |
| `vectorization` | 语义化向量化 | [使用示例](./VERIFICATION_EXAMPLES.md#4-分析结果查询示例) |
| `code_walkthrough` | 代码走读 | [使用示例](./VERIFICATION_EXAMPLES.md#4-分析结果查询示例) |

---

## 故障排查

### 常见问题

| 问题 | 解决方案 | 文档 |
|------|---------|------|
| 端口被占用 | 修改端口或杀死占用进程 | [故障排查](./VERIFICATION_EXAMPLES.md#7-故障排查) |
| 服务无法启动 | 检查 Java 版本和环境变量 | [故障排查](./VERIFICATION_EXAMPLES.md#7-故障排查) |
| API 调用失败 | 检查请求格式和参数 | [故障排查](./VERIFICATION_EXAMPLES.md#7-故障排查) |
| 响应时间过长 | 调整 topK 参数或禁用重排序 | [故障排查](./VERIFICATION_EXAMPLES.md#7-故障排查) |

---

## 性能参考

| API | 平均响应时间 | P95 响应时间 | QPS 上限 |
|-----|-------------|-------------|---------|
| 专家咨询 | 1-2s | 3s | 50 |
| 向量搜索 | 200-500ms | 1s | 100 |
| 分析结果查询 | 50-100ms | 200ms | 200 |
| H2 数据查询 | 10-50ms | 100ms | 500 |

详见: [API 文档 - 性能指标](./VERIFICATION_API.md#性能指标)

---

## 技术支持

- 问题反馈: [GitHub Issues](https://github.com/your-repo/issues)
- 文档: [项目 Wiki](https://github.com/your-repo/wiki)
- 邮件: support@example.com

---

## 版本历史

| 版本 | 日期 | 变更说明 |
|------|------|---------|
| 1.0.0 | 2026-01-31 | 初始版本，包含完整的 API 文档和使用示例 |
