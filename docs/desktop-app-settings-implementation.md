# 桌面应用 API Key 配置功能实现方案

## 概述

为 SmanClaw 桌面应用添加完整的配置管理功能，支持用户配置 LLM、向量召回、向量库等 API。

---

## 一、配置项总览

| 类别 | 配置项 | 说明 | 必填 |
|------|--------|------|------|
| **LLM 主模型** | `api_url`, `api_key`, `model` | OpenAI Compatible API | ✅ 必填 |
| **向量召回** | `embedding_url`, `embedding_key`, `embedding_model` | Embedding API | 可选 |
| **向量库** | `qdrant_url`, `qdrant_collection`, `qdrant_api_key` | Qdrant 配置 | 可选 |

---

## 二、数据结构设计

### 2.1 AppSettings（应用全局设置）

```rust
// smanclaw-types/src/settings.rs

use serde::{Deserialize, Serialize};

/// 应用全局设置（持久化存储）
#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct AppSettings {
    /// LLM 配置
    pub llm: LlmSettings,
    /// 向量召回配置（可选）
    pub embedding: Option<EmbeddingSettings>,
    /// 向量库配置（可选）
    pub qdrant: Option<QdrantSettings>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LlmSettings {
    /// API Base URL (OpenAI Compatible)
    pub api_url: String,
    /// API Key
    pub api_key: String,
    /// 默认模型
    pub default_model: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EmbeddingSettings {
    /// Embedding API URL
    pub api_url: String,
    /// Embedding API Key
    pub api_key: String,
    /// Embedding 模型
    pub model: String,
    /// 向量维度
    pub dimensions: usize,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct QdrantSettings {
    /// Qdrant URL
    pub url: String,
    /// Collection 名称
    pub collection: String,
    /// API Key（可选）
    pub api_key: Option<String>,
}

/// 连接测试结果
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ConnectionTestResult {
    pub success: bool,
    pub error: Option<String>,
    pub latency_ms: Option<u64>,
}
```

---

## 三、持久化存储

### 3.1 存储策略

| 数据 | 存储位置 | 说明 |
|------|----------|------|
| 设置元数据 | `~/.smanclaw/settings.json` | 非敏感配置 |
| API Keys | 安全文件存储 | 敏感数据（未来可集成 keyring） |

### 3.2 SettingsStore

```rust
// smanclaw-core/src/settings_store.rs

pub struct SettingsStore {
    config_dir: PathBuf,
    settings_file: PathBuf,
}

impl SettingsStore {
    pub fn new(config_dir: PathBuf) -> Result<Self>;
    pub fn load(&self) -> Result<AppSettings>;
    pub fn save(&self, settings: &AppSettings) -> Result<()>;
    fn load_secure_key(&self, service: &str) -> Result<String>;
    fn save_secure_key(&self, service: &str, key: &str) -> Result<()>;
}
```

---

## 四、Tauri 命令

| 命令 | 功能 |
|------|------|
| `get_app_settings` | 获取设置 |
| `update_app_settings` | 更新设置 |
| `test_llm_connection` | 测试 LLM API 连接 |
| `test_embedding_connection` | 测试 Embedding API 连接 |
| `test_qdrant_connection` | 测试 Qdrant 连接 |

---

## 五、ZeroclawBridge 改造

```rust
impl ZeroclawBridge {
    pub fn from_project_with_settings(
        project_path: &Path,
        settings: &AppSettings,
    ) -> Result<Self> {
        let mut config = zeroclaw::Config::default();
        config.workspace_dir = project_path.to_path_buf();

        // Configure LLM Provider (OpenAI Compatible)
        if settings.llm.is_configured() {
            config.default_provider = Some("custom".to_string());
            config.api_url = Some(settings.llm.api_url.clone());
            config.api_key = Some(settings.llm.api_key.clone());
            config.default_model = Some(settings.llm.default_model.clone());
        }

        // Configure Embedding (if provided)
        if let Some(emb) = &settings.embedding {
            if emb.is_configured() {
                config.memory.embedding_provider = format!("custom:{}", emb.api_url);
                config.memory.embedding_model = emb.model.clone();
                config.memory.embedding_dimensions = emb.dimensions;
            }
        }

        // Configure Qdrant (if provided)
        if let Some(qd) = &settings.qdrant {
            if qd.is_configured() {
                config.memory.backend = "sqlite_qdrant_hybrid".to_string();
                config.memory.qdrant.url = Some(qd.url.clone());
                config.memory.qdrant.collection = qd.collection.clone();
                config.memory.qdrant.api_key = qd.api_key.clone();
            }
        }

        Ok(Self { config })
    }
}
```

---

## 六、前端设计

### 6.1 设置页面布局

- LLM 配置区：API URL、API Key、默认模型
- 向量召回配置区（可开关）：API URL、API Key、模型、维度
- 向量库配置区（可开关）：URL、Collection、API Key
- 测试按钮：每个配置区都有测试连接功能
- 保存/取消按钮

---

## 七、文件清单

| 文件 | 操作 | 说明 |
|------|------|------|
| `smanclaw-types/src/settings.rs` | ✅ 已完成 | AppSettings 等类型 |
| `smanclaw-types/src/lib.rs` | ✅ 已完成 | 导出 settings |
| `smanclaw-core/src/settings_store.rs` | ✅ 已完成 | 设置持久化存储 |
| `smanclaw-core/src/lib.rs` | ✅ 已完成 | 导出 settings_store |
| `smanclaw-ffi/src/zeroclaw_bridge.rs` | ✅ 已完成 | 支持传入设置 |
| `smanclaw-desktop-tauri/src/state.rs` | ✅ 已完成 | 添加 settings 管理 |
| `smanclaw-desktop-tauri/src/commands.rs` | ✅ 已完成 | 添加设置命令 |
| `smanclaw-desktop-tauri/src/setup.rs` | ✅ 已完成 | 注册新命令 |
| `src/lib/types/index.ts` | ✅ 已完成 | 添加 AppSettings 类型 |
| `src/lib/api/tauri.ts` | ✅ 已完成 | 添加设置 API |
| `src/routes/settings/+page.svelte` | ✅ 已完成 | 设置页面 |
| `src/components/layout/Sidebar.svelte` | ✅ 已完成 | 添加设置入口 |

---

## 八、验收标准

- [x] 用户可在设置页面配置 LLM URL/API Key/Model
- [x] 用户可选择性配置 Embedding 和 Qdrant
- [x] 设置保存后持久化（重启应用仍保留）
- [x] API Key 安全存储
- [x] 测试连接按钮可验证各 API 有效性
- [ ] 执行任务时使用配置的 API（需实际测试）

---

## 九、实现进度

### Phase 1: 后端类型和存储 ✅

| Task | 状态 | 说明 |
|------|------|------|
| T1 | ✅ 完成 | 新增 settings.rs 类型定义 |
| T2 | ✅ 完成 | 新增 settings_store.rs 持久化存储 |
| T3 | ✅ 完成 | 修改 zeroclaw_bridge.rs 支持设置 |

### Phase 2: Tauri 命令层 ✅

| Task | 状态 | 说明 |
|------|------|------|
| T4 | ✅ 完成 | 添加设置相关 Tauri 命令 |
| T5 | ✅ 完成 | 修改 state.rs 管理 settings |

### Phase 3: 前端 ✅

| Task | 状态 | 说明 |
|------|------|------|
| T6 | ✅ 完成 | 添加前端类型定义 |
| T7 | ✅ 完成 | 添加 API 调用封装 |
| T8 | ✅ 完成 | 新增设置页面 |
| T9 | ✅ 完成 | 修改 Sidebar 添加入口 |

### Phase 4: 集成测试

| Task | 状态 | 说明 |
|------|------|------|
| T10 | ⏳ 待测试 | 测试完整流程（需配置真实 API Key） |

---

## 十、使用说明

### 运行应用

```bash
cd smanclaw-desktop/crates/smanclaw-desktop
npm install
npm run tauri dev
```

### 配置步骤

1. 启动应用后，点击侧边栏的 "Settings" 按钮
2. 在 LLM 配置区填写：
   - API URL: 例如 `https://api.openai.com/v1`
   - API Key: 你的 API Key
   - Model: 例如 `gpt-4o`
3. 点击 "Test" 按钮验证连接
4. 可选：配置 Embedding 和 Qdrant
5. 点击 "Save Settings" 保存

### 配置存储位置

- macOS: `~/Library/Application Support/smanclaw-desktop/`
- Windows: `%APPDATA%/smanclaw-desktop/`
- Linux: `~/.config/smanclaw-desktop/`
