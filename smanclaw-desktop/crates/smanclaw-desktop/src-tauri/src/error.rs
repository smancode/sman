//! Error types for Tauri commands

use serde::Serialize;
use thiserror::Error;

/// Tauri command error type
#[derive(Debug, Error)]
pub enum TauriError {
    /// Core error
    #[error("Core error: {0}")]
    Core(#[from] smanclaw_core::CoreError),

    /// IO error
    #[error("IO error: {0}")]
    Io(#[from] std::io::Error),

    /// Serialization error
    #[error("Serialization error: {0}")]
    Serialization(#[from] serde_json::Error),

    /// Project not found
    #[error("Project not found: {0}")]
    ProjectNotFound(String),

    /// Task not found
    #[error("Task not found: {0}")]
    TaskNotFound(String),

    /// Conversation not found
    #[error("Conversation not found: {0}")]
    ConversationNotFound(String),

    /// Invalid input
    #[error("Invalid input: {0}")]
    InvalidInput(String),

    /// Task execution error
    #[error("Task execution error: {0}")]
    TaskExecution(String),

    /// Internal error
    #[error("Internal error: {0}")]
    Internal(String),
}

impl Serialize for TauriError {
    fn serialize<S>(&self, serializer: S) -> Result<S::Ok, S::Error>
    where
        S: serde::Serializer,
    {
        serializer.serialize_str(&self.to_string())
    }
}

/// Result type alias for Tauri commands
pub type TauriResult<T> = Result<T, TauriError>;

impl From<anyhow::Error> for TauriError {
    fn from(err: anyhow::Error) -> Self {
        TauriError::Internal(err.to_string())
    }
}

impl From<tauri::Error> for TauriError {
    fn from(err: tauri::Error) -> Self {
        TauriError::Internal(err.to_string())
    }
}
