//! SmanClaw Desktop App - Core Business Logic
//!
//! This crate contains the core business logic, independent of UI.

pub mod config;
pub mod error;
pub mod history_store;
pub mod project_manager;
pub mod settings_store;
pub mod task_manager;

pub use config::*;
pub use error::*;
pub use history_store::*;
pub use project_manager::*;
pub use settings_store::*;
pub use task_manager::*;
