//! SmanClaw Desktop App - Core Business Logic
//!
//! This crate contains the core business logic, independent of UI.

pub mod acceptance_evaluator;
pub mod agents_generator;
pub mod config;
pub mod error;
pub mod experience_sink;
pub mod history_store;
pub mod identity;
pub mod llm_client;
pub mod main_task;
pub mod orchestrator;
pub mod project_explorer;
pub mod project_manager;
pub mod runtime;
pub mod settings_store;
pub mod skill_store;
pub mod sub_claw_executor;
pub mod task_generator;
pub mod task_manager;
pub mod task_poller;
pub mod user_experience;

pub use acceptance_evaluator::*;
pub use agents_generator::*;
pub use config::*;
pub use error::*;
pub use experience_sink::*;
pub use history_store::*;
pub use identity::*;
pub use llm_client::*;
pub use main_task::*;
pub use orchestrator::*;
pub use project_explorer::*;
pub use project_manager::*;
pub use runtime::*;
pub use settings_store::*;
pub use skill_store::*;
pub use sub_claw_executor::*;
pub use task_generator::*;
pub use task_manager::*;
pub use task_poller::*;
pub use user_experience::*;
