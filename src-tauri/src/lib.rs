//! SMAN Desktop - Minimal Tauri Shell
//!
//! This crate provides the minimal desktop shell for SMAN.
//! All context management and AI logic is handled by:
//! - SMAN Core (TypeScript) - Context manager
//! - OpenClaw Sidecar - AI executor

pub mod commands;

pub use commands::*;

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_shell::init())
        .plugin(tauri_plugin_dialog::init())
        .setup(|app| {
            let app_handle = app.handle().clone();
            tauri::async_runtime::spawn(async move {
                if let Err(e) = commands::sidecar::start_openclaw_server(app_handle).await {
                    eprintln!("[App] Auto start OpenClaw failed: {}", e);
                } else {
                    println!("[App] OpenClaw auto started");
                }
            });
            Ok(())
        })
        .on_window_event(|window, event| {
            if let tauri::WindowEvent::CloseRequested { .. } = event {
                // Stop OpenClaw server when window is closed
                println!("[App] Window closing, stopping OpenClaw server...");
                if let Err(e) = commands::sidecar::stop_openclaw_server_sync() {
                    eprintln!("[App] Failed to stop OpenClaw: {}", e);
                }
            }
        })
        .invoke_handler(tauri::generate_handler![
            // Sidecar
            commands::sidecar::start_openclaw_server,
            commands::sidecar::stop_openclaw_server,
            commands::sidecar::check_openclaw_health,
            commands::sidecar::is_server_running,
            commands::sidecar::get_sman_local_path,
            commands::sidecar::get_openclaw_port,
            commands::sidecar::get_gateway_token,
            // Shell
            commands::shell::minimize_window,
            commands::shell::maximize_window,
            commands::shell::close_window,
            commands::shell::show_in_finder,
            commands::shell::get_home_dir,
            commands::shell::get_app_data_dir,
            // FS
            commands::fs::read_text_file,
            commands::fs::write_text_file,
            commands::fs::list_directory,
            commands::fs::file_exists,
            commands::fs::create_directory,
            commands::fs::delete_file,
            commands::fs::append_to_file,
            // Settings
            commands::settings::get_app_settings,
            commands::settings::update_app_settings,
            commands::settings::save_settings_and_sync,
            commands::settings::is_llm_configured,
            commands::settings::test_llm_connection,
            commands::settings::test_llm_direct_chat,
            commands::settings::test_embedding_connection,
            commands::settings::test_qdrant_connection,
            // Project
            commands::project::get_projects,
            commands::project::add_project,
            commands::project::remove_project,
            commands::project::get_project_skills,
            // Conversation
            commands::conversation::list_conversations,
            commands::conversation::create_conversation,
            commands::conversation::get_conversation_messages,
            commands::conversation::send_message,
            commands::conversation::decide_message_route,
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
