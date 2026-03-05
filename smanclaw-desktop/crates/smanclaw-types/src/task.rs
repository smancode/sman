//! Task-related types

use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};

/// Unique task identifier
pub type TaskId = String;

/// A task represents a unit of work to be executed by ZeroClaw
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct Task {
    /// Unique task identifier
    pub id: TaskId,
    /// Project this task belongs to
    pub project_id: String,
    /// User input/prompt
    pub input: String,
    /// Current status
    pub status: TaskStatus,
    /// Task output (LLM response)
    #[serde(skip_serializing_if = "Option::is_none")]
    pub output: Option<String>,
    /// Error message if task failed
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
    /// Creation timestamp
    pub created_at: DateTime<Utc>,
    /// Last update timestamp
    pub updated_at: DateTime<Utc>,
    /// Completion timestamp
    #[serde(skip_serializing_if = "Option::is_none")]
    pub completed_at: Option<DateTime<Utc>>,
}

/// Task status enum
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
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

impl Serialize for TaskStatus {
    fn serialize<S>(&self, serializer: S) -> std::result::Result<S::Ok, S::Error>
    where
        S: serde::Serializer,
    {
        // Always serialize as lowercase for frontend compatibility
        match self {
            TaskStatus::Pending => serializer.serialize_str("pending"),
            TaskStatus::Running => serializer.serialize_str("running"),
            TaskStatus::Completed => serializer.serialize_str("completed"),
            TaskStatus::Failed => serializer.serialize_str("failed"),
        }
    }
}

impl<'de> Deserialize<'de> for TaskStatus {
    fn deserialize<D>(deserializer: D) -> std::result::Result<Self, D::Error>
    where
        D: serde::Deserializer<'de>,
    {
        let s = String::deserialize(deserializer)?;
        // Accept both lowercase and PascalCase for backward compatibility
        match s.to_lowercase().as_str() {
            "pending" => Ok(TaskStatus::Pending),
            "running" => Ok(TaskStatus::Running),
            "completed" => Ok(TaskStatus::Completed),
            "failed" => Ok(TaskStatus::Failed),
            _ => Err(serde::de::Error::custom(format!(
                "Unknown task status: {}",
                s
            ))),
        }
    }
}

/// Result of a task execution
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
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
#[serde(rename_all = "camelCase")]
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
            output: None,
            error: None,
            created_at: Utc::now(),
            updated_at: Utc::now(),
            completed_at: None,
        };

        let json = serde_json::to_string(&task).expect("serialize");
        let deserialized: Task = serde_json::from_str(&json).expect("deserialize");

        assert_eq!(task, deserialized);
    }

    #[test]
    fn task_with_output_serialization() {
        let task = Task {
            id: "task-123".to_string(),
            project_id: "proj-456".to_string(),
            input: "Hello".to_string(),
            status: TaskStatus::Completed,
            output: Some("Hi there! How can I help?".to_string()),
            error: None,
            created_at: Utc::now(),
            updated_at: Utc::now(),
            completed_at: Some(Utc::now()),
        };

        let json = serde_json::to_string(&task).expect("serialize");
        println!("Serialized JSON: {}", json);

        // Verify output is present
        assert!(json.contains("output"), "JSON should contain 'output' field");
        assert!(json.contains("Hi there"), "JSON should contain output value");

        let deserialized: Task = serde_json::from_str(&json).expect("deserialize");
        assert_eq!(task, deserialized);
        assert_eq!(deserialized.output, Some("Hi there! How can I help?".to_string()));
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

    #[test]
    fn task_status_serializes_to_lowercase() {
        // Verify that status is serialized as lowercase for frontend compatibility
        let task = Task {
            id: "task-123".to_string(),
            project_id: "proj-456".to_string(),
            input: "Test".to_string(),
            status: TaskStatus::Running,
            output: None,
            error: None,
            created_at: Utc::now(),
            updated_at: Utc::now(),
            completed_at: None,
        };

        let json = serde_json::to_string(&task).expect("serialize");
        assert!(json.contains(r#""status":"running""#), "Status should be lowercase 'running', got: {}", json);
    }

    #[test]
    fn task_status_deserializes_from_lowercase() {
        let json = r#"{"id":"task-123","projectId":"proj-456","input":"Test","status":"completed","createdAt":"2024-01-01T00:00:00Z","updatedAt":"2024-01-01T00:00:00Z"}"#;
        let task: Task = serde_json::from_str(json).expect("deserialize");
        assert_eq!(task.status, TaskStatus::Completed);
    }

    #[test]
    fn task_status_deserializes_from_pascal_case_for_backward_compatibility() {
        // Verify backward compatibility with old database format
        let json = r#"{"id":"task-123","projectId":"proj-456","input":"Test","status":"Completed","createdAt":"2024-01-01T00:00:00Z","updatedAt":"2024-01-01T00:00:00Z"}"#;
        let task: Task = serde_json::from_str(json).expect("deserialize");
        assert_eq!(task.status, TaskStatus::Completed);
    }
}
