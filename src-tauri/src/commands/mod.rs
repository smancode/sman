// src-tauri/src/commands/mod.rs
//! Minimal Tauri commands for desktop shell functionality

pub mod fs;
pub mod project;
pub mod settings;
pub mod shell;
pub mod sidecar;

pub use fs::*;
pub use project::*;
pub use settings::*;
pub use shell::*;
pub use sidecar::*;
