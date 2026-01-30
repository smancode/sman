# BGE 配置和前端界面设计总结

## 📋 更新完成

已成功将 BGE 配置和前端界面设计整合到 `11_语义化向量化.md` 文档中。

## ✅ 新增内容

### 1. gradle.properties 配置

```properties
# ==================== 向量数据库配置 ====================
vector-db:
  type: jvector
  jvector:
    dimension: 1024
    M: 16
    efConstruction: 100
    efSearch: 50
    basePath: ./data
    enablePersist: true
    rerankerThreshold: 0.1

# ==================== 向量搜索配置 ====================
vector:
  bge-m3:
    endpoint: "http://localhost:8000"
    model-name: "BAAI/bge-m3"
    dimension: 1024
    timeout: 30000
    batch-size: 10

  reranker:
    enabled: ${BGE_RERANKER_ENABLED:true}
    base-url: ${BGE_RERANKER_BASE_URL:http://localhost:8001/v1}
    model: ${BGE_RERANKER_MODEL:BAAI/bge-reranker-v2-m3}
    api-key: ${BGE_RERANKER_API_KEY:}
    timeout-ms: 30000
    retry: 2
    max-rounds: 3
    top-k: 15
```

### 2. 环境变量支持

**优先级**：环境变量 > gradle.properties > 默认值

```bash
# 环境变量覆盖示例
export BGE_RERANKER_ENABLED=true
export BGE_RERANKER_BASE_URL=http://localhost:8001/v1
export BGE_RERANKER_MODEL=BAAI/bge-reranker-v2-m3
export BGE_RERANKER_API_KEY=your-api-key
```

### 3. 前端配置界面

#### 设置页面位置
```
Settings/Preferences → SmanAgent → Vector Search
```

#### 配置项

**BGE-M3 向量化服务**：
- Endpoint（文本框）
- Model Name（文本框）
- API Key（密码框）
- Timeout（滑块：10s-60s）
- Batch Size（滑块：1-50）

**BGE-Reranker 重排服务**：
- Enabled（复选框）
- Base URL（文本框）
- Model（下拉选择）
- API Key（密码框）
- Top K（滑块：5-50）

**测试连接按钮**：
- 测试 BGE-M3 连接
- 测试 Reranker 连接

### 4. 配置加载代码

```kotlin
class VectorConfigLoader(
    private val project: Project
) {
    fun load(): VectorDatabaseConfig {
        val properties = loadGradleProperties()

        return VectorDatabaseConfig(
            type = properties.getProperty("vector-db.type", "jvector")
                .let { VectorDbType.valueOf(it.uppercase()) },
            jvector = parseJVectorConfig(properties),
            bgeM3 = parseBgeM3Config(properties),
            reranker = parseRerankerConfig(properties)
        )
    }

    private fun loadGradleProperties(): Properties {
        // 1. 从 gradle.properties 加载
        // 2. 应用环境变量覆盖
        // 3. 返回合并后的配置
    }
}
```

### 5. 数据模型

```kotlin
data class VectorDatabaseConfig(
    val type: VectorDbType,
    val jvector: JVectorConfig,
    val bgeM3: BgeM3Config,
    val reranker: RerankerConfig
)

data class BgeM3Config(
    val endpoint: String,
    val modelName: String,
    val dimension: Int,
    val timeout: Duration,
    val batchSize: Int
)

data class RerankerConfig(
    val enabled: Boolean,
    val baseUrl: String,
    val model: String,
    val apiKey: String,
    val timeout: Duration,
    val retry: Int,
    val maxRounds: Int,
    val topK: Int
)
```

## 🎯 关键特性

### 1. 灵活的配置方式

| 配置源 | 优先级 | 适用场景 |
|--------|-------|---------|
| 环境变量 | 最高 | 生产环境、CI/CD |
| gradle.properties | 中 | 项目配置 |
| 默认值 | 最低 | 本地开发 |

### 2. 完整的测试功能

```kotlin
class VectorSearchConfigurable(
    private val project: Project
) : SearchableConfigurable {

    override fun createComponent(): JComponent {
        return panel {
            // BGE-M3 配置
            row { label("Endpoint:") ; textField() }
            row { label("Model Name:") ; textField() }
            row { label("API Key:") ; passwordField() }
            row {
                label("Timeout:")
                slider(10..60, value = 30)
            }

            separator("BGE-Reranker")

            row { checkBox("Enabled") }
            row { label("Base URL:") ; textField() }
            row { label("API Key:") ; passwordField() }

            button("Test BGE-M3") {
                actionPerformed { testBgeConnection() }
            }
            button("Test Reranker") {
                actionPerformed { testRerankerConnection() }
            }
        }
    }

    private suspend fun testBgeConnection() {
        val result = bgeClient.testConnection(config)
        if (result.success) {
            showMessageDialog("Success", "BGE-M3 连接成功！")
        } else {
            showMessageDialog("Error", "连接失败：${result.error}")
        }
    }
}
```

### 3. 配置持久化

```kotlin
// 项目级配置（存储到 .idea/smanunion.xml）
class ProjectVectorConfig : PersistentStateComponent<ProjectVectorConfig> {
    var bgeEndpoint by string("http://localhost:8000")
    var bgeApiKey by string("")
    var rerankerEnabled by boolean(true)
    var rerankerBaseUrl by string("http://localhost:8001/v1")
}

// 用户级配置（存储到 ~/.smanunion/config.properties）
class UserVectorConfig : SimplePropertiesComponent {
    var bgeEndpoint by string("http://localhost:8000")
    var bgeApiKey by string("")
}
```

## 📊 配置参数说明

### JVector 参数

| 参数 | 推荐值 | 说明 |
|------|-------|------|
| dimension | 1024 | BGE-M3 向量维度 |
| M | 16 | HNSW 图连接数（8-32） |
| efConstruction | 100 | HNSW 构建参数（50-200） |
| efSearch | 50 | HNSW 搜索参数（20-100） |
| enablePersist | true | 启用磁盘持久化 |
| rerankerThreshold | 0.1 | Reranker 相似度阈值 |

### Reranker 参数

| 参数 | 推荐值 | 说明 |
|------|-------|------|
| enabled | true | 启用重排 |
| max-rounds | 3 | 最多遍历端点3轮 |
| top-k | 15 | 返回 top 15 |
| retry | 2 | 重试次数 |
| timeout-ms | 30000 | 超时时间（30秒） |

## 🚀 实施任务

- [x] 添加 gradle.properties 配置
- [ ] 实现配置加载服务
- [ ] 实现前端配置界面
- [ ] 实现 BGE-M3 客户端
- [ ] 实现 BGE-Reranker 客户端
- [ ] 添加测试连接功能
- [ ] 添加配置验证
- [ ] 编写单元测试

## 📁 更新的文档

| 文档 | 更新内容 |
|------|---------|
| 11_语义化向量化.md | ✅ 添加 BGE 配置章节 |
| 11_语义化向量化.md | ✅ 添加配置加载代码 |
| 11_语义化向量化.md | ✅ 添加前端配置界面设计 |
| 11_语义化向量化.md | ✅ 添加数据模型 |
| 11_语义化向量化.md | ✅ 添加下一步任务 |

所有 BGE 配置和前端界面设计已完整！🎉
