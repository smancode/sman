//! SmanClaw Desktop Tauri Backend
//!
//! This crate provides the Tauri backend for the SmanClaw desktop application.

pub mod commands;
pub mod error;
pub mod events;
pub(crate) mod orchestration;
pub mod setup;
pub mod state;

pub use commands::*;
pub use error::*;
pub use events::*;
pub use setup::*;
pub use state::*;
