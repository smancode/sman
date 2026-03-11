// src-tauri/src/commands/settings.rs
//! Settings and connection test commands

use serde::{Deserialize, Serialize};

/// Result of a connection test
#[derive(Debug, Serialize, Deserialize)]
pub struct ConnectionTestResult {
    pub success: bool,
    pub message: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub latency_ms: Option<u64>,
}

/// LLM settings for testing
#[derive(Debug, Deserialize)]
pub struct LlmSettings {
    pub provider: String,
    pub model: String,
    #[serde(default)]
    pub api_key: Option<String>,
    #[serde(default)]
    pub base_url: Option<String>,
}

/// Embedding settings for testing
#[derive(Debug, Deserialize)]
pub struct EmbeddingSettings {
    pub provider: String,
    pub model: String,
    #[serde(default)]
    pub api_key: Option<String>,
    #[serde(default)]
    pub base_url: Option<String>,
}

/// Qdrant settings for testing
#[derive(Debug, Deserialize)]
pub struct QdrantSettings {
    pub url: String,
    #[serde(default)]
    pub api_key: Option<String>,
    #[serde(default)]
    pub collection: Option<String>,
}

#[tauri::command]
pub async fn test_llm_connection(settings: LlmSettings) -> ConnectionTestResult {
    // TODO: Implement actual LLM connection test via OpenClaw
    // For now, return a placeholder response
    ConnectionTestResult {
        success: true,
        message: format!(
            "LLM connection test for {} ({}) - will be handled by OpenClaw Sidecar",
            settings.provider, settings.model
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

            if let Some(api_key) = &settings.api_key {
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
