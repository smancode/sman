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

    /// Cycle detected in task dependencies
    #[error("Cycle detected in task dependencies: {0}")]
    CycleDetected(String),

    /// Task already exists
    #[error("Task already exists: {0}")]
    TaskAlreadyExists(String),

    /// Skill not found
    #[error("Skill not found: {0}")]
    SkillNotFound(String),

    /// Skill already exists
    #[error("Skill already exists: {0}")]
    SkillAlreadyExists(String),

    /// Task file not found
    #[error("Task file not found: {0}")]
    TaskFileNotFound(String),

    /// Task file parse error
    #[error("Failed to parse task file: {0}")]
    TaskFileParseError(String),

    /// Poll timeout
    #[error("Poll timeout after {0:?}")]
    PollTimeout(std::time::Duration),

    /// Verification error
    #[error("Verification error: {0}")]
    Verification(String),

    /// Command execution error
    #[error("Command execution error: {0}")]
    CommandExecution(String),

    /// Regex error
    #[error("Regex error: {0}")]
    Regex(#[from] regex::Error),

    /// LLM error
    #[error("LLM error: {0}")]
    LLMError(String),
}

/// Result type alias for CoreError
pub type Result<T> = std::result::Result<T, CoreError>;
