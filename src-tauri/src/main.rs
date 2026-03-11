//! SmanClaw Desktop - Tauri Application Entry Point
//!
//! This is the main entry point for the SmanClaw desktop application.

#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use smanclaw_desktop_tauri::create_app_builder;

fn main() {
    create_app_builder()
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
