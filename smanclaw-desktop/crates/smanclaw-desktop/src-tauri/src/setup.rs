//! Tauri application setup and initialization

use tauri::Manager;
use axum::{Router, routing::get, Json};
use std::sync::Arc;

use crate::events::{emit_chat_message, emit_send_message};

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

    // 启动 HTTP 服务器
    let app_handle = app.handle().clone();
    let app_handle_arc = Arc::new(std::sync::Mutex::new(app_handle));
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

/// Setup logging infrastructure
fn setup_logging() -> Result<(), Box<dyn std::error::Error>> {
    let filter = std::env::var("RUST_LOG").unwrap_or_else(|_| {
        "smanclaw_desktop_tauri=info,smanclaw_core=info,smanclaw_ffi=info".to_string()
    });

    tracing_subscriber::fmt()
        .with_env_filter(tracing_subscriber::EnvFilter::new(filter))
        .with_file(true)
        .with_line_number(true)
        .init();

    Ok(())
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

/// HTTP handler for sending messages
async fn send_http_message(
    app_handle: Arc<std::sync::Mutex<tauri::AppHandle>>,
    Json(payload): Json<serde_json::Value>,
) -> Json<serde_json::Value> {
    let content = payload.get("content")
        .and_then(|v: &serde_json::Value| v.as_str())
        .unwrap_or("你好")
        .to_string();

    let conversation_id = payload.get("conversation_id")
        .and_then(|v: &serde_json::Value| v.as_str())
        .unwrap_or("test")
        .to_string();

    println!("[HTTP] Received message: {} for conversation: {}", content, conversation_id);

    // Emit chat-message event to frontend
    // Use first project ID (7950de30-bd9d-4bd7-ab10-4f73eaff2c27) as default
    let project_id = "7950de30-bd9d-4bd7-ab10-4f73eaff2c27";
    if let Ok(handle) = app_handle.lock() {
        if let Err(e) = emit_chat_message(&handle, project_id, &content, "user") {
            eprintln!("[HTTP] Failed to emit chat-message event: {}", e);
            return Json(serde_json::json!({
                "success": false,
                "error": format!("Failed to emit event: {}", e)
            }));
        }
    }

    Json(serde_json::json!({
        "success": true,
        "message": "Message sent to frontend",
        "content": content,
        "conversation_id": conversation_id
    }))
}
