//! Progress events for real-time updates

use serde::{Deserialize, Serialize};
use serde_json::Value;

use super::{FileAction, TaskResult};

/// Progress event emitted during task execution
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum ProgressEvent {
    /// Task started
    TaskStarted {
        /// Task ID
        task_id: String,
    },
    /// Tool call initiated
    ToolCall {
        /// Tool name
        tool: String,
        /// Tool arguments
        args: Value,
    },
    /// File read operation
    FileRead {
        /// File path
        path: String,
    },
    /// File written operation
    FileWritten {
        /// File path
        path: String,
        /// Action (created/modified)
        action: FileAction,
    },
    /// Command executed
    CommandRun {
        /// Command string
        command: String,
    },
    /// Progress update
    Progress {
        /// Progress message
        message: String,
        /// Progress percentage (0.0 - 1.0)
        percent: f32,
    },
    /// Task completed successfully
    TaskCompleted {
        /// Task result
        result: TaskResult,
    },
    /// Task failed
    TaskFailed {
        /// Error message
        error: String,
    },
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn progress_event_serialization() {
        let event = ProgressEvent::TaskStarted {
            task_id: "task-123".to_string(),
        };

        let json = serde_json::to_string(&event).expect("serialize");
        assert!(json.contains("task_started"));

        let deserialized: ProgressEvent =
            serde_json::from_str(&json).expect("deserialize");
        assert_eq!(event, deserialized);
    }

    #[test]
    fn progress_event_tool_call() {
        let event = ProgressEvent::ToolCall {
            tool: "file_read".to_string(),
            args: serde_json::json!({"path": "src/main.rs"}),
        };

        let json = serde_json::to_string(&event).expect("serialize");
        let deserialized: ProgressEvent =
            serde_json::from_str(&json).expect("deserialize");
        assert_eq!(event, deserialized);
    }

    #[test]
    fn progress_event_progress() {
        let event = ProgressEvent::Progress {
            message: "Processing...".to_string(),
            percent: 0.5,
        };

        let json = serde_json::to_string(&event).expect("serialize");
        let deserialized: ProgressEvent =
            serde_json::from_str(&json).expect("deserialize");
        assert_eq!(event, deserialized);
    }
}
