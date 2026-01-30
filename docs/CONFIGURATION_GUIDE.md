# 配置指南

本文档说明 SmanAgent 的配置系统。

## 配置格式说明

SmanAgent 使用 **Java Properties 格式** (`.properties`)，而不是 YAML 格式。

### 为什么使用 Properties 格式？

1. **原生支持** - Java/Kotlin 原生支持，无需额外依赖
2. **简单直观** - 键值对格式，易于理解和修改
3. **IDE 集成** - IntelliJ IDEA 对 properties 文件有良好的编辑支持
4. **环境变量** - 支持通过 `${ENV_VAR}` 引用环境变量

## 配置对照表

### 向量数据库配置

| YAML 格式 | Properties 格式 | 说明 |
|-----------|-----------------|------|
| `vector-db.type` | `vector.db.type=JVECTOR` | 向量数据库类型 |
| `vector-db.jvector.dimension` | `vector.db.jvector.dimension=1024` | BGE-M3 向量维度 |
| `vector-db.jvector.M` | `vector.db.jvector.M=16` | HNSW 图连接数 (8-32) |
| `vector-db.jvector.efConstruction` | `vector.db.jvector.efConstruction=100` | HNSW 构建参数 |
| `vector-db.jvector.efSearch` | `vector.db.jvector.efSearch=50` | HNSW 搜索参数 |
| `vector-db.jvector.enablePersist` | `vector.db.jvector.enablePersist=true` | 磁盘持久化 |
| `vector-db.jvector.rerankerThreshold` | `vector.db.jvector.rerankerThreshold=0.1` | Reranker 阈值 |

**存储路径（自动按项目隔离）：**
- 向量存储：`~/.smanunion/{projectKey}/vector_store/`
- H2 数据库：`~/.smanunion/{projectKey}/analysis.mv.db`
- AST 缓存：`~/.smanunion/{projectKey}/ast_cache/`
- 会话数据：`~/.smanunion/sessions/{projectKey}/`

### BGE-M3 配置

| YAML 格式 | Properties 格式 | 说明 |
|-----------|-----------------|------|
| `vector.bge-m3.endpoint` | `bge.endpoint=http://localhost:8000/v1/embeddings` | BGE-M3 端点 |
| `vector.bge-m3.model-name` | `bge.model.name=BAAI/bge-m3` | 模型名称 |
| `vector.bge-m3.dimension` | `bge.dimension=1024` | 向量维度 |
| `vector.bge-m3.timeout` | `bge.timeout.seconds=30` | 超时时间（秒） |
| `vector.bge-m3.batch-size` | `bge.batch.size=10` | 批处理大小 |

### Reranker 配置

| YAML 格式 | Properties 格式 | 说明 |
|-----------|-----------------|------|
| `vector.reranker.enabled` | `reranker.enabled=true` | 是否启用 |
| `vector.reranker.base-url` | `reranker.base.url=http://localhost:8001/v1` | 服务地址 |
| `vector.reranker.model` | `reranker.model=BAAI/bge-reranker-v2-m3` | 模型名称 |
| `vector.reranker.api-key` | `reranker.api.key=` | API 密钥 |
| `vector.reranker.timeout-ms` | `reranker.timeout.seconds=30` | 超时时间（秒） |
| `vector.reranker.retry` | `reranker.retry=2` | 重试次数 |
| `vector.reranker.max-rounds` | `reranker.max.rounds=3` | 最多遍历轮数 |
| `vector.reranker.top-k` | `reranker.top.k=15` | 返回 top K |

## 配置参数详细说明

### JVector HNSW 参数

```properties
# M: HNSW 图的连接数
# - 范围: 8-32
# - 推荐: 16
# - 影响: 值越大，精度越高，但内存和计算开销也越大
vector.db.jvector.M=16

# efConstruction: 构建索引时的搜索宽度
# - 范围: 50-200
# - 推荐: 100
# - 影响: 值越大，索引质量越好，但构建时间越长
vector.db.jvector.efConstruction=100

# efSearch: 搜索时的搜索宽度
# - 范围: 20-100
# - 推荐: 50
# - 影响: 值越大，搜索精度越高，但搜索速度越慢
vector.db.jvector.efSearch=50

# rerankerThreshold: Reranker 相似度阈值
# - 范围: 0.0-1.0
# - 推荐: 0.1
# - 状态: 已定义但未在代码中使用（保留参数）
vector.db.jvector.rerankerThreshold=0.1
```

### 三层缓存配置

```properties
# L1 (Hot): 内存 LRU 缓存
# 存储最近访问的热点数据，建议根据项目规模调整：
# - 小型项目: 100-500
# - 中型项目: 500-1000
# - 大型项目: 1000-2000
vector.db.l1.cache.size=500          # 最大缓存条数（默认 500）

# L3 (Cold): H2 数据库
vector.db.h2.path=~/.smanunion/analysis.mv.db
vector.db.h2.connection.pool.size=10 # 连接池大小
vector.db.h2.connection.idle=2       # 空闲连接数
```

**三层缓存工作原理：**
- **L1 (Hot)**: 内存 LRU 缓存，存储最近访问的 500 个向量片段，O(1) 访问速度
- **L2 (Warm)**: JVector HNSW 索引，存储全量数据，O(log n) 搜索速度
- **L3 (Cold)**: H2 数据库，持久化存储，磁盘访问速度

**数据流转：**
- 新数据 → 立即写入 L1 和 L2/L3
- L1 满 → 按 LRU 策略淘汰最久未使用的数据
- L2 查询命中 → 自动升级到 L1
- L3 查询命中 → 自动升级到 L2 和 L1

### Reranker 参数说明

```properties
# enabled: 是否启用重排
# - true: 使用 Reranker 重排搜索结果（精度更高，速度较慢）
# - false: 直接返回向量搜索结果（速度更快，精度较低）
reranker.enabled=true

# retry: 重试次数
# - 当 Reranker 调用失败时的重试次数
reranker.retry=2

# max-rounds: 最多遍历轮数
# - 当有多个 Reranker 端点时，最多遍历所有端点的轮数
# - 用于负载均衡和容错
reranker.max.rounds=3

# top-k: 返回 top K
# - Reranker 返回的结果数量
# - 应该小于等于召回的数量
reranker.top.k=15
```

## 环境变量支持

配置支持通过 `${ENV_VAR}` 引用环境变量：

```properties
# LLM API Key（推荐使用环境变量）
llm.api.key=${LLM_API_KEY}

# BGE 端点
bge.endpoint=${BGE_ENDPOINT:http://localhost:8000/v1/embeddings}

# Reranker 端点
reranker.base.url=${RERANKER_URL:http://localhost:8001/v1}
```

## 配置加载优先级

```
用户设置（UI） > 环境变量 > 配置文件 > 默认值
```

1. **用户设置** - 通过 SmanAgent 设置界面配置
2. **环境变量** - 通过 `${ENV_VAR}` 引用
3. **配置文件** - `smanagent.properties` 中的值
4. **默认值** - 代码中硬编码的默认值

## 未使用的配置参数

以下参数已定义但**未在代码中使用**（保留用于未来扩展）：

- `vector.db.jvector.rerankerThreshold` - Reranker 相似度阈值

这些参数会被正确加载和校验，但不会影响当前的行为。

## 配置示例

### 最小配置（仅 LLM）

```properties
# 最小配置，只使用 LLM 功能
llm.api.key=${LLM_API_KEY}
```

### 完整配置（含向量搜索）

```properties
# LLM 配置
llm.api.key=${LLM_API_KEY}
llm.base.url=https://open.bigmodel.cn/api/coding/paas/v4
llm.model.name=glm-4-flash

# 向量数据库
vector.db.type=JVECTOR
vector.db.jvector.dimension=1024
vector.db.jvector.M=16
vector.db.jvector.efConstruction=100
vector.db.jvector.efSearch=50

# BGE-M3（需自行部署）
bge.endpoint=http://localhost:8000/v1/embeddings
bge.model.name=BAAI/bge-m3
bge.dimension=1024

# Reranker（需自行部署）
reranker.enabled=true
reranker.base.url=http://localhost:8001/v1
reranker.model=BAAI/bge-reranker-v2-m3
```

## 查看当前配置

在日志中查看配置摘要：

```
SmanAgent 配置摘要:
LLM 配置:
- BaseUrl: https://open.bigmodel.cn/api/coding/paas/v4
- Model: glm-4-flash

向量数据库配置:
- 类型: JVECTOR
- 维度: 1024
- L1缓存: 100条
- H2路径: ~/.smanunion/analysis.mv.db

BGE-M3 配置:
- 端点: http://localhost:8000/v1/embeddings
- 模型: BAAI/bge-m3

BGE-Reranker 配置:
- 启用: true
- 端点: http://localhost:8001/v1
- 模型: BAAI/bge-reranker-v2-m3
```
