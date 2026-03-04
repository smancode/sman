//! SmanClaw Desktop App - FFI Bridge to ZeroClaw
//!
//! This crate provides the bridge between the desktop app and ZeroClaw,
//! including multi-agent orchestration capabilities.

pub mod orchestration_bridge;
pub mod progress_stream;
pub mod task_executor;
pub mod zeroclaw_bridge;

pub use orchestration_bridge::*;
pub use progress_stream::*;
pub use task_executor::*;
pub use zeroclaw_bridge::*;
