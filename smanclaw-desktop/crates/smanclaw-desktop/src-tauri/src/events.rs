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
