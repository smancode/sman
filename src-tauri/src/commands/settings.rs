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
                apiUrl: "https://open.bigmodel.cn/api/coding/paas/v4".to_string(),
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
    // TODO: Implement actual LLM connection test via OpenClaw
    // For now, return a placeholder response
    ConnectionTestResult {
        success: true,
        message: format!(
            "LLM connection test for {} ({}) - will be handled by OpenClaw Sidecar",
            settings.apiUrl, settings.defaultModel
        ),
        latency_ms: None,
    }
}

#[tauri::command]
pub async fn test_llm_direct_chat() -> ConnectionTestResult {
    // TODO: Implement direct chat test via OpenClaw
    ConnectionTestResult {
        success: true,
        message: "Direct chat test - will be handled by OpenClaw Sidecar".to_string(),
        latency_ms: None,
    }
}

#[tauri::command]
pub async fn test_embedding_connection(settings: EmbeddingSettings) -> ConnectionTestResult {
    // TODO: Implement actual embedding connection test via OpenClaw
    ConnectionTestResult {
        success: true,
        message: format!(
            "Embedding connection test for {} ({}) - will be handled by OpenClaw Sidecar",
            settings.provider, settings.model
        ),
        latency_ms: None,
    }
}

#[tauri::command]
pub async fn test_qdrant_connection(settings: QdrantSettings) -> ConnectionTestResult {
    use std::time::Instant;

    let start = Instant::now();

    // Simple HTTP health check to Qdrant
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
