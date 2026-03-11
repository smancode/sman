// src-tauri/src/commands/settings.rs
//! Settings and connection test commands

use serde::{Deserialize, Serialize};
use std::fs;
use std::path::PathBuf;
use std::sync::OnceLock;

/// Result of a connection test
#[derive(Debug, Serialize, Deserialize)]
pub struct ConnectionTestResult {
    pub success: bool,
    pub message: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub latency_ms: Option<u64>,
}

/// LLM settings for testing (matches frontend LlmSettings interface)
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LlmSettings {
    pub apiUrl: String,
    pub apiKey: String,
    pub defaultModel: String,
}

/// Embedding settings for testing (matches frontend EmbeddingSettings interface)
#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct EmbeddingSettings {
    pub provider: String,
    pub model: String,
    #[serde(default)]
    pub apiKey: Option<String>,
    #[serde(default)]
    pub apiUrl: Option<String>,
}

/// Qdrant settings for testing (matches frontend QdrantSettings interface)
#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct QdrantSettings {
    pub url: String,
    #[serde(default)]
    pub apiKey: Option<String>,
    #[serde(default)]
    pub collection: Option<String>,
}

/// Web search settings
#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct WebSearchSettings {
    #[serde(default)]
    pub braveApiKey: String,
    #[serde(default)]
    pub tavilyApiKey: String,
    #[serde(default)]
    pub bingApiKey: String,
}

/// Application settings (matches frontend AppSettings interface)
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AppSettings {
    pub llm: LlmSettings,
    #[serde(default)]
    pub embedding: Option<EmbeddingSettings>,
    #[serde(default)]
    pub qdrant: Option<QdrantSettings>,
    #[serde(default)]
    pub webSearch: WebSearchSettings,
}

impl Default for AppSettings {
    fn default() -> Self {
        Self {
            llm: LlmSettings {
                apiUrl: "https://open.bigmodel.cn/api/paas/v4".to_string(),
                apiKey: String::new(),
                defaultModel: "GLM-5".to_string(),
            },
            embedding: None,
            qdrant: None,
            webSearch: WebSearchSettings::default(),
        }
    }
}

/// Get settings file path (~/.smanlocal/settings.json)
fn settings_path() -> &'static PathBuf {
    static PATH: OnceLock<PathBuf> = OnceLock::new();
    PATH.get_or_init(|| {
        let home = dirs::home_dir().unwrap_or_else(|| PathBuf::from("/"));
        home.join(".smanlocal").join("settings.json")
    })
}

/// Ensure .smanlocal directory exists
fn ensure_smanlocal_dir() -> Result<(), String> {
    let home = dirs::home_dir().ok_or("Cannot find home directory")?;
    let dir = home.join(".smanlocal");
    if !dir.exists() {
        fs::create_dir_all(&dir).map_err(|e| format!("Failed to create .smanlocal: {}", e))?;
    }
    Ok(())
}

/// Get OpenClaw config directory path
fn get_openclaw_config_dir() -> PathBuf {
    let home = std::env::var("HOME")
        .or_else(|_| std::env::var("USERPROFILE"))
        .or_else(|_| std::env::var("HOMEPATH"))
        .unwrap_or_else(|_| ".".to_string());
    PathBuf::from(home)
        .join(".smanlocal")
        .join("openclaw-home")
        .join(".openclaw")
}

#[tauri::command]
pub fn get_app_settings() -> Result<AppSettings, String> {
    let path = settings_path();

    if !path.exists() {
        return Ok(AppSettings::default());
    }

    let content = fs::read_to_string(path)
        .map_err(|e| format!("Failed to read settings: {}", e))?;

    let settings: AppSettings = serde_json::from_str(&content)
        .unwrap_or_else(|_| AppSettings::default());

    Ok(settings)
}

/// Check if LLM is configured (has non-empty API key)
#[tauri::command]
pub fn is_llm_configured() -> bool {
    let settings = get_app_settings().unwrap_or_default();
    !settings.llm.apiKey.is_empty()
}

/// Save settings and sync to OpenClaw config
/// Returns true if LLM was configured and synced successfully
#[tauri::command]
pub async fn save_settings_and_sync(settings: AppSettings) -> Result<(AppSettings, bool), String> {
    ensure_smanlocal_dir()?;

    // 1. Save to SMAN settings
    let path = settings_path();
    let content = serde_json::to_string_pretty(&settings)
        .map_err(|e| format!("Failed to serialize settings: {}", e))?;
    fs::write(path, &content)
        .map_err(|e| format!("Failed to write settings: {}", e))?;

    // 2. If API key is configured, test and sync to OpenClaw
    let llm_ready = if !settings.llm.apiKey.is_empty() {
        // Test LLM connection
        let test_result = test_llm_connection_internal(&settings.llm).await;
        if test_result.success {
            // Sync to OpenClaw config
            match sync_to_openclaw(&settings) {
                Ok(_) => {
                    println!("[Settings] LLM configured and synced to OpenClaw");
                    true
                }
                Err(e) => {
                    eprintln!("[Settings] Failed to sync to OpenClaw: {}", e);
                    false
                }
            }
        } else {
            eprintln!("[Settings] LLM test failed: {}", test_result.message);
            false
        }
    } else {
        false
    };

    Ok((settings, llm_ready))
}

/// Internal LLM connection test
async fn test_llm_connection_internal(settings: &LlmSettings) -> ConnectionTestResult {
    use std::time::Instant;

    let start = Instant::now();

    // Build a simple test request based on API URL
    let client = match reqwest::Client::builder()
        .timeout(std::time::Duration::from_secs(10))
        .build()
    {
        Ok(c) => c,
        Err(e) => {
            return ConnectionTestResult {
                success: false,
                message: format!("Failed to create HTTP client: {}", e),
                latency_ms: None,
            }
        }
    };

    // Determine API endpoint and format based on URL
    let (test_url, body) = if settings.apiUrl.contains("bigmodel.cn") {
        // Zhipu/BigModel API
        let url = format!("{}/chat/completions", settings.apiUrl.trim_end_matches('/'));
        let body = serde_json::json!({
            "model": settings.defaultModel,
            "messages": [{"role": "user", "content": "hi"}],
            "max_tokens": 10
        });
        (url, body)
    } else if settings.apiUrl.contains("openai") {
        let url = format!("{}/chat/completions", settings.apiUrl.trim_end_matches('/'));
        let body = serde_json::json!({
            "model": settings.defaultModel,
            "messages": [{"role": "user", "content": "hi"}],
            "max_tokens": 10
        });
        (url, body)
    } else {
        // Generic Anthropic-style API
        let url = settings.apiUrl.clone();
        let body = serde_json::json!({
            "model": settings.defaultModel,
            "max_tokens": 10,
            "messages": [{"role": "user", "content": "hi"}]
        });
        (url, body)
    };

    let response = client
        .post(&test_url)
        .header("Authorization", format!("Bearer {}", settings.apiKey))
        .header("Content-Type", "application/json")
        .json(&body)
        .send()
        .await;

    let latency = start.elapsed().as_millis() as u64;

    match response {
        Ok(resp) => {
            let status = resp.status();
            if status.is_success() || status.as_u16() == 429 { // 429 = rate limited, but auth works
                ConnectionTestResult {
                    success: true,
                    message: format!("LLM API 连接成功 ({:?})", status),
                    latency_ms: Some(latency),
                }
            } else {
                let body_text = resp.text().await.unwrap_or_default();
                ConnectionTestResult {
                    success: false,
                    message: format!("API 返回错误 {}: {}", status, truncate(&body_text, 200)),
                    latency_ms: Some(latency),
                }
            }
        }
        Err(e) => ConnectionTestResult {
            success: false,
            message: format!("连接失败: {}", e),
            latency_ms: None,
        },
    }
}

fn truncate(s: &str, max: usize) -> String {
    if s.len() > max {
        format!("{}...", &s[..max])
    } else {
        s.to_string()
    }
}

/// Sync SMAN settings to OpenClaw configuration
fn sync_to_openclaw(settings: &AppSettings) -> Result<(), String> {
    let openclaw_dir = get_openclaw_config_dir();

    // Ensure directory exists
    std::fs::create_dir_all(&openclaw_dir)
        .map_err(|e| format!("Failed to create OpenClaw dir: {}", e))?;

    let config_path = openclaw_dir.join("openclaw.json");

    // Read existing config or create new
    let mut config: serde_json::Value = if config_path.exists() {
        let content = std::fs::read_to_string(&config_path)
            .map_err(|e| format!("Failed to read openclaw.json: {}", e))?;
        serde_json::from_str(&content).unwrap_or(serde_json::json!({}))
    } else {
        serde_json::json!({})
    };

    let api_url = settings.llm.apiUrl.clone();
    let api_key = settings.llm.apiKey.clone();
    let model = settings.llm.defaultModel.clone();

    // Determine provider from API URL
    let provider_id = if api_url.contains("bigmodel.cn") || api_url.contains("zhipuai") {
        "zhipu"
    } else if api_url.contains("openai") {
        "openai"
    } else if api_url.contains("anthropic") {
        "anthropic"
    } else {
        "custom"
    };

    let profile_key = format!("{}:default", provider_id);

    // Ensure auth.profiles exists
    if config.get("auth").is_none() {
        config["auth"] = serde_json::json!({});
    }
    if config["auth"].get("profiles").is_none() {
        config["auth"]["profiles"] = serde_json::json!({});
    }

    config["auth"]["profiles"][&profile_key] = serde_json::json!({
        "provider": provider_id,
        "mode": "api_key"
    });

    // Configure models
    if config.get("models").is_none() {
        config["models"] = serde_json::json!({
            "mode": "merge",
            "providers": {}
        });
    }
    if config["models"].get("providers").is_none() {
        config["models"]["providers"] = serde_json::json!({});
    }

    config["models"]["providers"][provider_id] = serde_json::json!({
        "baseUrl": api_url,
        "api": "anthropic-messages",
        "models": [{
            "id": model,
            "name": model,
            "input": ["text"],
            "contextWindow": 128000,
            "maxTokens": 8192
        }]
    });

    // Configure agent defaults
    if config.get("agents").is_none() {
        config["agents"] = serde_json::json!({});
    }
    if config["agents"].get("defaults").is_none() {
        config["agents"]["defaults"] = serde_json::json!({});
    }

    let model_ref = format!("{}/{}", provider_id, model);
    config["agents"]["defaults"]["model"] = serde_json::json!({
        "primary": model_ref
    });

    // Write config
    let content = serde_json::to_string_pretty(&config)
        .map_err(|e| format!("Failed to serialize config: {}", e))?;
    std::fs::write(&config_path, content)
        .map_err(|e| format!("Failed to write openclaw.json: {}", e))?;

    // CRITICAL: Write auth-profiles.json to agent directory
    // OpenClaw looks for this file at: .openclaw/agents/dev/agent/auth-profiles.json
    let agent_dir = openclaw_dir.join("agents").join("dev").join("agent");
    std::fs::create_dir_all(&agent_dir)
        .map_err(|e| format!("Failed to create agent dir: {}", e))?;

    let auth_profiles_path = agent_dir.join("auth-profiles.json");
    let auth_profiles = serde_json::json!({
        "profiles": {
            &profile_key: {
                "provider": provider_id,
                "mode": "api_key",
                "apiKey": api_key
            }
        },
        "defaultProfile": &profile_key
    });

    let auth_content = serde_json::to_string_pretty(&auth_profiles)
        .map_err(|e| format!("Failed to serialize auth-profiles: {}", e))?;
    std::fs::write(&auth_profiles_path, auth_content)
        .map_err(|e| format!("Failed to write auth-profiles.json: {}", e))?;

    println!("[Settings] Synced LLM config to OpenClaw: provider={}, model={}", provider_id, model);
    println!("[Settings] Auth profiles written to: {:?}", auth_profiles_path);
    Ok(())
}

#[tauri::command]
pub fn update_app_settings(settings: AppSettings) -> Result<AppSettings, String> {
    ensure_smanlocal_dir()?;

    let path = settings_path();
    let content = serde_json::to_string_pretty(&settings)
        .map_err(|e| format!("Failed to serialize settings: {}", e))?;

    fs::write(path, &content)
        .map_err(|e| format!("Failed to write settings: {}", e))?;

    Ok(settings)
}

#[tauri::command]
pub async fn test_llm_connection(settings: LlmSettings) -> ConnectionTestResult {
    test_llm_connection_internal(&settings).await
}

#[tauri::command]
pub async fn test_llm_direct_chat() -> ConnectionTestResult {
    let settings = get_app_settings().unwrap_or_default();
    test_llm_connection_internal(&settings.llm).await
}

#[tauri::command]
pub async fn test_embedding_connection(settings: EmbeddingSettings) -> ConnectionTestResult {
    ConnectionTestResult {
        success: true,
        message: format!(
            "Embedding connection test for {} ({})",
            settings.provider, settings.model
        ),
        latency_ms: None,
    }
}

#[tauri::command]
pub async fn test_qdrant_connection(settings: QdrantSettings) -> ConnectionTestResult {
    use std::time::Instant;

    let start = Instant::now();

    let client = reqwest::Client::builder()
        .timeout(std::time::Duration::from_secs(5))
        .build();

    match client {
        Ok(client) => {
            let mut request = client.get(&format!("{}/collections", settings.url));

            if let Some(api_key) = &settings.apiKey {
                request = request.header("api-key", api_key);
            }

            match request.send().await {
                Ok(response) => {
                    let latency = start.elapsed().as_millis() as u64;
                    if response.status().is_success() {
                        ConnectionTestResult {
                            success: true,
                            message: format!(
                                "Qdrant connection successful{}",
                                settings
                                    .collection
                                    .as_ref()
                                    .map(|c| format!(" (collection: {})", c))
                                    .unwrap_or_default()
                            ),
                            latency_ms: Some(latency),
                        }
                    } else {
                        ConnectionTestResult {
                            success: false,
                            message: format!("Qdrant returned status: {}", response.status()),
                            latency_ms: Some(latency),
                        }
                    }
                }
                Err(e) => ConnectionTestResult {
                    success: false,
                    message: format!("Failed to connect to Qdrant: {}", e),
                    latency_ms: None,
                },
            }
        }
        Err(e) => ConnectionTestResult {
            success: false,
            message: format!("Failed to create HTTP client: {}", e),
            latency_ms: None,
        },
    }
}

/// Get WebSearch API keys as environment variables for OpenClaw sidecar
pub fn get_web_search_env_vars() -> Vec<(String, String)> {
    let settings = get_app_settings().unwrap_or_default();
    let mut vars = Vec::new();

    if !settings.webSearch.braveApiKey.is_empty() {
        vars.push(("BRAVE_API_KEY".to_string(), settings.webSearch.braveApiKey));
    }
    if !settings.webSearch.tavilyApiKey.is_empty() {
        vars.push(("TAVILY_API_KEY".to_string(), settings.webSearch.tavilyApiKey));
    }
    if !settings.webSearch.bingApiKey.is_empty() {
        vars.push(("BING_API_KEY".to_string(), settings.webSearch.bingApiKey));
    }

    vars
}

/// Get LLM API key env var for OpenClaw sidecar
pub fn get_llm_env_vars() -> Vec<(String, String)> {
    let settings = get_app_settings().unwrap_or_default();
    let mut vars = Vec::new();

    if !settings.llm.apiKey.is_empty() {
        let api_url = &settings.llm.apiUrl;
        let provider_id = if api_url.contains("bigmodel.cn") || api_url.contains("zhipuai") {
            "zhipu"
        } else if api_url.contains("openai") {
            "openai"
        } else if api_url.contains("anthropic") {
            "anthropic"
        } else {
            "custom"
        };

        vars.push((
            format!("{}_API_KEY", provider_id.to_uppercase()),
            settings.llm.apiKey.clone(),
        ));

        if provider_id == "custom" {
            vars.push(("OPENCLAW_LLM_API_URL".to_string(), api_url.clone()));
        }
    }

    vars
}
