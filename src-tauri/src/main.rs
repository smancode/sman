//! SMAN Desktop - Tauri Application Entry Point
//!
//! This is the main entry point for the SMAN desktop application.

#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

fn main() {
    sman::run()
}
