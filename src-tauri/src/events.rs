//! Progress event emission for Tauri frontend

use smanclaw_types::ProgressEvent;
use tauri::{AppHandle, Emitter};

/// Emit a progress event to the frontend
pub fn emit_progress_event(
    app_handle: &AppHandle,
    event: &ProgressEvent,
) -> Result<(), tauri::Error> {
    app_handle.emit("progress", event)
}

/// Event payload for task status changes
#[derive(Debug, Clone, serde::Serialize)]
pub struct TaskStatusEvent {
    /// Task ID
    pub task_id: String,
    /// New status
    pub status: String,
    /// Message
    pub message: Option<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TaskEventStatus {
    Running,
    Completed,
    Failed,
    Cancelled,
}

impl TaskEventStatus {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Running => "running",
            Self::Completed => "completed",
            Self::Failed => "failed",
            Self::Cancelled => "cancelled",
        }
    }
}

pub fn task_event_status_from_success(success: bool) -> TaskEventStatus {
    if success {
        TaskEventStatus::Completed
    } else {
        TaskEventStatus::Failed
    }
}

/// Emit a task status event to the frontend
pub fn emit_task_status(
    app_handle: &AppHandle,
    task_id: &str,
    status: &str,
    message: Option<String>,
) -> Result<(), tauri::Error> {
    app_handle.emit(
        "task-status",
        TaskStatusEvent {
            task_id: task_id.to_string(),
            status: status.to_string(),
            message,
        },
    )
}

pub fn emit_task_status_event(
    app_handle: &AppHandle,
    task_id: &str,
    status: TaskEventStatus,
    message: Option<String>,
) -> Result<(), tauri::Error> {
    emit_task_status(app_handle, task_id, status.as_str(), message)
}

/// Event payload for file changes
#[derive(Debug, Clone, serde::Serialize)]
pub struct FileChangeEvent {
    /// File path
    pub path: String,
    /// Action (created/modified/deleted)
    pub action: String,
}

/// Emit a file change event to the frontend
pub fn emit_file_change(
    app_handle: &AppHandle,
    path: &str,
    action: &str,
) -> Result<(), tauri::Error> {
    app_handle.emit(
        "file-change",
        FileChangeEvent {
            path: path.to_string(),
            action: action.to_string(),
        },
    )
}

// ============================================================================
// Orchestration Events
// ============================================================================

/// Event payload for subtask start
#[derive(Debug, Clone, serde::Serialize)]
pub struct SubTaskStartedEvent {
    /// Parent task ID
    pub task_id: String,
    /// Subtask ID
    pub subtask_id: String,
    /// Subtask description
    pub description: String,
}

/// Event payload for subtask completion
#[derive(Debug, Clone, serde::Serialize)]
pub struct SubTaskCompletedEvent {
    /// Parent task ID
    pub task_id: String,
    /// Subtask ID
    pub subtask_id: String,
    /// Whether the subtask succeeded
    pub success: bool,
    /// Output from the subtask
    pub output: String,
    /// Error message if failed
    pub error: Option<String>,
}

/// Event payload for test result
#[derive(Debug, Clone, serde::Serialize)]
pub struct TestResultEvent {
    /// Parent task ID
    pub task_id: String,
    /// Subtask ID
    pub subtask_id: String,
    /// Whether the test passed
    pub passed: bool,
    /// Test output
    pub output: String,
    /// Number of tests run
    pub tests_run: Option<usize>,
    /// Number of tests passed
    pub tests_passed: Option<usize>,
}

/// Event payload for orchestration progress
#[derive(Debug, Clone, serde::Serialize)]
pub struct OrchestrationProgressEvent {
    /// Parent task ID
    pub task_id: String,
    /// Number of completed subtasks
    pub completed: usize,
    /// Total number of subtasks
    pub total: usize,
    /// Progress percentage (0.0 - 1.0)
    pub percent: f32,
}

/// Event payload for task DAG structure
#[derive(Debug, Clone, serde::Serialize)]
pub struct TaskDagEvent {
    /// Parent task ID
    pub task_id: String,
    /// All subtasks with their status
    pub tasks: Vec<SubTaskInfo>,
    /// Parallel execution groups (indices reference tasks array)
    pub parallel_groups: Vec<Vec<String>>,
}

/// Subtask info for DAG display
#[derive(Debug, Clone, serde::Serialize)]
pub struct SubTaskInfo {
    /// Subtask ID
    pub id: String,
    /// Description
    pub description: String,
    /// Current status
    pub status: String,
    /// IDs of tasks this depends on
    pub depends_on: Vec<String>,
}

/// Emit a subtask started event
pub fn emit_subtask_started(
    app_handle: &AppHandle,
    task_id: &str,
    subtask_id: &str,
    description: &str,
) -> Result<(), tauri::Error> {
    app_handle.emit(
        "subtask-started",
        SubTaskStartedEvent {
            task_id: task_id.to_string(),
            subtask_id: subtask_id.to_string(),
            description: description.to_string(),
        },
    )
}

/// Emit a subtask completed event
pub fn emit_subtask_completed(
    app_handle: &AppHandle,
    task_id: &str,
    subtask_id: &str,
    success: bool,
    output: &str,
    error: Option<String>,
) -> Result<(), tauri::Error> {
    app_handle.emit(
        "subtask-completed",
        SubTaskCompletedEvent {
            task_id: task_id.to_string(),
            subtask_id: subtask_id.to_string(),
            success,
            output: output.to_string(),
            error,
        },
    )
}

/// Emit a test result event
pub fn emit_test_result(
    app_handle: &AppHandle,
    task_id: &str,
    subtask_id: &str,
    passed: bool,
    output: &str,
    tests_run: Option<usize>,
    tests_passed: Option<usize>,
) -> Result<(), tauri::Error> {
    app_handle.emit(
        "test-result",
        TestResultEvent {
            task_id: task_id.to_string(),
            subtask_id: subtask_id.to_string(),
            passed,
            output: output.to_string(),
            tests_run,
            tests_passed,
        },
    )
}

/// Emit an orchestration progress event
pub fn emit_orchestration_progress(
    app_handle: &AppHandle,
    task_id: &str,
    completed: usize,
    total: usize,
) -> Result<(), tauri::Error> {
    let percent = if total > 0 {
        completed as f32 / total as f32
    } else {
        0.0
    };
    app_handle.emit(
        "orchestration-progress",
        OrchestrationProgressEvent {
            task_id: task_id.to_string(),
            completed,
            total,
            percent,
        },
    )
}

/// Emit a task DAG event
pub fn emit_task_dag(
    app_handle: &AppHandle,
    task_id: &str,
    tasks: Vec<SubTaskInfo>,
    parallel_groups: Vec<Vec<String>>,
) -> Result<(), tauri::Error> {
    app_handle.emit(
        "task-dag",
        TaskDagEvent {
            task_id: task_id.to_string(),
            tasks,
            parallel_groups,
        },
    )
}

// ============================================================================
// Message Events
// ============================================================================

/// Event payload for sending a message from backend to frontend
#[derive(Debug, Clone, serde::Serialize)]
pub struct SendMessageEvent {
    /// Conversation ID
    pub conversation_id: String,
    /// Message content to send
    pub content: String,
}

/// Emit a send message event to frontend
pub fn emit_send_message(
    app_handle: &AppHandle,
    conversation_id: &str,
    content: &str,
) -> Result<(), tauri::Error> {
    app_handle.emit(
        "send-message",
        SendMessageEvent {
            conversation_id: conversation_id.to_string(),
            content: content.to_string(),
        },
    )
}

#[cfg(test)]
mod tests {
    use super::{task_event_status_from_success, TaskEventStatus};

    #[test]
    fn task_event_status_strings_are_stable() {
        assert_eq!(TaskEventStatus::Running.as_str(), "running");
        assert_eq!(TaskEventStatus::Completed.as_str(), "completed");
        assert_eq!(TaskEventStatus::Failed.as_str(), "failed");
        assert_eq!(TaskEventStatus::Cancelled.as_str(), "cancelled");
    }

    #[test]
    fn task_event_status_maps_from_success_flag() {
        assert_eq!(
            task_event_status_from_success(true),
            TaskEventStatus::Completed
        );
        assert_eq!(
            task_event_status_from_success(false),
            TaskEventStatus::Failed
        );
    }
}

/// Event payload for chat messages from backend to frontend
#[derive(Debug, Clone, serde::Serialize)]
pub struct ChatMessageEvent {
    /// Project ID
    pub project_id: String,
    /// Message content
    pub content: String,
    /// Message role (user or assistant)
    pub role: String,
}

/// Emit a chat message to the frontend
pub fn emit_chat_message(
    app_handle: &AppHandle,
    project_id: &str,
    content: &str,
    role: &str,
) -> Result<(), tauri::Error> {
    let event = ChatMessageEvent {
        project_id: project_id.to_string(),
        content: content.to_string(),
        role: role.to_string(),
    };
    app_handle.emit("chat-message", event)
}
