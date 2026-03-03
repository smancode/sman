//! Error types for smanclaw-core

use thiserror::Error;

/// Core error type
#[derive(Debug, Error)]
pub enum CoreError {
    /// Database error
    #[error("Database error: {0}")]
    Database(#[from] rusqlite::Error),

    /// IO error
    #[error("IO error: {0}")]
    Io(#[from] std::io::Error),

    /// Task not found
    #[error("Task not found: {0}")]
    TaskNotFound(String),

    /// Project not found
    #[error("Project not found: {0}")]
    ProjectNotFound(String),

    /// Conversation not found
    #[error("Conversation not found: {0}")]
    ConversationNotFound(String),

    /// Invalid input
    #[error("Invalid input: {0}")]
    InvalidInput(String),

    /// Serialization error
    #[error("Serialization error: {0}")]
    Serialization(#[from] serde_json::Error),
}

/// Result type alias for CoreError
pub type Result<T> = std::result::Result<T, CoreError>;
