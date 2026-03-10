//! Tauri application setup and initialization

use tauri::Manager;
use axum::{Router, routing::get, Json};
use std::path::PathBuf;
use std::sync::Arc;
use tracing_subscriber::fmt::time::FormatTime;

use crate::commands::chat_execution;
use crate::events::emit_chat_message;

use crate::state::{default_config_dir, AppState};

/// Setup the Tauri application
pub fn setup_app(app: &mut tauri::App) -> Result<(), Box<dyn std::error::Error>> {
    // Initialize logging
    setup_logging()?;

    // Initialize application state
    let config_dir = default_config_dir();
    tracing::info!("Using config directory: {:?}", config_dir);

    let state = AppState::new(config_dir)?;
    app.manage(state);
    configure_packaged_claudecode_runtime();

    // 启动 HTTP 服务器
    let app_handle = app.handle().clone();
    let app_handle_arc = Arc::new(tokio::sync::Mutex::new(app_handle));
    std::thread::spawn(move || {
        println!("[HTTP Server] Starting HTTP server thread...");
        let rt = tokio::runtime::Runtime::new().unwrap();
        rt.block_on(async {
            println!("[HTTP Server] Tokio runtime created");
            let app_handle = app_handle_arc.clone();
            let router = Router::new()
                .route("/send", get(|| async {
                    "Use POST with JSON body: {\"content\": \"你好\", \"conversation_id\": \"conv-id\"}"
                }))
                .route("/send", axum::routing::post({
                    let app_handle = app_handle_arc.clone();
                    move |payload: Json<serde_json::Value>| async move {
                        send_http_message(app_handle, payload).await
                    }
                }));

            let addr = "127.0.0.1:18080";
            println!("[HTTP Server] Attempting to bind to {}", addr);
            match tokio::net::TcpListener::bind(addr).await {
                Ok(listener) => {
                    println!("[HTTP Server] Successfully bound to {}", addr);
                    println!("[HTTP Server] Starting axum server...");
                    if let Err(e) = axum::serve(listener, router.into_make_service()).await {
                        eprintln!("[HTTP Server] Error: {}", e);
                    }
                }
                Err(e) => {
                    eprintln!("[HTTP Server] Failed to bind to {}: {}", addr, e);
                }
            }
        });
    });

    tracing::info!("SmanClaw Desktop initialized successfully");

    Ok(())
}

fn configure_packaged_claudecode_runtime() {
    if std::env::var("SMANCLAW_CLAUDE_CODE_CMD")
        .ok()
        .map(|value| !value.trim().is_empty())
        .unwrap_or(false)
    {
        return;
    }
    if let Some(path) = discover_packaged_claudecode_binary() {
        if let Some(path_str) = path.to_str() {
            std::env::set_var("SMANCLAW_CLAUDE_CODE_CMD", path_str);
            tracing::info!("Configured packaged ClaudeCode binary: {}", path.display());
        }
    }
}

fn discover_packaged_claudecode_binary() -> Option<PathBuf> {
    let current_exe = std::env::current_exe().ok()?;
    let exe_dir = current_exe.parent()?;
    let mut candidates = vec![
        exe_dir.join("claudecode"),
        exe_dir.join("claudecode-cli"),
        exe_dir.join("claude"),
        exe_dir.join("../Resources/claudecode"),
        exe_dir.join("../Resources/claudecode-cli"),
        exe_dir.join("../Resources/claude"),
    ];
    if let Ok(bundle_path) = std::env::var("SMANCLAW_BUNDLED_CLAUDE_PATH") {
        let custom = bundle_path.trim();
        if !custom.is_empty() {
            candidates.insert(0, PathBuf::from(custom));
        }
    }
    for candidate in candidates {
        if candidate.exists() {
            return std::fs::canonicalize(candidate).ok();
        }
    }
    None
}

/// Setup logging infrastructure
fn setup_logging() -> Result<(), Box<dyn std::error::Error>> {
    let filter = std::env::var("RUST_LOG").unwrap_or_else(|_| {
        "smanclaw_desktop_tauri=info,smanclaw_core=info,smanclaw_ffi=info".to_string()
    });

    tracing_subscriber::fmt()
        .with_env_filter(tracing_subscriber::EnvFilter::new(filter))
        .with_timer(East8Timer)
        .with_file(true)
        .with_line_number(true)
        .init();

    Ok(())
}

struct East8Timer;

impl FormatTime for East8Timer {
    fn format_time(
        &self,
        w: &mut tracing_subscriber::fmt::format::Writer<'_>,
    ) -> std::fmt::Result {
        write!(w, "{}", chrono::Local::now().format("%Y-%m-%dT%H:%M:%S%.3f%:z"))
    }
}

/// Create the Tauri app builder with all configurations
pub fn create_app_builder() -> tauri::Builder<tauri::Wry> {
    tauri::Builder::default()
        .setup(|app| {
            setup_app(app)?;

            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            // Project commands
            crate::commands::project_commands::get_projects,
            crate::commands::project_commands::get_project_skills,
            crate::commands::project_commands::add_project,
            crate::commands::project_commands::remove_project,
            crate::commands::project_commands::get_project_config,
            crate::commands::project_commands::update_project_config,
            // Task commands
            crate::commands::task_commands::execute_task,
            crate::commands::task_commands::get_task,
            crate::commands::task_commands::list_tasks,
            crate::commands::task_commands::cancel_task,
            // Orchestration commands
            crate::orchestration::api::execute_orchestrated_task,
            crate::orchestration::status::get_task_dag,
            crate::orchestration::status::get_orchestration_status,
            // Conversation commands
            crate::commands::conversation_commands::get_conversation,
            crate::commands::conversation_commands::get_conversation_messages,
            crate::commands::conversation_commands::list_conversations,
            crate::commands::conversation_commands::create_conversation,
            crate::commands::conversation_commands::send_message,
            crate::commands::conversation_commands::decide_message_route,
            // Settings commands
            crate::commands::settings_commands::get_app_settings,
            crate::commands::settings_commands::update_app_settings,
            crate::commands::settings_commands::test_llm_connection,
            crate::commands::settings_commands::test_llm_direct_chat,
            crate::commands::settings_commands::test_embedding_connection,
            crate::commands::settings_commands::test_qdrant_connection,
            // Utility commands
            crate::commands::utility_commands::get_version,
            crate::commands::utility_commands::path_exists,
            crate::commands::utility_commands::select_folder,
        ])
        .plugin(tauri_plugin_shell::init())
        .plugin(tauri_plugin_fs::init())
        .plugin(tauri_plugin_dialog::init())
}

/// Initialize plugin permissions
pub fn init_permissions() {
    // Permissions are now handled via capabilities in Tauri 2.x
    // See: src-tauri/capabilities/
}

/// HTTP handler for sending messages - directly calls skill execution
/// Uses the project ID and conversation ID from the request
async fn send_http_message(
    app_handle: Arc<tokio::sync::Mutex<tauri::AppHandle>>,
    Json(payload): Json<serde_json::Value>,
) -> Json<serde_json::Value> {
    let content = payload.get("content")
        .and_then(|v: &serde_json::Value| v.as_str())
        .unwrap_or("你好")
        .to_string();

    let conversation_id = payload.get("conversation_id")
        .and_then(|v: &serde_json::Value| v.as_str())
        .unwrap_or("default")
        .to_string();

    // Get project_id from payload, or use default project
    let project_id = payload.get("project_id")
        .and_then(|v: &serde_json::Value| v.as_str())
        .unwrap_or("7950de30-bd9d-4bd7-ab10-4f73eaff2c27");

    println!("[HTTP] Received message: {} for conversation: {}, project: {}", content, conversation_id, project_id);

    // Get app handle with async lock
    let handle = app_handle.lock().await;

    // Emit user message event to frontend
    let _ = emit_chat_message(&handle, project_id, &content, "user");

    // Get state from app handle
    let state = handle.state::<AppState>();

    // Call send_message to execute skill or normal conversation
    let result = chat_execution::send_message(
        handle.clone(),
        state.clone(),
        conversation_id.clone(),
        content.clone(),
    ).await;

    match result {
        Ok(response) => {
            // Emit assistant response event to frontend
            let _ = emit_chat_message(&handle, project_id, &response.content, "assistant");

            let response_preview = if response.content.len() > 200 {
                format!("{}...", &response.content[..200])
            } else {
                response.content.clone()
            };
            println!("[HTTP] Skill execution completed: {}", response_preview);
            Json(serde_json::json!({
                "success": true,
                "message": "Message processed",
                "content": content,
                "conversation_id": conversation_id,
                "response": response.content
            }))
        }
        Err(e) => {
            eprintln!("[HTTP] Failed to execute message: {}", e);
            // Emit error message to frontend
            let error_msg = format!("Error: {}", e);
            let _ = emit_chat_message(&handle, project_id, &error_msg, "assistant");

            Json(serde_json::json!({
                "success": false,
                "error": format!("Failed to execute message: {}", e)
            }))
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn discover_packaged_binary_respects_custom_env_path() {
        let binary_path = std::env::temp_dir().join(format!("claudecode-{}", uuid::Uuid::new_v4()));
        std::fs::write(&binary_path, "placeholder").expect("write placeholder");
        std::env::set_var(
            "SMANCLAW_BUNDLED_CLAUDE_PATH",
            binary_path.to_string_lossy().to_string(),
        );
        let discovered = discover_packaged_claudecode_binary();
        std::env::remove_var("SMANCLAW_BUNDLED_CLAUDE_PATH");
        let _ = std::fs::remove_file(&binary_path);
        assert!(discovered.is_some());
    }
}
