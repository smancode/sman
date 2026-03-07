use smanclaw_ffi::test_llm_direct;
use smanclaw_types::{
    AppSettings, ConnectionTestResult, EmbeddingSettings, LlmSettings, QdrantSettings,
};
use tauri::State;

use crate::error::TauriResult;
use crate::state::AppState;

#[tauri::command]
pub async fn get_app_settings(state: State<'_, AppState>) -> TauriResult<AppSettings> {
    let store = state.settings_store.lock().await;
    let settings = store.load()?;
    Ok(settings)
}

#[tauri::command(rename_all = "snake_case")]
pub async fn update_app_settings(
    state: State<'_, AppState>,
    settings: AppSettings,
) -> TauriResult<AppSettings> {
    let store = state.settings_store.lock().await;
    store.save(&settings)?;
    let loaded = store.load()?;
    Ok(loaded)
}

#[tauri::command(rename_all = "snake_case")]
pub async fn test_llm_connection(settings: LlmSettings) -> TauriResult<ConnectionTestResult> {
    use std::time::Instant;

    let start = Instant::now();
    let client = reqwest::Client::new();
    let url = format!(
        "{}/chat/completions",
        settings.api_url.trim_end_matches('/')
    );
    let body = serde_json::json!({
        "model": settings.default_model,
        "messages": [{"role": "user", "content": "Hello"}],
        "max_tokens": 1
    });

    let result = client
        .post(&url)
        .header("Authorization", format!("Bearer {}", settings.api_key))
        .header("Content-Type", "application/json")
        .json(&body)
        .timeout(std::time::Duration::from_secs(10))
        .send()
        .await;

    match result {
        Ok(response) => {
            let latency_ms = start.elapsed().as_millis() as u64;
            if response.status().is_success() {
                Ok(ConnectionTestResult {
                    success: true,
                    error: None,
                    latency_ms: Some(latency_ms),
                })
            } else {
                let status = response.status();
                let error_text = response.text().await.unwrap_or_default();
                Ok(ConnectionTestResult {
                    success: false,
                    error: Some(format!("HTTP {}: {}", status, error_text)),
                    latency_ms: Some(latency_ms),
                })
            }
        }
        Err(e) => Ok(ConnectionTestResult {
            success: false,
            error: Some(e.to_string()),
            latency_ms: None,
        }),
    }
}

#[tauri::command(rename_all = "snake_case")]
pub async fn test_llm_direct_chat(state: State<'_, AppState>) -> TauriResult<ConnectionTestResult> {
    use std::time::Instant;

    let start = Instant::now();
    let settings = {
        let store = state.settings_store.lock().await;
        store.load()?
    };

    if !settings.llm.is_configured() {
        return Ok(ConnectionTestResult {
            success: false,
            error: Some("LLM not configured (API key missing)".to_string()),
            latency_ms: None,
        });
    }

    match test_llm_direct(&settings).await {
        Ok(response) => {
            let latency_ms = start.elapsed().as_millis() as u64;
            eprintln!("LLM direct test succeeded: {}", response);
            Ok(ConnectionTestResult {
                success: true,
                error: None,
                latency_ms: Some(latency_ms),
            })
        }
        Err(e) => {
            eprintln!("LLM direct test failed: {}", e);
            Ok(ConnectionTestResult {
                success: false,
                error: Some(e.to_string()),
                latency_ms: None,
            })
        }
    }
}

#[tauri::command(rename_all = "snake_case")]
pub async fn test_embedding_connection(
    settings: EmbeddingSettings,
) -> TauriResult<ConnectionTestResult> {
    use std::time::Instant;

    let start = Instant::now();
    let client = reqwest::Client::new();
    let url = format!("{}/embeddings", settings.api_url.trim_end_matches('/'));
    let body = serde_json::json!({
        "model": settings.model,
        "input": "test"
    });

    let result = client
        .post(&url)
        .header("Authorization", format!("Bearer {}", settings.api_key))
        .header("Content-Type", "application/json")
        .json(&body)
        .timeout(std::time::Duration::from_secs(10))
        .send()
        .await;

    match result {
        Ok(response) => {
            let latency_ms = start.elapsed().as_millis() as u64;
            if response.status().is_success() {
                Ok(ConnectionTestResult {
                    success: true,
                    error: None,
                    latency_ms: Some(latency_ms),
                })
            } else {
                let status = response.status();
                let error_text = response.text().await.unwrap_or_default();
                Ok(ConnectionTestResult {
                    success: false,
                    error: Some(format!("HTTP {}: {}", status, error_text)),
                    latency_ms: Some(latency_ms),
                })
            }
        }
        Err(e) => Ok(ConnectionTestResult {
            success: false,
            error: Some(e.to_string()),
            latency_ms: None,
        }),
    }
}

#[tauri::command(rename_all = "snake_case")]
pub async fn test_qdrant_connection(settings: QdrantSettings) -> TauriResult<ConnectionTestResult> {
    use std::time::Instant;

    let start = Instant::now();
    let client = reqwest::Client::new();
    let url = format!(
        "{}/collections/{}",
        settings.url.trim_end_matches('/'),
        settings.collection
    );
    let mut request = client.get(&url).timeout(std::time::Duration::from_secs(10));
    if let Some(api_key) = settings.api_key {
        request = request.header("api-key", api_key);
    }
    let result = request.send().await;
    match result {
        Ok(response) => {
            let latency_ms = start.elapsed().as_millis() as u64;
            if response.status().is_success() {
                Ok(ConnectionTestResult {
                    success: true,
                    error: None,
                    latency_ms: Some(latency_ms),
                })
            } else {
                let status = response.status();
                Ok(ConnectionTestResult {
                    success: false,
                    error: Some(format!("HTTP {}", status)),
                    latency_ms: Some(latency_ms),
                })
            }
        }
        Err(e) => Ok(ConnectionTestResult {
            success: false,
            error: Some(e.to_string()),
            latency_ms: None,
        }),
    }
}
