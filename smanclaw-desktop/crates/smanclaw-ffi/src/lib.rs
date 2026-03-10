//! SmanClaw Desktop App - FFI Bridge to ZeroClaw
//!
//! This crate provides the bridge between the desktop app and ZeroClaw,
//! including multi-agent orchestration capabilities.

pub mod orchestration_bridge;
pub mod progress_stream;
pub mod step_executor_factory;
pub mod task_executor;
pub mod claude_code_step_executor;
pub mod zeroclaw_bridge;
pub mod zeroclaw_step_executor;

pub use orchestration_bridge::*;
pub use claude_code_step_executor::*;
pub use progress_stream::*;
pub use step_executor_factory::*;
pub use task_executor::*;
pub use zeroclaw_bridge::*;
pub use zeroclaw_step_executor::*;
