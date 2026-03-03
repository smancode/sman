//! Task-related types

use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};

/// Unique task identifier
pub type TaskId = String;

/// A task represents a unit of work to be executed by ZeroClaw
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct Task {
    /// Unique task identifier
    pub id: TaskId,
    /// Project this task belongs to
    pub project_id: String,
    /// User input/prompt
    pub input: String,
    /// Current status
    pub status: TaskStatus,
    /// Creation timestamp
    pub created_at: DateTime<Utc>,
    /// Last update timestamp
    pub updated_at: DateTime<Utc>,
}

/// Task status enum
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
pub enum TaskStatus {
    /// Task is pending execution
    Pending,
    /// Task is currently running
    Running,
    /// Task completed successfully
    Completed,
    /// Task failed with an error
    Failed,
}

impl Default for TaskStatus {
    fn default() -> Self {
        Self::Pending
    }
}

/// Result of a task execution
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct TaskResult {
    /// Task ID this result belongs to
    pub task_id: TaskId,
    /// Whether the task succeeded
    pub success: bool,
    /// Output from the task
    pub output: String,
    /// Error message if failed
    pub error: Option<String>,
    /// Files changed during execution
    pub files_changed: Vec<FileChange>,
}

/// Represents a file change
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct FileChange {
    /// File path relative to project root
    pub path: String,
    /// Action performed on the file
    pub action: FileAction,
}

/// File action enum
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
pub enum FileAction {
    /// File was created
    Created,
    /// File was modified
    Modified,
    /// File was deleted
    Deleted,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn task_serialization_roundtrip() {
        let task = Task {
            id: "task-123".to_string(),
            project_id: "proj-456".to_string(),
            input: "Implement login".to_string(),
            status: TaskStatus::Pending,
            created_at: Utc::now(),
            updated_at: Utc::now(),
        };

        let json = serde_json::to_string(&task).expect("serialize");
        let deserialized: Task = serde_json::from_str(&json).expect("deserialize");

        assert_eq!(task, deserialized);
    }

    #[test]
    fn task_status_default_is_pending() {
        assert_eq!(TaskStatus::default(), TaskStatus::Pending);
    }

    #[test]
    fn task_result_serialization() {
        let result = TaskResult {
            task_id: "task-123".to_string(),
            success: true,
            output: "Done".to_string(),
            error: None,
            files_changed: vec![FileChange {
                path: "src/main.rs".to_string(),
                action: FileAction::Modified,
            }],
        };

        let json = serde_json::to_string(&result).expect("serialize");
        let deserialized: TaskResult = serde_json::from_str(&json).expect("deserialize");

        assert_eq!(result, deserialized);
    }
}
