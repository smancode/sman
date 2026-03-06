//! Tauri application setup and initialization

use tauri::Manager;

use crate::error::TauriError;
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
            crate::commands::get_projects,
            crate::commands::add_project,
            crate::commands::remove_project,
            crate::commands::get_project_config,
            crate::commands::update_project_config,
            // Task commands
            crate::commands::execute_task,
            crate::commands::get_task,
            crate::commands::list_tasks,
            crate::commands::cancel_task,
            // Orchestration commands
            crate::commands::execute_orchestrated_task,
            crate::commands::get_task_dag,
            crate::commands::get_orchestration_status,
            // Conversation commands
            crate::commands::get_conversation,
            crate::commands::get_conversation_messages,
            crate::commands::list_conversations,
            crate::commands::create_conversation,
            crate::commands::send_message,
            // Settings commands
            crate::commands::get_app_settings,
            crate::commands::update_app_settings,
            crate::commands::test_llm_connection,
            crate::commands::test_llm_direct_chat,
            crate::commands::test_embedding_connection,
            crate::commands::test_qdrant_connection,
            // Utility commands
            crate::commands::get_version,
            crate::commands::path_exists,
            crate::commands::select_folder,
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
