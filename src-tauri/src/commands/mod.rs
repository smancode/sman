// src-tauri/src/commands/mod.rs
//! Minimal Tauri commands for desktop shell functionality

pub mod fs;
pub mod shell;
pub mod sidecar;

pub use fs::*;
pub use shell::*;
pub use sidecar::*;
