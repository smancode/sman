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
        .invoke_handler(tauri::generate_handler![
            // Sidecar
            commands::sidecar::start_openclaw_server,
            commands::sidecar::stop_openclaw_server,
            commands::sidecar::check_openclaw_health,
            commands::sidecar::is_server_running,
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
            commands::settings::test_llm_connection,
            commands::settings::test_llm_direct_chat,
            commands::settings::test_embedding_connection,
            commands::settings::test_qdrant_connection,
            // Project
            commands::project::get_projects,
            commands::project::add_project,
            commands::project::remove_project,
            commands::project::get_project_skills,
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
